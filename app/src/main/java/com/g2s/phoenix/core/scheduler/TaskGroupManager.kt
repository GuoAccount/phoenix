package com.g2s.phoenix.core.scheduler

import com.g2s.phoenix.model.*
import com.g2s.phoenix.core.engine.OperationEngine
import com.g2s.phoenix.core.selector.OcrElementSelector

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务组状态枚举
 */
enum class TaskGroupStatus {
    READY,      // 就绪状态，可以执行
    RUNNING,    // 运行中
    SUSPENDED,  // 挂起状态，等待异步任务完成
    COMPLETED,  // 已完成
    FAILED,     // 执行失败
    CANCELLED   // 已取消
}

/**
 * 任务组数据模型
 */
data class TaskGroup(
    val id: String,
    val name: String,
    val description: String = "",
    val level: Int = 1,                    // 任务组级别，用于排序
    val enabled: Boolean = true,           // 是否启用
    val tasks: MutableList<Task> = mutableListOf(), // 任务列表
    val dependencies: List<String> = emptyList(),   // 依赖的任务组ID
    val conditions: List<TaskCondition> = emptyList(), // 执行条件
    val skipStrategy: TaskSkipStrategy = TaskSkipStrategy(), // 智能跳过策略
    val waitAfter: Long = 1000L,          // 执行后等待时间
    val timeout: Long = 3600000L,         // 任务组超时时间
    val retryPolicy: TaskRetryPolicy = TaskRetryPolicy(), // 重试策略
    val createdAt: Long = System.currentTimeMillis(),
    var status: TaskGroupStatus = TaskGroupStatus.READY,
    var currentTaskIndex: Int = 0,        // 当前执行的任务索引
    var startTime: Long? = null,
    var endTime: Long? = null,
    var lastError: String? = null,
    var executionCount: Int = 0
) {
    /**
     * 获取下一个待执行的任务
     */
    fun getNextTask(): Task? {
        return if (currentTaskIndex < tasks.size) {
            tasks[currentTaskIndex]
        } else {
            null
        }
    }
    
    /**
     * 标记当前任务完成，移动到下一个任务
     */
    fun moveToNextTask() {
        currentTaskIndex++
    }
    
    /**
     * 检查是否还有下一个任务
     */
    fun hasNextTask(): Boolean {
        return currentTaskIndex < tasks.size
    }
    
    /**
     * 检查任务组是否完成
     */
    fun isCompleted(): Boolean {
        return currentTaskIndex >= tasks.size
    }
    
    /**
     * 重置任务组状态
     */
    fun reset() {
        currentTaskIndex = 0
        status = TaskGroupStatus.READY
        startTime = null
        endTime = null
        lastError = null
    }
    
    /**
     * 检查是否可以执行（满足依赖条件）
     */
    fun canExecute(completedGroups: Set<String>): Boolean {
        return enabled && dependencies.all { it in completedGroups }
    }
}

/**
 * 任务条件
 */
data class TaskCondition(
    val type: String,                     // 条件类型：element_exists, element_not_exists, text_contains等
    val selector: String,                 // 选择器
    val expectedValue: String? = null,    // 期望值
    val timeout: Long = 5000L            // 检查超时时间
)

/**
 * 智能跳过条件配置
 */
data class SkipCondition(
    val type: String,                     // 跳过类型：text_exists, element_exists, state_check等
    val patterns: List<String>,           // 匹配模式列表
    val timeout: Long = 3000L,           // 检查超时时间
    val description: String = ""          // 条件描述
)

/**
 * 任务跳过策略配置
 */
data class TaskSkipStrategy(
    val enabled: Boolean = true,          // 是否启用智能跳过
    val skipConditions: List<SkipCondition> = emptyList(), // 跳过条件列表
    val skipOnTaskFailure: Boolean = false, // 任务失败时是否检查跳过条件
    val skipEntireGroup: Boolean = false   // 是否跳过整个任务组
)

/**
 * 任务重试策略
 */
data class TaskRetryPolicy(
    val maxRetries: Int = 3,             // 最大重试次数
    val retryDelay: Long = 1000L,        // 重试延迟
    val backoffMultiplier: Double = 2.0,  // 退避倍数
    val retryOnFailure: Boolean = true    // 失败时是否重试
)

/**
 * 任务组管理器
 * 实现旧架构中的任务组调度逻辑
 */
class TaskGroupManager(
    private val operationEngine: OperationEngine? = null, // 操作执行引擎
    private val ocrElementSelector: OcrElementSelector? = null, // OCR元素选择器
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    
    // 任务组双向链表
    private val taskGroups = LinkedList<TaskGroup>()
    private val taskGroupMap = ConcurrentHashMap<String, TaskGroup>()
    private val completedGroups = mutableSetOf<String>()
    
    // 状态管理
    private val isRunning = AtomicBoolean(false)
    private val mutex = Mutex()
    
    // 事件流
    private val _groupUpdates = MutableSharedFlow<TaskGroupUpdate>()
    val groupUpdates: SharedFlow<TaskGroupUpdate> = _groupUpdates.asSharedFlow()
    
    /**
     * 添加任务组
     */
    suspend fun addTaskGroup(taskGroup: TaskGroup): Boolean {
        return mutex.withLock {
            if (taskGroupMap.containsKey(taskGroup.id)) {
                false
            } else {
                // 按级别插入到合适位置
                val insertIndex = taskGroups.indexOfFirst { it.level > taskGroup.level }
                if (insertIndex == -1) {
                    taskGroups.add(taskGroup)
                } else {
                    taskGroups.add(insertIndex, taskGroup)
                }
                taskGroupMap[taskGroup.id] = taskGroup
                
                _groupUpdates.emit(TaskGroupUpdate.Added(taskGroup))
                true
            }
        }
    }
    
    /**
     * 批量添加任务组
     */
    suspend fun addTaskGroups(groups: List<TaskGroup>): List<Boolean> {
        return groups.map { addTaskGroup(it) }
    }
    
    /**
     * 移除任务组
     */
    suspend fun removeTaskGroup(groupId: String): Boolean {
        return mutex.withLock {
            val group = taskGroupMap.remove(groupId)
            if (group != null) {
                taskGroups.remove(group)
                _groupUpdates.emit(TaskGroupUpdate.Removed(group))
                true
            } else {
                false
            }
        }
    }
    
    /**
     * 获取下一个就绪的任务组
     */
    suspend fun getNextReadyGroup(): TaskGroup? {
        return mutex.withLock {
            taskGroups.find { group ->
                group.status == TaskGroupStatus.READY && 
                group.canExecute(completedGroups)
            }
        }
    }
    
    /**
     * 更新任务组状态
     */
    suspend fun updateGroupStatus(groupId: String, status: TaskGroupStatus, error: String? = null) {
        mutex.withLock {
            taskGroupMap[groupId]?.let { group ->
                val oldStatus = group.status
                group.status = status
                group.lastError = error
                
                when (status) {
                    TaskGroupStatus.RUNNING -> {
                        group.startTime = System.currentTimeMillis()
                    }
                    TaskGroupStatus.COMPLETED -> {
                        group.endTime = System.currentTimeMillis()
                        completedGroups.add(groupId)
                        // 从链表中移除已完成的任务组
                        taskGroups.remove(group)
                    }
                    TaskGroupStatus.FAILED -> {
                        group.endTime = System.currentTimeMillis()
                        // 检查是否需要重试
                        if (group.executionCount < group.retryPolicy.maxRetries) {
                            group.executionCount++
                            group.reset()
                            // 延迟后重新加入队列
                            scope.launch {
                                delay(group.retryPolicy.retryDelay * 
                                     Math.pow(group.retryPolicy.backoffMultiplier, group.executionCount.toDouble()).toLong())
                                mutex.withLock {
                                    group.status = TaskGroupStatus.READY
                                }
                            }
                        } else {
                            // 超过最大重试次数，移除任务组
                            taskGroups.remove(group)
                        }
                    }
                    else -> {}
                }
                
                _groupUpdates.emit(TaskGroupUpdate.StatusChanged(group, oldStatus, status))
            }
        }
    }
    
    /**
     * 挂起任务组（异步任务执行时）
     */
    suspend fun suspendGroup(groupId: String) {
        updateGroupStatus(groupId, TaskGroupStatus.SUSPENDED)
        
        // 将挂起的任务组移到队尾
        mutex.withLock {
            taskGroupMap[groupId]?.let { group ->
                if (taskGroups.remove(group)) {
                    taskGroups.addLast(group)
                }
            }
        }
    }
    
    /**
     * 恢复任务组（异步任务完成时）
     */
    suspend fun resumeGroup(groupId: String, success: Boolean = true) {
        if (success) {
            // 移动到下一个任务
            taskGroupMap[groupId]?.moveToNextTask()
            updateGroupStatus(groupId, TaskGroupStatus.READY)
        } else {
            updateGroupStatus(groupId, TaskGroupStatus.FAILED, "异步任务执行失败")
        }
    }
    
    /**
     * 执行任务组（支持智能跳过功能）
     * 实现旧架构中的任务组智能执行逻辑
     */
    suspend fun executeGroup(group: TaskGroup): Boolean {
        return try {
            // 更新状态为运行中
            updateGroupStatus(group.id, TaskGroupStatus.RUNNING)
            
            // 检查执行条件
            if (!checkExecutionConditions(group)) {
                // 条件不满足，智能跳过当前任务组
                updateGroupStatus(group.id, TaskGroupStatus.COMPLETED)
                return true
            }
            
            // 执行任务组中的任务
            var allTasksSuccessful = true
            
            while (group.hasNextTask()) {
                val task = group.getNextTask() ?: break
                
                try {
                    // 检查任务执行前的状态（智能跳过逻辑）
                    if (shouldSkipTask(task, group)) {
                        // 跳过当前任务，直接标记为完成
                        group.moveToNextTask()
                        continue
                    }
                    
                    // 执行任务
                    val taskResult = executeTask(task, group)
                    
                    if (taskResult) {
                        group.moveToNextTask()
                        
                        // 任务间等待
                        if (group.waitAfter > 0) {
                            delay(group.waitAfter)
                        }
                    } else {
                        allTasksSuccessful = false
                        
                        // 检查是否应该跳过整个任务组
                        if (shouldSkipGroupOnTaskFailure(task, group)) {
                            // 智能跳过：任务失败但检测到目标状态已达成
                            updateGroupStatus(group.id, TaskGroupStatus.COMPLETED)
                            return true
                        }
                        
                        // 任务失败，退出任务组
                        break
                    }
                } catch (e: Exception) {
                    allTasksSuccessful = false
                    group.lastError = "任务执行异常: ${e.message}"
                    break
                }
            }
            
            // 更新最终状态
            if (allTasksSuccessful) {
                updateGroupStatus(group.id, TaskGroupStatus.COMPLETED)
            } else {
                updateGroupStatus(group.id, TaskGroupStatus.FAILED, group.lastError)
            }
            
            allTasksSuccessful
        } catch (e: Exception) {
            updateGroupStatus(group.id, TaskGroupStatus.FAILED, "任务组执行异常: ${e.message}")
            false
        }
    }
    
    /**
     * 检查执行条件
     */
    private suspend fun checkExecutionConditions(group: TaskGroup): Boolean {
        for (condition in group.conditions) {
            if (!evaluateCondition(condition)) {
                return false
            }
        }
        return true
    }
    
    /**
     * 评估单个条件
     */
    private suspend fun evaluateCondition(condition: TaskCondition): Boolean {
        return try {
            when (condition.type) {
                "element_exists" -> {
                    // 检查元素是否存在
                    checkElementExists(condition.selector, condition.timeout)
                }
                "element_not_exists" -> {
                    // 检查元素是否不存在
                    !checkElementExists(condition.selector, condition.timeout)
                }
                "text_contains" -> {
                    // 检查页面是否包含指定文本
                    checkTextExists(condition.expectedValue ?: "", condition.timeout)
                }
                "app_launched" -> {
                    // 检查应用是否已启动
                    checkAppLaunched(condition.selector)
                }
                else -> true // 未知条件类型，默认通过
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 智能跳过任务判断
     * 基于配置的跳过条件进行智能判断
     */
    private suspend fun shouldSkipTask(task: Task, group: TaskGroup): Boolean {
        return try {
            // 检查任务组的跳过策略是否启用
            if (!group.skipStrategy.enabled) {
                return false
            }
            
            // 检查任务级别的跳过条件
            val taskSkipStrategy = task.skipStrategy ?: TaskSkipStrategy(enabled = false)
            if (taskSkipStrategy.enabled) {
                if (checkSkipConditions(taskSkipStrategy.skipConditions)) {
                    return true
                }
            }
            
            // 检查任务组级别的跳过条件
            checkSkipConditions(group.skipStrategy.skipConditions)
        } catch (e: Exception) {
            false // 检查失败时不跳过
        }
    }
    
    /**
     * 任务失败时是否应该跳过整个任务组
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun shouldSkipGroupOnTaskFailure(task: Task, group: TaskGroup): Boolean {
        return try {
            // 检查跳过策略是否启用任务失败时的跳过检查
            if (!group.skipStrategy.enabled || !group.skipStrategy.skipOnTaskFailure) {
                return false
            }
            
            // 检查是否应该跳过整个任务组
            if (group.skipStrategy.skipEntireGroup) {
                return checkSkipConditions(group.skipStrategy.skipConditions)
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查跳过条件列表
     */
    private suspend fun checkSkipConditions(conditions: List<SkipCondition>): Boolean {
        for (condition in conditions) {
            if (checkSingleSkipCondition(condition)) {
                return true
            }
        }
        return false
    }
    
    /**
     * 检查单个跳过条件
     */
    private suspend fun checkSingleSkipCondition(condition: SkipCondition): Boolean {
        return try {
            when (condition.type) {
                "text_exists" -> {
                    // 检查页面是否包含指定文本模式
                    condition.patterns.any { pattern ->
                        ocrElementSelector?.findElementByText(
                            text = pattern
                        ) != null
                    }
                }
                "element_exists" -> {
                    // 检查元素是否存在
                    condition.patterns.any { selector ->
                        checkElementExists(selector, condition.timeout)
                    }
                }
                "state_check" -> {
                    // 检查应用状态
                    condition.patterns.any { state ->
                        checkApplicationState(state)
                    }
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 执行单个任务
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun executeTask(task: Task, group: TaskGroup): Boolean {
        return try {
            // 检查OperationEngine是否可用
            val engine = operationEngine ?: return false
            
            // 创建操作上下文
            val context = com.g2s.phoenix.core.engine.OperationContext(
                taskId = task.id,
                operationId = "task_${task.id}_${System.currentTimeMillis()}"
            )
            
            // 根据任务类型调用相应的操作
            val result = when (task.type) {
                "click" -> {
                    engine.executeOperation(
                        com.g2s.phoenix.core.engine.OperationType.CLICK,
                        com.g2s.phoenix.core.engine.OperationParams(
                            selector = task.target.selector,
                            timeout = task.target.timeout
                        ),
                        context
                    )
                }
                "swipe" -> {
                    val direction = task.parameters["direction"] as? String ?: "up"
                    engine.executeOperation(
                        com.g2s.phoenix.core.engine.OperationType.SWIPE,
                        com.g2s.phoenix.core.engine.OperationParams(
                            selector = direction,
                            value = task.parameters["distance"] as? Int ?: 500
                        ),
                        context
                    )
                }
                "input" -> {
                    val text = task.parameters["text"] as? String ?: ""
                    engine.executeOperation(
                        com.g2s.phoenix.core.engine.OperationType.INPUT,
                        com.g2s.phoenix.core.engine.OperationParams(
                            selector = task.target.selector,
                            value = text,
                            timeout = task.target.timeout
                        ),
                        context
                    )
                }
                "back" -> {
                    engine.executeOperation(
                        com.g2s.phoenix.core.engine.OperationType.SYSTEM,
                        com.g2s.phoenix.core.engine.OperationParams(
                            selector = "back"
                        ),
                        context
                    )
                }
                "wait" -> {
                    val duration = task.parameters["duration"] as? Long ?: 1000L
                    delay(duration)
                    return true
                }
                "scroll" -> {
                    val direction = task.parameters["direction"] as? String ?: "down"
                    engine.executeOperation(
                        com.g2s.phoenix.core.engine.OperationType.SWIPE,
                        com.g2s.phoenix.core.engine.OperationParams(
                            selector = "scroll_$direction",
                            value = task.parameters["count"] as? Int ?: 1
                        ),
                        context
                    )
                }
                "launch_app" -> {
                    val packageName = task.parameters["package"] as? String ?: ""
                    engine.executeOperation(
                        com.g2s.phoenix.core.engine.OperationType.SYSTEM,
                        com.g2s.phoenix.core.engine.OperationParams(
                            selector = "launch_app",
                            value = packageName
                        ),
                        context
                    )
                }
                else -> {
                    // 未知任务类型，返回失败
                    return false
                }
            }
            
            result.success
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查应用状态
     */
    private suspend fun checkApplicationState(state: String): Boolean {
        return try {
            when (state) {
                "app_launched" -> {
                    // 检查应用是否已启动
                    checkAppRunning()
                }
                "network_available" -> {
                    // 检查网络是否可用
                    checkNetworkAvailable()
                }
                "screen_on" -> {
                    // 检查屏幕是否点亮
                    checkScreenOn()
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    // 以下是智能检测方法的实现
    
    /**
     * 检查元素是否存在
     */
    private suspend fun checkElementExists(selector: String, timeout: Long): Boolean {
        // 使用OCR或无障碍服务检查元素是否存在
        return try {
            operationEngine?.checkElementExists(selector, timeout) ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查应用是否正在运行
     */
    private suspend fun checkAppRunning(): Boolean {
        return try {
            // 通过系统操作检查应用状态
            val context = com.g2s.phoenix.core.engine.OperationContext(
                taskId = "state_check",
                operationId = "app_running_check_${System.currentTimeMillis()}"
            )
            val result = operationEngine?.executeOperation(
                com.g2s.phoenix.core.engine.OperationType.SYSTEM,
                com.g2s.phoenix.core.engine.OperationParams(
                    selector = "check_app_running"
                ),
                context
            )
            result?.success ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查网络是否可用
     */
    private suspend fun checkNetworkAvailable(): Boolean {
        return try {
            // 通过系统操作检查网络状态
            val context = com.g2s.phoenix.core.engine.OperationContext(
                taskId = "state_check",
                operationId = "network_check_${System.currentTimeMillis()}"
            )
            val result = operationEngine?.executeOperation(
                com.g2s.phoenix.core.engine.OperationType.SYSTEM,
                com.g2s.phoenix.core.engine.OperationParams(
                    selector = "check_network"
                ),
                context
            )
            result?.success ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查屏幕是否点亮
     */
    private suspend fun checkScreenOn(): Boolean {
        return try {
            // 通过系统操作检查屏幕状态
            val context = com.g2s.phoenix.core.engine.OperationContext(
                taskId = "state_check",
                operationId = "screen_check_${System.currentTimeMillis()}"
            )
            val result = operationEngine?.executeOperation(
                com.g2s.phoenix.core.engine.OperationType.SYSTEM,
                com.g2s.phoenix.core.engine.OperationParams(
                    selector = "check_screen_on"
                ),
                context
            )
            result?.success ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private suspend fun checkTextExists(text: String, timeout: Long): Boolean {
        // 使用OCR检查文本是否存在
        return try {
            delay(100)
            false
        } catch (e: Exception) {
            false
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private suspend fun checkAppLaunched(packageName: String): Boolean {
        // 检查应用是否已启动
        return try {
            delay(50)
            true // 暂时返回true
        } catch (e: Exception) {
            false
        }
    }
    
    // 旧的硬编码检查方法已移除，现在使用配置化的跳过条件系统
    
    /**
     * 停止所有任务组
     */
    suspend fun stopAllGroups() {
        mutex.withLock {
            taskGroups.forEach { group ->
                if (group.status == TaskGroupStatus.RUNNING) {
                    group.status = TaskGroupStatus.FAILED
                    group.lastError = "用户停止执行"
                }
            }
        }
    }
    
    /**
     * 检查是否有活跃的任务组
     */
    suspend fun hasActiveGroups(): Boolean {
        return mutex.withLock {
            taskGroups.isNotEmpty()
        }
    }
    
    /**
     * 获取所有任务组状态
     */
    suspend fun getAllGroups(): List<TaskGroup> {
        return mutex.withLock {
            taskGroups.toList()
        }
    }
    
    /**
     * 获取任务组统计信息
     */
    suspend fun getStatistics(): TaskGroupStatistics {
        return mutex.withLock {
            val total = taskGroups.size + completedGroups.size
            val ready = taskGroups.count { it.status == TaskGroupStatus.READY }
            val running = taskGroups.count { it.status == TaskGroupStatus.RUNNING }
            val suspended = taskGroups.count { it.status == TaskGroupStatus.SUSPENDED }
            val completed = completedGroups.size
            val failed = taskGroups.count { it.status == TaskGroupStatus.FAILED }
            
            TaskGroupStatistics(
                total = total,
                ready = ready,
                running = running,
                suspended = suspended,
                completed = completed,
                failed = failed
            )
        }
    }
    
    /**
     * 清理已完成的任务组
     */
    suspend fun cleanup() {
        mutex.withLock {
            completedGroups.clear()
        }
    }
    
    /**
     * 启动任务组管理器
     */
    fun start() {
        isRunning.set(true)
    }
    
    /**
     * 停止任务组管理器
     */
    suspend fun stop() {
        isRunning.set(false)
        mutex.withLock {
            taskGroups.clear()
            taskGroupMap.clear()
            completedGroups.clear()
        }
    }
}

/**
 * 任务组更新事件
 */
sealed class TaskGroupUpdate {
    data class Added(val group: TaskGroup) : TaskGroupUpdate()
    data class Removed(val group: TaskGroup) : TaskGroupUpdate()
    data class StatusChanged(val group: TaskGroup, val oldStatus: TaskGroupStatus, val newStatus: TaskGroupStatus) : TaskGroupUpdate()
}

/**
 * 任务组统计信息
 */
data class TaskGroupStatistics(
    val total: Int,
    val ready: Int,
    val running: Int,
    val suspended: Int,
    val completed: Int,
    val failed: Int
)