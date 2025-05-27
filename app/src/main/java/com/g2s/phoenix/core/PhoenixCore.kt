package com.g2s.phoenix.core

import com.g2s.phoenix.model.*
import com.g2s.phoenix.core.scheduler.*
import com.g2s.phoenix.core.engine.*
import com.g2s.phoenix.core.resilience.*
import com.g2s.phoenix.core.resilience.HealthCheckResult
import com.g2s.phoenix.config.*
import com.g2s.phoenix.config.ConfigSource
import com.g2s.phoenix.runtime.monitoring.*
import com.g2s.phoenix.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 凤凰系统核心类
 * 整合三元核心架构：调度器、执行引擎、韧性核心
 */
class PhoenixCore private constructor(
    private val configDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        @Volatile
        private var INSTANCE: PhoenixCore? = null
        
        /**
         * 获取凤凰系统实例
         */
        fun getInstance(configDir: File): PhoenixCore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhoenixCore(configDir).also { INSTANCE = it }
            }
        }
        
        /**
         * 销毁实例
         */
        suspend fun destroyInstance() {
            INSTANCE?.shutdown()
            INSTANCE = null
        }
    }

    // 核心组件
    private lateinit var configManager: ConfigManager
    private lateinit var taskScheduler: TaskScheduler
    private lateinit var operationEngine: OperationEngine
    private lateinit var resilienceCore: ResilienceCore
    private lateinit var monitorSystem: MonitorSystem
    private lateinit var pluginManager: PluginManager

    // 系统状态
    private val isInitialized = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val currentStatus = AtomicReference(PhoenixStatus.STOPPED)
    
    // 系统配置
    private var currentConfig: PhoenixConfig? = null
    
    // 执行锁
    private val initMutex = Mutex()
    private val operationMutex = Mutex()
    
    // 统计信息
    private var startTime: Long = 0
    private var lastHeartbeat: Long = 0

    /**
     * 初始化凤凰系统
     */
    suspend fun initialize(targetApp: String): Boolean {
        return initMutex.withLock {
            if (isInitialized.get()) {
                return@withLock true
            }

            try {
                updateStatus(PhoenixStatus.INITIALIZING)
                
                // 1. 初始化配置管理器
                configManager = PhoenixConfigManager(configDir, scope)
                
                // 2. 加载或创建配置
                currentConfig = configManager.loadConfig(ConfigSource.FILE) ?: run {
                    val defaultConfig = PhoenixConfig.createDefault(targetApp)
                    configManager.saveConfig(defaultConfig, ConfigSource.FILE)
                    defaultConfig
                }
                
                val config = currentConfig!!
                
                // 3. 初始化核心组件
                initializeCoreComponents(config)
                
                // 4. 启动监控系统
                monitorSystem.startMonitoring()
                
                // 5. 启动韧性核心
                resilienceCore.startLifeSupport()
                
                // 6. 启动调度器
                taskScheduler.start()
                
                // 7. 初始化执行引擎
                operationEngine.initialize()
                
                // 8. 加载插件
                if (config.plugin.enabled) {
                    pluginManager.loadPlugins()
                }
                
                isInitialized.set(true)
                startTime = System.currentTimeMillis()
                lastHeartbeat = startTime
                
                updateStatus(PhoenixStatus.READY)
                
                // 发送初始化完成日志
                logInfo("凤凰系统初始化完成", mapOf(
                    "targetApp" to targetApp,
                    "version" to config.version,
                    "mode" to config.mode.name
                ))
                
                true
                
            } catch (e: Exception) {
                updateStatus(PhoenixStatus.ERROR)
                logError("凤凰系统初始化失败", e)
                false
            }
        }
    }

    /**
     * 启动凤凰系统
     */
    suspend fun start(): Boolean {
        if (!isInitialized.get()) {
            logError("系统未初始化，无法启动")
            return false
        }

        return operationMutex.withLock {
            if (isRunning.compareAndSet(false, true)) {
                try {
                    updateStatus(PhoenixStatus.RUNNING)
                    
                    // 启动心跳检测
                    startHeartbeat()
                    
                    // 启动任务调度
                    startTaskScheduling()
                    
                    logInfo("凤凰系统启动成功")
                    true
                } catch (e: Exception) {
                    isRunning.set(false)
                    updateStatus(PhoenixStatus.ERROR)
                    logError("凤凰系统启动失败", e)
                    false
                }
            } else {
                true // 已经在运行
            }
        }
    }

    /**
     * 停止凤凰系统
     */
    suspend fun stop(): Boolean {
        return operationMutex.withLock {
            if (isRunning.compareAndSet(true, false)) {
                try {
                    updateStatus(PhoenixStatus.STOPPING)
                    
                    // 停止调度器
                    taskScheduler.stop()
                    
                    // 停止监控
                    monitorSystem.stopMonitoring()
                    
                    // 停止韧性核心
                    resilienceCore.stopLifeSupport()
                    
                    // 销毁执行引擎
                    operationEngine.destroy()
                    
                    updateStatus(PhoenixStatus.STOPPED)
                    logInfo("凤凰系统停止成功")
                    true
                } catch (e: Exception) {
                    updateStatus(PhoenixStatus.ERROR)
                    logError("凤凰系统停止失败", e)
                    false
                }
            } else {
                true // 已经停止
            }
        }
    }

    /**
     * 关闭凤凰系统
     */
    suspend fun shutdown() {
        if (isRunning.get()) {
            stop()
        }
        
        scope.cancel()
        updateStatus(PhoenixStatus.SHUTDOWN)
    }

    /**
     * 提交任务
     */
    suspend fun submitTask(task: Task): Boolean {
        if (!isRunning.get()) {
            logError("系统未运行，无法提交任务")
            return false
        }

        return try {
            val success = taskScheduler.submitTask(task)
            if (success) {
                logInfo("任务提交成功", mapOf(
                    "taskId" to task.id,
                    "taskName" to task.name,
                    "taskType" to task.type.name
                ))
            } else {
                logError("任务提交失败", null, mapOf(
                    "taskId" to task.id,
                    "reason" to "调度器拒绝"
                ))
            }
            success
        } catch (e: Exception) {
            logError("任务提交异常", e, mapOf("taskId" to task.id))
            false
        }
    }

    /**
     * 批量提交任务
     */
    suspend fun submitTasks(tasks: List<Task>): List<Boolean> {
        if (!isRunning.get()) {
            logError("系统未运行，无法提交任务")
            return tasks.map { false }
        }

        return try {
            taskScheduler.submitTasks(tasks)
        } catch (e: Exception) {
            logError("批量任务提交异常", e)
            tasks.map { false }
        }
    }

    /**
     * 取消任务
     */
    suspend fun cancelTask(taskId: String): Boolean {
        return try {
            taskScheduler.cancelTask(taskId)
        } catch (e: Exception) {
            logError("任务取消异常", e, mapOf("taskId" to taskId))
            false
        }
    }

    /**
     * 获取任务状态
     */
    suspend fun getTaskStatus(taskId: String): TaskStatus? {
        return try {
            taskScheduler.getTaskStatus(taskId)
        } catch (e: Exception) {
            logError("获取任务状态异常", e, mapOf("taskId" to taskId))
            null
        }
    }

    /**
     * 获取系统状态
     */
    fun getSystemStatus(): PhoenixStatus {
        return currentStatus.get()
    }

    /**
     * 获取系统统计信息
     */
    suspend fun getSystemStatistics(): SystemStatistics {
        val schedulerStats = taskScheduler.getStatistics()
        val resilienceStats = resilienceCore.getResilienceStatistics()
        val performanceStats = monitorSystem.getPerformanceStats()
        val systemHealth = monitorSystem.getSystemHealthMetrics()
        
        return SystemStatistics(
            status = currentStatus.get(),
            uptime = if (startTime > 0) System.currentTimeMillis() - startTime else 0,
            lastHeartbeat = lastHeartbeat,
            schedulerStats = schedulerStats,
            resilienceStats = resilienceStats,
            performanceStats = performanceStats,
            systemHealth = systemHealth,
            configVersion = currentConfig?.version ?: "unknown"
        )
    }

    /**
     * 更新配置
     */
    suspend fun updateConfig(updater: (PhoenixConfig) -> PhoenixConfig): Boolean {
        return try {
            val success = configManager.updateConfig(updater)
            if (success) {
                currentConfig = configManager.getCurrentConfig()
                logInfo("配置更新成功")
            }
            success
        } catch (e: Exception) {
            logError("配置更新失败", e)
            false
        }
    }

    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): PhoenixConfig? {
        return currentConfig
    }

    /**
     * 监听系统事件
     */
    fun observeSystemEvents(): Flow<SystemEvent> {
        return merge(
            taskScheduler.observeTaskUpdates().map { SystemEvent.TaskUpdate(it) },
            monitorSystem.observeAlerts().map { SystemEvent.Alert(it) },
            resilienceCore.observeHealthStatus().map { SystemEvent.HealthStatus(it) }
        )
    }

    /**
     * 执行健康检查
     */
    suspend fun performHealthCheck(): HealthCheckResult {
        return resilienceCore.performHealthCheck()
    }

    /**
     * 进入紧急模式
     */
    suspend fun enterEmergencyMode() {
        resilienceCore.enterEmergencyMode()
        logError("系统进入紧急模式")
    }

    /**
     * 退出紧急模式
     */
    suspend fun exitEmergencyMode() {
        resilienceCore.exitEmergencyMode()
        logInfo("系统退出紧急模式")
    }

    /**
     * 初始化核心组件
     */
    private suspend fun initializeCoreComponents(config: PhoenixConfig) {
        // 初始化监控系统
        monitorSystem = PhoenixMonitorSystem(config, scope)
        
        // 初始化韧性核心
        resilienceCore = PhoenixResilienceCore(config, scope)
        
        // 初始化任务调度器
        taskScheduler = SmartTaskScheduler(config, scope)
        
        // 初始化执行引擎
        operationEngine = PhoenixOperationEngine(config, scope)
        
        // 初始化插件管理器
        pluginManager = PhoenixPluginManager(config, scope)
        
        // 连接组件之间的协作
        setupComponentCollaboration()
    }

    /**
     * 设置组件协作
     */
    private fun setupComponentCollaboration() {
        // 监听任务更新并执行
        scope.launch {
            taskScheduler.observeTaskUpdates().collect { update ->
                when (update) {
                    is TaskUpdate.TaskStarted -> {
                        // 执行任务
                        executeTask(update.task)
                    }
                    else -> {
                        // 记录其他任务事件
                        logInfo("任务状态更新", mapOf<String, Any>(
                            "taskId" to update.toString(),
                            "updateType" to (update::class.simpleName ?: "Unknown")
                        ))
                    }
                }
            }
        }
        
        // 监听健康状态变化
        scope.launch {
            resilienceCore.observeHealthStatus().collect { healthResult ->
                if (healthResult.status == HealthStatus.CRITICAL) {
                    // 发送严重警报
                    monitorSystem.sendAlert(
                        Alert(
                            id = "health_critical_${System.currentTimeMillis()}",
                            level = AlertLevel.CRITICAL,
                            title = "系统健康状况严重",
                            message = healthResult.message,
                            source = "ResilienceCore"
                        )
                    )
                }
            }
        }
    }

    /**
     * 执行任务
     */
    private suspend fun executeTask(task: Task) {
        try {
            val result = operationEngine.executeTask(task)
            
            if (result.success) {
                (taskScheduler as SmartTaskScheduler).markTaskCompleted(task.id, result)
                resilienceCore.recordSuccess("task_execution")
            } else {
                (taskScheduler as SmartTaskScheduler).markTaskFailed(task.id, result)
                
                // 分类和处理异常
                if (result.exception != null) {
                    val exceptionInfo = resilienceCore.classifyException(
                        result.exception, 
                        mapOf("taskId" to task.id)
                    )
                    resilienceCore.handleException(exceptionInfo)
                }
            }
        } catch (e: Exception) {
            val errorResult = TaskResult(
                success = false,
                message = "任务执行异常: ${e.message}",
                exception = e,
                executionTime = 0L
            )
            
            (taskScheduler as SmartTaskScheduler).markTaskFailed(task.id, errorResult)
            
            val exceptionInfo = resilienceCore.classifyException(
                e, 
                mapOf("taskId" to task.id)
            )
            resilienceCore.handleException(exceptionInfo)
        }
    }

    /**
     * 启动心跳检测
     */
    private fun startHeartbeat() {
        scope.launch {
            while (isRunning.get()) {
                try {
                    lastHeartbeat = System.currentTimeMillis()
                    
                    // 执行定期健康检查
                    val healthResult = resilienceCore.performHealthCheck()
                    
                    // 记录心跳指标
                    monitorSystem.recordMetric(
                        Metric(
                            name = "system.heartbeat",
                            type = MetricType.SYSTEM,
                            value = 1.0,
                            tags = mapOf("status" to healthResult.status.name)
                        )
                    )
                    
                    delay(currentConfig?.resilience?.healthCheckInterval ?: 10000L)
                } catch (e: Exception) {
                    logError("心跳检测异常", e)
                }
            }
        }
    }

    /**
     * 启动任务调度
     */
    private fun startTaskScheduling() {
        scope.launch {
            while (isRunning.get()) {
                try {
                    // 定期清理已完成的任务
                    val cleanedCount = taskScheduler.cleanupCompletedTasks()
                    if (cleanedCount > 0) {
                        logInfo("清理已完成任务", mapOf("count" to cleanedCount))
                    }
                    
                    delay(60000L) // 每分钟清理一次
                } catch (e: Exception) {
                    logError("任务调度异常", e)
                }
            }
        }
    }

    /**
     * 更新系统状态
     */
    private suspend fun updateStatus(status: PhoenixStatus) {
        val oldStatus = currentStatus.getAndSet(status)
        if (oldStatus != status) {
            logInfo("系统状态变更", mapOf(
                "oldStatus" to oldStatus.name,
                "newStatus" to status.name
            ))
        }
    }

    /**
     * 记录信息日志
     */
    private suspend fun logInfo(message: String, data: Map<String, Any> = emptyMap()) {
        if (::monitorSystem.isInitialized) {
            monitorSystem.log(
                LogEntry(
                    level = LogLevel.INFO,
                    message = message,
                    tag = "PhoenixCore",
                    data = data
                )
            )
        }
    }

    /**
     * 记录错误日志
     */
    private suspend fun logError(message: String, exception: Throwable? = null, data: Map<String, Any> = emptyMap()) {
        if (::monitorSystem.isInitialized) {
            monitorSystem.log(
                LogEntry(
                    level = LogLevel.ERROR,
                    message = message,
                    tag = "PhoenixCore",
                    exception = exception,
                    data = data
                )
            )
        }
    }
}

/**
 * 凤凰系统状态
 */
enum class PhoenixStatus {
    STOPPED,        // 已停止
    INITIALIZING,   // 初始化中
    READY,          // 就绪
    RUNNING,        // 运行中
    STOPPING,       // 停止中
    ERROR,          // 错误
    SHUTDOWN        // 已关闭
}

/**
 * 系统统计信息
 */
data class SystemStatistics(
    val status: PhoenixStatus,
    val uptime: Long,
    val lastHeartbeat: Long,
    val schedulerStats: SchedulerStatistics,
    val resilienceStats: ResilienceStatistics,
    val performanceStats: PerformanceStats,
    val systemHealth: SystemHealthMetrics,
    val configVersion: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 系统事件
 */
sealed class SystemEvent {
    data class TaskUpdate(val update: com.g2s.phoenix.core.scheduler.TaskUpdate) : SystemEvent()
    data class Alert(val alert: com.g2s.phoenix.runtime.monitoring.Alert) : SystemEvent()
    data class HealthStatus(val health: HealthCheckResult) : SystemEvent()
    data class StatusChange(val oldStatus: PhoenixStatus, val newStatus: PhoenixStatus) : SystemEvent()
}