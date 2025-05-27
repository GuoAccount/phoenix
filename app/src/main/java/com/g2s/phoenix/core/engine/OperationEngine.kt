package com.g2s.phoenix.core.engine

import com.g2s.phoenix.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 操作类型枚举
 */
enum class OperationType {
    CLICK,          // 点击操作
    INPUT,          // 输入操作
    SWIPE,          // 滑动操作
    WAIT,           // 等待操作
    NAVIGATE,       // 导航操作
    CUSTOM,         // 自定义操作
    SYSTEM,         // 系统操作
    FILE,           // 文件操作
    NETWORK         // 网络操作
}

/**
 * 操作参数
 */
data class OperationParams(
    val selector: String,
    val value: Any? = null,
    val timeout: Long = 10000L,
    val retryCount: Int = 3,
    val waitBefore: Long = 0L,
    val waitAfter: Long = 0L,
    val attributes: Map<String, Any> = emptyMap()
)

/**
 * 操作结果
 */
data class OperationResult(
    val success: Boolean,
    val message: String,
    val data: Any? = null,
    val executionTime: Long,
    val screenshot: ByteArray? = null,
    val elementInfo: Map<String, Any>? = null,
    val exception: Throwable? = null
)

/**
 * 操作执行上下文
 */
data class OperationContext(
    val taskId: String,
    val operationId: String,
    val variables: MutableMap<String, Any> = mutableMapOf(),
    val screenshots: MutableList<ByteArray> = mutableListOf(),
    val logs: MutableList<String> = mutableListOf()
)

/**
 * 操作执行引擎接口
 */
interface OperationEngine {
    
    /**
     * 执行单个操作
     */
    suspend fun executeOperation(
        type: OperationType,
        params: OperationParams,
        context: OperationContext
    ): OperationResult
    
    /**
     * 批量执行操作
     */
    suspend fun executeOperations(
        operations: List<Pair<OperationType, OperationParams>>,
        context: OperationContext
    ): List<OperationResult>
    
    /**
     * 执行任务
     */
    suspend fun executeTask(task: Task): TaskResult
    
    /**
     * 检查元素是否存在
     */
    suspend fun checkElementExists(selector: String, timeout: Long = 5000L): Boolean
    
    /**
     * 等待元素出现
     */
    suspend fun waitForElement(selector: String, timeout: Long = 10000L): Boolean
    
    /**
     * 获取元素信息
     */
    suspend fun getElementInfo(selector: String): Map<String, Any>?
    
    /**
     * 截取屏幕截图
     */
    suspend fun takeScreenshot(): ByteArray?
    
    /**
     * 获取当前页面信息
     */
    suspend fun getCurrentPageInfo(): Map<String, Any>
    
    /**
     * 监听操作执行进度
     */
    fun observeExecutionProgress(): Flow<ExecutionProgress>
    
    /**
     * 注册操作处理器
     */
    fun registerOperationHandler(type: OperationType, handler: OperationHandler)
    
    /**
     * 注销操作处理器
     */
    fun unregisterOperationHandler(type: OperationType)
    
    /**
     * 获取支持的操作类型
     */
    fun getSupportedOperations(): List<OperationType>
    
    /**
     * 初始化引擎
     */
    suspend fun initialize(): Boolean
    
    /**
     * 销毁引擎
     */
    suspend fun destroy()
    
    /**
     * 检查引擎状态
     */
    fun isInitialized(): Boolean
}

/**
 * 操作处理器接口
 */
interface OperationHandler {
    /**
     * 处理操作
     */
    suspend fun handle(params: OperationParams, context: OperationContext): OperationResult
    
    /**
     * 验证参数
     */
    fun validateParams(params: OperationParams): Boolean
    
    /**
     * 获取操作描述
     */
    fun getDescription(): String
}

/**
 * 执行进度
 */
data class ExecutionProgress(
    val taskId: String,
    val operationIndex: Int,
    val totalOperations: Int,
    val currentOperation: String,
    val progress: Double, // 0.0 - 1.0
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 执行状态
 */
enum class ExecutionStatus {
    IDLE,           // 空闲
    INITIALIZING,   // 初始化中
    EXECUTING,      // 执行中
    WAITING,        // 等待中
    COMPLETED,      // 已完成
    FAILED,         // 失败
    CANCELLED       // 已取消
}