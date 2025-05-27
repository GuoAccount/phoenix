# 凤凰任务调度系统 (Phoenix Task Scheduler)

## 概述

凤凰任务调度系统是一个基于 Android 平台的智能任务调度框架，采用三元核心架构设计，具备强大的韧性和自愈能力。

### 核心特性

- **智能调度器 (TaskScheduler)**: 基于优先级的任务调度，支持动态优先级调整
- **执行引擎 (OperationEngine)**: 插件化的操作执行引擎，支持多种操作类型
- **韧性核心 (ResilienceCore)**: 异常处理、自愈机制和熔断器模式
- **实时监控**: 全面的性能监控和日志系统
- **配置管理**: 灵活的配置系统，支持热更新
- **插件系统**: 可扩展的插件架构

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Phoenix Core                             │
├─────────────────┬─────────────────┬─────────────────────────┤
│  TaskScheduler  │ OperationEngine │   ResilienceCore        │
│  智能调度器      │    执行引擎      │     韧性核心            │
├─────────────────┼─────────────────┼─────────────────────────┤
│   ConfigManager │  MonitorSystem  │   PluginManager         │
│    配置管理      │    监控系统      │     插件管理            │
└─────────────────┴─────────────────┴─────────────────────────┘
```

## 快速开始

### 1. 初始化系统

```kotlin
val configDir = File(context.filesDir, "phoenix_config")
val phoenixCore = PhoenixCore.getInstance(configDir)

// 初始化系统
val success = phoenixCore.initialize("com.example.targetapp")
if (success) {
    println("凤凰系统初始化成功")
}
```

### 2. 启动系统

```kotlin
// 启动系统
val started = phoenixCore.start()
if (started) {
    println("凤凰系统启动成功")
}
```

### 3. 创建和提交任务

```kotlin
val task = Task(
    id = "sample_task",
    name = "示例任务",
    type = TaskType.APP_OPERATION,
    priority = TaskPriority.NORMAL,
    target = TaskTarget(
        selector = "com.example.app:id/button",
        fallback = "com.example.app:id/button_alt"
    ),
    recovery = TaskRecovery(
        onFailure = "retry",
        maxAttempts = 3
    )
)

// 提交任务
val submitted = phoenixCore.submitTask(task)
```

### 4. 监听系统事件

```kotlin
phoenixCore.observeSystemEvents().collect { event ->
    when (event) {
        is SystemEvent.TaskUpdate -> {
            println("任务更新: ${event.update}")
        }
        is SystemEvent.Alert -> {
            println("系统警报: ${event.alert.message}")
        }
        is SystemEvent.HealthStatus -> {
            println("健康状态: ${event.health.status}")
        }
    }
}
```

## 配置系统

### 基础配置

```kotlin
val config = PhoenixConfig(
    version = "1.0.0",
    mode = PhoenixMode.PRODUCTION,
    target = TargetApp(app = "com.example.app"),
    resilience = ResilienceConfig(
        maxRetries = 3,
        recoveryTimeout = 60000L,
        autoRestart = true
    ),
    performance = PerformanceConfig(
        maxConcurrentTasks = 10,
        threadPoolSize = 5
    )
)
```

### 任务配置模板

```kotlin
val taskTemplate = TaskConfigTemplate(
    id = "auto_click_task",
    name = "自动点击任务",
    type = TaskType.APP_OPERATION,
    priority = TaskPriority.NORMAL,
    target = TaskTarget(selector = "id/click_button"),
    recovery = TaskRecovery(onFailure = "retry", maxAttempts = 3),
    schedule = "0 */5 * * * ?" // 每5分钟执行一次
)
```

## 任务类型

### APP_OPERATION - 应用操作
- 点击按钮
- 输入文本
- 滑动操作
- 导航操作

### SYSTEM_COMMAND - 系统命令
- Shell 命令执行
- 系统设置修改
- 服务管理

### FILE_OPERATION - 文件操作
- 文件读写
- 目录管理
- 文件传输

### NETWORK_REQUEST - 网络请求
- HTTP 请求
- API 调用
- 数据同步

### CUSTOM_PLUGIN - 自定义插件
- 用户自定义操作
- 第三方集成

## 监控和日志

### 获取系统统计

```kotlin
val stats = phoenixCore.getSystemStatistics()
println("系统运行时间: ${stats.uptime}")
println("任务完成率: ${stats.schedulerStats.completedTasks}")
println("内存使用率: ${stats.performanceStats.memoryUsage}")
```

### 健康检查

```kotlin
val healthResult = phoenixCore.performHealthCheck()
println("健康状态: ${healthResult.status}")
println("健康消息: ${healthResult.message}")
```

### 日志查看

```kotlin
// 监听实时日志
monitorSystem.observeLogs().collect { logEntry ->
    println("[${logEntry.level}] ${logEntry.message}")
}

// 获取历史日志
val logs = monitorSystem.getLogs(
    level = LogLevel.ERROR,
    startTime = System.currentTimeMillis() - 86400000L, // 24小时前
    limit = 100
)
```

## 韧性和容错

### 异常处理

系统自动分类和处理以下异常类型：
- 网络错误 (NETWORK_ERROR)
- 超时错误 (TIMEOUT_ERROR)
- 元素未找到 (ELEMENT_NOT_FOUND)
- 权限拒绝 (PERMISSION_DENIED)
- 资源耗尽 (RESOURCE_EXHAUSTED)

### 恢复策略

- **RETRY**: 重试执行
- **FALLBACK**: 使用备用方案
- **RESTART**: 重启服务
- **SKIP**: 跳过当前任务
- **ABORT**: 中止执行
- **CIRCUIT_BREAKER**: 熔断保护

### 紧急模式

```kotlin
// 进入紧急模式
phoenixCore.enterEmergencyMode()

// 检查是否在紧急模式
val isEmergency = resilienceCore.isInEmergencyMode()

// 退出紧急模式
phoenixCore.exitEmergencyMode()
```

## 插件开发

### 创建自定义插件

```kotlin
class CustomPlugin : Plugin {
    override fun getInfo(): PluginInfo {
        return PluginInfo(
            id = "custom_plugin",
            name = "自定义插件",
            version = "1.0.0",
            description = "这是一个自定义插件",
            author = "开发者"
        )
    }

    override suspend fun initialize(context: PluginContext): Boolean {
        // 初始化插件
        return true
    }

    override suspend fun start(): Boolean {
        // 启动插件
        return true
    }

    override suspend fun stop(): Boolean {
        // 停止插件
        return true
    }

    override suspend fun destroy() {
        // 清理资源
    }

    override fun getCapabilities(): List<String> {
        return listOf("custom_operation")
    }
}
```

### 加载插件

```kotlin
// 加载单个插件
val pluginFile = File("path/to/plugin.jar")
val loaded = pluginManager.loadPlugin(pluginFile)

// 启动插件
val started = pluginManager.startPlugin("custom_plugin")
```

## 配置文件示例

```json
{
  "version": "1.0.0",
  "mode": "PRODUCTION",
  "target": {
    "app": "com.example.targetapp",
    "version": "2.0.0"
  },
  "resilience": {
    "maxRetries": 3,
    "recoveryTimeout": 60000,
    "autoRestart": true,
    "emergencyMode": false
  },
  "monitor": {
    "enabled": true,
    "logLevel": "INFO",
    "metricsInterval": 5000
  },
  "performance": {
    "maxConcurrentTasks": 10,
    "threadPoolSize": 5,
    "cacheEnabled": true
  },
  "tasks": [
    {
      "id": "health_check",
      "name": "健康检查任务",
      "type": "SYSTEM_COMMAND",
      "priority": "LOW",
      "target": {
        "selector": "system.health"
      },
      "schedule": "0 */5 * * * ?"
    }
  ]
}
```

## API 参考

### PhoenixCore 主要方法

- `initialize(targetApp: String): Boolean` - 初始化系统
- `start(): Boolean` - 启动系统
- `stop(): Boolean` - 停止系统
- `submitTask(task: Task): Boolean` - 提交任务
- `getSystemStatus(): PhoenixStatus` - 获取系统状态
- `getSystemStatistics(): SystemStatistics` - 获取统计信息
- `observeSystemEvents(): Flow<SystemEvent>` - 监听系统事件

### TaskScheduler 主要方法

- `submitTask(task: Task): Boolean` - 提交任务
- `cancelTask(taskId: String): Boolean` - 取消任务
- `getNextTask(): Task?` - 获取下一个任务
- `getStatistics(): SchedulerStatistics` - 获取调度统计

### ResilienceCore 主要方法

- `handleException(exception: ExceptionInfo): RecoveryResult` - 处理异常
- `performHealthCheck(): HealthCheckResult` - 执行健康检查
- `enterEmergencyMode()` - 进入紧急模式
- `performSelfHealing(): Boolean` - 执行自愈

## 最佳实践

### 任务设计
1. 保持任务粒度适中，避免过于复杂的单个任务
2. 合理设置任务优先级和超时时间
3. 充分利用任务依赖关系
4. 为关键任务设置回退策略

### 配置管理
1. 根据环境使用不同的配置模式
2. 定期备份配置文件
3. 使用配置验证确保配置正确性
4. 避免在生产环境使用调试模式

### 监控运维
1. 定期检查系统健康状态
2. 设置合适的警报阈值
3. 及时清理历史日志和数据
4. 监控资源使用情况

### 异常处理
1. 为不同类型的异常设置合适的恢复策略
2. 避免无限重试，设置合理的重试次数
3. 关键服务使用熔断器模式
4. 定期测试异常恢复机制

## 故障排除

### 常见问题

1. **系统初始化失败**
   - 检查配置文件是否正确
   - 确认目标应用包名是否正确
   - 查看初始化日志获取详细错误信息

2. **任务执行失败**
   - 检查任务选择器是否正确
   - 确认目标元素是否存在
   - 查看任务执行日志

3. **内存使用过高**
   - 调整任务队列大小
   - 清理历史数据
   - 优化任务执行逻辑

4. **性能问题**
   - 减少并发任务数
   - 优化任务调度间隔
   - 检查是否有阻塞操作

### 调试模式

```kotlin
// 启用调试模式
val debugConfig = config.copy(mode = PhoenixMode.DEBUG)
configManager.saveConfig(debugConfig)
```

调试模式特性：
- 详细的执行日志
- 异常堆栈跟踪
- 性能分析数据
- 调试API访问

## 版本历史

### v1.0.0
- 初始版本发布
- 实现三元核心架构
- 基础任务调度功能
- 监控和日志系统
- 配置管理系统

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进这个项目。

## 许可证

本项目采用 MIT 许可证。