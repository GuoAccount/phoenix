package com.g2s.phoenix.core.resilience

import com.g2s.phoenix.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 异常类型枚举
 */
enum class ExceptionType {
    NETWORK_ERROR,      // 网络错误
    TIMEOUT_ERROR,      // 超时错误
    ELEMENT_NOT_FOUND,  // 元素未找到
    PERMISSION_DENIED,  // 权限拒绝
    RESOURCE_EXHAUSTED, // 资源耗尽
    APP_CRASH,          // 应用崩溃
    SYSTEM_ERROR,       // 系统错误
    UNKNOWN_ERROR       // 未知错误
}

/**
 * 异常严重程度
 */
enum class ExceptionSeverity {
    LOW,        // 低
    MEDIUM,     // 中等
    HIGH,       // 高
    CRITICAL    // 严重
}

/**
 * 恢复策略类型
 */
enum class RecoveryStrategy {
    RETRY,              // 重试
    FALLBACK,           // 回退
    RESTART,            // 重启
    SKIP,               // 跳过
    ABORT,              // 中止
    CIRCUIT_BREAKER,    // 熔断
    CUSTOM              // 自定义
}

/**
 * 系统健康状态
 */
enum class HealthStatus {
    HEALTHY,    // 健康
    DEGRADED,   // 降级
    UNHEALTHY,  // 不健康
    CRITICAL    // 危险
}

/**
 * 异常信息
 */
data class ExceptionInfo(
    val id: String,
    val type: ExceptionType,
    val severity: ExceptionSeverity,
    val message: String,
    val stackTrace: String? = null,
    val context: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val taskId: String? = null,
    val operationId: String? = null
)

/**
 * 恢复结果
 */
data class RecoveryResult(
    val success: Boolean,
    val strategy: RecoveryStrategy,
    val message: String,
    val data: Any? = null,
    val nextAction: String? = null,
    val retryDelay: Long = 0L,
    val executionTime: Long
)

/**
 * 健康检查结果
 */
data class HealthCheckResult(
    val status: HealthStatus,
    val message: String,
    val metrics: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 熔断器状态
 */
enum class CircuitBreakerState {
    CLOSED,     // 关闭（正常）
    OPEN,       // 开启（熔断）
    HALF_OPEN   // 半开（测试）
}

/**
 * 熔断器配置
 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,          // 失败阈值
    val timeout: Long = 30000L,             // 超时时间
    val retryTimeout: Long = 60000L,        // 重试超时
    val successThreshold: Int = 3           // 成功阈值
)

/**
 * 韧性核心接口
 */
interface ResilienceCore {
    
    /**
     * 处理异常
     */
    suspend fun handleException(exception: ExceptionInfo): RecoveryResult
    
    /**
     * 注册异常处理器
     */
    fun registerExceptionHandler(type: ExceptionType, handler: ExceptionHandler)
    
    /**
     * 注销异常处理器
     */
    fun unregisterExceptionHandler(type: ExceptionType)
    
    /**
     * 分类异常
     */
    fun classifyException(exception: Throwable, context: Map<String, Any> = emptyMap()): ExceptionInfo
    
    /**
     * 执行健康检查
     */
    suspend fun performHealthCheck(): HealthCheckResult
    
    /**
     * 获取系统健康状态
     */
    suspend fun getHealthStatus(): HealthStatus
    
    /**
     * 监听健康状态变化
     */
    fun observeHealthStatus(): Flow<HealthCheckResult>
    
    /**
     * 获取熔断器状态
     */
    fun getCircuitBreakerState(serviceId: String): CircuitBreakerState
    
    /**
     * 记录成功执行
     */
    suspend fun recordSuccess(serviceId: String)
    
    /**
     * 记录失败执行
     */
    suspend fun recordFailure(serviceId: String, exception: ExceptionInfo)
    
    /**
     * 检查服务是否可用
     */
    suspend fun isServiceAvailable(serviceId: String): Boolean
    
    /**
     * 启动生命维持系统
     */
    suspend fun startLifeSupport()
    
    /**
     * 停止生命维持系统
     */
    suspend fun stopLifeSupport()
    
    /**
     * 进入紧急模式
     */
    suspend fun enterEmergencyMode()
    
    /**
     * 退出紧急模式
     */
    suspend fun exitEmergencyMode()
    
    /**
     * 检查是否处于紧急模式
     */
    fun isInEmergencyMode(): Boolean
    
    /**
     * 获取恢复建议
     */
    suspend fun getRecoveryRecommendation(exception: ExceptionInfo): List<RecoveryStrategy>
    
    /**
     * 执行自愈操作
     */
    suspend fun performSelfHealing(): Boolean
    
    /**
     * 获取韧性统计信息
     */
    suspend fun getResilienceStatistics(): ResilienceStatistics
    
    /**
     * 重置统计信息
     */
    suspend fun resetStatistics()
}

/**
 * 异常处理器接口
 */
interface ExceptionHandler {
    /**
     * 处理异常
     */
    suspend fun handle(exception: ExceptionInfo): RecoveryResult
    
    /**
     * 检查是否可以处理该异常
     */
    fun canHandle(exception: ExceptionInfo): Boolean
    
    /**
     * 获取处理器优先级
     */
    fun getPriority(): Int
    
    /**
     * 获取处理器描述
     */
    fun getDescription(): String
}

/**
 * 韧性统计信息
 */
data class ResilienceStatistics(
    val totalExceptions: Long,
    val handledExceptions: Long,
    val unhandledExceptions: Long,
    val recoverySuccessRate: Double,
    val averageRecoveryTime: Long,
    val circuitBreakerTrips: Long,
    val emergencyModeActivations: Long,
    val selfHealingAttempts: Long,
    val selfHealingSuccesses: Long,
    val exceptionsByType: Map<ExceptionType, Long>,
    val exceptionsBySeverity: Map<ExceptionSeverity, Long>,
    val lastUpdateTime: Long = System.currentTimeMillis()
)

/**
 * 韧性事件
 */
sealed class ResilienceEvent {
    data class ExceptionOccurred(val exception: ExceptionInfo) : ResilienceEvent()
    data class RecoveryAttempted(val strategy: RecoveryStrategy, val success: Boolean) : ResilienceEvent()
    data class HealthStatusChanged(val oldStatus: HealthStatus, val newStatus: HealthStatus) : ResilienceEvent()
    data class CircuitBreakerStateChanged(val serviceId: String, val state: CircuitBreakerState) : ResilienceEvent()
    data class EmergencyModeActivated(val reason: String) : ResilienceEvent()
    data class EmergencyModeDeactivated(val reason: String) : ResilienceEvent()
    data class SelfHealingTriggered(val reason: String) : ResilienceEvent()
}