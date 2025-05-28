package com.g2s.phoenix.config

import com.g2s.phoenix.model.*
import com.g2s.phoenix.core.scheduler.TaskGroup
import com.g2s.phoenix.core.scheduler.TaskCondition
import com.g2s.phoenix.core.scheduler.TaskRetryPolicy
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

/**
 * JSON任务配置加载器
 * 支持旧架构的JSON配置格式，实现任务和操作的完全解耦
 */
class JsonTaskLoader {
    
    /**
     * 从JSON文件加载任务配置
     */
    fun loadTaskConfig(file: File): TaskConfig? {
        return try {
            val content = file.readText(StandardCharsets.UTF_8)
            val json = JSONObject(content)
            parseTaskConfig(json)
        } catch (e: Exception) {
            println("加载任务配置失败: ${file.absolutePath}, 错误: ${e.message}")
            null
        }
    }
    
    /**
     * 从JSON字符串加载任务配置
     */
    fun loadTaskConfigFromString(jsonString: String): TaskConfig? {
        return try {
            val json = JSONObject(jsonString)
            parseTaskConfig(json)
        } catch (e: Exception) {
            println("解析JSON任务配置失败: ${e.message}")
            null
        }
    }
    
    /**
     * 批量加载目录下的所有JSON任务配置
     */
    fun loadTaskConfigsFromDirectory(directory: File): List<TaskConfig> {
        val configs = mutableListOf<TaskConfig>()
        
        if (!directory.exists() || !directory.isDirectory) {
            println("任务配置目录不存在: ${directory.absolutePath}")
            return configs
        }
        
        directory.listFiles { _, name -> name.endsWith(".json") }?.forEach { file ->
            loadTaskConfig(file)?.let { config ->
                configs.add(config)
                println("成功加载任务配置: ${file.name}")
            }
        }
        
        return configs
    }
    
    /**
     * 解析JSON任务配置
     */
    private fun parseTaskConfig(json: JSONObject): TaskConfig {
        // 解析应用信息
        val appJson = json.getJSONObject("app")
        val appInfo = AppInfo(
            pkg = appJson.getString("pkg"),
            name = appJson.optString("name", ""),
            mainActivity = appJson.optString("main_activity", "")
        )
        
        // 解析全局配置
        val configJson = json.getJSONObject("config")
        val globalConfig = parseGlobalConfig(configJson)
        
        // 解析任务组
        val taskGroupsJson = json.getJSONArray("task_groups")
        val taskGroups = parseTaskGroups(taskGroupsJson, globalConfig)
        
        return TaskConfig(
            app = appInfo,
            config = globalConfig,
            taskGroups = taskGroups
        )
    }
    
    /**
     * 解析全局配置
     */
    private fun parseGlobalConfig(configJson: JSONObject): GlobalConfig {
        val globals = configJson.optJSONObject("globals")?.let { globalsJson ->
            GlobalParams(
                timeout = globalsJson.optLong("timeout", 10000L),
                swipeDuration = globalsJson.optLong("swipe_duration", 500L),
                retryCount = globalsJson.optInt("retry_count", 3),
                waitAfter = globalsJson.optLong("wait_after", 1000L)
            )
        } ?: GlobalParams()
        
        val taskGroupDefaults = configJson.optJSONObject("task_group_defaults")?.let { defaultsJson ->
            TaskGroupDefaults(
                enabled = defaultsJson.optBoolean("enabled", true),
                level = defaultsJson.optInt("level", 1),
                waitAfter = defaultsJson.optLong("wait_after", 1000L),
                timeout = defaultsJson.optLong("timeout", 3600000L)
            )
        } ?: TaskGroupDefaults()
        
        val taskDefaults = configJson.optJSONObject("task_defaults")?.let { defaultsJson ->
            TaskDefaults(
                enabled = defaultsJson.optBoolean("enabled", true),
                waitAfter = defaultsJson.optLong("wait_after", 1000L),
                timeout = defaultsJson.optLong("timeout", 30000L),
                retry = defaultsJson.optInt("retry", 3),
                repeat = defaultsJson.optInt("repeat", 1)
            )
        } ?: TaskDefaults()
        
        return GlobalConfig(
            globals = globals,
            taskGroupDefaults = taskGroupDefaults,
            taskDefaults = taskDefaults
        )
    }
    
    /**
     * 解析任务组列表
     */
    private fun parseTaskGroups(taskGroupsJson: JSONArray, globalConfig: GlobalConfig): List<TaskGroup> {
        val taskGroups = mutableListOf<TaskGroup>()
        
        for (i in 0 until taskGroupsJson.length()) {
            val groupJson = taskGroupsJson.getJSONObject(i)
            val taskGroup = parseTaskGroup(groupJson, globalConfig)
            taskGroups.add(taskGroup)
        }
        
        return taskGroups
    }
    
    /**
     * 解析单个任务组
     */
    private fun parseTaskGroup(groupJson: JSONObject, globalConfig: GlobalConfig): TaskGroup {
        val id = groupJson.optString("id", "group_${System.currentTimeMillis()}")
        val name = groupJson.getString("name")
        val description = groupJson.optString("desc", "")
        val level = groupJson.optInt("level", globalConfig.taskGroupDefaults.level)
        val enabled = groupJson.optBoolean("enabled", globalConfig.taskGroupDefaults.enabled)
        val waitAfter = groupJson.optLong("wait_after", globalConfig.taskGroupDefaults.waitAfter)
        val timeout = groupJson.optLong("timeout", globalConfig.taskGroupDefaults.timeout)
        
        // 解析依赖关系
        val dependencies = groupJson.optJSONArray("dependencies")?.let { depsArray ->
            (0 until depsArray.length()).map { depsArray.getString(it) }
        } ?: emptyList()
        
        // 解析执行条件
        val conditions = groupJson.optJSONArray("conditions")?.let { conditionsArray ->
            parseConditions(conditionsArray)
        } ?: emptyList()
        
        // 解析重试策略
        val retryPolicy = groupJson.optJSONObject("retry_policy")?.let { retryJson ->
            TaskRetryPolicy(
                maxRetries = retryJson.optInt("max_retries", 3),
                retryDelay = retryJson.optLong("retry_delay", 1000L),
                backoffMultiplier = retryJson.optDouble("backoff_multiplier", 2.0),
                retryOnFailure = retryJson.optBoolean("retry_on_failure", true)
            )
        } ?: TaskRetryPolicy()
        
        // 解析任务列表
        val tasksJson = groupJson.getJSONArray("tasks")
        val tasks = parseTasks(tasksJson, globalConfig)
        
        return TaskGroup(
            id = id,
            name = name,
            description = description,
            level = level,
            enabled = enabled,
            tasks = tasks.toMutableList(),
            dependencies = dependencies,
            conditions = conditions,
            waitAfter = waitAfter,
            timeout = timeout,
            retryPolicy = retryPolicy
        )
    }
    
    /**
     * 解析任务列表
     */
    private fun parseTasks(tasksJson: JSONArray, globalConfig: GlobalConfig): List<Task> {
        val tasks = mutableListOf<Task>()
        
        for (i in 0 until tasksJson.length()) {
            val taskJson = tasksJson.getJSONObject(i)
            val task = parseTask(taskJson, globalConfig)
            tasks.add(task)
        }
        
        return tasks
    }
    
    /**
     * 解析单个任务
     */
    private fun parseTask(taskJson: JSONObject, globalConfig: GlobalConfig): Task {
        val id = taskJson.optString("id", "task_${System.currentTimeMillis()}")
        val name = taskJson.getString("name")
        val description = taskJson.optString("desc", "")
        val type = parseTaskType(taskJson.getString("type"))
        val priority = parseTaskPriority(taskJson.optString("priority", "NORMAL"))
        val timeout = taskJson.optLong("timeout", globalConfig.taskDefaults.timeout)
        val enabled = taskJson.optBoolean("enabled", globalConfig.taskDefaults.enabled)
        
        // 解析任务参数
        val params = taskJson.optJSONObject("params")?.let { paramsJson ->
            parseTaskParams(paramsJson)
        } ?: emptyMap()
        
        // 解析目标配置
        val target = parseTaskTarget(taskJson, params)
        
        // 解析恢复配置
        val recovery = taskJson.optJSONObject("recovery")?.let { recoveryJson ->
            TaskRecovery(
                onFailure = recoveryJson.optString("on_failure", "retry"),
                maxAttempts = recoveryJson.optInt("max_attempts", globalConfig.taskDefaults.retry),
                retryDelay = recoveryJson.optLong("retry_delay", 1000L),
                backoffMultiplier = recoveryJson.optDouble("backoff_multiplier", 2.0)
            )
        } ?: TaskRecovery("retry", globalConfig.taskDefaults.retry)
        
        // 解析依赖关系
        val dependencies = taskJson.optJSONArray("dependencies")?.let { depsArray ->
            (0 until depsArray.length()).map { depsArray.getString(it) }
        } ?: emptyList()
        
        return Task(
            id = id,
            name = name,
            type = type.name, // 将 TaskType 枚举转换为 String
            priority = priority,
            timeout = timeout,
            target = target,
            recovery = recovery,
            dependencies = dependencies,
            parameters = params
        )
    }
    
    /**
     * 解析任务类型
     */
    private fun parseTaskType(typeString: String): TaskType { // 保持返回 TaskType，在构造 Task 时转换
        return when (typeString.lowercase()) {
            "click", "click_text", "click_element" -> TaskType.APP_OPERATION
            "input", "input_text" -> TaskType.APP_OPERATION
            "swipe", "scroll" -> TaskType.APP_OPERATION
            "wait", "wait_element", "wait_text" -> TaskType.APP_OPERATION
            "navigate", "back", "home" -> TaskType.APP_OPERATION
            "system" -> TaskType.SYSTEM_COMMAND
            "file" -> TaskType.FILE_OPERATION
            "network", "http" -> TaskType.NETWORK_REQUEST
            "custom" -> TaskType.CUSTOM_PLUGIN
            else -> TaskType.APP_OPERATION
        }
    }
    
    /**
     * 解析任务优先级
     */
    private fun parseTaskPriority(priorityString: String): TaskPriority {
        return when (priorityString.uppercase()) {
            "LOW" -> TaskPriority.LOW
            "NORMAL" -> TaskPriority.NORMAL
            "HIGH" -> TaskPriority.HIGH
            "CRITICAL" -> TaskPriority.CRITICAL
            "EMERGENCY" -> TaskPriority.EMERGENCY
            else -> TaskPriority.NORMAL
        }
    }
    
    /**
     * 解析任务参数
     */
    private fun parseTaskParams(paramsJson: JSONObject): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        paramsJson.keys().forEach { key ->
            val value = paramsJson.get(key)
            params[key] = value
        }
        
        return params
    }
    
    /**
     * 解析任务目标配置
     */
    private fun parseTaskTarget(taskJson: JSONObject, params: Map<String, Any>): TaskTarget {
        // 优先从target字段解析
        val targetJson = taskJson.optJSONObject("target")
        if (targetJson != null) {
            return TaskTarget(
                selector = targetJson.getString("selector"),
                fallback = targetJson.optString("fallback"),
                timeout = targetJson.optLong("timeout", 30000L)
            )
        }
        
        // 从params中解析（兼容旧格式）
        val selector = params["selector"] as? String 
            ?: params["text"] as? String 
            ?: params["element"] as? String 
            ?: "unknown"
            
        val fallback = params["fallback"] as? String
        val timeout = (params["timeout"] as? Number)?.toLong() ?: 30000L
        
        return TaskTarget(
            selector = selector,
            fallback = fallback,
            timeout = timeout
        )
    }
    
    /**
     * 解析执行条件
     */
    private fun parseConditions(conditionsArray: JSONArray): List<TaskCondition> {
        val conditions = mutableListOf<TaskCondition>()
        
        for (i in 0 until conditionsArray.length()) {
            val conditionJson = conditionsArray.getJSONObject(i)
            val condition = TaskCondition(
                type = conditionJson.getString("type"),
                selector = conditionJson.getString("selector"),
                expectedValue = conditionJson.optString("expected_value"),
                timeout = conditionJson.optLong("timeout", 5000L)
            )
            conditions.add(condition)
        }
        
        return conditions
    }
}

/**
 * 任务配置数据模型
 */
data class TaskConfig(
    val app: AppInfo,
    val config: GlobalConfig,
    val taskGroups: List<TaskGroup>
)

/**
 * 应用信息
 */
data class AppInfo(
    val pkg: String,
    val name: String,
    val mainActivity: String
)

/**
 * 全局配置
 */
data class GlobalConfig(
    val globals: GlobalParams,
    val taskGroupDefaults: TaskGroupDefaults,
    val taskDefaults: TaskDefaults
)

/**
 * 全局参数
 */
data class GlobalParams(
    val timeout: Long = 10000L,
    val swipeDuration: Long = 500L,
    val retryCount: Int = 3,
    val waitAfter: Long = 1000L
)

/**
 * 任务组默认配置
 */
data class TaskGroupDefaults(
    val enabled: Boolean = true,
    val level: Int = 1,
    val waitAfter: Long = 1000L,
    val timeout: Long = 3600000L
)

/**
 * 任务默认配置
 */
data class TaskDefaults(
    val enabled: Boolean = true,
    val waitAfter: Long = 1000L,
    val timeout: Long = 30000L,
    val retry: Int = 3,
    val repeat: Int = 1
)