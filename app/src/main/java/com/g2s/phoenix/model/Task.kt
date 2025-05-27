package com.g2s.phoenix.model

import java.util.*

/**
 * 任务状态枚举
 */
enum class TaskStatus {
    PENDING,    // 等待执行
    RUNNING,    // 正在执行
    COMPLETED,  // 执行完成
    FAILED,     // 执行失败
    CANCELLED,  // 已取消
    RETRYING,   // 重试中
    SUSPENDED   // 已暂停
}

/**
 * 任务优先级枚举
 */
enum class TaskPriority(val value: Int) {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    CRITICAL(4),
    EMERGENCY(5)
}

/**
 * 任务类型枚举
 */
enum class TaskType {
    APP_OPERATION,      // 应用操作
    SYSTEM_COMMAND,     // 系统命令
    FILE_OPERATION,     // 文件操作
    NETWORK_REQUEST,    // 网络请求
    CUSTOM_PLUGIN       // 自定义插件
}

/**
 * 任务目标配置
 */
data class TaskTarget(
    val selector: String,           // 目标选择器
    val fallback: String? = null,   // 回退选择器
    val timeout: Long = 30000L      // 超时时间(毫秒)
)

/**
 * 任务恢复配置
 */
data class TaskRecovery(
    val onFailure: String,          // 失败时的处理策略
    val maxAttempts: Int = 3,       // 最大重试次数
    val retryDelay: Long = 1000L,   // 重试延迟(毫秒)
    val backoffMultiplier: Double = 2.0  // 退避倍数
)

/**
 * 任务执行结果
 */
data class TaskResult(
    val success: Boolean,
    val message: String,
    val data: Any? = null,
    val exception: Throwable? = null,
    val executionTime: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 任务执行上下文
 */
data class TaskContext(
    val taskId: String,
    val executionId: String = UUID.randomUUID().toString(),
    val parentTaskId: String? = null,
    val variables: MutableMap<String, Any> = mutableMapOf(),
    val metadata: MutableMap<String, Any> = mutableMapOf()
)

/**
 * 核心任务数据模型
 */
data class Task(
    val id: String,
    val name: String,
    val type: TaskType,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val timeout: Long = 30000L,
    val target: TaskTarget,
    val recovery: TaskRecovery = TaskRecovery("retry"),
    val dependencies: List<String> = emptyList(),
    val parameters: Map<String, Any> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    var status: TaskStatus = TaskStatus.PENDING,
    var lastResult: TaskResult? = null,
    var executionCount: Int = 0,
    var lastExecutionTime: Long? = null,
    var scheduledTime: Long? = null,
    var nextRetryTime: Long? = null
) {
    /**
     * 检查任务是否可以执行
     */
    fun canExecute(): Boolean {
        return status == TaskStatus.PENDING || 
               (status == TaskStatus.RETRYING && 
                (nextRetryTime == null || System.currentTimeMillis() >= nextRetryTime!!))
    }

    /**
     * 检查任务是否已完成
     */
    fun isCompleted(): Boolean {
        return status == TaskStatus.COMPLETED || 
               status == TaskStatus.FAILED || 
               status == TaskStatus.CANCELLED
    }

    /**
     * 检查任务是否需要重试
     */
    fun needsRetry(): Boolean {
        return status == TaskStatus.FAILED && 
               executionCount < recovery.maxAttempts
    }

    /**
     * 计算下次重试时间
     */
    fun calculateNextRetryTime(): Long {
        val baseDelay = recovery.retryDelay
        val multiplier = Math.pow(recovery.backoffMultiplier, (executionCount - 1).toDouble())
        return System.currentTimeMillis() + (baseDelay * multiplier).toLong()
    }

    /**
     * 标记任务开始执行
     */
    fun markStarted(): Task {
        return this.copy(
            status = TaskStatus.RUNNING,
            lastExecutionTime = System.currentTimeMillis(),
            executionCount = executionCount + 1
        )
    }

    /**
     * 标记任务完成
     */
    fun markCompleted(result: TaskResult): Task {
        return this.copy(
            status = TaskStatus.COMPLETED,
            lastResult = result
        )
    }

    /**
     * 标记任务失败
     */
    fun markFailed(result: TaskResult): Task {
        val newStatus = if (needsRetry()) {
            TaskStatus.RETRYING
        } else {
            TaskStatus.FAILED
        }
        
        return this.copy(
            status = newStatus,
            lastResult = result,
            nextRetryTime = if (newStatus == TaskStatus.RETRYING) calculateNextRetryTime() else null
        )
    }

    /**
     * 取消任务
     */
    fun cancel(): Task {
        return this.copy(status = TaskStatus.CANCELLED)
    }

    /**
     * 获取任务的优先级分数
     */
    fun getPriorityScore(): Int {
        var score = priority.value * 100
        
        // 考虑等待时间
        val waitTime = System.currentTimeMillis() - createdAt
        score += (waitTime / 1000).toInt() // 每秒增加1分
        
        // 考虑重试次数
        score += executionCount * 10
        
        return score
    }
}