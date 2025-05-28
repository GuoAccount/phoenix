package com.g2s.phoenix.core

import com.g2s.phoenix.config.JsonTaskLoader
import com.g2s.phoenix.config.TaskConfig
import com.g2s.phoenix.core.scheduler.TaskGroup
import com.g2s.phoenix.core.scheduler.TaskGroupManager
import com.g2s.phoenix.model.Task
import com.g2s.phoenix.model.TaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一任务入口管理器
 * 提供任务加载、选择、队列管理等功能，支持旧架构的任务解耦设计
 */
class TaskEntryManager {
    
    private val jsonTaskLoader = JsonTaskLoader()
    private val taskGroupManager = TaskGroupManager()
    private val mutex = Mutex()
    private val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 任务配置存储
    private val taskConfigs = ConcurrentHashMap<String, TaskConfig>()
    private val availableTaskGroups = ConcurrentHashMap<String, TaskGroup>()
    
    // 执行队列
    private val executionQueue = mutableListOf<TaskExecutionItem>()
    private val executionHistory = mutableListOf<TaskExecutionRecord>()
    private val executionIdGenerator = AtomicLong(0)
    
    // 状态流
    private val _availableTasks = MutableStateFlow<List<TaskInfo>>(emptyList())
    val availableTasks: StateFlow<List<TaskInfo>> = _availableTasks.asStateFlow()
    
    private val _executionQueue = MutableStateFlow<List<TaskExecutionItem>>(emptyList())
    val executionQueueState: StateFlow<List<TaskExecutionItem>> = _executionQueue.asStateFlow()
    
    private val _executionStatus = MutableStateFlow(ExecutionStatus.IDLE)
    val executionStatus: StateFlow<ExecutionStatus> = _executionStatus.asStateFlow()
    
    /**
     * 从JSON字符串加载任务配置
     */
    suspend fun loadTaskConfigFromString(jsonString: String): LoadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val config = jsonTaskLoader.loadTaskConfigFromString(jsonString)
                    ?: return@withLock LoadResult.Error("无法解析任务配置JSON")
                
                val configId = generateConfigId(config)
                taskConfigs[configId] = config
                
                // 注册任务组
                config.taskGroups.forEach { taskGroup ->
                    val groupId = "${configId}_${taskGroup.id}"
                    availableTaskGroups[groupId] = taskGroup.copy(id = groupId)
                }
                
                updateAvailableTasksList()
                
                LoadResult.Success(
                    loadedCount = 1,
                    totalGroups = config.taskGroups.size,
                    message = "成功加载 1 个任务配置，共 ${config.taskGroups.size} 个任务组"
                )
            } catch (e: Exception) {
                LoadResult.Error("加载任务配置失败: ${e.message}")
            }
        }
    }
    
    /**
     * 从目录加载所有任务配置
     */
    suspend fun loadTasksFromDirectory(directory: File): LoadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val configs = jsonTaskLoader.loadTaskConfigsFromDirectory(directory)
                val loadedCount = configs.size
                
                configs.forEach { config ->
                    val configId = generateConfigId(config)
                    taskConfigs[configId] = config
                    
                    // 注册任务组
                    config.taskGroups.forEach { taskGroup ->
                        val groupId = "${configId}_${taskGroup.id}"
                        availableTaskGroups[groupId] = taskGroup.copy(id = groupId)
                    }
                }
                
                updateAvailableTasksList()
                
                LoadResult.Success(
                    loadedCount = loadedCount,
                    totalGroups = availableTaskGroups.size,
                    message = "成功加载 $loadedCount 个任务配置，共 ${availableTaskGroups.size} 个任务组"
                )
            } catch (e: Exception) {
                LoadResult.Error("加载任务配置失败: ${e.message}")
            }
        }
    }
    
    /**
     * 从单个文件加载任务配置
     */
    suspend fun loadTaskFromFile(file: File): LoadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val config = jsonTaskLoader.loadTaskConfig(file)
                    ?: return@withLock LoadResult.Error("无法解析任务配置文件: ${file.name}")
                
                val configId = generateConfigId(config)
                taskConfigs[configId] = config
                
                // 注册任务组
                config.taskGroups.forEach { taskGroup ->
                    val groupId = "${configId}_${taskGroup.id}"
                    availableTaskGroups[groupId] = taskGroup.copy(id = groupId)
                }
                
                updateAvailableTasksList()
                
                LoadResult.Success(
                    loadedCount = 1,
                    totalGroups = config.taskGroups.size,
                    message = "成功加载任务配置: ${config.app.name}，包含 ${config.taskGroups.size} 个任务组"
                )
            } catch (e: Exception) {
                LoadResult.Error("加载任务配置失败: ${e.message}")
            }
        }
    }
    
    /**
     * 从JSON字符串加载任务配置
     */
    suspend fun loadTaskFromJson(jsonString: String, name: String = "custom"): LoadResult {
        mutex.withLock {
            try {
                val config = jsonTaskLoader.loadTaskConfigFromString(jsonString)
                    ?: return LoadResult.Error("无法解析JSON任务配置")
                
                val configId = "custom_${name}_${System.currentTimeMillis()}"
                taskConfigs[configId] = config
                
                // 注册任务组
                config.taskGroups.forEach { taskGroup ->
                    val groupId = "${configId}_${taskGroup.id}"
                    availableTaskGroups[groupId] = taskGroup.copy(id = groupId)
                }
                
                updateAvailableTasksList()
                
                return LoadResult.Success(
                    loadedCount = 1,
                    totalGroups = config.taskGroups.size,
                    message = "成功加载自定义任务配置，包含 ${config.taskGroups.size} 个任务组"
                )
            } catch (e: Exception) {
                return LoadResult.Error("加载JSON任务配置失败: ${e.message}")
            }
        }
    }
    
    /**
     * 获取所有可用的任务信息
     */
    fun getAvailableTasks(): List<TaskInfo> {
        return _availableTasks.value
    }
    
    /**
     * 根据ID获取任务组
     */
    fun getTaskGroup(groupId: String): TaskGroup? {
        return availableTaskGroups[groupId]
    }
    
    /**
     * 搜索任务
     */
    fun searchTasks(query: String): List<TaskInfo> {
        if (query.isBlank()) return getAvailableTasks()
        
        return getAvailableTasks().filter { taskInfo ->
            taskInfo.name.contains(query, ignoreCase = true) ||
            taskInfo.description.contains(query, ignoreCase = true) ||
            taskInfo.appName.contains(query, ignoreCase = true) ||
            taskInfo.tags.any { it.contains(query, ignoreCase = true) }
        }
    }
    
    /**
     * 添加任务到执行队列
     */
    suspend fun addToExecutionQueue(
        taskGroupIds: List<String>,
        options: ExecutionOptions = ExecutionOptions()
    ): Boolean {
        mutex.withLock {
            try {
                val validGroups = taskGroupIds.mapNotNull { groupId ->
                    availableTaskGroups[groupId]
                }.filter { it.enabled }
                
                if (validGroups.isEmpty()) {
                    return false
                }
                
                val executionId = executionIdGenerator.incrementAndGet()
                val executionItem = TaskExecutionItem(
                    id = executionId,
                    taskGroups = validGroups,
                    options = options,
                    status = TaskExecutionStatus.QUEUED,
                    createdAt = System.currentTimeMillis()
                )
                
                executionQueue.add(executionItem)
                updateExecutionQueueState()
                
                return true
            } catch (e: Exception) {
                return false
            }
        }
    }
    
    /**
     * 移除队列中的任务
     */
    suspend fun removeFromExecutionQueue(executionId: Long): Boolean {
        mutex.withLock {
            val removed = executionQueue.removeIf { it.id == executionId && it.status == TaskExecutionStatus.QUEUED }
            if (removed) {
                updateExecutionQueueState()
            }
            return removed
        }
    }
    
    /**
     * 清空执行队列
     */
    suspend fun clearExecutionQueue(): Boolean {
        mutex.withLock {
            val queuedItems = executionQueue.filter { it.status == TaskExecutionStatus.QUEUED }
            executionQueue.removeAll(queuedItems)
            updateExecutionQueueState()
            return queuedItems.isNotEmpty()
        }
    }
    
    /**
     * 开始执行队列中的任务
     */
    suspend fun startExecution(): Boolean {
        if (_executionStatus.value != ExecutionStatus.IDLE) {
            return false
        }
        
        val queuedItems = mutex.withLock {
            executionQueue.filter { it.status == TaskExecutionStatus.QUEUED }
        }
        
        if (queuedItems.isEmpty()) {
            return false
        }
        
        _executionStatus.value = ExecutionStatus.RUNNING
        
        executionScope.launch {
            try {
                for (item in queuedItems) {
                    if (_executionStatus.value != ExecutionStatus.RUNNING) {
                        break
                    }
                    
                    executeTaskItem(item)
                }
            } finally {
                _executionStatus.value = ExecutionStatus.IDLE
            }
        }
        
        return true
    }
    
    /**
     * 停止执行
     */
    suspend fun stopExecution(): Boolean {
        if (_executionStatus.value != ExecutionStatus.RUNNING) {
            return false
        }
        
        _executionStatus.value = ExecutionStatus.STOPPING
        
        // 停止任务组管理器
        taskGroupManager.stopAllGroups()
        
        // 等待当前任务完成
        delay(1000)
        
        _executionStatus.value = ExecutionStatus.IDLE
        return true
    }
    
    /**
     * 暂停执行
     */
    suspend fun pauseExecution(): Boolean {
        if (_executionStatus.value != ExecutionStatus.RUNNING) {
            return false
        }
        
        _executionStatus.value = ExecutionStatus.PAUSED
        return true
    }
    
    /**
     * 恢复执行
     */
    suspend fun resumeExecution(): Boolean {
        if (_executionStatus.value != ExecutionStatus.PAUSED) {
            return false
        }
        
        _executionStatus.value = ExecutionStatus.RUNNING
        return true
    }
    
    /**
     * 获取执行历史
     */
    fun getExecutionHistory(limit: Int = 50): List<TaskExecutionRecord> {
        return executionHistory.takeLast(limit)
    }
    
    /**
     * 获取执行统计
     */
    fun getExecutionStats(): ExecutionStats {
        val history = executionHistory
        val total = history.size
        val successful = history.count { it.success }
        val failed = total - successful
        val avgDuration = if (total > 0) {
            history.map { it.duration }.average().toLong()
        } else 0L
        
        return ExecutionStats(
            totalExecutions = total,
            successfulExecutions = successful,
            failedExecutions = failed,
            averageDuration = avgDuration,
            lastExecutionTime = history.lastOrNull()?.endTime
        )
    }
    
    /**
     * 执行单个任务项
     */
    private suspend fun executeTaskItem(item: TaskExecutionItem) {
        val startTime = System.currentTimeMillis()
        var success = true
        var errorMessage: String? = null
        
        try {
            // 更新状态为执行中
            updateTaskItemStatus(item.id, TaskExecutionStatus.RUNNING)
            
            // 按优先级排序任务组
            val sortedGroups = item.taskGroups.sortedBy { it.level }
            
            for (group in sortedGroups) {
                if (_executionStatus.value == ExecutionStatus.STOPPING) {
                    break
                }
                
                // 等待暂停状态
                while (_executionStatus.value == ExecutionStatus.PAUSED) {
                    delay(100)
                }
                
                try {
                    // 执行任务组
                    val result = taskGroupManager.executeGroup(group)
                    if (!result) {
                        success = false
                        errorMessage = "任务组执行失败: ${group.name}"
                        
                        // 根据选项决定是否继续
                        if (!item.options.continueOnFailure) {
                            break
                        }
                    } else {
                        _executionStatus.value = ExecutionStatus.RUNNING
                    }
                } catch (e: Exception) {
                    success = false
                    errorMessage = "任务组执行异常: ${group.name}, ${e.message}"
                    
                    if (!item.options.continueOnFailure) {
                        break
                    }
                }
                
                // 任务组间等待
                if (item.options.delayBetweenGroups > 0) {
                    delay(item.options.delayBetweenGroups)
                }
            }
            
            // 更新状态为完成
            val finalStatus = if (success) TaskExecutionStatus.COMPLETED else TaskExecutionStatus.FAILED
            updateTaskItemStatus(item.id, finalStatus)
            
        } catch (e: Exception) {
            success = false
            errorMessage = "执行异常: ${e.message}"
            updateTaskItemStatus(item.id, TaskExecutionStatus.FAILED)
        } finally {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            
            // 记录执行历史
            val record = TaskExecutionRecord(
                executionId = item.id,
                taskGroups = item.taskGroups.map { it.name },
                success = success,
                startTime = startTime,
                endTime = endTime,
                duration = duration,
                errorMessage = errorMessage
            )
            
            mutex.withLock {
                executionHistory.add(record)
                // 保持历史记录数量限制
                if (executionHistory.size > 1000) {
                    executionHistory.removeAt(0)
                }
            }
        }
    }
    
    /**
     * 更新任务项状态
     */
    private suspend fun updateTaskItemStatus(executionId: Long, status: TaskExecutionStatus) {
        mutex.withLock {
            val item = executionQueue.find { it.id == executionId }
            if (item != null) {
                val index = executionQueue.indexOf(item)
                executionQueue[index] = item.copy(status = status)
                updateExecutionQueueState()
            }
        }
    }
    
    /**
     * 更新可用任务列表
     */
    private fun updateAvailableTasksList() {
        val taskInfos = mutableListOf<TaskInfo>()
        
        taskConfigs.forEach { (configId, config) ->
            config.taskGroups.forEach { group ->
                val groupId = "${configId}_${group.id}"
                val taskInfo = TaskInfo(
                    id = groupId,
                    name = group.name,
                    description = group.description,
                    appName = config.app.name,
                    appPackage = config.app.pkg,
                    level = group.level,
                    enabled = group.enabled,
                    taskCount = group.tasks.size,
                    estimatedDuration = estimateGroupDuration(group),
                    tags = extractTags(group),
                    dependencies = group.dependencies
                )
                taskInfos.add(taskInfo)
            }
        }
        
        _availableTasks.value = taskInfos.sortedWith(
            compareBy<TaskInfo> { it.level }
                .thenBy { it.appName }
                .thenBy { it.name }
        )
    }
    
    /**
     * 更新执行队列状态
     */
    private fun updateExecutionQueueState() {
        _executionQueue.value = executionQueue.toList()
    }
    
    /**
     * 生成配置ID
     */
    private fun generateConfigId(config: TaskConfig): String {
        return "${config.app.pkg}_${System.currentTimeMillis()}"
    }
    
    /**
     * 估算任务组执行时间
     */
    private fun estimateGroupDuration(group: TaskGroup): Long {
        return group.tasks.sumOf { task ->
            task.timeout.toLong() + ((task.parameters["wait_after"] as? Number)?.toLong() ?: 1000L) // 确保所有数值都转换为Long类型
        }
    }
    
    /**
     * 提取任务组标签
     */
    private fun extractTags(group: TaskGroup): List<String> {
        val tags = mutableSetOf<String>()
        
        // 从任务类型提取标签
        group.tasks.forEach { task ->
            tags.add(task.type.lowercase()) // task.type 已经是 String 类型
        }
        
        // 从任务名称提取标签
        if (group.name.contains("签到", ignoreCase = true)) tags.add("签到")
        if (group.name.contains("浏览", ignoreCase = true)) tags.add("浏览")
        if (group.name.contains("点赞", ignoreCase = true)) tags.add("点赞")
        if (group.name.contains("分享", ignoreCase = true)) tags.add("分享")
        
        return tags.toList()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        executionScope.cancel()
        taskConfigs.clear()
        availableTaskGroups.clear()
        executionQueue.clear()
        executionHistory.clear()
    }
}

/**
 * 任务信息
 */
data class TaskInfo(
    val id: String,
    val name: String,
    val description: String,
    val appName: String,
    val appPackage: String,
    val level: Int,
    val enabled: Boolean,
    val taskCount: Int,
    val estimatedDuration: Long,
    val tags: List<String>,
    val dependencies: List<String>
)

/**
 * 执行选项
 */
data class ExecutionOptions(
    val continueOnFailure: Boolean = false,
    val delayBetweenGroups: Long = 1000L,
    val maxRetries: Int = 3,
    val retryDelay: Long = 2000L
)

/**
 * 任务执行项
 */
data class TaskExecutionItem(
    val id: Long,
    val taskGroups: List<TaskGroup>,
    val options: ExecutionOptions,
    val status: TaskExecutionStatus,
    val createdAt: Long
)

/**
 * 任务执行状态
 */
enum class TaskExecutionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 执行状态
 */
enum class ExecutionStatus {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPING
}

/**
 * 加载结果
 */
sealed class LoadResult {
    data class Success(
        val loadedCount: Int,
        val totalGroups: Int,
        val message: String
    ) : LoadResult()
    
    data class Error(
        val message: String
    ) : LoadResult()
}

/**
 * 任务执行记录
 */
data class TaskExecutionRecord(
    val executionId: Long,
    val taskGroups: List<String>,
    val success: Boolean,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val errorMessage: String?
)

/**
 * 执行统计
 */
data class ExecutionStats(
    val totalExecutions: Int,
    val successfulExecutions: Int,
    val failedExecutions: Int,
    val averageDuration: Long,
    val lastExecutionTime: Long?
)