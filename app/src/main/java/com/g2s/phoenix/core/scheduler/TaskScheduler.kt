package com.g2s.phoenix.core.scheduler

import com.g2s.phoenix.model.Task
import com.g2s.phoenix.model.TaskPriority
import com.g2s.phoenix.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/**
 * 任务调度器接口
 */
interface TaskScheduler {
    
    /**
     * 提交任务到调度器
     */
    suspend fun submitTask(task: Task): Boolean
    
    /**
     * 批量提交任务
     */
    suspend fun submitTasks(tasks: List<Task>): List<Boolean>
    
    /**
     * 取消任务
     */
    suspend fun cancelTask(taskId: String): Boolean
    
    /**
     * 暂停任务
     */
    suspend fun pauseTask(taskId: String): Boolean
    
    /**
     * 恢复任务
     */
    suspend fun resumeTask(taskId: String): Boolean
    
    /**
     * 获取下一个待执行的任务
     */
    suspend fun getNextTask(): Task?
    
    /**
     * 获取任务状态
     */
    suspend fun getTaskStatus(taskId: String): TaskStatus?
    
    /**
     * 获取任务详情
     */
    suspend fun getTask(taskId: String): Task?
    
    /**
     * 获取所有任务
     */
    suspend fun getAllTasks(): List<Task>
    
    /**
     * 根据状态获取任务列表
     */
    suspend fun getTasksByStatus(status: TaskStatus): List<Task>
    
    /**
     * 根据优先级获取任务列表
     */
    suspend fun getTasksByPriority(priority: TaskPriority): List<Task>
    
    /**
     * 清理已完成的任务
     */
    suspend fun cleanupCompletedTasks(): Int
    
    /**
     * 获取调度器统计信息
     */
    suspend fun getStatistics(): SchedulerStatistics
    
    /**
     * 监听任务状态变化
     */
    fun observeTaskUpdates(): Flow<TaskUpdate>
    
    /**
     * 启动调度器
     */
    suspend fun start()
    
    /**
     * 停止调度器
     */
    suspend fun stop()
    
    /**
     * 检查调度器是否运行中
     */
    fun isRunning(): Boolean
}

/**
 * 调度器统计信息
 */
data class SchedulerStatistics(
    val totalTasks: Int,
    val pendingTasks: Int,
    val runningTasks: Int,
    val completedTasks: Int,
    val failedTasks: Int,
    val cancelledTasks: Int,
    val averageExecutionTime: Long,
    val throughput: Double, // 每秒处理的任务数
    val queueUtilization: Double, // 队列使用率
    val lastUpdateTime: Long = System.currentTimeMillis()
)

/**
 * 任务更新事件
 */
sealed class TaskUpdate {
    data class TaskSubmitted(val task: Task) : TaskUpdate()
    data class TaskStarted(val task: Task) : TaskUpdate()
    data class TaskCompleted(val task: Task) : TaskUpdate()
    data class TaskFailed(val task: Task) : TaskUpdate()
    data class TaskCancelled(val task: Task) : TaskUpdate()
    data class TaskRetrying(val task: Task) : TaskUpdate()
}