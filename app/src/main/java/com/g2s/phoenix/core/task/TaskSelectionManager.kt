package com.g2s.phoenix.core.task

import android.content.Context
import com.g2s.phoenix.config.JsonTaskLoader 
import com.g2s.phoenix.core.scheduler.TaskGroup
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * 任务选择管理器
 * 实现旧架构中的统一任务入口和解耦设计
 * 支持从多个JSON文件加载任务，每个文件代表一个独立的应用任务
 */
class TaskSelectionManager(
    // JSON任务加载器
    private val jsonTaskLoader: JsonTaskLoader, 
    // 任务入口管理器
    private val taskEntryManager: TaskEntryManager,
    // 上下文
    private val context: Context? = null // 添加Context参数用于访问assets
) {
    private val mutex = Mutex()
    
    // 可用的应用任务映射 (应用名 -> 任务组列表)
    private val _availableAppTasks = MutableStateFlow<Map<String, List<TaskGroup>>>(emptyMap())
    val availableAppTasks: StateFlow<Map<String, List<TaskGroup>>> = _availableAppTasks.asStateFlow()
    
    // 已选择的任务队列
    private val _selectedTasks = MutableStateFlow<List<SelectedTask>>(emptyList())
    val selectedTasks: StateFlow<List<SelectedTask>> = _selectedTasks.asStateFlow()
    
    // 任务加载状态
    private val _loadingStatus = MutableStateFlow<LoadingStatus>(LoadingStatus.Idle)
    val loadingStatus: StateFlow<LoadingStatus> = _loadingStatus.asStateFlow()
    
    // 执行状态
    private val _executionStatus = MutableStateFlow<ExecutionStatus>(ExecutionStatus.Idle)
    val executionStatus: StateFlow<ExecutionStatus> = _executionStatus.asStateFlow()
    
    /**
     * 已选择的任务数据类
     */
    data class SelectedTask(
        val id: String,
        val appName: String,
        val taskGroup: TaskGroup,
        val priority: Int = 0,
        val enabled: Boolean = true,
        val selectedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * 加载状态枚举
     */
    enum class LoadingStatus {
        Idle,
        Loading,
        Success,
        Error
    }
    
    /**
     * 执行状态枚举
     */
    enum class ExecutionStatus {
        Idle,
        Running,
        Paused,
        Completed,
        Failed
    }
    
    /**
     * 从assets目录加载所有应用任务
     * 优先从assets目录加载，如果失败则回退到指定目录
     */
    suspend fun loadAppTasksFromAssets(): Boolean {
        return mutex.withLock {
            try {
                _loadingStatus.value = LoadingStatus.Loading
                
                if (context == null) {
                    _loadingStatus.value = LoadingStatus.Error
                    return@withLock false
                }
                
                val appTasksMap = mutableMapOf<String, List<TaskGroup>>()
                
                try {
                    // 获取assets目录下的所有JSON文件
                    val assetFiles = context.assets.list("") ?: emptyArray()
                    val jsonFiles = assetFiles.filter { it.endsWith(".json") }
                    
                    jsonFiles.forEach { fileName ->
                        try {
                            // 从assets读取JSON内容
                            val inputStream = context.assets.open(fileName)
                            val jsonContent = inputStream.bufferedReader().use { it.readText() }
                            inputStream.close()
                            
                            // 解析任务配置
                            val taskConfig = jsonTaskLoader.loadTaskConfigFromString(jsonContent)
                            
                            if (taskConfig != null && taskConfig.taskGroups.isNotEmpty()) {
                                // 使用app.name作为显示名称
                                val appName = taskConfig.app.name.ifEmpty { 
                                    fileName.substringBeforeLast(".json")
                                }
                                appTasksMap[appName] = taskConfig.taskGroups
                                
                                // 将任务配置同步到TaskEntryManager
                                val loadResult = taskEntryManager.loadTaskConfigFromString(jsonContent)
                                when (loadResult) {
                                    is LoadResult.Success -> {
                                        println("成功从assets加载任务配置: $fileName -> $appName，已同步到TaskEntryManager")
                                    }
                                    is LoadResult.Error -> {
                                        println("任务配置同步到TaskEntryManager失败: $fileName -> $appName, 错误: ${loadResult.message}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("加载assets任务文件失败: $fileName, 错误: ${e.message}")
                        }
                    }
                    
                    _availableAppTasks.value = appTasksMap
                    _loadingStatus.value = LoadingStatus.Success
                    
                    true
                } catch (e: IOException) {
                    println("访问assets目录失败: ${e.message}")
                    _loadingStatus.value = LoadingStatus.Error
                    false
                }
            } catch (e: Exception) {
                _loadingStatus.value = LoadingStatus.Error
                false
            }
        }
    }
    
    /**
     * 从指定目录加载所有应用任务
     * 实现旧架构中的任务解耦设计：每个JSON文件代表一个应用的任务
     */
    suspend fun loadAppTasksFromDirectory(directory: String): Boolean {
        return mutex.withLock {
            try {
                _loadingStatus.value = LoadingStatus.Loading
                
                val taskDir = File(directory)
                if (!taskDir.exists() || !taskDir.isDirectory) {
                    _loadingStatus.value = LoadingStatus.Error
                    return@withLock false
                }
                
                val appTasksMap = mutableMapOf<String, List<TaskGroup>>()
                
                // 扫描目录中的所有JSON文件
                taskDir.listFiles { file -> 
                    file.isFile && file.extension.lowercase() == "json" 
                }?.forEach { jsonFile ->
                    try {
                        // 从文件名推断应用名（去掉.json后缀）
                        val appName = jsonFile.nameWithoutExtension
                        
                        // 加载任务配置
                        val taskConfig = jsonTaskLoader.loadTaskConfig(jsonFile)
                        if (taskConfig != null && taskConfig.taskGroups.isNotEmpty()) {
                            appTasksMap[appName] = taskConfig.taskGroups
                            
                            // 将任务配置同步到TaskEntryManager
                            val loadResult = taskEntryManager.loadTaskFromFile(jsonFile)
                            when (loadResult) {
                                 is LoadResult.Success -> {
                                     println("成功从目录加载任务配置: ${jsonFile.name} -> $appName，已同步到TaskEntryManager")
                                 }
                                 is LoadResult.Error -> {
                                     println("任务配置同步到TaskEntryManager失败: ${jsonFile.name} -> $appName, 错误: ${loadResult.message}")
                                 }
                             }
                        }
                    } catch (e: Exception) {
                        // 单个文件加载失败不影响其他文件
                        println("加载任务文件失败: ${jsonFile.name}, 错误: ${e.message}")
                    }
                }
                
                _availableAppTasks.value = appTasksMap
                _loadingStatus.value = LoadingStatus.Success
                
                true
            } catch (e: Exception) {
                _loadingStatus.value = LoadingStatus.Error
                false
            }
        }
    }
    
    /**
     * 加载单个应用任务文件
     */
    suspend fun loadSingleAppTask(filePath: String): Boolean {
        return mutex.withLock {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withLock false
                }
                
                val appName = file.nameWithoutExtension
                val taskConfig = jsonTaskLoader.loadTaskConfig(File(filePath))
                
                if (taskConfig != null && taskConfig.taskGroups.isNotEmpty()) {
                    val currentTasks = _availableAppTasks.value.toMutableMap()
                    currentTasks[appName] = taskConfig.taskGroups
                    _availableAppTasks.value = currentTasks
                    
                    // 将任务配置同步到TaskEntryManager
                    val loadResult = taskEntryManager.loadTaskFromFile(file)
                    when (loadResult) {
                         is LoadResult.Success -> {
                             println("成功加载单个任务配置: ${file.name} -> $appName，已同步到TaskEntryManager")
                         }
                         is LoadResult.Error -> {
                             println("单个任务配置同步到TaskEntryManager失败: ${file.name} -> $appName, 错误: ${loadResult.message}")
                         }
                     }
                    
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * 选择任务组添加到执行队列
     */
    suspend fun selectTaskGroup(appName: String, taskGroupId: String, priority: Int = 0): Boolean {
        return mutex.withLock {
            val appTasks = _availableAppTasks.value[appName] ?: return@withLock false
            val taskGroup = appTasks.find { it.id == taskGroupId } ?: return@withLock false
            
            val selectedTask = SelectedTask(
                id = "${appName}_${taskGroupId}_${System.currentTimeMillis()}",
                appName = appName,
                taskGroup = taskGroup.copy(), // 创建副本避免状态污染
                priority = priority
            )
            
            val currentSelected = _selectedTasks.value.toMutableList()
            currentSelected.add(selectedTask)
            
            // 按优先级排序
            currentSelected.sortByDescending { it.priority }
            
            _selectedTasks.value = currentSelected
            true
        }
    }
    
    /**
     * 批量选择任务组
     */
    suspend fun selectMultipleTaskGroups(selections: List<TaskSelection>): Int {
        return mutex.withLock {
            var successCount = 0
            
            selections.forEach { selection ->
                if (selectTaskGroup(selection.appName, selection.taskGroupId, selection.priority)) {
                    successCount++
                }
            }
            
            successCount
        }
    }
    
    /**
     * 任务选择数据类
     */
    data class TaskSelection(
        val appName: String,
        val taskGroupId: String,
        val priority: Int = 0
    )
    
    /**
     * 移除选中的任务
     */
    suspend fun removeSelectedTask(taskId: String): Boolean {
        return mutex.withLock {
            val currentSelected = _selectedTasks.value.toMutableList()
            val removed = currentSelected.removeIf { it.id == taskId }
            if (removed) {
                _selectedTasks.value = currentSelected
            }
            removed
        }
    }
    
    /**
     * 清空选中的任务
     */
    suspend fun clearSelectedTasks() {
        mutex.withLock {
            _selectedTasks.value = emptyList()
        }
    }
    
    /**
     * 调整任务优先级
     */
    suspend fun adjustTaskPriority(taskId: String, newPriority: Int): Boolean {
        return mutex.withLock {
            val currentSelected = _selectedTasks.value.toMutableList()
            val taskIndex = currentSelected.indexOfFirst { it.id == taskId }
            
            if (taskIndex >= 0) {
                val task = currentSelected[taskIndex]
                currentSelected[taskIndex] = task.copy(priority = newPriority)
                
                // 重新排序
                currentSelected.sortByDescending { it.priority }
                _selectedTasks.value = currentSelected
                true
            } else {
                false
            }
        }
    }
    
    /**
     * 开始执行选中的任务队列
     */
    suspend fun startExecution(): Boolean {
        return mutex.withLock {
            // 如果选中的任务队列为空，则返回失败,并且释放锁
            if (_selectedTasks.value.isEmpty()) {
                // 提前返回需要显式@withLock释放锁,否则会死锁
                // return@withLock 代表从lambda表达式中提前返回
                // 直接return 会返回到suspend fun startExecution()的调用处x
                return@withLock false
            }
            
            if (_executionStatus.value == ExecutionStatus.Running) {
                return@withLock false
            }
            
            try {
                _executionStatus.value = ExecutionStatus.Running
                
                // 将选中的任务组添加到TaskEntryManager的执行队列
                val taskGroupsForExecution = _selectedTasks.value.map { it.taskGroup }
                taskEntryManager.clearExecutionQueue()
                
                taskEntryManager.addToExecutionQueue(
                    taskGroupsForExecution.map { it.id }
                ) // 传递任务组ID列表
                
                // 开始执行
                taskEntryManager.startExecution()
                
                // 释放锁 返回成功
                true
            } catch (e: Exception) {
                _executionStatus.value = ExecutionStatus.Failed
                false
            }
        }
    }
    
    /**
     * 暂停执行
     */
    suspend fun pauseExecution(): Boolean {
        return try {
            taskEntryManager.pauseExecution()
            _executionStatus.value = ExecutionStatus.Paused
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 恢复执行
     */
    suspend fun resumeExecution(): Boolean {
        return try {
            taskEntryManager.resumeExecution()
            _executionStatus.value = ExecutionStatus.Running
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 停止执行
     */
    suspend fun stopExecution(): Boolean {
        return try {
            taskEntryManager.stopExecution()
            _executionStatus.value = ExecutionStatus.Idle
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取应用任务统计信息
     */
    fun getAppTaskStatistics(): Map<String, TaskStatistics> {
        val stats = mutableMapOf<String, TaskStatistics>()
        
        _availableAppTasks.value.forEach { (appName, taskGroups) ->
            val totalTasks = taskGroups.sumOf { it.tasks.size }
            val totalGroups = taskGroups.size
            val enabledGroups = taskGroups.count { it.enabled }
            
            stats[appName] = TaskStatistics(
                totalTaskGroups = totalGroups,
                enabledTaskGroups = enabledGroups,
                totalTasks = totalTasks,
                averageTasksPerGroup = if (totalGroups > 0) totalTasks.toDouble() / totalGroups else 0.0
            )
        }
        
        return stats
    }
    
    /**
     * 任务统计数据类
     */
    data class TaskStatistics(
        val totalTaskGroups: Int,
        val enabledTaskGroups: Int,
        val totalTasks: Int,
        val averageTasksPerGroup: Double
    )
    
    /**
     * 搜索任务组
     */
    fun searchTaskGroups(query: String): Map<String, List<TaskGroup>> {
        if (query.isBlank()) {
            return _availableAppTasks.value
        }
        
        val filteredTasks = mutableMapOf<String, List<TaskGroup>>()
        
        _availableAppTasks.value.forEach { (appName, taskGroups) ->
            val matchingGroups = taskGroups.filter { group ->
                group.name.contains(query, ignoreCase = true) ||
                group.description.contains(query, ignoreCase = true) ||
                appName.contains(query, ignoreCase = true)
            }
            
            if (matchingGroups.isNotEmpty()) {
                filteredTasks[appName] = matchingGroups
            }
        }
        
        return filteredTasks
    }
    
    /**
     * 获取执行历史统计
     */
    fun getExecutionHistory(): List<TaskExecutionRecord> {
        // 这里可以从TaskEntryManager获取执行历史
        return taskEntryManager.getExecutionHistory()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        // 清理相关资源
    }
}