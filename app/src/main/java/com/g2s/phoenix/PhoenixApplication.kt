package com.g2s.phoenix

import android.app.Application
import android.util.Log
import com.g2s.phoenix.core.PhoenixCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** PhoenixApplication 是应用程序的自定义 Application 类。 它负责初始化 PhoenixCore 并提供全局上下文。 */
class PhoenixApplication : Application() {
    companion object {
        @Volatile private var _instance: PhoenixApplication? = null

        val instance: PhoenixApplication
            get() = _instance ?: throw IllegalStateException("PhoenixApplication not initialized")
    }

    private val appScope = CoroutineScope(Dispatchers.Main)
    private val TAG = "PhoenixApplication"

    override fun onCreate() {
        super.onCreate()
        _instance = this

        Log.d(TAG, "Initializing PhoenixApplication...")

        // 初始化 PhoenixCore
        val configDir = getExternalFilesDir(null) ?: filesDir
        Log.d(TAG, "Using config directory: ${configDir.absolutePath}")

        try {
            val phoenixCore = PhoenixCore.getInstance(configDir, this)

            // 在协程中异步初始化 PhoenixCore
            appScope.launch {
                try {
                    Log.d(TAG, "Starting PhoenixCore initialization...")
                    phoenixCore.initialize(packageName)
                    phoenixCore.start()
                    Log.d(TAG, "PhoenixCore initialized and started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize PhoenixCore", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting PhoenixCore instance", e)
        }
    }
}
