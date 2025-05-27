package com.g2s.phoenix.core.scheduler

import com.g2s.phoenix.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 智能任务调度器实现
 * 基于优先级队列和协程的高性能调度器
 */
class SmartTaskScheduler(
    private val config: PhoenixConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : TaskScheduler {

    // 任务存储
    private val tasks = ConcurrentHashMap<String, Task>()
    
    // 优先级队列
    private val taskQueue = PriorityBlockingQueue<Task>(
        config.performance.taskQueueSize,
        compareByDescending<Task> { it.getPriorityScore() }
    )
    
    // 运行时状态
    private val isRunning = AtomicBoolean(false)
    private val mutex = Mutex()
    
    // 统计信息
    private val totalTasksCounter = AtomicLong(0)
    private val completedTasksCounter = AtomicLong(0)
    private val failedTasksCounter = AtomicLong(0)
    private val executionTimeSum = AtomicLong(0)
    
    // 任务更新流
    private val _taskUpdates = MutableSharedFlow<TaskUpdate>()
    
    // 调度器作业
    private var schedulerJob: Job? = null
    
    override suspend fun submitTask(task: Task): Boolean {
        if (!isRunning.get()) {
            return false
        }
        
        return mutex.withLock {
            // 检查任务是否已存在
            if (tasks.containsKey(task.id)) {
                return@withLock false
            }
            
            // 检查队列容量
            if (taskQueue.size >= config.performance.taskQueueSize) {
                return@withLock false
            }
            
            // 添加任务
            tasks[task.id] = task
            taskQueue.offer(task)
            totalTasksCounter.incrementAndGet()
            
            // 发送更新事件
            _taskUpdates.tryEmit(TaskUpdate.TaskSubmitted(task))
            
            true
        }
    }
    
    override suspend fun submitTasks(tasks: List<Task>): List<Boolean> {
        return tasks.map { submitTask(it) }
    }
    
    override suspend fun cancelTask(taskId: String): Boolean {
        return mutex.withLock {
            val task = tasks[taskId] ?: return@withLock false
            
            if (task.isCompleted()) {
                return@withLock false
            }
            
            val cancelledTask = task.cancel()
            tasks[taskId] = cancelledTask
            
            // 从队列中移除（如果存在）
            taskQueue.removeIf { it.id == taskId }
            
            _taskUpdates.tryEmit(TaskUpdate.TaskCancelled(cancelledTask))
            true
        }
    }
    
    override suspend fun pauseTask(taskId: String): Boolean {
        return mutex.withLock {
            val task = tasks[taskId] ?: return@withLock false
            
            if (task.status != TaskStatus.PENDING && task.status != TaskStatus.RETRYING) {
                return@withLock false
            }
            
            val pausedTask = task.copy(status = TaskStatus.SUSPENDED)
            tasks[taskId] = pausedTask
            
            // 从队列中移除
            taskQueue.removeIf { it.id == taskId }
            
            true
        }
    }
    
    override suspend fun resumeTask(taskId: String): Boolean {
        return mutex.withLock {
            val task = tasks[taskId] ?: return@withLock false
            
            if (task.status != TaskStatus.SUSPENDED) {
                return@withLock false
            }
            
            val resumedTask = task.copy(status = TaskStatus.PENDING)
            tasks[taskId] = resumedTask
            taskQueue.offer(resumedTask)
            
            true
        }
    }
    
    override suspend fun getNextTask(): Task? {
        return mutex.withLock {
            // 清理不可执行的任务
            while (taskQueue.isNotEmpty()) {
                val task = taskQueue.peek()
                if (task?.canExecute() == true) {
                    taskQueue.poll()
                    val runningTask = task.markStarted()
                    tasks[task.id] = runningTask
                    _taskUpdates.tryEmit(TaskUpdate.TaskStarted(runningTask))
                    return@withLock runningTask
                } else {
                    taskQueue.poll() // 移除不可执行的任务
                }
            }
            null
        }
    }
    
    override suspend fun getTaskStatus(taskId: String): TaskStatus? {
        return tasks[taskId]?.status
    }
    
    override suspend fun getTask(taskId: String): Task? {
        return tasks[taskId]
    }
    
    override suspend fun getAllTasks(): List<Task> {
        return tasks.values.toList()
    }
    
    override suspend fun getTasksByStatus(status: TaskStatus): List<Task> {
        return tasks.values.filter { it.status == status }
    }
    
    override suspend fun getTasksByPriority(priority: TaskPriority): List<Task> {
        return tasks.values.filter { it.priority == priority }
    }
    
    override suspend fun cleanupCompletedTasks(): Int {
        return mutex.withLock {
            val completedTasks = tasks.values.filter { it.isCompleted() }
            completedTasks.forEach { tasks.remove(it.id) }
            completedTasks.size
        }
    }
    
    override suspend fun getStatistics(): SchedulerStatistics {
        val allTasks = tasks.values
        val pendingTasks = allTasks.count { it.status == TaskStatus.PENDING }
        val runningTasks = allTasks.count { it.status == TaskStatus.RUNNING }
        val completedTasks = completedTasksCounter.get()
        val failedTasks = failedTasksCounter.get()
        val cancelledTasks = allTasks.count { it.status == TaskStatus.CANCELLED }
        
        val avgExecutionTime = if (completedTasks > 0) {
            executionTimeSum.get() / completedTasks
        } else 0L
        
        val queueUtilization = taskQueue.size.toDouble() / config.performance.taskQueueSize
        val throughput = calculateThroughput()
        
        return SchedulerStatistics(
            totalTasks = totalTasksCounter.get().toInt(),
            pendingTasks = pendingTasks,
            runningTasks = runningTasks,
            completedTasks = completedTasks.toInt(),
            failedTasks = failedTasks.toInt(),
            cancelledTasks = cancelledTasks,
            averageExecutionTime = avgExecutionTime,
            throughput = throughput,
            queueUtilization = queueUtilization
        )
    }
    
    override fun observeTaskUpdates(): Flow<TaskUpdate> {
        return _taskUpdates.asSharedFlow()
    }
    
    override suspend fun start() {
        if (isRunning.compareAndSet(false, true)) {
            schedulerJob = scope.launch {
                startSchedulerLoop()
            }
            
            // 启动重试任务检查
            scope.launch {
                startRetryTaskChecker()
            }
            
            // 启动统计信息更新
            scope.launch {
                startStatisticsUpdater()
            }
        }
    }
    
    override suspend fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            schedulerJob?.cancel()
            schedulerJob = null
        }
    }
    
    override fun isRunning(): Boolean {
        return isRunning.get()
    }
    
    /**
     * 标记任务完成
     */
    suspend fun markTaskCompleted(taskId: String, result: TaskResult) {
        mutex.withLock {
            val task = tasks[taskId] ?: return@withLock
            val completedTask = task.markCompleted(result)
            tasks[taskId] = completedTask
            
            // 更新统计信息
            completedTasksCounter.incrementAndGet()
            executionTimeSum.addAndGet(result.executionTime)
            
            _taskUpdates.tryEmit(TaskUpdate.TaskCompleted(completedTask))
        }
    }
    
    /**
     * 标记任务失败
     */
    suspend fun markTaskFailed(taskId: String, result: TaskResult) {
        mutex.withLock {
            val task = tasks[taskId] ?: return@withLock
            val failedTask = task.markFailed(result)
            tasks[taskId] = failedTask
            
            // 如果需要重试，重新加入队列
            if (failedTask.status == TaskStatus.RETRYING) {
                _taskUpdates.tryEmit(TaskUpdate.TaskRetrying(failedTask))
            } else {
                failedTasksCounter.incrementAndGet()
                _taskUpdates.tryEmit(TaskUpdate.TaskFailed(failedTask))
            }
        }
    }
    
    /**
     * 调度器主循环
     */
    private suspend fun startSchedulerLoop() {
        while (isRunning.get()) {
            try {
                // 定期重新排序队列中的任务
                reorderTaskQueue()
                
                // 检查超时任务
                checkTimeoutTasks()
                
                delay(1000) // 每秒检查一次
            } catch (e: Exception) {
                // 记录异常但继续运行
                if (config.isDebugMode()) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 重试任务检查器
     */
    private suspend fun startRetryTaskChecker() {
        while (isRunning.get()) {
            try {
                val currentTime = System.currentTimeMillis()
                val retryingTasks = tasks.values.filter { 
                    it.status == TaskStatus.RETRYING && 
                    it.nextRetryTime != null && 
                    currentTime >= it.nextRetryTime!!
                }
                
                retryingTasks.forEach { task ->
                    val pendingTask = task.copy(status = TaskStatus.PENDING)
                    tasks[task.id] = pendingTask
                    taskQueue.offer(pendingTask)
                }
                
                delay(5000) // 每5秒检查一次
            } catch (e: Exception) {
                if (config.isDebugMode()) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 统计信息更新器
     */
    private suspend fun startStatisticsUpdater() {
        while (isRunning.get()) {
            try {
                // 这里可以添加定期统计信息更新逻辑
                delay(config.monitor.metricsInterval)
            } catch (e: Exception) {
                if (config.isDebugMode()) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * 重新排序任务队列
     */
    private fun reorderTaskQueue() {
        if (taskQueue.size > 10) { // 只有队列中任务较多时才重新排序
            val currentTasks = mutableListOf<Task>()
            taskQueue.drainTo(currentTasks)
            
            // 重新计算优先级分数并排序
            currentTasks.sortByDescending { it.getPriorityScore() }
            taskQueue.addAll(currentTasks)
        }
    }
    
    /**
     * 检查超时任务
     */
    private suspend fun checkTimeoutTasks() {
        val currentTime = System.currentTimeMillis()
        val timeoutTasks = tasks.values.filter { task ->
            task.status == TaskStatus.RUNNING &&
            task.lastExecutionTime != null &&
            (currentTime - task.lastExecutionTime!!) > task.timeout
        }
        
        timeoutTasks.forEach { task ->
            val timeoutResult = TaskResult(
                success = false,
                message = "任务执行超时",
                executionTime = currentTime - (task.lastExecutionTime ?: currentTime),
                exception = RuntimeException("Task execution timeout")
            )
            markTaskFailed(task.id, timeoutResult)
        }
    }
    
    /**
     * 计算吞吐量
     */
    private fun calculateThroughput(): Double {
        // 简单实现：基于完成的任务数计算
        val completedTasks = completedTasksCounter.get()
        val runningTime = if (schedulerJob?.isActive == true) {
            // 这里应该记录启动时间，简化处理
            60.0 // 假设运行了60秒
        } else 1.0
        
        return completedTasks / runningTime
    }
}