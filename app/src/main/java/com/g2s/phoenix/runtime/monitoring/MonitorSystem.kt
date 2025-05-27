package com.g2s.phoenix.runtime.monitoring

import com.g2s.phoenix.model.*
import com.g2s.phoenix.core.resilience.HealthStatus
import kotlinx.coroutines.flow.Flow

/**
 * 监控指标类型
 */
enum class MetricType {
    PERFORMANCE,    // 性能指标
    SYSTEM,         // 系统指标
    TASK,           // 任务指标
    ERROR,          // 错误指标
    CUSTOM          // 自定义指标
}

/**
 * 监控指标
 */
data class Metric(
    val name: String,
    val type: MetricType,
    val value: Double,
    val unit: String = "",
    val tags: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 性能统计
 */
data class PerformanceStats(
    val cpuUsage: Double,
    val memoryUsage: Double,
    val diskUsage: Double,
    val networkLatency: Long,
    val throughput: Double,
    val responseTime: Long,
    val errorRate: Double,
    val activeThreads: Int,
    val queueSize: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 任务统计
 */
data class TaskStats(
    val totalTasks: Long,
    val completedTasks: Long,
    val failedTasks: Long,
    val runningTasks: Long,
    val pendingTasks: Long,
    val averageExecutionTime: Long,
    val successRate: Double,
    val taskThroughput: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 系统健康指标
 */
data class SystemHealthMetrics(
    val status: HealthStatus,
    val uptime: Long,
    val lastRestart: Long,
    val errorCount: Long,
    val warningCount: Long,
    val criticalIssues: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 警报级别
 */
enum class AlertLevel {
    INFO,       // 信息
    WARNING,    // 警告
    ERROR,      // 错误
    CRITICAL    // 严重
}

/**
 * 警报信息
 */
data class Alert(
    val id: String,
    val level: AlertLevel,
    val title: String,
    val message: String,
    val source: String,
    val data: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val acknowledged: Boolean = false
)

/**
 * 日志级别
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}

/**
 * 日志条目
 */
data class LogEntry(
    val level: LogLevel,
    val message: String,
    val tag: String = "",
    val taskId: String? = null,
    val operationId: String? = null,
    val exception: Throwable? = null,
    val data: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 监控系统接口
 */
interface MonitorSystem {
    
    /**
     * 记录指标
     */
    suspend fun recordMetric(metric: Metric)
    
    /**
     * 批量记录指标
     */
    suspend fun recordMetrics(metrics: List<Metric>)
    
    /**
     * 获取指标历史
     */
    suspend fun getMetricHistory(
        name: String,
        startTime: Long,
        endTime: Long = System.currentTimeMillis()
    ): List<Metric>
    
    /**
     * 获取实时指标
     */
    fun observeMetrics(): Flow<Metric>
    
    /**
     * 记录日志
     */
    suspend fun log(entry: LogEntry)
    
    /**
     * 获取日志
     */
    suspend fun getLogs(
        level: LogLevel? = null,
        tag: String? = null,
        startTime: Long = 0,
        endTime: Long = System.currentTimeMillis(),
        limit: Int = 100
    ): List<LogEntry>
    
    /**
     * 监听日志流
     */
    fun observeLogs(): Flow<LogEntry>
    
    /**
     * 发送警报
     */
    suspend fun sendAlert(alert: Alert)
    
    /**
     * 获取活跃警报
     */
    suspend fun getActiveAlerts(): List<Alert>
    
    /**
     * 确认警报
     */
    suspend fun acknowledgeAlert(alertId: String): Boolean
    
    /**
     * 监听警报
     */
    fun observeAlerts(): Flow<Alert>
    
    /**
     * 获取性能统计
     */
    suspend fun getPerformanceStats(): PerformanceStats
    
    /**
     * 获取任务统计
     */
    suspend fun getTaskStats(): TaskStats
    
    /**
     * 获取系统健康指标
     */
    suspend fun getSystemHealthMetrics(): SystemHealthMetrics
    
    /**
     * 监听性能统计
     */
    fun observePerformanceStats(): Flow<PerformanceStats>
    
    /**
     * 启动监控
     */
    suspend fun startMonitoring()
    
    /**
     * 停止监控
     */
    suspend fun stopMonitoring()
    
    /**
     * 检查监控状态
     */
    fun isMonitoring(): Boolean
    
    /**
     * 设置监控配置
     */
    suspend fun updateConfig(config: MonitorConfig)
    
    /**
     * 获取监控报告
     */
    suspend fun generateReport(
        startTime: Long,
        endTime: Long = System.currentTimeMillis()
    ): MonitorReport
    
    /**
     * 清理历史数据
     */
    suspend fun cleanupHistory(olderThan: Long): Int
}

/**
 * 监控报告
 */
data class MonitorReport(
    val period: Pair<Long, Long>,
    val performanceStats: PerformanceStats,
    val taskStats: TaskStats,
    val systemHealth: SystemHealthMetrics,
    val topMetrics: List<Metric>,
    val criticalAlerts: List<Alert>,
    val errorSummary: Map<String, Int>,
    val recommendations: List<String> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

/**
 * 监控事件
 */
sealed class MonitorEvent {
    data class MetricRecorded(val metric: Metric) : MonitorEvent()
    data class AlertTriggered(val alert: Alert) : MonitorEvent()
    data class PerformanceThresholdExceeded(val metric: String, val value: Double, val threshold: Double) : MonitorEvent()
    data class SystemHealthChanged(val oldStatus: HealthStatus, val newStatus: HealthStatus) : MonitorEvent()
}