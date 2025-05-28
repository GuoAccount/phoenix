package com.g2s.phoenix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.g2s.phoenix.core.PhoenixCore
import com.g2s.phoenix.core.TaskSelectionManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * 极简任务管理ViewModel
 * 只包含基本的任务选择和执行控制功能
 */
class SimpleTaskManagerViewModel : ViewModel() {
    
    // 使用已初始化的PhoenixCore实例
    private val phoenixCore by lazy { PhoenixCore.getInstance() }
    private val taskSelectionManager: TaskSelectionManager
        get() = phoenixCore.taskSelectionManager
    
    init {
        // 启动时加载任务
        viewModelScope.launch {
            loadTasks()
        }
    }
    
    // UI状态流
    val availableAppTasks = taskSelectionManager.availableAppTasks
    val selectedTasks = taskSelectionManager.selectedTasks
    val executionStatus = taskSelectionManager.executionStatus
    
    /**
     * 选择任务组
     */
    fun selectTaskGroup(appName: String, taskGroupId: String, priority: Int = 0) {
        viewModelScope.launch {
            taskSelectionManager.selectTaskGroup(appName, taskGroupId, priority)
        }
    }
    
    /**
     * 移除选中的任务
     */
    fun removeSelectedTask(taskId: String) {
        viewModelScope.launch {
            taskSelectionManager.removeSelectedTask(taskId)
        }
    }
    
    /**
     * 清空选中的任务
     */
    fun clearSelectedTasks() {
        viewModelScope.launch {
            taskSelectionManager.clearSelectedTasks()
        }
    }
    
    /**
     * 开始执行选中的任务
     */
    fun startExecution() {
        viewModelScope.launch {
            taskSelectionManager.startExecution()
        }
    }
    
    /**
     * 停止执行
     */
    fun stopExecution() {
        viewModelScope.launch {
            taskSelectionManager.stopExecution()
        }
    }
    
    /**
     * 加载任务
     */
    private suspend fun loadTasks() {
        try {
            // 优先从assets目录加载任务
            val assetsLoadSuccess = taskSelectionManager.loadAppTasksFromAssets()
            
            if (!assetsLoadSuccess) {
                // 如果assets加载失败，回退到配置目录加载
                val configDir = phoenixCore.getConfigDirectory()
                val tasksDir = File(configDir, "tasks")
                
                if (!tasksDir.exists()) {
                    tasksDir.mkdirs()
                }
                
                taskSelectionManager.loadAppTasksFromDirectory(tasksDir.absolutePath)
            }
        } catch (e: Exception) {
            // 处理加载错误
            println("加载任务失败: ${e.message}")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // 清理资源
        taskSelectionManager.cleanup()
    }
}