package com.gmail.yongdagan.elkalert.curator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.Stat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gmail.yongdagan.elkalert.constant.CuratorConstant;

public class RecoveredAssignments {
    private static final Logger logger = LoggerFactory.getLogger(RecoveredAssignments.class);
    
    /*
     * Various lists wew need to keep track of.
     */
    List<String> tasks;
    List<String> assignments;
    List<String> status;
    List<String> activeWorkers;
    List<String> assignedWorkers;
    
    RecoveryCallback cb;
    
    ZooKeeper zk;
    
    /**
     * Callback interface. Called once 
     * recovery completes or fails.
     *
     */
    public interface RecoveryCallback {
        final static int OK = 0;
        final static int FAILED = -1;
        
        public void recoveryComplete(int rc, List<String> tasks);
    }
    
    /**
     * Recover unassigned tasks.
     * 
     * @param zk
     */
    public RecoveredAssignments(ZooKeeper zk){
        this.zk = zk;
        this.assignments = new ArrayList<String>();
    }
    
    /**
     * Starts recovery.
     * 
     * @param recoveryCallback
     */
    public void recover(RecoveryCallback recoveryCallback){
        // Read task list with getChildren
        cb = recoveryCallback;
        getTasks();
    }
    
    private void getTasks(){
        zk.getChildren(CuratorConstant.ZK_TASKS_NODE, false, tasksCallback, null);
    }
    
    ChildrenCallback tasksCallback = new ChildrenCallback(){
        public void processResult(int rc, String path, Object ctx, List<String> children){
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                getTasks();
                
                break;
            case OK:
            	logger.info("Getting tasks for recovery");
                tasks = children;
                getAssignedWorkers();
                
                break;
            default:
            	logger.error("getChildren failed",  KeeperException.create(Code.get(rc), path));
                cb.recoveryComplete(RecoveryCallback.FAILED, null);
            }
        }
    };
    
    private void getAssignedWorkers(){
        zk.getChildren(CuratorConstant.ZK_ASSIGN_NODE, false, assignedWorkersCallback, null);
    }
    
    ChildrenCallback assignedWorkersCallback = new ChildrenCallback(){
        public void processResult(int rc, String path, Object ctx, List<String> children){    
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                getAssignedWorkers();
                
                break;
            case OK:  
                assignedWorkers = children;
                getWorkers(children);

                break;
            default:
            	logger.error("getChildren failed",  KeeperException.create(Code.get(rc), path));
                cb.recoveryComplete(RecoveryCallback.FAILED, null);
            }
        }
    };
        
    private void getWorkers(Object ctx){
        zk.getChildren(CuratorConstant.ZK_HANDLERS_NODE, false, workersCallback, ctx);
    }
    
    
    ChildrenCallback workersCallback = new ChildrenCallback(){
        public void processResult(int rc, String path, Object ctx, List<String> children){    
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                getWorkers(ctx);
                break;
            case OK:
            	logger.info("Getting worker assignments for recovery: " + children.size());
                
                /*
                 * No worker available yet, so the master is probably let's just return an empty list.
                 */
                if(children.size() == 0) {
                	logger.warn( "Empty list of workers, possibly just starting" );
                    cb.recoveryComplete(RecoveryCallback.OK, new ArrayList<String>());
                    
                    break;
                }
                
                /*
                 * Need to know which of the assigned workers are active.
                 */
                        
                activeWorkers = children;
                
                for(String s : assignedWorkers){
                    getWorkerAssignments(CuratorConstant.ZK_ASSIGN_NODE + "/" + s);
                }
                
                break;
            default:
            	logger.error("getChildren failed",  KeeperException.create(Code.get(rc), path));
                cb.recoveryComplete(RecoveryCallback.FAILED, null);
            }
        }
    };
    
    private void getWorkerAssignments(String s) {
        zk.getChildren(s, false, workerAssignmentsCallback, null);
    }
    
    ChildrenCallback workerAssignmentsCallback = new ChildrenCallback(){
        public void processResult(int rc, 
                String path, 
                Object ctx, 
                List<String> children) {    
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                getWorkerAssignments(path);
                break;
            case OK:
                String worker = path.replace(CuratorConstant.ZK_ASSIGN_NODE + "/", "");
                
                /*
                 * If the worker is in the list of active
                 * workers, then we add the tasks to the
                 * assignments list. Otherwise, we need to 
                 * re-assign those tasks, so we add them to
                 * the list of tasks.
                 */
                if(activeWorkers.contains(worker)) {
                    assignments.addAll(children);
                } else {
                    for( String task : children ) {
                        if(!tasks.contains( task )) {
                            tasks.add( task );
                            getDataReassign( path, task );
                        } else {
                            /*
                             * If the task is still in the list
                             * we delete the assignment.
                             */
                            deleteAssignment(path + "/" + task);
                        }
                        
                        /*
                         * Delete the assignment parent. 
                         */
                        deleteAssignment(path);
                    }
                    
                }
                   
                assignedWorkers.remove(worker);
                
                /*
                 * Once we have checked all assignments,
                 * it is time to check the status of tasks
                 */
                if(assignedWorkers.size() == 0){
                	logger.info("Getting statuses for recovery");
                    getStatuses();
                 }
                
                break;
            case NONODE:
            	logger.info( "No such znode exists: " + path );
                
                break;
            default:
            	logger.error("getChildren failed",  KeeperException.create(Code.get(rc), path));
                cb.recoveryComplete(RecoveryCallback.FAILED, null);
            }
        }
    };
    
    /**
     * Get data of task being reassigned.
     * 
     * @param path
     * @param task
     */
    void getDataReassign(String path, String task) {
        zk.getData(path, 
                false, 
                getDataReassignCallback, 
                task);
    }
    
    /**
     * Context for recreate operation.
     *
     */
    class RecreateTaskCtx {
        String path; 
        String task;
        byte[] data;
        
        RecreateTaskCtx(String path, String task, byte[] data) {
            this.path = path;
            this.task = task;
            this.data = data;
        }
    }

    /**
     * Get task data reassign callback.
     */
    DataCallback getDataReassignCallback = new DataCallback() {
        public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat)  {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                getDataReassign(path, (String) ctx); 
                
                break;
            case OK:
                recreateTask(new RecreateTaskCtx(path, (String) ctx, data));
                
                break;
            default:
            	logger.error("Something went wrong when getting data ",
                        KeeperException.create(Code.get(rc)));
            }
        }
    };
    
    /**
     * Recreate task znode in /tasks
     * 
     * @param ctx Recreate text context
     */
    void recreateTask(RecreateTaskCtx ctx) {
        zk.create(CuratorConstant.ZK_TASKS_NODE + "/" + ctx.task,
                ctx.data,
                Ids.OPEN_ACL_UNSAFE, 
                CreateMode.PERSISTENT,
                recreateTaskCallback,
                ctx);
    }
    
    /**
     * Recreate znode callback
     */
    StringCallback recreateTaskCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                recreateTask((RecreateTaskCtx) ctx);
       
                break;
            case OK:
                deleteAssignment(((RecreateTaskCtx) ctx).path);
                
                break;
            case NODEEXISTS:
            	logger.warn("Node shouldn't exist: " + path);
                
                break;
            default:
            	logger.error("Something wwnt wrong when recreating task", 
                        KeeperException.create(Code.get(rc)));
            }
        }
    };
    
    /**
     * Delete assignment of absent worker
     * 
     * @param path Path of znode to be deleted
     */
    void deleteAssignment(String path){
        zk.delete(path, -1, taskDeletionCallback, null);
    }
    
    VoidCallback taskDeletionCallback = new VoidCallback(){
        public void processResult(int rc, String path, Object rtx){
            switch(Code.get(rc)) {
            case CONNECTIONLOSS:
                deleteAssignment(path);
                break;
            case OK:
            	logger.info("Task correctly deleted: " + path);
                break;
            default:
            	logger.error("Failed to delete task data" + 
                        KeeperException.create(Code.get(rc), path));
            } 
        }
    };
    
    
    void getStatuses(){
        zk.getChildren(CuratorConstant.ZK_STATUS_NODE, false, statusCallback, null); 
    }
    
    ChildrenCallback statusCallback = new ChildrenCallback(){
        public void processResult(int rc, 
                String path, 
                Object ctx, 
                List<String> children){    
            switch (Code.get(rc)) { 
            case CONNECTIONLOSS:
                getStatuses();
                
                break;
            case OK:
            	logger.info("Processing assignments for recovery");
                status = children;
                processAssignments();
                
                break;
            default:
            	logger.error("getChildren failed",  KeeperException.create(Code.get(rc), path));
                cb.recoveryComplete(RecoveryCallback.FAILED, null);
            }
        }
    };
    
    private void processAssignments(){
    	logger.info("Size of tasks: " + tasks.size());
        // Process list of pending assignments
        for(String s: assignments){
        	logger.info("Assignment: " + s);
            deleteAssignment(CuratorConstant.ZK_TASKS_NODE + "/" + s);
            tasks.remove(s);
        }
        
        logger.info("Size of tasks after assignment filtering: " + tasks.size());
        
        for(String s: status){
        	logger.info( "Checking task: {} ", s );
            deleteAssignment(CuratorConstant.ZK_TASKS_NODE + "/" + s);
            tasks.remove(s);
        }
        logger.info("Size of tasks after status filtering: " + tasks.size());
        
        // Invoke callback
        cb.recoveryComplete(RecoveryCallback.OK, tasks);     
    }
}
