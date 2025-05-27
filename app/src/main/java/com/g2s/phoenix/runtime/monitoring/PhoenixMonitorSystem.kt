package com.g2s.phoenix.runtime.monitoring

import com.g2s.phoenix.model.*
import com.g2s.phoenix.core.resilience.HealthStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 凤凰监控系统实现
 */
class PhoenixMonitorSystem(
    private val config: PhoenixConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : MonitorSystem {

    // 监控状态
    private val isMonitoring = AtomicBoolean(false)
    
    // 数据存储
    private val metrics = ConcurrentHashMap<String, MutableList<Metric>>()
    private val logs = mutableListOf<LogEntry>()
    private val alerts = ConcurrentHashMap<String, Alert>()
    
    // 统计计数器
    private val totalMetrics = AtomicLong(0)
    private val totalLogs = AtomicLong(0)
    private val totalAlerts = AtomicLong(0)
    
    // 流控制
    private val _metricsFlow = MutableSharedFlow<Metric>()
    private val _logsFlow = MutableSharedFlow<LogEntry>()
    private val _alertsFlow = MutableSharedFlow<Alert>()
    private val _performanceStatsFlow = MutableSharedFlow<PerformanceStats>()
    
    // 监控作业
    private var monitoringJob: Job? = null
    private var performanceJob: Job? = null
    
    // 日志文件管理
    private val logDir = File(config.customSettings["logDirectory"] as? String ?: "logs")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    // 执行锁
    private val mutex = Mutex()
    
    // 系统启动时间
    private val startTime = System.currentTimeMillis()

    init {
        // 确保日志目录存在
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
    }

    override suspend fun recordMetric(metric: Metric) {
        if (!isMonitoring.get()) return
        
        mutex.withLock {
            // 存储指标
            metrics.computeIfAbsent(metric.name) { mutableListOf() }.add(metric)
            
            // 限制历史数据量
            val metricList = metrics[metric.name]!!
            if (metricList.size > 1000) {
                metricList.removeAt(0)
            }
            
            totalMetrics.incrementAndGet()
            
            // 发送到流
            _metricsFlow.tryEmit(metric)
            
            // 检查是否需要触发警报
            checkMetricThresholds(metric)
        }
    }

    override suspend fun recordMetrics(metrics: List<Metric>) {
        metrics.forEach { recordMetric(it) }
    }

    override suspend fun getMetricHistory(
        name: String,
        startTime: Long,
        endTime: Long
    ): List<Metric> {
        return metrics[name]?.filter { 
            it.timestamp in startTime..endTime 
        } ?: emptyList()
    }

    override fun observeMetrics(): Flow<Metric> {
        return _metricsFlow.asSharedFlow()
    }

    override suspend fun log(entry: LogEntry) {
        if (!config.monitor.enabled) return
        
        mutex.withLock {
            // 检查日志级别
            val configLevel = LogLevel.valueOf(config.monitor.logLevel.uppercase())
            if (entry.level.ordinal < configLevel.ordinal) {
                return@withLock
            }
            
            // 添加到内存日志
            logs.add(entry)
            
            // 限制内存日志数量
            if (logs.size > 10000) {
                logs.removeAt(0)
            }
            
            totalLogs.incrementAndGet()
            
            // 发送到流
            _logsFlow.tryEmit(entry)
            
            // 写入文件
            if (config.monitor.enabled) {
                writeLogToFile(entry)
            }
            
            // 检查是否需要触发警报
            if (entry.level == LogLevel.ERROR || entry.level == LogLevel.FATAL) {
                val alert = Alert(
                    id = "log_alert_${System.currentTimeMillis()}",
                    level = if (entry.level == LogLevel.FATAL) AlertLevel.CRITICAL else AlertLevel.ERROR,
                    title = "日志错误警报",
                    message = entry.message,
                    source = entry.tag,
                    data = mapOf(
                        "logLevel" to entry.level.name,
                        "taskId" to (entry.taskId ?: ""),
                        "exception" to (entry.exception?.message ?: "")
                    )
                )
                sendAlert(alert)
            }
        }
    }

    override suspend fun getLogs(
        level: LogLevel?,
        tag: String?,
        startTime: Long,
        endTime: Long,
        limit: Int
    ): List<LogEntry> {
        return logs.filter { log ->
            (level == null || log.level == level) &&
            (tag == null || log.tag.contains(tag, ignoreCase = true)) &&
            log.timestamp in startTime..endTime
        }.takeLast(limit)
    }

    override fun observeLogs(): Flow<LogEntry> {
        return _logsFlow.asSharedFlow()
    }

    override suspend fun sendAlert(alert: Alert) {
        mutex.withLock {
            alerts[alert.id] = alert
            totalAlerts.incrementAndGet()
            _alertsFlow.tryEmit(alert)
            
            // 记录警报日志
            log(LogEntry(
                level = when (alert.level) {
                    AlertLevel.INFO -> LogLevel.INFO
                    AlertLevel.WARNING -> LogLevel.WARN
                    AlertLevel.ERROR -> LogLevel.ERROR
                    AlertLevel.CRITICAL -> LogLevel.FATAL
                },
                message = "警报触发: ${alert.title} - ${alert.message}",
                tag = "AlertSystem"
            ))
        }
    }

    override suspend fun getActiveAlerts(): List<Alert> {
        return alerts.values.filter { !it.acknowledged }.toList()
    }

    override suspend fun acknowledgeAlert(alertId: String): Boolean {
        return mutex.withLock {
            val alert = alerts[alertId]
            if (alert != null) {
                alerts[alertId] = alert.copy(acknowledged = true)
                true
            } else {
                false
            }
        }
    }

    override fun observeAlerts(): Flow<Alert> {
        return _alertsFlow.asSharedFlow()
    }

    override suspend fun getPerformanceStats(): PerformanceStats {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val memoryUsage = usedMemory.toDouble() / totalMemory
        
        return PerformanceStats(
            cpuUsage = getCpuUsage(),
            memoryUsage = memoryUsage,
            diskUsage = getDiskUsage(),
            networkLatency = getNetworkLatency(),
            throughput = getThroughput(),
            responseTime = getAverageResponseTime(),
            errorRate = getErrorRate(),
            activeThreads = Thread.activeCount(),
            queueSize = getQueueSize()
        )
    }

    override suspend fun getTaskStats(): TaskStats {
        // 这里需要从调度器获取实际数据，暂时返回模拟数据
        return TaskStats(
            totalTasks = 100L,
            completedTasks = 85L,
            failedTasks = 5L,
            runningTasks = 3L,
            pendingTasks = 7L,
            averageExecutionTime = 2500L,
            successRate = 0.85,
            taskThroughput = 10.5
        )
    }

    override suspend fun getSystemHealthMetrics(): SystemHealthMetrics {
        val uptime = System.currentTimeMillis() - startTime
        val errorCount = logs.count { it.level == LogLevel.ERROR }
        val warningCount = logs.count { it.level == LogLevel.WARN }
        val criticalIssues = getActiveAlerts().filter { it.level == AlertLevel.CRITICAL }
            .map { it.title }
        
        val status = when {
            criticalIssues.isNotEmpty() -> HealthStatus.CRITICAL
            errorCount > 10 -> HealthStatus.UNHEALTHY
            warningCount > 20 -> HealthStatus.DEGRADED
            else -> HealthStatus.HEALTHY
        }
        
        return SystemHealthMetrics(
            status = status,
            uptime = uptime,
            lastRestart = startTime,
            errorCount = errorCount.toLong(),
            warningCount = warningCount.toLong(),
            criticalIssues = criticalIssues
        )
    }

    override fun observePerformanceStats(): Flow<PerformanceStats> {
        return _performanceStatsFlow.asSharedFlow()
    }

    override suspend fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            monitoringJob = scope.launch {
                startMonitoringLoop()
            }
            
            performanceJob = scope.launch {
                startPerformanceMonitoring()
            }
            
            log(LogEntry(
                level = LogLevel.INFO,
                message = "监控系统启动",
                tag = "MonitorSystem"
            ))
        }
    }

    override suspend fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            monitoringJob?.cancel()
            performanceJob?.cancel()
            
            log(LogEntry(
                level = LogLevel.INFO,
                message = "监控系统停止",
                tag = "MonitorSystem"
            ))
        }
    }

    override fun isMonitoring(): Boolean {
        return isMonitoring.get()
    }

    override suspend fun updateConfig(config: MonitorConfig) {
        // 更新配置逻辑
        log(LogEntry(
            level = LogLevel.INFO,
            message = "监控配置已更新",
            tag = "MonitorSystem"
        ))
    }

    override suspend fun generateReport(startTime: Long, endTime: Long): MonitorReport {
        val performanceStats = getPerformanceStats()
        val taskStats = getTaskStats()
        val systemHealth = getSystemHealthMetrics()
        
        val topMetrics = metrics.values.flatten()
            .filter { it.timestamp in startTime..endTime }
            .sortedByDescending { it.value }
            .take(10)
        
        val criticalAlerts = alerts.values
            .filter { it.timestamp in startTime..endTime && it.level == AlertLevel.CRITICAL }
            .toList()
        
        val errorSummary = logs
            .filter { it.timestamp in startTime..endTime && it.level == LogLevel.ERROR }
            .groupingBy { it.tag }
            .eachCount()
        
        val recommendations = generateRecommendations(performanceStats, systemHealth)
        
        return MonitorReport(
            period = startTime to endTime,
            performanceStats = performanceStats,
            taskStats = taskStats,
            systemHealth = systemHealth,
            topMetrics = topMetrics,
            criticalAlerts = criticalAlerts,
            errorSummary = errorSummary,
            recommendations = recommendations
        )
    }

    override suspend fun cleanupHistory(olderThan: Long): Int {
        var cleanedCount = 0
        
        mutex.withLock {
            // 清理指标
            metrics.forEach { (_, metricList) ->
                val sizeBefore = metricList.size
                metricList.removeIf { it.timestamp < olderThan }
                cleanedCount += sizeBefore - metricList.size
            }
            
            // 清理日志
            val logSizeBefore = logs.size
            logs.removeIf { it.timestamp < olderThan }
            cleanedCount += logSizeBefore - logs.size
            
            // 清理警报
            val alertSizeBefore = alerts.size
            alerts.values.removeIf { it.timestamp < olderThan }
            cleanedCount += alertSizeBefore - alerts.size
        }
        
        return cleanedCount
    }

    /**
     * 监控主循环
     */
    private suspend fun startMonitoringLoop() {
        while (isMonitoring.get()) {
            try {
                // 记录系统指标
                recordSystemMetrics()
                
                // 清理过期数据
                val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L) // 24小时前
                if (System.currentTimeMillis() % (60 * 60 * 1000L) == 0L) { // 每小时清理一次
                    cleanupHistory(cutoffTime)
                }
                
                delay(config.monitor.metricsInterval)
            } catch (e: Exception) {
                if (config.isDebugMode()) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 性能监控
     */
    private suspend fun startPerformanceMonitoring() {
        while (isMonitoring.get()) {
            try {
                if (config.monitor.performanceTracking) {
                    val stats = getPerformanceStats()
                    _performanceStatsFlow.tryEmit(stats)
                }
                
                delay(config.monitor.metricsInterval * 2) // 性能监控频率稍低
            } catch (e: Exception) {
                if (config.isDebugMode()) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 记录系统指标
     */
    private suspend fun recordSystemMetrics() {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val memoryUsage = usedMemory.toDouble() / totalMemory
        
        recordMetric(Metric(
            name = "system.memory.usage",
            type = MetricType.SYSTEM,
            value = memoryUsage,
            unit = "percentage"
        ))
        
        recordMetric(Metric(
            name = "system.threads.active",
            type = MetricType.SYSTEM,
            value = Thread.activeCount().toDouble(),
            unit = "count"
        ))
        
        recordMetric(Metric(
            name = "monitor.metrics.total",
            type = MetricType.SYSTEM,
            value = totalMetrics.get().toDouble(),
            unit = "count"
        ))
        
        recordMetric(Metric(
            name = "monitor.logs.total",
            type = MetricType.SYSTEM,
            value = totalLogs.get().toDouble(),
            unit = "count"
        ))
    }

    /**
     * 检查指标阈值
     */
    private suspend fun checkMetricThresholds(metric: Metric) {
        when (metric.name) {
            "system.memory.usage" -> {
                if (metric.value > config.monitor.memoryThreshold) {
                    sendAlert(Alert(
                        id = "memory_threshold_${System.currentTimeMillis()}",
                        level = AlertLevel.WARNING,
                        title = "内存使用率过高",
                        message = "当前内存使用率: ${String.format("%.2f", metric.value * 100)}%",
                        source = "MonitorSystem"
                    ))
                }
            }
            "system.cpu.usage" -> {
                if (metric.value > config.monitor.cpuThreshold) {
                    sendAlert(Alert(
                        id = "cpu_threshold_${System.currentTimeMillis()}",
                        level = AlertLevel.WARNING,
                        title = "CPU使用率过高",
                        message = "当前CPU使用率: ${String.format("%.2f", metric.value * 100)}%",
                        source = "MonitorSystem"
                    ))
                }
            }
        }
    }

    /**
     * 写入日志文件
     */
    private suspend fun writeLogToFile(entry: LogEntry) {
        try {
            withContext(Dispatchers.IO) {
                val today = dateFormat.format(Date(entry.timestamp))
                val logFile = File(logDir, "phoenix_$today.log")
                
                FileWriter(logFile, true).use { writer ->
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                        .format(Date(entry.timestamp))
                    
                    writer.write("$timestamp [${entry.level}] [${entry.tag}] ${entry.message}")
                    if (entry.taskId != null) {
                        writer.write(" [taskId=${entry.taskId}]")
                    }
                    if (entry.operationId != null) {
                        writer.write(" [operationId=${entry.operationId}]")
                    }
                    if (entry.exception != null) {
                        writer.write(" [exception=${entry.exception.message}]")
                    }
                    writer.write("\n")
                }
                
                // 检查日志文件大小并轮转
                if (logFile.length() > config.monitor.logFileSize) {
                    rotateLogFile(logFile)
                }
            }
        } catch (e: Exception) {
            // 写入日志文件失败，不影响主流程
            if (config.isDebugMode()) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 轮转日志文件
     */
    private fun rotateLogFile(logFile: File) {
        try {
            val timestamp = SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())
            val rotatedFile = File(logFile.parent, "${logFile.nameWithoutExtension}_$timestamp.log")
            logFile.renameTo(rotatedFile)
            
            // 清理旧日志文件
            cleanupOldLogFiles()
        } catch (e: Exception) {
            if (config.isDebugMode()) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 清理旧日志文件
     */
    private fun cleanupOldLogFiles() {
        try {
            val logFiles = logDir.listFiles { file ->
                file.isFile && file.name.startsWith("phoenix_") && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: return
            
            if (logFiles.size > config.monitor.maxLogFiles) {
                logFiles.drop(config.monitor.maxLogFiles).forEach { file ->
                    file.delete()
                }
            }
        } catch (e: Exception) {
            if (config.isDebugMode()) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 获取CPU使用率 (简化实现)
     */
    private fun getCpuUsage(): Double {
        // 实际实现需要使用JMX或其他方式获取CPU使用率
        return Math.random() * 0.5 // 模拟数据
    }

    /**
     * 获取磁盘使用率
     */
    private fun getDiskUsage(): Double {
        return try {
            val file = File(".")
            val totalSpace = file.totalSpace
            val freeSpace = file.freeSpace
            val usedSpace = totalSpace - freeSpace
            usedSpace.toDouble() / totalSpace
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * 获取网络延迟 (简化实现)
     */
    private fun getNetworkLatency(): Long {
        return (50 + Math.random() * 100).toLong() // 模拟数据
    }

    /**
     * 获取吞吐量
     */
    private fun getThroughput(): Double {
        return totalMetrics.get().toDouble() / ((System.currentTimeMillis() - startTime) / 1000.0)
    }

    /**
     * 获取平均响应时间
     */
    private fun getAverageResponseTime(): Long {
        return 250L // 模拟数据
    }

    /**
     * 获取错误率
     */
    private fun getErrorRate(): Double {
        val errorCount = logs.count { it.level == LogLevel.ERROR }
        val totalCount = logs.size
        return if (totalCount > 0) errorCount.toDouble() / totalCount else 0.0
    }

    /**
     * 获取队列大小
     */
    private fun getQueueSize(): Int {
        return 0 // 需要从调度器获取实际数据
    }

    /**
     * 生成建议
     */
    private fun generateRecommendations(
        performanceStats: PerformanceStats,
        systemHealth: SystemHealthMetrics
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        if (performanceStats.memoryUsage > 0.8) {
            recommendations.add("内存使用率过高，建议增加内存或优化内存使用")
        }
        
        if (performanceStats.cpuUsage > 0.7) {
            recommendations.add("CPU使用率过高，建议检查是否有高负载任务")
        }
        
        if (performanceStats.errorRate > 0.1) {
            recommendations.add("错误率较高，建议检查系统配置和任务逻辑")
        }
        
        if (systemHealth.criticalIssues.isNotEmpty()) {
            recommendations.add("存在严重问题，建议立即处理: ${systemHealth.criticalIssues.joinToString(", ")}")
        }
        
        return recommendations
    }
}