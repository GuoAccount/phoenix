package com.g2s.phoenix.config

import com.g2s.phoenix.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配置来源类型
 */
enum class ConfigSource {
    FILE,           // 文件配置
    NETWORK,        // 网络配置
    DATABASE,       // 数据库配置
    MEMORY,         // 内存配置
    DEFAULT         // 默认配置
}

/**
 * 配置变更事件
 */
data class ConfigChangeEvent(
    val key: String,
    val oldValue: Any?,
    val newValue: Any?,
    val source: ConfigSource,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 配置验证结果
 */
data class ConfigValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * 配置管理器接口
 */
interface ConfigManager {
    
    /**
     * 加载配置
     */
    suspend fun loadConfig(source: ConfigSource = ConfigSource.FILE): PhoenixConfig?
    
    /**
     * 保存配置
     */
    suspend fun saveConfig(config: PhoenixConfig, source: ConfigSource = ConfigSource.FILE): Boolean
    
    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): PhoenixConfig?
    
    /**
     * 更新配置
     */
    suspend fun updateConfig(updater: (PhoenixConfig) -> PhoenixConfig): Boolean
    
    /**
     * 验证配置
     */
    fun validateConfig(config: PhoenixConfig): ConfigValidationResult
    
    /**
     * 监听配置变更
     */
    fun observeConfigChanges(): Flow<ConfigChangeEvent>
    
    /**
     * 重置为默认配置
     */
    suspend fun resetToDefault(targetApp: String): Boolean
    
    /**
     * 获取配置值
     */
    fun <T> getConfigValue(key: String, defaultValue: T): T
    
    /**
     * 设置配置值
     */
    suspend fun setConfigValue(key: String, value: Any): Boolean
    
    /**
     * 获取所有配置键
     */
    fun getConfigKeys(): List<String>
    
    /**
     * 导出配置
     */
    suspend fun exportConfig(format: String = "json"): String?
    
    /**
     * 导入配置
     */
    suspend fun importConfig(data: String, format: String = "json"): Boolean
}

/**
 * 凤凰配置管理器实现
 */
class PhoenixConfigManager(
    private val configDir: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ConfigManager {

    companion object {
        private const val CONFIG_FILE_NAME = "phoenix_config.json"
        private const val BACKUP_SUFFIX = "_backup"
    }

    // 当前配置
    private var currentConfig: PhoenixConfig? = null
    
    // 配置锁
    private val configMutex = Mutex()
    
    // 配置变更流
    private val _configChanges = MutableSharedFlow<ConfigChangeEvent>()
    
    // 是否已初始化
    private val isInitialized = AtomicBoolean(false)

    init {
        // 确保配置目录存在
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
    }

    override suspend fun loadConfig(source: ConfigSource): PhoenixConfig? {
        return configMutex.withLock {
            try {
                when (source) {
                    ConfigSource.FILE -> loadFromFile()
                    ConfigSource.MEMORY -> currentConfig
                    ConfigSource.DEFAULT -> null // 需要提供默认应用名
                    else -> null // 其他来源暂未实现
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun saveConfig(config: PhoenixConfig, source: ConfigSource): Boolean {
        return configMutex.withLock {
            try {
                // 验证配置
                val validation = validateConfig(config)
                if (!validation.isValid) {
                    return@withLock false
                }

                when (source) {
                    ConfigSource.FILE -> {
                        // 备份当前配置
                        backupCurrentConfig()
                        
                        // 保存新配置
                        val success = saveToFile(config)
                        if (success) {
                            val oldConfig = currentConfig
                            currentConfig = config
                            
                            // 发送配置变更事件
                            _configChanges.tryEmit(
                                ConfigChangeEvent(
                                    key = "full_config",
                                    oldValue = oldConfig,
                                    newValue = config,
                                    source = source
                                )
                            )
                        }
                        success
                    }
                    ConfigSource.MEMORY -> {
                        val oldConfig = currentConfig
                        currentConfig = config
                        
                        _configChanges.tryEmit(
                            ConfigChangeEvent(
                                key = "full_config",
                                oldValue = oldConfig,
                                newValue = config,
                                source = source
                            )
                        )
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun getCurrentConfig(): PhoenixConfig? {
        return currentConfig
    }

    override suspend fun updateConfig(updater: (PhoenixConfig) -> PhoenixConfig): Boolean {
        return configMutex.withLock {
            val current = currentConfig ?: return@withLock false
            val updated = updater(current)
            saveConfig(updated, ConfigSource.MEMORY)
        }
    }

    override fun validateConfig(config: PhoenixConfig): ConfigValidationResult {
        val errors = config.validate()
        val warnings = mutableListOf<String>()

        // 添加警告检查
        if (config.performance.maxConcurrentTasks > 20) {
            warnings.add("并发任务数过高，可能影响性能")
        }
        
        if (config.resilience.maxRetries > 10) {
            warnings.add("重试次数过多，可能延长执行时间")
        }
        
        if (config.mode == PhoenixMode.DEBUG && config.isProductionMode()) {
            warnings.add("调试模式与生产模式配置不一致")
        }

        return ConfigValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    override fun observeConfigChanges(): Flow<ConfigChangeEvent> {
        return _configChanges.asSharedFlow()
    }

    override suspend fun resetToDefault(targetApp: String): Boolean {
        val defaultConfig = PhoenixConfig.createDefault(targetApp)
        return saveConfig(defaultConfig, ConfigSource.MEMORY)
    }

    override fun <T> getConfigValue(key: String, defaultValue: T): T {
        val config = currentConfig ?: return defaultValue
        
        return try {
            when (key) {
                "version" -> config.version as? T ?: defaultValue
                "mode" -> config.mode as? T ?: defaultValue
                "target.app" -> config.target.app as? T ?: defaultValue
                "target.version" -> config.target.version as? T ?: defaultValue
                "resilience.maxRetries" -> config.resilience.maxRetries as? T ?: defaultValue
                "resilience.recoveryTimeout" -> config.resilience.recoveryTimeout as? T ?: defaultValue
                "resilience.autoRestart" -> config.resilience.autoRestart as? T ?: defaultValue
                "resilience.emergencyMode" -> config.resilience.emergencyMode as? T ?: defaultValue
                "monitor.enabled" -> config.monitor.enabled as? T ?: defaultValue
                "monitor.logLevel" -> config.monitor.logLevel as? T ?: defaultValue
                "performance.maxConcurrentTasks" -> config.performance.maxConcurrentTasks as? T ?: defaultValue
                "performance.threadPoolSize" -> config.performance.threadPoolSize as? T ?: defaultValue
                else -> {
                    // 检查自定义设置
                    config.customSettings[key] as? T ?: defaultValue
                }
            }
        } catch (e: Exception) {
            defaultValue
        }
    }

    override suspend fun setConfigValue(key: String, value: Any): Boolean {
        return updateConfig { config ->
            when (key) {
                "version" -> config.copy(version = value as String)
                "mode" -> config.copy(mode = value as PhoenixMode)
                "target.app" -> config.copy(target = config.target.copy(app = value as String))
                "target.version" -> config.copy(target = config.target.copy(version = value as String))
                "resilience.maxRetries" -> config.copy(
                    resilience = config.resilience.copy(maxRetries = value as Int)
                )
                "resilience.recoveryTimeout" -> config.copy(
                    resilience = config.resilience.copy(recoveryTimeout = value as Long)
                )
                "resilience.autoRestart" -> config.copy(
                    resilience = config.resilience.copy(autoRestart = value as Boolean)
                )
                "resilience.emergencyMode" -> config.copy(
                    resilience = config.resilience.copy(emergencyMode = value as Boolean)
                )
                "monitor.enabled" -> config.copy(
                    monitor = config.monitor.copy(enabled = value as Boolean)
                )
                "monitor.logLevel" -> config.copy(
                    monitor = config.monitor.copy(logLevel = value as String)
                )
                "performance.maxConcurrentTasks" -> config.copy(
                    performance = config.performance.copy(maxConcurrentTasks = value as Int)
                )
                "performance.threadPoolSize" -> config.copy(
                    performance = config.performance.copy(threadPoolSize = value as Int)
                )
                else -> {
                    // 添加到自定义设置
                    val customSettings = config.customSettings.toMutableMap()
                    customSettings[key] = value
                    config.copy(customSettings = customSettings)
                }
            }
        }
    }

    override fun getConfigKeys(): List<String> {
        val config = currentConfig ?: return emptyList()
        
        val keys = mutableListOf(
            "version", "mode", "target.app", "target.version",
            "resilience.maxRetries", "resilience.recoveryTimeout", 
            "resilience.autoRestart", "resilience.emergencyMode",
            "monitor.enabled", "monitor.logLevel",
            "performance.maxConcurrentTasks", "performance.threadPoolSize"
        )
        
        keys.addAll(config.customSettings.keys)
        return keys
    }

    override suspend fun exportConfig(format: String): String? {
        val config = currentConfig ?: return null
        
        return try {
            when (format.lowercase()) {
                "json" -> configToJson(config).toString(2)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun importConfig(data: String, format: String): Boolean {
        return try {
            val config = when (format.lowercase()) {
                "json" -> jsonToConfig(JSONObject(data))
                else -> return false
            }
            
            saveConfig(config, ConfigSource.MEMORY)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从文件加载配置
     */
    private fun loadFromFile(): PhoenixConfig? {
        val configFile = File(configDir, CONFIG_FILE_NAME)
        if (!configFile.exists()) {
            return null
        }

        return try {
            val jsonString = configFile.readText()
            val jsonObject = JSONObject(jsonString)
            jsonToConfig(jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 保存配置到文件
     */
    private fun saveToFile(config: PhoenixConfig): Boolean {
        val configFile = File(configDir, CONFIG_FILE_NAME)
        
        return try {
            val jsonObject = configToJson(config)
            configFile.writeText(jsonObject.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 备份当前配置
     */
    private fun backupCurrentConfig() {
        try {
            val configFile = File(configDir, CONFIG_FILE_NAME)
            if (configFile.exists()) {
                val backupFile = File(configDir, CONFIG_FILE_NAME + BACKUP_SUFFIX)
                configFile.copyTo(backupFile, overwrite = true)
            }
        } catch (e: Exception) {
            // 备份失败不影响主流程
        }
    }

    /**
     * 配置转JSON
     */
    private fun configToJson(config: PhoenixConfig): JSONObject {
        return JSONObject().apply {
            put("version", config.version)
            put("mode", config.mode.name)
            
            put("target", JSONObject().apply {
                put("app", config.target.app)
                put("version", config.target.version)
            })
            
            put("resilience", JSONObject().apply {
                put("maxRetries", config.resilience.maxRetries)
                put("recoveryTimeout", config.resilience.recoveryTimeout)
                put("autoRestart", config.resilience.autoRestart)
                put("emergencyMode", config.resilience.emergencyMode)
                put("circuitBreakerThreshold", config.resilience.circuitBreakerThreshold)
                put("circuitBreakerTimeout", config.resilience.circuitBreakerTimeout)
                put("healthCheckInterval", config.resilience.healthCheckInterval)
                put("resourceMonitorEnabled", config.resilience.resourceMonitorEnabled)
            })
            
            put("monitor", JSONObject().apply {
                put("enabled", config.monitor.enabled)
                put("metricsInterval", config.monitor.metricsInterval)
                put("logLevel", config.monitor.logLevel)
                put("performanceTracking", config.monitor.performanceTracking)
                put("memoryThreshold", config.monitor.memoryThreshold)
                put("cpuThreshold", config.monitor.cpuThreshold)
                put("maxLogFiles", config.monitor.maxLogFiles)
                put("logFileSize", config.monitor.logFileSize)
            })
            
            put("plugin", JSONObject().apply {
                put("enabled", config.plugin.enabled)
                put("autoLoad", config.plugin.autoLoad)
                put("pluginDirectory", config.plugin.pluginDirectory)
                put("allowedPlugins", JSONArray(config.plugin.allowedPlugins))
                put("securityEnabled", config.plugin.securityEnabled)
            })
            
            put("performance", JSONObject().apply {
                put("maxConcurrentTasks", config.performance.maxConcurrentTasks)
                put("taskQueueSize", config.performance.taskQueueSize)
                put("threadPoolSize", config.performance.threadPoolSize)
                put("cacheEnabled", config.performance.cacheEnabled)
                put("cacheSize", config.performance.cacheSize)
                put("cacheTtl", config.performance.cacheTtl)
                put("resourcePoolEnabled", config.performance.resourcePoolEnabled)
                put("connectionTimeout", config.performance.connectionTimeout)
                put("readTimeout", config.performance.readTimeout)
            })
            
            put("security", JSONObject().apply {
                put("permissionCheck", config.security.permissionCheck)
                put("encryptionEnabled", config.security.encryptionEnabled)
                put("auditLog", config.security.auditLog)
                put("accessControl", config.security.accessControl)
                put("tokenExpiration", config.security.tokenExpiration)
                put("maxLoginAttempts", config.security.maxLoginAttempts)
                put("sessionTimeout", config.security.sessionTimeout)
            })
            
            put("tasks", JSONArray().apply {
                config.tasks.forEach { task ->
                    put(JSONObject().apply {
                        put("id", task.id)
                        put("name", task.name)
                        put("type", task.type.name)
                        put("priority", task.priority.name)
                        put("timeout", task.timeout)
                        put("enabled", task.enabled)
                        put("schedule", task.schedule)
                        
                        put("target", JSONObject().apply {
                            put("selector", task.target.selector)
                            put("fallback", task.target.fallback)
                            put("timeout", task.target.timeout)
                        })
                        
                        put("recovery", JSONObject().apply {
                            put("onFailure", task.recovery.onFailure)
                            put("maxAttempts", task.recovery.maxAttempts)
                            put("retryDelay", task.recovery.retryDelay)
                            put("backoffMultiplier", task.recovery.backoffMultiplier)
                        })
                        
                        put("parameters", JSONObject(task.parameters))
                    })
                }
            })
            
            put("customSettings", JSONObject(config.customSettings))
        }
    }

    /**
     * JSON转配置
     */
    private fun jsonToConfig(json: JSONObject): PhoenixConfig {
        val targetJson = json.getJSONObject("target")
        val target = TargetApp(
            app = targetJson.getString("app"),
            version = targetJson.optString("version", null)
        )
        
        val resilienceJson = json.getJSONObject("resilience")
        val resilience = ResilienceConfig(
            maxRetries = resilienceJson.getInt("maxRetries"),
            recoveryTimeout = resilienceJson.getLong("recoveryTimeout"),
            autoRestart = resilienceJson.getBoolean("autoRestart"),
            emergencyMode = resilienceJson.getBoolean("emergencyMode"),
            circuitBreakerThreshold = resilienceJson.optInt("circuitBreakerThreshold", 5),
            circuitBreakerTimeout = resilienceJson.optLong("circuitBreakerTimeout", 30000L),
            healthCheckInterval = resilienceJson.optLong("healthCheckInterval", 10000L),
            resourceMonitorEnabled = resilienceJson.optBoolean("resourceMonitorEnabled", true)
        )
        
        // 其他配置项的解析...
        val monitorJson = json.getJSONObject("monitor")
        val monitor = MonitorConfig(
            enabled = monitorJson.getBoolean("enabled"),
            metricsInterval = monitorJson.getLong("metricsInterval"),
            logLevel = monitorJson.getString("logLevel"),
            performanceTracking = monitorJson.getBoolean("performanceTracking"),
            memoryThreshold = monitorJson.getDouble("memoryThreshold"),
            cpuThreshold = monitorJson.getDouble("cpuThreshold"),
            maxLogFiles = monitorJson.getInt("maxLogFiles"),
            logFileSize = monitorJson.getLong("logFileSize")
        )
        
        val tasks = mutableListOf<TaskConfigTemplate>()
        val tasksJson = json.getJSONArray("tasks")
        for (i in 0 until tasksJson.length()) {
            val taskJson = tasksJson.getJSONObject(i)
            val taskTargetJson = taskJson.getJSONObject("target")
            val taskRecoveryJson = taskJson.getJSONObject("recovery")
            
            tasks.add(
                TaskConfigTemplate(
                    id = taskJson.getString("id"),
                    name = taskJson.getString("name"),
                    type = TaskType.valueOf(taskJson.getString("type")),
                    priority = TaskPriority.valueOf(taskJson.getString("priority")),
                    timeout = taskJson.getLong("timeout"),
                    enabled = taskJson.getBoolean("enabled"),
                    schedule = taskJson.optString("schedule", null),
                    target = TaskTarget(
                        selector = taskTargetJson.getString("selector"),
                        fallback = taskTargetJson.optString("fallback", null),
                        timeout = taskTargetJson.getLong("timeout")
                    ),
                    recovery = TaskRecovery(
                        onFailure = taskRecoveryJson.getString("onFailure"),
                        maxAttempts = taskRecoveryJson.getInt("maxAttempts"),
                        retryDelay = taskRecoveryJson.getLong("retryDelay"),
                        backoffMultiplier = taskRecoveryJson.getDouble("backoffMultiplier")
                    ),
                    parameters = taskJson.getJSONObject("parameters").let { paramsJson ->
                        paramsJson.keys().asSequence().associateWith { key ->
                            paramsJson.get(key)
                        }
                    }
                )
            )
        }
        
        return PhoenixConfig(
            version = json.getString("version"),
            mode = PhoenixMode.valueOf(json.getString("mode")),
            target = target,
            resilience = resilience,
            monitor = monitor,
            tasks = tasks,
            customSettings = json.getJSONObject("customSettings").let { customJson ->
                customJson.keys().asSequence().associateWith { key ->
                    customJson.get(key)
                }
            }
        )
    }
}