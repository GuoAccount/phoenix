# 凤凰任务调度系统架构设计文档

## 系统概览

**凤凰(Phoenix)** - 不死鸟任务调度系统，寓意系统具备极强的生命力和自愈能力，无论遇到何种异常都能自动恢复并继续执行。

### 设计哲学
- **不死性**: 系统永不崩溃，具备完全的自愈能力
- **简洁性**: 少即是多，最简设计实现最强功能
- **韧性**: 面对任何异常都能优雅恢复
- **进化性**: 系统能自主学习和适应环境变化

## 核心架构设计

### 三元核心架构

```
┌─────────────────────────────────────────┐
│      Phoenix Core (凤凰核心)              │
│  ┌─────────────────────────────────────┐ │
│  │    TaskScheduler (任务调度器)        │ │
│  │         - 智能调度算法              │ │
│  │         - 优先级管理               │ │
│  │         - 资源分配                │ │
│  └─────────────────────────────────────┘ │
│  ┌─────────────────────────────────────┐ │
│  │   OperationEngine (执行引擎)        │ │
│  │         - 插件化执行               │ │
│  │         - 操作抽象                │ │
│  │         - 结果标准化              │ │
│  └─────────────────────────────────────┘ │
│  ┌─────────────────────────────────────┐ │
│  │  ResilienceCore (韧性核心)          │ │
│  │         - 异常检测                │ │
│  │         - 自动恢复                │ │
│  │         - 生命维持                │ │
│  └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

## 1. TaskScheduler - 智能调度器

### 1.1 核心职责
- **智能调度**: 基于优先级、依赖关系、资源状态的智能调度
- **生命周期管理**: 任务从创建到销毁的全生命周期控制
- **资源协调**: 系统资源的分配、监控和回收
- **状态维护**: 任务状态的实时跟踪和更新

### 1.2 调度算法

```kotlin
class TaskScheduler {
    private val priorityQueue = PriorityQueue<Task>(TaskComparator())
    private val runningTasks = ConcurrentHashMap<String, Task>()
    private val suspendedTasks = ConcurrentHashMap<String, SuspendedTask>()
    
    fun schedule() {
        while (hasActiveTasks()) {
            val task = selectNextTask()
            when (task.type) {
                TaskType.IMMEDIATE -> executeImmediate(task)
                TaskType.ASYNC -> scheduleAsync(task)
                TaskType.DEPENDENT -> checkDependenciesAndExecute(task)
            }
        }
    }
    
    private fun selectNextTask(): Task {
        return priorityQueue.poll() ?: waitForAsyncCompletion()
    }
}
```

### 1.3 优先级策略

```kotlin
enum class Priority(val value: Int) {
    CRITICAL(1000),    // 关键任务，立即执行
    HIGH(800),         // 高优先级
    NORMAL(500),       // 普通优先级
    LOW(200),          // 低优先级
    BACKGROUND(100)    // 后台任务
}

class TaskComparator : Comparator<Task> {
    override fun compare(t1: Task, t2: Task): Int {
        return when {
            t1.priority != t2.priority -> t2.priority.value - t1.priority.value
            t1.deadline != t2.deadline -> t1.deadline.compareTo(t2.deadline)
            else -> t1.createTime.compareTo(t2.createTime)
        }
    }
}
```

## 2. OperationEngine - 执行引擎

### 2.1 操作抽象层

```kotlin
interface Operation {
    val id: String
    val type: OperationType
    val description: String
    
    fun execute(context: ExecutionContext): OperationResult
    fun validate(params: Map<String, Any>): ValidationResult
    fun canExecute(context: ExecutionContext): Boolean
    fun getRequiredResources(): Set<Resource>
}

abstract class BaseOperation : Operation {
    override fun execute(context: ExecutionContext): OperationResult {
        return try {
            preExecute(context)
            val result = doExecute(context)
            postExecute(context, result)
            result
        } catch (e: Exception) {
            handleExecutionError(e, context)
        }
    }
    
    protected abstract fun doExecute(context: ExecutionContext): OperationResult
    protected open fun preExecute(context: ExecutionContext) {}
    protected open fun postExecute(context: ExecutionContext, result: OperationResult) {}
    protected open fun handleExecutionError(e: Exception, context: ExecutionContext): OperationResult {
        return OperationResult.failure(e.message ?: "Unknown error")
    }
}
```

### 2.2 插件化设计

```kotlin
interface OperationPlugin {
    val supportedTypes: Set<OperationType>
    fun createOperation(type: OperationType, config: Map<String, Any>): Operation
    fun initialize(context: PluginContext)
    fun shutdown()
}

class PluginManager {
    private val plugins = mutableMapOf<OperationType, OperationPlugin>()
    
    fun registerPlugin(plugin: OperationPlugin) {
        plugin.supportedTypes.forEach { type ->
            plugins[type] = plugin
        }
    }
    
    fun createOperation(type: OperationType, config: Map<String, Any>): Operation {
        return plugins[type]?.createOperation(type, config)
            ?: throw UnsupportedOperationException("Operation type $type not supported")
    }
}
```

### 2.3 执行上下文

```kotlin
data class ExecutionContext(
    val taskId: String,
    val sessionId: String,
    val environment: Environment,
    val resources: ResourceManager,
    val config: Configuration,
    val logger: Logger
) {
    private val variables = mutableMapOf<String, Any>()
    
    fun setVariable(key: String, value: Any) {
        variables[key] = value
    }
    
    fun getVariable(key: String): Any? = variables[key]
    
    fun <T> getVariableAs(key: String, type: Class<T>): T? {
        return variables[key]?.let { 
            if (type.isInstance(it)) type.cast(it) else null 
        }
    }
}
```

## 3. ResilienceCore - 韧性核心

### 3.1 异常分类系统

```kotlin
sealed class PhoenixException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    // 应用级异常
    class AppCrashedException(cause: Throwable) : PhoenixException("Application crashed", cause)
    class AppNotResponseException : PhoenixException("Application not responding")
    class UnexpectedPageException(expected: String, actual: String) : 
        PhoenixException("Expected page: $expected, actual: $actual")
    
    // 系统级异常
    class SystemResourceException(resource: String) : PhoenixException("System resource unavailable: $resource")
    class PermissionDeniedException(permission: String) : PhoenixException("Permission denied: $permission")
    class DeviceStateException(state: String) : PhoenixException("Invalid device state: $state")
    
    // 网络级异常
    class NetworkTimeoutException : PhoenixException("Network operation timeout")
    class ConnectionLostException : PhoenixException("Network connection lost")
    
    // 任务级异常
    class TaskTimeoutException(taskId: String) : PhoenixException("Task timeout: $taskId")
    class TaskDependencyException(taskId: String, dependency: String) : 
        PhoenixException("Task dependency failed: $taskId depends on $dependency")
    
    // 用户界面异常
    class ElementNotFoundException(selector: String) : PhoenixException("Element not found: $selector")
    class UnexpectedDialogException(dialogType: String) : PhoenixException("Unexpected dialog: $dialogType")
}
```

### 3.2 自愈策略

```kotlin
interface RecoveryStrategy {
    val priority: Int
    fun canHandle(exception: PhoenixException): Boolean
    fun recover(exception: PhoenixException, context: RecoveryContext): RecoveryResult
}

class ResilienceCore {
    private val strategies = listOf<RecoveryStrategy>(
        // 按优先级排序
        CriticalRecoveryStrategy(),
        ApplicationRecoveryStrategy(), 
        SystemRecoveryStrategy(),
        TaskRecoveryStrategy(),
        FallbackRecoveryStrategy()
    ).sortedBy { it.priority }
    
    fun handleException(exception: PhoenixException, context: RecoveryContext): RecoveryResult {
        for (strategy in strategies) {
            if (strategy.canHandle(exception)) {
                val result = strategy.recover(exception, context)
                if (result.isSuccess) {
                    logRecovery(exception, strategy, result)
                    return result
                }
            }
        }
        return RecoveryResult.failed("No suitable recovery strategy found")
    }
}
```

### 3.3 生命维持系统

```kotlin
class LifeSupportSystem {
    private val healthCheckers = listOf(
        SystemHealthChecker(),
        ApplicationHealthChecker(),
        TaskHealthChecker(),
        ResourceHealthChecker()
    )
    
    fun startLifeSupport() {
        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                performHealthCheck()
                delay(HEALTH_CHECK_INTERVAL)
            }
        }
    }
    
    private suspend fun performHealthCheck() {
        val healthStatus = healthCheckers.map { checker ->
            checker.checkHealth()
        }.reduce { acc, status -> acc.combine(status) }
        
        if (healthStatus.isCritical) {
            triggerEmergencyRecovery()
        } else if (healthStatus.isUnhealthy) {
            triggerPreventiveRecovery()
        }
    }
    
    private suspend fun triggerEmergencyRecovery() {
        // 紧急恢复：重启应用、清理资源、重置状态
        emergencyRecoveryProtocol.execute()
    }
}
```

## 4. 配置系统

### 4.1 极简配置结构

```json
{
  "phoenix": {
    "version": "1.0",
    "mode": "production"
  },
  "target": {
    "app": "com.example.app",
    "version": ">=1.0.0"
  },
  "resilience": {
    "maxRetries": 3,
    "recoveryTimeout": 30000,
    "autoRestart": true,
    "emergencyMode": true
  },
  "tasks": [
    {
      "id": "task_001",
      "name": "点击登录",
      "type": "click",
      "priority": "HIGH",
      "timeout": 10000,
      "target": {
        "selector": "text:登录",
        "fallback": "id:login_btn"
      },
      "recovery": {
        "onFailure": "retry",
        "maxAttempts": 3
      }
    }
  ]
}
```

### 4.2 配置验证器

```kotlin
class ConfigurationValidator {
    fun validate(config: PhoenixConfiguration): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        
        // 基础验证
        if (config.target.app.isBlank()) {
            errors.add(ValidationError("target.app", "Application package name is required"))
        }
        
        // 任务验证
        config.tasks.forEach { task ->
            validateTask(task, errors)
        }
        
        // 依赖关系验证
        validateDependencies(config.tasks, errors)
        
        return if (errors.isEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure(errors)
        }
    }
    
    private fun validateTask(task: TaskConfiguration, errors: MutableList<ValidationError>) {
        if (task.id.isBlank()) {
            errors.add(ValidationError("task.id", "Task ID is required"))
        }
        
        if (task.timeout <= 0) {
            errors.add(ValidationError("task.timeout", "Task timeout must be positive"))
        }
        
        // 验证操作特定参数
        val operation = OperationRegistry.getOperation(task.type)
        operation?.validate(task.params)?.let { result ->
            if (!result.isValid) {
                errors.addAll(result.errors.map { ValidationError("task.${task.id}.${it.field}", it.message) })
            }
        }
    }
}
```

## 5. 监控与调试系统

### 5.1 实时监控

```kotlin
class PhoenixMonitor {
    private val metrics = MetricsRegistry()
    
    fun startMonitoring() {
        // 系统指标监控
        scheduleAtFixedRate(5.seconds) {
            collectSystemMetrics()
        }
        
        // 任务执行监控
        scheduleAtFixedRate(1.seconds) {
            monitorTaskExecution()
        }
        
        // 异常监控
        scheduleAtFixedRate(1.seconds) {
            monitorExceptions()
        }
    }
    
    private fun collectSystemMetrics() {
        metrics.gauge("phoenix.memory.used").set(getUsedMemory())
        metrics.gauge("phoenix.cpu.usage").set(getCpuUsage())
        metrics.gauge("phoenix.tasks.active").set(getActiveTaskCount())
        metrics.gauge("phoenix.recovery.rate").set(getRecoverySuccessRate())
    }
}
```

### 5.2 智能日志系统

```kotlin
class IntelligentLogger {
    private val contextualLoggers = mapOf(
        LogContext.TASK_EXECUTION to TaskExecutionLogger(),
        LogContext.RECOVERY to RecoveryLogger(),
        LogContext.SYSTEM to SystemLogger(),
        LogContext.USER_INTERACTION to InteractionLogger()
    )
    
    fun log(level: LogLevel, context: LogContext, message: String, data: Map<String, Any> = emptyMap()) {
        val logger = contextualLoggers[context] ?: defaultLogger
        
        val enrichedData = data.toMutableMap().apply {
            put("timestamp", System.currentTimeMillis())
            put("thread", Thread.currentThread().name)
            put("context", context.name)
        }
        
        logger.log(level, message, enrichedData)
    }
}
```

## 6. 插件系统

### 6.1 插件接口

```kotlin
interface PhoenixPlugin {
    val name: String
    val version: String
    val dependencies: List<String>
    
    fun initialize(context: PluginContext)
    fun shutdown()
    fun getProvidedOperations(): List<OperationType>
    fun getProvidedRecoveryStrategies(): List<RecoveryStrategy>
}

abstract class BasePhoenixPlugin : PhoenixPlugin {
    protected lateinit var context: PluginContext
    
    final override fun initialize(context: PluginContext) {
        this.context = context
        onInitialize()
    }
    
    protected abstract fun onInitialize()
}
```

### 6.2 插件管理器

```kotlin
class PluginManager {
    private val loadedPlugins = mutableMapOf<String, PhoenixPlugin>()
    private val pluginClassLoader = PluginClassLoader()
    
    fun loadPlugin(pluginPath: String): Boolean {
        return try {
            val plugin = pluginClassLoader.loadPlugin(pluginPath)
            validatePlugin(plugin)
            initializePlugin(plugin)
            loadedPlugins[plugin.name] = plugin
            true
        } catch (e: Exception) {
            logger.error("Failed to load plugin: $pluginPath", e)
            false
        }
    }
    
    fun unloadPlugin(name: String): Boolean {
        return loadedPlugins[name]?.let { plugin ->
            try {
                plugin.shutdown()
                loadedPlugins.remove(name)
                true
            } catch (e: Exception) {
                logger.error("Failed to unload plugin: $name", e)
                false
            }
        } ?: false
    }
}
```

## 7. 性能优化

### 7.1 资源池化

```kotlin
class ResourcePoolManager {
    private val operationPool = ObjectPool<Operation>(
        maxSize = 100,
        factory = { type -> OperationRegistry.createOperation(type) },
        reset = { operation -> operation.reset() }
    )
    
    private val contextPool = ObjectPool<ExecutionContext>(
        maxSize = 50,
        factory = { ExecutionContext.create() },
        reset = { context -> context.clear() }
    )
    
    fun borrowOperation(type: OperationType): Operation = operationPool.borrow(type)
    fun returnOperation(operation: Operation) = operationPool.return(operation)
}
```

### 7.2 智能缓存

```kotlin
class IntelligentCache {
    private val operationResults = LRUCache<String, OperationResult>(maxSize = 1000)
    private val elementCache = LRUCache<String, UIElement>(maxSize = 500)
    
    fun cacheOperationResult(operationId: String, result: OperationResult) {
        if (result.isCacheable) {
            operationResults.put(operationId, result)
        }
    }
    
    fun getCachedResult(operationId: String): OperationResult? {
        return operationResults.get(operationId)?.takeIf { !it.isExpired() }
    }
}
```

## 8. 数据模型

### 8.1 核心数据结构

```kotlin
data class Task(
    val id: String,
    val name: String,
    val type: OperationType,
    val priority: Priority,
    val params: Map<String, Any>,
    val timeout: Long,
    val retryPolicy: RetryPolicy,
    val dependencies: List<String> = emptyList(),
    val conditions: List<Condition> = emptyList()
) {
    var status: TaskStatus = TaskStatus.PENDING
    var startTime: Long = 0
    var endTime: Long = 0
    var attemptCount: Int = 0
    var lastError: PhoenixException? = null
}

enum class TaskStatus {
    PENDING,
    READY,
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class OperationResult(
    val success: Boolean,
    val data: Map<String, Any> = emptyMap(),
    val error: String? = null,
    val executionTime: Long = 0,
    val isCacheable: Boolean = false
) {
    companion object {
        fun success(data: Map<String, Any> = emptyMap()) = OperationResult(true, data)
        fun failure(error: String) = OperationResult(false, error = error)
    }
}
```

### 8.2 配置数据模型

```kotlin
data class PhoenixConfiguration(
    val phoenix: PhoenixInfo,
    val target: TargetApp,
    val resilience: ResilienceConfig,
    val tasks: List<TaskConfiguration>,
    val plugins: List<PluginConfiguration> = emptyList()
)

data class TaskConfiguration(
    val id: String,
    val name: String,
    val type: String,
    val priority: String = "NORMAL",
    val timeout: Long = 30000,
    val params: Map<String, Any> = emptyMap(),
    val recovery: RecoveryConfiguration = RecoveryConfiguration(),
    val dependencies: List<String> = emptyList(),
    val conditions: List<Map<String, Any>> = emptyList()
)
```

## 9. 安全与权限

### 9.1 权限管理

```kotlin
class PermissionManager {
    private val requiredPermissions = setOf(
        Manifest.permission.ACCESSIBILITY_SERVICE,
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    
    fun checkPermissions(): PermissionStatus {
        val missingPermissions = requiredPermissions.filter { permission ->
            !hasPermission(permission)
        }
        
        return when {
            missingPermissions.isEmpty() -> PermissionStatus.GRANTED
            missingPermissions.any { it in criticalPermissions } -> PermissionStatus.CRITICAL_MISSING
            else -> PermissionStatus.PARTIALLY_GRANTED
        }
    }
    
    suspend fun requestPermissions(): Boolean {
        val status = checkPermissions()
        return when (status) {
            PermissionStatus.GRANTED -> true
            else -> requestMissingPermissions()
        }
    }
}
```

### 9.2 安全策略

```kotlin
class SecurityPolicy {
    fun validateOperation(operation: Operation, context: ExecutionContext): Boolean {
        return when {
            operation.requiresSystemAccess() -> hasSystemPermission(context)
            operation.accessesSensitiveData() -> hasDataPermission(context)
            operation.modifiesSystemState() -> hasModificationPermission(context)
            else -> true
        }
    }
    
    fun sanitizeParams(params: Map<String, Any>): Map<String, Any> {
        return params.filterKeys { key ->
            !key.startsWith("_") && key !in blacklistedKeys
        }.mapValues { (_, value) ->
            sanitizeValue(value)
        }
    }
}
```

## 10. 部署架构

### 10.1 模块结构

```
phoenix/
├── .gitignore
├── .gradle/              # Gradle构建缓存
├── .idea/                # Android Studio配置
├── ARCHITECTURE.md       # 架构设计文档
├── README.md            # 项目说明文档
├── app/                 # 主应用模块
│   ├── .gitignore
│   ├── build.gradle.kts # 应用构建配置
│   ├── proguard-rules.pro # 代码混淆规则
│   └── src/             # 源代码目录
│       ├── androidTest/ # Android测试
│       ├── main/        # 主要源代码
│       │   ├── java/    # Java/Kotlin源码
│       │   │   └── com/phoenix/
│       │   │       ├── core/        # 核心模块
│       │   │       │   ├── scheduler/   # 任务调度器
│       │   │       │   ├── engine/     # 执行引擎
│       │   │       │   └── resilience/ # 韧性核心
│       │   │       ├── plugins/     # 插件模块
│       │   │       │   ├── standard/   # 标准操作插件
│       │   │       │   ├── advanced/   # 高级操作插件
│       │   │       │   └── custom/     # 自定义插件
│       │   │       ├── config/      # 配置模块
│       │   │       │   ├── schemas/    # 配置架构
│       │   │       │   └── validators/ # 验证器
│       │   │       └── runtime/     # 运行时模块
│       │   │           ├── monitoring/ # 监控组件
│       │   │           ├── logging/    # 日志组件
│       │   │           └── security/   # 安全组件
│       │   ├── res/     # 资源文件
│       │   └── AndroidManifest.xml # 应用清单
│       └── test/        # 单元测试
├── build.gradle.kts     # 项目构建配置
├── gradle.properties    # Gradle属性配置
├── gradle/              # Gradle Wrapper
│   ├── libs.versions.toml # 依赖版本管理
│   └── wrapper/
├── gradlew              # Gradle Wrapper脚本(Unix)
├── gradlew.bat          # Gradle Wrapper脚本(Windows)
├── local.properties     # 本地配置(SDK路径等)
└── settings.gradle.kts  # 项目设置
```

### 10.2 启动流程

```kotlin
class PhoenixBootstrap {
    suspend fun start(configPath: String) {
        try {
            // 1. 初始化核心组件
            initializeCoreComponents()
            
            // 2. 加载配置
            val config = loadAndValidateConfiguration(configPath)
            
            // 3. 加载插件
            loadPlugins(config.plugins)
            
            // 4. 启动监控
            startMonitoring()
            
            // 5. 启动生命维持系统
            startLifeSupport()
            
            // 6. 启动任务调度器
            taskScheduler.start(config.tasks)
            
            logger.info("Phoenix system started successfully")
            
        } catch (e: Exception) {
            logger.error("Failed to start Phoenix system", e)
            shutdown()
            throw e
        }
    }
    
    suspend fun shutdown() {
        try {
            taskScheduler.stop()
            pluginManager.unloadAllPlugins()
            resilienceCore.shutdown()
            monitor.stop()
            logger.info("Phoenix system shutdown completed")
        } catch (e: Exception) {
            logger.error("Error during shutdown", e)
        }
    }
}
```

## 总结

凤凰任务调度系统通过三元核心架构设计，实现了：

1. **不死性**: 通过ResilienceCore的多层自愈机制，系统能从任何异常中恢复
2. **简洁性**: 三个核心组件替代复杂的多组件设计，降低系统复杂度
3. **韧性**: 异常分类处理、智能恢复策略、生命维持系统确保系统稳定
4. **进化性**: 插件化设计支持功能扩展，智能缓存和学习机制提升性能

系统设计遵循最佳实践，具备生产级的稳定性、可维护性和扩展性，真正实现了"不死鸟"的设计理念。