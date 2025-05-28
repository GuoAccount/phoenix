package com.g2s.phoenix.core.engine

import com.g2s.phoenix.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 凤凰操作执行引擎实现
 */
class PhoenixOperationEngine(
    private val config: PhoenixConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : OperationEngine {

    // 操作处理器映射
    private val operationHandlers = ConcurrentHashMap<OperationType, OperationHandler>()
    
    // 引擎状态
    private val isInitialized = AtomicBoolean(false)
    private val executionStatus = AtomicReference(ExecutionStatus.IDLE)
    
    // 进度监听
    private val _executionProgress = MutableSharedFlow<ExecutionProgress>()
    
    // 执行锁
    private val executionMutex = Mutex()
    
    init {
        // 注册默认操作处理器
        registerDefaultHandlers()
    }

    override suspend fun executeOperation(
        type: OperationType,
        params: OperationParams,
        context: OperationContext
    ): OperationResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // 获取处理器
            val handler = operationHandlers[type]
                ?: return OperationResult(
                    success = false,
                    message = "不支持的操作类型: $type",
                    executionTime = System.currentTimeMillis() - startTime
                )
            
            // 验证参数
            if (!handler.validateParams(params)) {
                return OperationResult(
                    success = false,
                    message = "无效的操作参数",
                    executionTime = System.currentTimeMillis() - startTime
                )
            }
            
            // 执行前等待
            if (params.waitBefore > 0) {
                delay(params.waitBefore)
            }
            
            // 执行操作
            val result = withTimeout(params.timeout) {
                handler.handle(params, context)
            }
            
            // 执行后等待
            if (params.waitAfter > 0) {
                delay(params.waitAfter)
            }
            
            result
            
        } catch (e: TimeoutCancellationException) {
            OperationResult(
                success = false,
                message = "操作执行超时",
                executionTime = System.currentTimeMillis() - startTime,
                exception = e
            )
        } catch (e: Exception) {
            OperationResult(
                success = false,
                message = "操作执行异常: ${e.message}",
                executionTime = System.currentTimeMillis() - startTime,
                exception = e
            )
        }
    }

    override suspend fun executeOperations(
        operations: List<Pair<OperationType, OperationParams>>,
        context: OperationContext
    ): List<OperationResult> {
        val results = mutableListOf<OperationResult>()
        
        for ((index, operation) in operations.withIndex()) {
            val (type, params) = operation
            // 发送进度更新
            val progress = index.toDouble() / operations.size
            _executionProgress.tryEmit(
                ExecutionProgress(
                    taskId = context.taskId,
                    operationIndex = index,
                    totalOperations = operations.size,
                    currentOperation = "$type: ${params.selector}",
                    progress = progress,
                    status = "执行中"
                )
            )
            
            val result = executeOperation(type, params, context)
            results.add(result)
            
            // 如果操作失败且不允许继续，则停止执行
            if (!result.success && !config.resilience.autoRestart) {
                break
            }
        }
        
        return results
    }

    override suspend fun executeTask(task: Task): TaskResult {
        val startTime = System.currentTimeMillis()
        
        return executionMutex.withLock {
            try {
                executionStatus.set(ExecutionStatus.EXECUTING)
                
                // 创建执行上下文
                val context = OperationContext(
                    taskId = task.id,
                    operationId = "task_${task.id}_${System.currentTimeMillis()}"
                )
                
                // 根据任务类型执行相应操作
                val operationResult = when (task.type) {
                    TaskType.APP_OPERATION.name -> executeAppOperation(task, context)
                    TaskType.SYSTEM_COMMAND.name -> executeSystemCommand(task, context)
                    TaskType.FILE_OPERATION.name -> executeFileOperation(task, context)
                    TaskType.NETWORK_REQUEST.name -> executeNetworkRequest(task, context)
                    TaskType.CUSTOM_PLUGIN.name -> executeCustomPlugin(task, context)
                    else -> {
                        // 处理未知的任务类型
                        // 直接返回TaskResult，因为这是特殊情况
                        executionStatus.set(ExecutionStatus.COMPLETED)
                        return TaskResult(
                            success = false,
                            message = "未知的任务类型: ${task.type}",
                            executionTime = 0L // 或者记录实际的少量处理时间
                        )
                    }
                }
                
                executionStatus.set(ExecutionStatus.COMPLETED)
                
                // 将OperationResult转换为TaskResult
                return TaskResult(
                    success = operationResult.success,
                    message = operationResult.message,
                    data = operationResult.data,
                    executionTime = System.currentTimeMillis() - startTime,
                    exception = operationResult.exception
                )
                
            } catch (e: Exception) {
                executionStatus.set(ExecutionStatus.FAILED)
                
                TaskResult(
                    success = false,
                    message = "任务执行异常: ${e.message}",
                    executionTime = System.currentTimeMillis() - startTime,
                    exception = e
                )
            }
        }
    }

    override suspend fun checkElementExists(selector: String, timeout: Long): Boolean {
        return try {
            withTimeout(timeout) {
                // 这里应该实现实际的元素检查逻辑
                // 暂时返回模拟结果
                delay(100)
                selector.isNotEmpty()
            }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    override suspend fun waitForElement(selector: String, timeout: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (checkElementExists(selector, 1000L)) {
                return true
            }
            delay(500)
        }
        
        return false
    }

    override suspend fun getElementInfo(selector: String): Map<String, Any>? {
        return try {
            // 这里应该实现实际的元素信息获取逻辑
            // 暂时返回模拟数据
            mapOf(
                "selector" to selector,
                "exists" to checkElementExists(selector),
                "bounds" to mapOf("x" to 0, "y" to 0, "width" to 100, "height" to 50),
                "text" to "",
                "enabled" to true,
                "visible" to true
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun takeScreenshot(): ByteArray? {
        return try {
            // 这里应该实现实际的截图逻辑
            // 暂时返回空数据
            ByteArray(0)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getCurrentPageInfo(): Map<String, Any> {
        return mapOf(
            "package" to config.target.app,
            "activity" to "unknown",
            "timestamp" to System.currentTimeMillis(),
            "screenSize" to mapOf("width" to 1080, "height" to 1920),
            "orientation" to "portrait"
        )
    }

    override fun observeExecutionProgress(): Flow<ExecutionProgress> {
        return _executionProgress.asSharedFlow()
    }

    override fun registerOperationHandler(type: OperationType, handler: OperationHandler) {
        operationHandlers[type] = handler
    }

    override fun unregisterOperationHandler(type: OperationType) {
        operationHandlers.remove(type)
    }

    override fun getSupportedOperations(): List<OperationType> {
        return operationHandlers.keys.toList()
    }

    override suspend fun initialize(): Boolean {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                executionStatus.set(ExecutionStatus.INITIALIZING)
                
                // 初始化各种服务
                initializeServices()
                
                executionStatus.set(ExecutionStatus.IDLE)
                return true
            } catch (e: Exception) {
                isInitialized.set(false)
                executionStatus.set(ExecutionStatus.FAILED)
                if (config.isDebugMode()) {
                    e.printStackTrace()
                }
                return false
            }
        }
        return true
    }

    override suspend fun destroy() {
        if (isInitialized.compareAndSet(true, false)) {
            executionStatus.set(ExecutionStatus.IDLE)
            operationHandlers.clear()
        }
    }

    override fun isInitialized(): Boolean {
        return isInitialized.get()
    }

    /**
     * 注册默认操作处理器
     */
    private fun registerDefaultHandlers() {
        // 点击操作处理器
        registerOperationHandler(OperationType.CLICK, ClickHandler())
        
        // 输入操作处理器
        registerOperationHandler(OperationType.INPUT, InputHandler())
        
        // 等待操作处理器
        registerOperationHandler(OperationType.WAIT, WaitHandler())
        
        // 滑动操作处理器
        registerOperationHandler(OperationType.SWIPE, SwipeHandler())
        
        // 系统操作处理器
        registerOperationHandler(OperationType.SYSTEM, SystemHandler())
    }

    /**
     * 初始化服务
     */
    private suspend fun initializeServices() {
        // 初始化各种服务
        delay(100) // 模拟初始化时间
    }

    /**
     * 执行应用操作
     */
    private suspend fun executeAppOperation(task: Task, context: OperationContext): OperationResult {
        val operations = parseTaskOperations(task)
        val results = executeOperations(operations, context)
        
        val success = results.all { it.success }
        val message = if (success) "应用操作执行成功" else "应用操作执行失败"
        
        return OperationResult(
            success = success,
            message = message,
            data = results,
            executionTime = results.sumOf { it.executionTime }
        )
    }

    /**
     * 执行系统命令
     */
    private suspend fun executeSystemCommand(task: Task, context: OperationContext): OperationResult {
        return executeOperation(OperationType.SYSTEM, 
            OperationParams(selector = task.target.selector), 
            context
        )
    }

    /**
     * 执行文件操作
     */
    private suspend fun executeFileOperation(task: Task, context: OperationContext): OperationResult {
        return executeOperation(OperationType.FILE,
            OperationParams(selector = task.target.selector),
            context
        )
    }

    /**
     * 执行网络请求
     */
    private suspend fun executeNetworkRequest(task: Task, context: OperationContext): OperationResult {
        return executeOperation(OperationType.NETWORK,
            OperationParams(selector = task.target.selector),
            context
        )
    }

    /**
     * 执行自定义插件
     */
    private suspend fun executeCustomPlugin(task: Task, context: OperationContext): OperationResult {
        return executeOperation(OperationType.CUSTOM,
            OperationParams(selector = task.target.selector),
            context
        )
    }

    /**
     * 解析任务操作
     */
    private fun parseTaskOperations(task: Task): List<Pair<OperationType, OperationParams>> {
        // 这里应该根据任务配置解析出具体的操作序列
        // 暂时返回简单的点击操作
        return listOf(
            OperationType.CLICK to OperationParams(selector = task.target.selector)
        )
    }

    /**
     * 默认操作处理器实现
     */
    inner class ClickHandler : OperationHandler {
        override suspend fun handle(params: OperationParams, context: OperationContext): OperationResult {
            val startTime = System.currentTimeMillis()
            
            return try {
                // 等待元素出现
                if (!waitForElement(params.selector, params.timeout)) {
                    return OperationResult(
                        success = false,
                        message = "元素未找到: ${params.selector}",
                        executionTime = System.currentTimeMillis() - startTime
                    )
                }
                
                // 执行点击操作
                delay(100) // 模拟点击时间
                
                OperationResult(
                    success = true,
                    message = "点击操作成功",
                    executionTime = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                OperationResult(
                    success = false,
                    message = "点击操作失败: ${e.message}",
                    executionTime = System.currentTimeMillis() - startTime,
                    exception = e
                )
            }
        }

        override fun validateParams(params: OperationParams): Boolean {
            return params.selector.isNotEmpty()
        }

        override fun getDescription(): String = "点击指定元素"
    }

    inner class InputHandler : OperationHandler {
        override suspend fun handle(params: OperationParams, context: OperationContext): OperationResult {
            val startTime = System.currentTimeMillis()
            
            return try {
                if (!waitForElement(params.selector, params.timeout)) {
                    return OperationResult(
                        success = false,
                        message = "输入框未找到: ${params.selector}",
                        executionTime = System.currentTimeMillis() - startTime
                    )
                }
                
                // 执行输入操作
                delay(200) // 模拟输入时间
                
                OperationResult(
                    success = true,
                    message = "输入操作成功",
                    executionTime = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                OperationResult(
                    success = false,
                    message = "输入操作失败: ${e.message}",
                    executionTime = System.currentTimeMillis() - startTime,
                    exception = e
                )
            }
        }

        override fun validateParams(params: OperationParams): Boolean {
            return params.selector.isNotEmpty() && params.value != null
        }

        override fun getDescription(): String = "在指定元素中输入文本"
    }

    inner class WaitHandler : OperationHandler {
        override suspend fun handle(params: OperationParams, context: OperationContext): OperationResult {
            val startTime = System.currentTimeMillis()
            
            return try {
                val waitTime = params.value as? Long ?: 1000L
                delay(waitTime)
                
                OperationResult(
                    success = true,
                    message = "等待操作完成",
                    executionTime = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                OperationResult(
                    success = false,
                    message = "等待操作失败: ${e.message}",
                    executionTime = System.currentTimeMillis() - startTime,
                    exception = e
                )
            }
        }

        override fun validateParams(params: OperationParams): Boolean {
            return params.value is Long && params.value > 0
        }

        override fun getDescription(): String = "等待指定时间"
    }

    inner class SwipeHandler : OperationHandler {
        override suspend fun handle(params: OperationParams, context: OperationContext): OperationResult {
            val startTime = System.currentTimeMillis()
            
            return try {
                // 执行滑动操作
                delay(300) // 模拟滑动时间
                
                OperationResult(
                    success = true,
                    message = "滑动操作成功",
                    executionTime = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                OperationResult(
                    success = false,
                    message = "滑动操作失败: ${e.message}",
                    executionTime = System.currentTimeMillis() - startTime,
                    exception = e
                )
            }
        }

        override fun validateParams(params: OperationParams): Boolean {
            return params.selector.isNotEmpty()
        }

        override fun getDescription(): String = "执行滑动操作"
    }

    inner class SystemHandler : OperationHandler {
        override suspend fun handle(params: OperationParams, context: OperationContext): OperationResult {
            val startTime = System.currentTimeMillis()
            
            return try {
                // 执行系统命令
                delay(500) // 模拟系统操作时间
                
                OperationResult(
                    success = true,
                    message = "系统操作成功",
                    executionTime = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                OperationResult(
                    success = false,
                    message = "系统操作失败: ${e.message}",
                    executionTime = System.currentTimeMillis() - startTime,
                    exception = e
                )
            }
        }

        override fun validateParams(params: OperationParams): Boolean {
            return params.selector.isNotEmpty()
        }

        override fun getDescription(): String = "执行系统命令"
    }
}