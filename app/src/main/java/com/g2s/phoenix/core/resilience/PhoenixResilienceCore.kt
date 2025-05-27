package com.g2s.phoenix.core.resilience

import com.g2s.phoenix.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 凤凰韧性核心实现
 */
class PhoenixResilienceCore(
    private val config: PhoenixConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : ResilienceCore {

    // 异常处理器映射
    private val exceptionHandlers = ConcurrentHashMap<ExceptionType, MutableList<ExceptionHandler>>()
    
    // 熔断器映射
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()
    
    // 系统状态
    private val currentHealthStatus = AtomicReference(HealthStatus.HEALTHY)
    private val isEmergencyMode = AtomicBoolean(false)
    private val isLifeSupportActive = AtomicBoolean(false)
    
    // 统计信息
    private val totalExceptions = AtomicLong(0)
    private val handledExceptions = AtomicLong(0)
    private val unhandledExceptions = AtomicLong(0)
    private val recoveryTimes = mutableListOf<Long>()
    private val exceptionsByType = ConcurrentHashMap<ExceptionType, AtomicLong>()
    private val exceptionsBySeverity = ConcurrentHashMap<ExceptionSeverity, AtomicLong>()
    
    // 健康检查流
    private val _healthStatus = MutableSharedFlow<HealthCheckResult>()
    
    // 生命维持系统作业
    private var lifeSupportJob: Job? = null
    
    // 执行锁
    private val mutex = Mutex()
    
    init {
        // 注册默认异常处理器
        registerDefaultHandlers()
        
        // 初始化统计计数器
        ExceptionType.values().forEach { type ->
            exceptionsByType[type] = AtomicLong(0)
        }
        ExceptionSeverity.values().forEach { severity ->
            exceptionsBySeverity[severity] = AtomicLong(0)
        }
    }

    override suspend fun handleException(exception: ExceptionInfo): RecoveryResult {
        val startTime = System.currentTimeMillis()
        
        return mutex.withLock {
            try {
                // 更新统计信息
                totalExceptions.incrementAndGet()
                exceptionsByType[exception.type]?.incrementAndGet()
                exceptionsBySeverity[exception.severity]?.incrementAndGet()
                
                // 获取合适的处理器
                val handlers = exceptionHandlers[exception.type]?.sortedByDescending { it.getPriority() }
                    ?: emptyList()
                
                var lastResult: RecoveryResult? = null
                
                for (handler in handlers) {
                    if (handler.canHandle(exception)) {
                        try {
                            val result = handler.handle(exception)
                            
                            if (result.success) {
                                handledExceptions.incrementAndGet()
                                recordRecoveryTime(System.currentTimeMillis() - startTime)
                                return@withLock result
                            } else {
                                lastResult = result
                            }
                        } catch (e: Exception) {
                            // 处理器执行失败，继续尝试下一个
                            continue
                        }
                    }
                }
                
                // 所有处理器都失败了
                unhandledExceptions.incrementAndGet()
                
                // 检查是否需要进入紧急模式
                checkEmergencyMode(exception)
                
                lastResult ?: RecoveryResult(
                    success = false,
                    strategy = RecoveryStrategy.ABORT,
                    message = "无法处理异常: ${exception.message}",
                    executionTime = System.currentTimeMillis() - startTime
                )
                
            } catch (e: Exception) {
                unhandledExceptions.incrementAndGet()
                RecoveryResult(
                    success = false,
                    strategy = RecoveryStrategy.ABORT,
                    message = "异常处理器执行失败: ${e.message}",
                    executionTime = System.currentTimeMillis() - startTime
                )
            }
        }
    }

    override fun registerExceptionHandler(type: ExceptionType, handler: ExceptionHandler) {
        exceptionHandlers.computeIfAbsent(type) { mutableListOf() }.add(handler)
    }

    override fun unregisterExceptionHandler(type: ExceptionType) {
        exceptionHandlers.remove(type)
    }

    override fun classifyException(exception: Throwable, context: Map<String, Any>): ExceptionInfo {
        val type = when {
            exception is java.net.SocketTimeoutException -> ExceptionType.TIMEOUT_ERROR
            exception is java.net.UnknownHostException -> ExceptionType.NETWORK_ERROR
            exception is SecurityException -> ExceptionType.PERMISSION_DENIED
            exception is OutOfMemoryError -> ExceptionType.RESOURCE_EXHAUSTED
            exception.message?.contains("not found", ignoreCase = true) == true -> ExceptionType.ELEMENT_NOT_FOUND
            else -> ExceptionType.UNKNOWN_ERROR
        }
        
        val severity = when (type) {
            ExceptionType.RESOURCE_EXHAUSTED, ExceptionType.APP_CRASH -> ExceptionSeverity.CRITICAL
            ExceptionType.PERMISSION_DENIED, ExceptionType.SYSTEM_ERROR -> ExceptionSeverity.HIGH
            ExceptionType.TIMEOUT_ERROR, ExceptionType.NETWORK_ERROR -> ExceptionSeverity.MEDIUM
            else -> ExceptionSeverity.LOW
        }
        
        return ExceptionInfo(
            id = "exc_${System.currentTimeMillis()}_${hashCode()}",
            type = type,
            severity = severity,
            message = exception.message ?: "未知异常",
            stackTrace = exception.stackTraceToString(),
            context = context,
            taskId = context["taskId"] as? String,
            operationId = context["operationId"] as? String
        )
    }

    override suspend fun performHealthCheck(): HealthCheckResult {
        val metrics = mutableMapOf<String, Any>()
        
        // 检查内存使用率
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val memoryUsage = usedMemory.toDouble() / totalMemory
        
        metrics["memoryUsage"] = memoryUsage
        metrics["totalMemory"] = totalMemory
        metrics["usedMemory"] = usedMemory
        
        // 检查异常率
        val totalExceptionsCount = totalExceptions.get()
        val handledExceptionsCount = handledExceptions.get()
        val exceptionRate = if (totalExceptionsCount > 0) {
            handledExceptionsCount.toDouble() / totalExceptionsCount
        } else 1.0
        
        metrics["exceptionRate"] = exceptionRate
        metrics["totalExceptions"] = totalExceptionsCount
        metrics["handledExceptions"] = handledExceptionsCount
        
        // 检查熔断器状态
        val openCircuitBreakers = circuitBreakers.values.count { it.state == CircuitBreakerState.OPEN }
        metrics["openCircuitBreakers"] = openCircuitBreakers
        
        // 确定健康状态
        val status = when {
            isEmergencyMode.get() -> HealthStatus.CRITICAL
            memoryUsage > config.monitor.memoryThreshold -> HealthStatus.UNHEALTHY
            exceptionRate < 0.5 || openCircuitBreakers > 3 -> HealthStatus.DEGRADED
            else -> HealthStatus.HEALTHY
        }
        
        val result = HealthCheckResult(
            status = status,
            message = getHealthStatusMessage(status, metrics),
            metrics = metrics
        )
        
        // 更新当前状态
        val oldStatus = currentHealthStatus.getAndSet(status)
        if (oldStatus != status) {
            _healthStatus.tryEmit(result)
        }
        
        return result
    }

    override suspend fun getHealthStatus(): HealthStatus {
        return currentHealthStatus.get()
    }

    override fun observeHealthStatus(): Flow<HealthCheckResult> {
        return _healthStatus.asSharedFlow()
    }

    override fun getCircuitBreakerState(serviceId: String): CircuitBreakerState {
        return circuitBreakers[serviceId]?.state ?: CircuitBreakerState.CLOSED
    }

    override suspend fun recordSuccess(serviceId: String) {
        val breaker = circuitBreakers.computeIfAbsent(serviceId) { 
            CircuitBreaker(serviceId, CircuitBreakerConfig()) 
        }
        breaker.recordSuccess()
    }

    override suspend fun recordFailure(serviceId: String, exception: ExceptionInfo) {
        val breaker = circuitBreakers.computeIfAbsent(serviceId) { 
            CircuitBreaker(serviceId, CircuitBreakerConfig()) 
        }
        breaker.recordFailure()
    }

    override suspend fun isServiceAvailable(serviceId: String): Boolean {
        val breaker = circuitBreakers[serviceId] ?: return true
        return breaker.canExecute()
    }

    override suspend fun startLifeSupport() {
        if (isLifeSupportActive.compareAndSet(false, true)) {
            lifeSupportJob = scope.launch {
                runLifeSupportLoop()
            }
        }
    }

    override suspend fun stopLifeSupport() {
        if (isLifeSupportActive.compareAndSet(true, false)) {
            lifeSupportJob?.cancel()
            lifeSupportJob = null
        }
    }

    override suspend fun enterEmergencyMode() {
        if (isEmergencyMode.compareAndSet(false, true)) {
            currentHealthStatus.set(HealthStatus.CRITICAL)
            
            // 启动生命维持系统
            startLifeSupport()
            
            // 发送健康状态更新
            _healthStatus.tryEmit(
                HealthCheckResult(
                    status = HealthStatus.CRITICAL,
                    message = "系统进入紧急模式"
                )
            )
        }
    }

    override suspend fun exitEmergencyMode() {
        if (isEmergencyMode.compareAndSet(true, false)) {
            // 重新评估健康状态
            performHealthCheck()
        }
    }

    override fun isInEmergencyMode(): Boolean {
        return isEmergencyMode.get()
    }

    override suspend fun getRecoveryRecommendation(exception: ExceptionInfo): List<RecoveryStrategy> {
        return when (exception.type) {
            ExceptionType.NETWORK_ERROR -> listOf(
                RecoveryStrategy.RETRY,
                RecoveryStrategy.FALLBACK,
                RecoveryStrategy.CIRCUIT_BREAKER
            )
            ExceptionType.TIMEOUT_ERROR -> listOf(
                RecoveryStrategy.RETRY,
                RecoveryStrategy.RESTART
            )
            ExceptionType.ELEMENT_NOT_FOUND -> listOf(
                RecoveryStrategy.RETRY,
                RecoveryStrategy.FALLBACK,
                RecoveryStrategy.SKIP
            )
            ExceptionType.PERMISSION_DENIED -> listOf(
                RecoveryStrategy.FALLBACK,
                RecoveryStrategy.ABORT
            )
            ExceptionType.RESOURCE_EXHAUSTED -> listOf(
                RecoveryStrategy.RESTART,
                RecoveryStrategy.CIRCUIT_BREAKER
            )
            ExceptionType.APP_CRASH -> listOf(
                RecoveryStrategy.RESTART,
                RecoveryStrategy.ABORT
            )
            else -> listOf(
                RecoveryStrategy.RETRY,
                RecoveryStrategy.FALLBACK
            )
        }
    }

    override suspend fun performSelfHealing(): Boolean {
        return try {
            // 清理资源
            System.gc()
            
            // 重置失败的熔断器
            circuitBreakers.values.forEach { breaker ->
                if (breaker.state == CircuitBreakerState.OPEN) {
                    breaker.reset()
                }
            }
            
            // 重新评估健康状态
            val healthResult = performHealthCheck()
            
            healthResult.status != HealthStatus.CRITICAL
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getResilienceStatistics(): ResilienceStatistics {
        val avgRecoveryTime = if (recoveryTimes.isNotEmpty()) {
            recoveryTimes.average().toLong()
        } else 0L
        
        val successRate = if (totalExceptions.get() > 0) {
            handledExceptions.get().toDouble() / totalExceptions.get()
        } else 1.0
        
        return ResilienceStatistics(
            totalExceptions = totalExceptions.get(),
            handledExceptions = handledExceptions.get(),
            unhandledExceptions = unhandledExceptions.get(),
            recoverySuccessRate = successRate,
            averageRecoveryTime = avgRecoveryTime,
            circuitBreakerTrips = circuitBreakers.values.sumOf { it.tripCount },
            emergencyModeActivations = 0L, // TODO: 实现计数
            selfHealingAttempts = 0L, // TODO: 实现计数
            selfHealingSuccesses = 0L, // TODO: 实现计数
            exceptionsByType = exceptionsByType.mapValues { it.value.get() },
            exceptionsBySeverity = exceptionsBySeverity.mapValues { it.value.get() }
        )
    }

    override suspend fun resetStatistics() {
        totalExceptions.set(0)
        handledExceptions.set(0)
        unhandledExceptions.set(0)
        recoveryTimes.clear()
        exceptionsByType.values.forEach { it.set(0) }
        exceptionsBySeverity.values.forEach { it.set(0) }
    }

    /**
     * 注册默认异常处理器
     */
    private fun registerDefaultHandlers() {
        // 网络错误处理器
        registerExceptionHandler(ExceptionType.NETWORK_ERROR, NetworkErrorHandler())
        
        // 超时错误处理器
        registerExceptionHandler(ExceptionType.TIMEOUT_ERROR, TimeoutErrorHandler())
        
        // 元素未找到处理器
        registerExceptionHandler(ExceptionType.ELEMENT_NOT_FOUND, ElementNotFoundHandler())
        
        // 资源耗尽处理器
        registerExceptionHandler(ExceptionType.RESOURCE_EXHAUSTED, ResourceExhaustedHandler())
        
        // 通用错误处理器
        registerExceptionHandler(ExceptionType.UNKNOWN_ERROR, GenericErrorHandler())
    }

    /**
     * 检查是否需要进入紧急模式
     */
    private suspend fun checkEmergencyMode(exception: ExceptionInfo) {
        if (exception.severity == ExceptionSeverity.CRITICAL) {
            enterEmergencyMode()
        }
    }

    /**
     * 记录恢复时间
     */
    private fun recordRecoveryTime(time: Long) {
        synchronized(recoveryTimes) {
            recoveryTimes.add(time)
            // 只保留最近的100次记录
            if (recoveryTimes.size > 100) {
                recoveryTimes.removeAt(0)
            }
        }
    }

    /**
     * 生命维持系统循环
     */
    private suspend fun runLifeSupportLoop() {
        while (isLifeSupportActive.get()) {
            try {
                // 执行健康检查
                performHealthCheck()
                
                // 尝试自愈
                if (currentHealthStatus.get() != HealthStatus.HEALTHY) {
                    performSelfHealing()
                }
                
                // 检查是否可以退出紧急模式
                if (isEmergencyMode.get() && currentHealthStatus.get() == HealthStatus.HEALTHY) {
                    exitEmergencyMode()
                }
                
                delay(config.resilience.healthCheckInterval)
            } catch (e: Exception) {
                if (config.isDebugMode()) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 获取健康状态消息
     */
    private fun getHealthStatusMessage(status: HealthStatus, metrics: Map<String, Any>): String {
        return when (status) {
            HealthStatus.HEALTHY -> "系统运行正常"
            HealthStatus.DEGRADED -> "系统性能下降，内存使用率: ${String.format("%.2f", metrics["memoryUsage"] as Double * 100)}%"
            HealthStatus.UNHEALTHY -> "系统不健康，需要关注"
            HealthStatus.CRITICAL -> "系统处于危险状态，已进入紧急模式"
        }
    }

    /**
     * 熔断器实现
     */
    inner class CircuitBreaker(
        private val serviceId: String,
        private val config: CircuitBreakerConfig
    ) {
        var state = CircuitBreakerState.CLOSED
            private set
        
        private var failureCount = 0
        private var successCount = 0
        private var lastFailureTime = 0L
        var tripCount = 0L
            private set

        fun canExecute(): Boolean {
            return when (state) {
                CircuitBreakerState.CLOSED -> true
                CircuitBreakerState.OPEN -> {
                    if (System.currentTimeMillis() - lastFailureTime > config.retryTimeout) {
                        state = CircuitBreakerState.HALF_OPEN
                        true
                    } else {
                        false
                    }
                }
                CircuitBreakerState.HALF_OPEN -> true
            }
        }

        fun recordSuccess() {
            when (state) {
                CircuitBreakerState.CLOSED -> {
                    failureCount = 0
                }
                CircuitBreakerState.HALF_OPEN -> {
                    successCount++
                    if (successCount >= config.successThreshold) {
                        state = CircuitBreakerState.CLOSED
                        failureCount = 0
                        successCount = 0
                    }
                }
                CircuitBreakerState.OPEN -> {
                    // 不应该发生
                }
            }
        }

        fun recordFailure() {
            failureCount++
            lastFailureTime = System.currentTimeMillis()
            
            when (state) {
                CircuitBreakerState.CLOSED -> {
                    if (failureCount >= config.failureThreshold) {
                        state = CircuitBreakerState.OPEN
                        tripCount++
                    }
                }
                CircuitBreakerState.HALF_OPEN -> {
                    state = CircuitBreakerState.OPEN
                    successCount = 0
                    tripCount++
                }
                CircuitBreakerState.OPEN -> {
                    // 保持开启状态
                }
            }
        }

        fun reset() {
            state = CircuitBreakerState.CLOSED
            failureCount = 0
            successCount = 0
        }
    }

    /**
     * 默认异常处理器实现
     */
    inner class NetworkErrorHandler : ExceptionHandler {
        override suspend fun handle(exception: ExceptionInfo): RecoveryResult {
            return RecoveryResult(
                success = true,
                strategy = RecoveryStrategy.RETRY,
                message = "网络错误，将在延迟后重试",
                retryDelay = 2000L,
                executionTime = 100L
            )
        }

        override fun canHandle(exception: ExceptionInfo): Boolean = 
            exception.type == ExceptionType.NETWORK_ERROR

        override fun getPriority(): Int = 5

        override fun getDescription(): String = "处理网络连接错误"
    }

    inner class TimeoutErrorHandler : ExceptionHandler {
        override suspend fun handle(exception: ExceptionInfo): RecoveryResult {
            return RecoveryResult(
                success = true,
                strategy = RecoveryStrategy.RETRY,
                message = "超时错误，将增加超时时间后重试",
                retryDelay = 1000L,
                executionTime = 50L
            )
        }

        override fun canHandle(exception: ExceptionInfo): Boolean = 
            exception.type == ExceptionType.TIMEOUT_ERROR

        override fun getPriority(): Int = 4

        override fun getDescription(): String = "处理超时错误"
    }

    inner class ElementNotFoundHandler : ExceptionHandler {
        override suspend fun handle(exception: ExceptionInfo): RecoveryResult {
            return RecoveryResult(
                success = true,
                strategy = RecoveryStrategy.FALLBACK,
                message = "元素未找到，尝试使用备用选择器",
                executionTime = 30L
            )
        }

        override fun canHandle(exception: ExceptionInfo): Boolean = 
            exception.type == ExceptionType.ELEMENT_NOT_FOUND

        override fun getPriority(): Int = 3

        override fun getDescription(): String = "处理元素未找到错误"
    }

    inner class ResourceExhaustedHandler : ExceptionHandler {
        override suspend fun handle(exception: ExceptionInfo): RecoveryResult {
            // 触发垃圾回收
            System.gc()
            
            return RecoveryResult(
                success = true,
                strategy = RecoveryStrategy.RESTART,
                message = "资源耗尽，已清理内存并建议重启",
                executionTime = 200L
            )
        }

        override fun canHandle(exception: ExceptionInfo): Boolean = 
            exception.type == ExceptionType.RESOURCE_EXHAUSTED

        override fun getPriority(): Int = 8

        override fun getDescription(): String = "处理资源耗尽错误"
    }

    inner class GenericErrorHandler : ExceptionHandler {
        override suspend fun handle(exception: ExceptionInfo): RecoveryResult {
            return RecoveryResult(
                success = false,
                strategy = RecoveryStrategy.ABORT,
                message = "无法处理的通用错误",
                executionTime = 10L
            )
        }

        override fun canHandle(exception: ExceptionInfo): Boolean = true

        override fun getPriority(): Int = 1

        override fun getDescription(): String = "通用错误处理器"
    }
}