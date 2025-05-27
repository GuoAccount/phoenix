package com.g2s.phoenix.model

/**
 * 凤凰系统运行模式
 */
enum class PhoenixMode {
    DEVELOPMENT,    // 开发模式
    PRODUCTION,     // 生产模式
    DEBUG,          // 调试模式
    SAFE            // 安全模式
}

/**
 * 目标应用配置
 */
data class TargetApp(
    val app: String,            // 目标应用包名或标识
    val version: String? = null // 目标应用版本
)

/**
 * 韧性配置
 */
data class ResilienceConfig(
    val maxRetries: Int = 3,                    // 最大重试次数
    val recoveryTimeout: Long = 60000L,         // 恢复超时时间(毫秒)
    val autoRestart: Boolean = true,            // 自动重启
    val emergencyMode: Boolean = false,         // 紧急模式
    val circuitBreakerThreshold: Int = 5,       // 熔断器阈值
    val circuitBreakerTimeout: Long = 30000L,   // 熔断器超时
    val healthCheckInterval: Long = 10000L,     // 健康检查间隔
    val resourceMonitorEnabled: Boolean = true  // 资源监控启用
)

/**
 * 监控配置
 */
data class MonitorConfig(
    val enabled: Boolean = true,                // 启用监控
    val metricsInterval: Long = 5000L,         // 指标收集间隔
    val logLevel: String = "INFO",             // 日志级别
    val performanceTracking: Boolean = true,   // 性能追踪
    val memoryThreshold: Double = 0.8,         // 内存阈值
    val cpuThreshold: Double = 0.7,            // CPU阈值
    val maxLogFiles: Int = 10,                 // 最大日志文件数
    val logFileSize: Long = 10 * 1024 * 1024L  // 日志文件大小(10MB)
)

/**
 * 插件配置
 */
data class PluginConfig(
    val enabled: Boolean = true,               // 启用插件系统
    val autoLoad: Boolean = true,              // 自动加载插件
    val pluginDirectory: String = "plugins",   // 插件目录
    val allowedPlugins: List<String> = emptyList(), // 允许的插件列表
    val securityEnabled: Boolean = true        // 插件安全检查
)

/**
 * 性能配置
 */
data class PerformanceConfig(
    val maxConcurrentTasks: Int = 10,          // 最大并发任务数
    val taskQueueSize: Int = 1000,             // 任务队列大小
    val threadPoolSize: Int = 5,               // 线程池大小
    val cacheEnabled: Boolean = true,          // 启用缓存
    val cacheSize: Int = 100,                  // 缓存大小
    val cacheTtl: Long = 300000L,              // 缓存TTL(5分钟)
    val resourcePoolEnabled: Boolean = true,   // 启用资源池
    val connectionTimeout: Long = 10000L,      // 连接超时
    val readTimeout: Long = 30000L             // 读取超时
)

/**
 * 安全配置
 */
data class SecurityConfig(
    val permissionCheck: Boolean = true,       // 权限检查
    val encryptionEnabled: Boolean = false,    // 加密启用
    val auditLog: Boolean = true,              // 审计日志
    val accessControl: Boolean = true,         // 访问控制
    val tokenExpiration: Long = 3600000L,      // Token过期时间(1小时)
    val maxLoginAttempts: Int = 3,             // 最大登录尝试次数
    val sessionTimeout: Long = 1800000L        // 会话超时(30分钟)
)

/**
 * 任务配置模板
 */
data class TaskConfigTemplate(
    val id: String,
    val name: String,
    val type: TaskType,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val timeout: Long = 30000L,
    val target: TaskTarget,
    val recovery: TaskRecovery = TaskRecovery("retry"),
    val enabled: Boolean = true,
    val schedule: String? = null,              // Cron表达式
    val parameters: Map<String, Any> = emptyMap()
)

/**
 * 凤凰系统主配置
 */
data class PhoenixConfig(
    val version: String = "1.0.0",            // 系统版本
    val mode: PhoenixMode = PhoenixMode.PRODUCTION, // 运行模式
    val target: TargetApp,                     // 目标应用
    val resilience: ResilienceConfig = ResilienceConfig(), // 韧性配置
    val monitor: MonitorConfig = MonitorConfig(), // 监控配置
    val plugin: PluginConfig = PluginConfig(), // 插件配置
    val performance: PerformanceConfig = PerformanceConfig(), // 性能配置
    val security: SecurityConfig = SecurityConfig(), // 安全配置
    val tasks: List<TaskConfigTemplate> = emptyList(), // 任务模板
    val customSettings: Map<String, Any> = emptyMap() // 自定义设置
) {
    /**
     * 验证配置有效性
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        // 验证基本配置
        if (target.app.isBlank()) {
            errors.add("目标应用不能为空")
        }
        
        // 验证韧性配置
        if (resilience.maxRetries < 0) {
            errors.add("最大重试次数不能为负数")
        }
        if (resilience.recoveryTimeout <= 0) {
            errors.add("恢复超时时间必须大于0")
        }
        
        // 验证性能配置
        if (performance.maxConcurrentTasks <= 0) {
            errors.add("最大并发任务数必须大于0")
        }
        if (performance.threadPoolSize <= 0) {
            errors.add("线程池大小必须大于0")
        }
        
        // 验证任务配置
        val taskIds = tasks.map { it.id }
        if (taskIds.size != taskIds.distinct().size) {
            errors.add("任务ID存在重复")
        }
        
        tasks.forEach { task ->
            if (task.id.isBlank()) {
                errors.add("任务ID不能为空")
            }
            if (task.name.isBlank()) {
                errors.add("任务名称不能为空")
            }
            if (task.timeout <= 0) {
                errors.add("任务超时时间必须大于0")
            }
        }
        
        return errors
    }
    
    /**
     * 获取任务配置
     */
    fun getTaskConfig(taskId: String): TaskConfigTemplate? {
        return tasks.find { it.id == taskId }
    }
    
    /**
     * 是否为调试模式
     */
    fun isDebugMode(): Boolean {
        return mode == PhoenixMode.DEBUG || mode == PhoenixMode.DEVELOPMENT
    }
    
    /**
     * 是否为生产模式
     */
    fun isProductionMode(): Boolean {
        return mode == PhoenixMode.PRODUCTION
    }
    
    /**
     * 是否为安全模式
     */
    fun isSafeMode(): Boolean {
        return mode == PhoenixMode.SAFE
    }
    
    /**
     * 获取有效的任务配置列表
     */
    fun getEnabledTasks(): List<TaskConfigTemplate> {
        return tasks.filter { it.enabled }
    }
    
    /**
     * 创建默认配置
     */
    companion object {
        fun createDefault(targetApp: String): PhoenixConfig {
            return PhoenixConfig(
                target = TargetApp(app = targetApp),
                tasks = listOf(
                    TaskConfigTemplate(
                        id = "default_health_check",
                        name = "默认健康检查",
                        type = TaskType.SYSTEM_COMMAND,
                        priority = TaskPriority.LOW,
                        target = TaskTarget(selector = "system.health"),
                        schedule = "0 */5 * * * ?" // 每5分钟执行一次
                    )
                )
            )
        }
        
        /**
         * 创建开发模式配置
         */
        fun createDevelopment(targetApp: String): PhoenixConfig {
            return createDefault(targetApp).copy(
                mode = PhoenixMode.DEVELOPMENT,
                monitor = MonitorConfig(
                    logLevel = "DEBUG",
                    metricsInterval = 1000L
                ),
                security = SecurityConfig(
                    permissionCheck = false,
                    auditLog = false
                )
            )
        }
        
        /**
         * 创建安全模式配置
         */
        fun createSafeMode(targetApp: String): PhoenixConfig {
            return createDefault(targetApp).copy(
                mode = PhoenixMode.SAFE,
                resilience = ResilienceConfig(
                    maxRetries = 1,
                    emergencyMode = true,
                    autoRestart = false
                ),
                performance = PerformanceConfig(
                    maxConcurrentTasks = 1,
                    threadPoolSize = 1
                )
            )
        }
    }
}