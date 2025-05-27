package com.g2s.phoenix.plugins

import com.g2s.phoenix.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 插件状态
 */
enum class PluginState {
    UNLOADED,   // 未加载
    LOADING,    // 加载中
    LOADED,     // 已加载
    RUNNING,    // 运行中
    STOPPED,    // 已停止
    ERROR       // 错误
}

/**
 * 插件信息
 */
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val entryPoint: String,
    val state: PluginState = PluginState.UNLOADED
)

/**
 * 插件接口
 */
interface Plugin {
    /**
     * 获取插件信息
     */
    fun getInfo(): PluginInfo
    
    /**
     * 初始化插件
     */
    suspend fun initialize(context: PluginContext): Boolean
    
    /**
     * 启动插件
     */
    suspend fun start(): Boolean
    
    /**
     * 停止插件
     */
    suspend fun stop(): Boolean
    
    /**
     * 销毁插件
     */
    suspend fun destroy()
    
    /**
     * 获取插件能力
     */
    fun getCapabilities(): List<String>
}

/**
 * 插件上下文
 */
data class PluginContext(
    val pluginId: String,
    val config: PhoenixConfig,
    val dataDir: File,
    val logger: PluginLogger,
    val eventBus: PluginEventBus
)

/**
 * 插件日志器
 */
interface PluginLogger {
    fun debug(message: String, data: Map<String, Any> = emptyMap())
    fun info(message: String, data: Map<String, Any> = emptyMap())
    fun warn(message: String, data: Map<String, Any> = emptyMap())
    fun error(message: String, exception: Throwable? = null, data: Map<String, Any> = emptyMap())
}

/**
 * 插件事件总线
 */
interface PluginEventBus {
    suspend fun publish(event: PluginEvent)
    fun subscribe(eventType: String): Flow<PluginEvent>
    fun unsubscribe(eventType: String)
}

/**
 * 插件事件
 */
data class PluginEvent(
    val type: String,
    val source: String,
    val data: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 插件管理器接口
 */
interface PluginManager {
    /**
     * 加载插件
     */
    suspend fun loadPlugin(pluginFile: File): Boolean
    
    /**
     * 加载所有插件
     */
    suspend fun loadPlugins(): Int
    
    /**
     * 卸载插件
     */
    suspend fun unloadPlugin(pluginId: String): Boolean
    
    /**
     * 启动插件
     */
    suspend fun startPlugin(pluginId: String): Boolean
    
    /**
     * 停止插件
     */
    suspend fun stopPlugin(pluginId: String): Boolean
    
    /**
     * 获取插件信息
     */
    fun getPluginInfo(pluginId: String): PluginInfo?
    
    /**
     * 获取所有插件信息
     */
    fun getAllPlugins(): List<PluginInfo>
    
    /**
     * 检查插件是否已加载
     */
    fun isPluginLoaded(pluginId: String): Boolean
    
    /**
     * 检查插件是否正在运行
     */
    fun isPluginRunning(pluginId: String): Boolean
    
    /**
     * 监听插件事件
     */
    fun observePluginEvents(): Flow<PluginEvent>
    
    /**
     * 验证插件
     */
    suspend fun validatePlugin(pluginFile: File): Boolean
    
    /**
     * 重载插件
     */
    suspend fun reloadPlugin(pluginId: String): Boolean
}

/**
 * 凤凰插件管理器实现
 */
class PhoenixPluginManager(
    private val config: PhoenixConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : PluginManager {

    // 插件存储
    private val plugins = ConcurrentHashMap<String, Plugin>()
    private val pluginInfos = ConcurrentHashMap<String, PluginInfo>()
    private val pluginContexts = ConcurrentHashMap<String, PluginContext>()
    
    // 插件目录
    private val pluginDirectory = File(config.plugin.pluginDirectory)
    
    // 状态管理
    private val isInitialized = AtomicBoolean(false)
    private val loadMutex = Mutex()
    
    // 事件流
    private val _pluginEvents = MutableSharedFlow<PluginEvent>()
    private val eventBus = SimplePluginEventBus()

    init {
        // 确保插件目录存在
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs()
        }
    }

    override suspend fun loadPlugin(pluginFile: File): Boolean {
        return loadMutex.withLock {
            try {
                // 验证插件
                if (!validatePlugin(pluginFile)) {
                    return@withLock false
                }
                
                // 创建插件实例 (简化实现)
                val plugin = createPluginInstance(pluginFile) ?: return@withLock false
                val info = plugin.getInfo()
                
                // 检查是否已加载
                if (plugins.containsKey(info.id)) {
                    return@withLock false
                }
                
                // 检查权限
                if (config.plugin.securityEnabled && !checkPluginPermissions(info)) {
                    return@withLock false
                }
                
                // 创建插件上下文
                val context = createPluginContext(info.id)
                
                // 初始化插件
                val success = plugin.initialize(context)
                if (!success) {
                    return@withLock false
                }
                
                // 注册插件
                plugins[info.id] = plugin
                pluginInfos[info.id] = info.copy(state = PluginState.LOADED)
                pluginContexts[info.id] = context
                
                // 发送事件
                eventBus.publish(
                    PluginEvent(
                        type = "plugin.loaded",
                        source = info.id,
                        data = mapOf("pluginInfo" to info)
                    )
                )
                
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun loadPlugins(): Int {
        if (!config.plugin.autoLoad) {
            return 0
        }
        
        val pluginFiles = pluginDirectory.listFiles { file ->
            file.isFile && file.extension.lowercase() in listOf("jar", "zip", "apk")
        } ?: emptyArray()
        
        var loadedCount = 0
        for (file in pluginFiles) {
            if (loadPlugin(file)) {
                loadedCount++
            }
        }
        
        return loadedCount
    }

    override suspend fun unloadPlugin(pluginId: String): Boolean {
        return loadMutex.withLock {
            val plugin = plugins[pluginId] ?: return@withLock false
            val info = pluginInfos[pluginId] ?: return@withLock false
            
            try {
                // 停止插件
                if (info.state == PluginState.RUNNING) {
                    plugin.stop()
                }
                
                // 销毁插件
                plugin.destroy()
                
                // 移除插件
                plugins.remove(pluginId)
                pluginInfos.remove(pluginId)
                pluginContexts.remove(pluginId)
                
                // 发送事件
                eventBus.publish(
                    PluginEvent(
                        type = "plugin.unloaded",
                        source = pluginId,
                        data = mapOf("pluginInfo" to info)
                    )
                )
                
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun startPlugin(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        val info = pluginInfos[pluginId] ?: return false
        
        return try {
            if (info.state == PluginState.LOADED) {
                val success = plugin.start()
                if (success) {
                    pluginInfos[pluginId] = info.copy(state = PluginState.RUNNING)
                    
                    eventBus.publish(
                        PluginEvent(
                            type = "plugin.started",
                            source = pluginId
                        )
                    )
                }
                success
            } else {
                false
            }
        } catch (e: Exception) {
            pluginInfos[pluginId] = info.copy(state = PluginState.ERROR)
            false
        }
    }

    override suspend fun stopPlugin(pluginId: String): Boolean {
        val plugin = plugins[pluginId] ?: return false
        val info = pluginInfos[pluginId] ?: return false
        
        return try {
            if (info.state == PluginState.RUNNING) {
                val success = plugin.stop()
                if (success) {
                    pluginInfos[pluginId] = info.copy(state = PluginState.STOPPED)
                    
                    eventBus.publish(
                        PluginEvent(
                            type = "plugin.stopped",
                            source = pluginId
                        )
                    )
                }
                success
            } else {
                false
            }
        } catch (e: Exception) {
            pluginInfos[pluginId] = info.copy(state = PluginState.ERROR)
            false
        }
    }

    override fun getPluginInfo(pluginId: String): PluginInfo? {
        return pluginInfos[pluginId]
    }

    override fun getAllPlugins(): List<PluginInfo> {
        return pluginInfos.values.toList()
    }

    override fun isPluginLoaded(pluginId: String): Boolean {
        return plugins.containsKey(pluginId)
    }

    override fun isPluginRunning(pluginId: String): Boolean {
        return pluginInfos[pluginId]?.state == PluginState.RUNNING
    }

    override fun observePluginEvents(): Flow<PluginEvent> {
        return _pluginEvents.asSharedFlow()
    }

    override suspend fun validatePlugin(pluginFile: File): Boolean {
        if (!pluginFile.exists() || !pluginFile.canRead()) {
            return false
        }
        
        // 检查文件大小
        if (pluginFile.length() > 100 * 1024 * 1024) { // 100MB限制
            return false
        }
        
        // 检查文件类型
        val validExtensions = listOf("jar", "zip", "apk")
        if (pluginFile.extension.lowercase() !in validExtensions) {
            return false
        }
        
        return true
    }

    override suspend fun reloadPlugin(pluginId: String): Boolean {
        val info = pluginInfos[pluginId] ?: return false
        
        // 停止和卸载插件
        stopPlugin(pluginId)
        unloadPlugin(pluginId)
        
        // 重新加载插件
        val pluginFile = File(pluginDirectory, "${info.id}.jar")
        return if (pluginFile.exists()) {
            loadPlugin(pluginFile)
        } else {
            false
        }
    }

    /**
     * 创建插件实例 (简化实现)
     */
    private fun createPluginInstance(pluginFile: File): Plugin? {
        // 这里应该实现真正的插件加载逻辑，比如使用类加载器加载JAR文件
        // 为了简化，这里返回一个示例插件
        return SamplePlugin(pluginFile.nameWithoutExtension)
    }

    /**
     * 创建插件上下文
     */
    private fun createPluginContext(pluginId: String): PluginContext {
        val dataDir = File(pluginDirectory, "data/$pluginId")
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        
        return PluginContext(
            pluginId = pluginId,
            config = config,
            dataDir = dataDir,
            logger = SimplePluginLogger(pluginId),
            eventBus = eventBus
        )
    }

    /**
     * 检查插件权限
     */
    private fun checkPluginPermissions(info: PluginInfo): Boolean {
        // 如果配置了允许的插件列表，检查是否在列表中
        if (config.plugin.allowedPlugins.isNotEmpty()) {
            return info.id in config.plugin.allowedPlugins
        }
        
        // 检查危险权限
        val dangerousPermissions = listOf("SYSTEM_ADMIN", "FILE_WRITE", "NETWORK_ACCESS")
        return info.permissions.none { it in dangerousPermissions }
    }

    /**
     * 简单插件事件总线实现
     */
    inner class SimplePluginEventBus : PluginEventBus {
        private val subscribers = ConcurrentHashMap<String, MutableSharedFlow<PluginEvent>>()

        override suspend fun publish(event: PluginEvent) {
            _pluginEvents.emit(event)
            subscribers[event.type]?.emit(event)
        }

        override fun subscribe(eventType: String): Flow<PluginEvent> {
            return subscribers.computeIfAbsent(eventType) { 
                MutableSharedFlow<PluginEvent>() 
            }.asSharedFlow()
        }

        override fun unsubscribe(eventType: String) {
            subscribers.remove(eventType)
        }
    }

    /**
     * 简单插件日志器实现
     */
    inner class SimplePluginLogger(private val pluginId: String) : PluginLogger {
        override fun debug(message: String, data: Map<String, Any>) {
            println("DEBUG [$pluginId]: $message $data")
        }

        override fun info(message: String, data: Map<String, Any>) {
            println("INFO [$pluginId]: $message $data")
        }

        override fun warn(message: String, data: Map<String, Any>) {
            println("WARN [$pluginId]: $message $data")
        }

        override fun error(message: String, exception: Throwable?, data: Map<String, Any>) {
            println("ERROR [$pluginId]: $message $data")
            exception?.printStackTrace()
        }
    }

    /**
     * 示例插件实现
     */
    inner class SamplePlugin(private val id: String) : Plugin {
        private val info = PluginInfo(
            id = id,
            name = "示例插件",
            version = "1.0.0",
            description = "这是一个示例插件",
            author = "Phoenix Team",
            entryPoint = "SamplePlugin"
        )

        override fun getInfo(): PluginInfo = info

        override suspend fun initialize(context: PluginContext): Boolean {
            context.logger.info("插件初始化")
            return true
        }

        override suspend fun start(): Boolean {
            return true
        }

        override suspend fun stop(): Boolean {
            return true
        }

        override suspend fun destroy() {
            // 清理资源
        }

        override fun getCapabilities(): List<String> {
            return listOf("sample")
        }
    }
}