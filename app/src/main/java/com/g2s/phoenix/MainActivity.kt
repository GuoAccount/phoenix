package com.g2s.phoenix

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.g2s.phoenix.core.PhoenixCore
import com.g2s.phoenix.core.PhoenixStatus
import com.g2s.phoenix.core.SystemEvent
import com.g2s.phoenix.model.*
import com.g2s.phoenix.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var phoenixCore: PhoenixCore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initializePhoenixSystem()
    }

    private fun setupUI() {
        binding.apply {
            // 初始化按钮
            btnInitialize.setOnClickListener {
                lifecycleScope.launch {
                    initializeSystem()
                }
            }

            // 启动按钮
            btnStart.setOnClickListener {
                lifecycleScope.launch {
                    startSystem()
                }
            }

            // 停止按钮
            btnStop.setOnClickListener {
                lifecycleScope.launch {
                    stopSystem()
                }
            }

            // 提交任务按钮
            btnSubmitTask.setOnClickListener {
                lifecycleScope.launch {
                    submitSampleTask()
                }
            }

            // 健康检查按钮
            btnHealthCheck.setOnClickListener {
                lifecycleScope.launch {
                    performHealthCheck()
                }
            }

            // 清空日志按钮
            btnClearLogs.setOnClickListener {
                tvLogs.text = ""
            }
        }
    }

    private fun initializePhoenixSystem() {
        val configDir = File(filesDir, "phoenix_config")
        phoenixCore = PhoenixCore.getInstance(configDir)
    }

    /**
     * 开始监听系统事件（仅在PhoenixCore初始化完成后调用）
     */
    private fun startObservingSystemEvents() {
        lifecycleScope.launch {
            phoenixCore.observeSystemEvents().collect { event ->
                when (event) {
                    is SystemEvent.TaskUpdate -> {
                        updateUI("任务更新: ${event.update::class.simpleName}")
                    }
                    is SystemEvent.Alert -> {
                        updateUI("警报: ${event.alert.title} - ${event.alert.message}")
                    }
                    is SystemEvent.HealthStatus -> {
                        updateUI("健康状态: ${event.health.status}")
                    }
                    is SystemEvent.StatusChange -> {
                        updateUI("状态变化: ${event.oldStatus} -> ${event.newStatus}")
                    }
                }
            }
        }
    }

    private suspend fun initializeSystem() {
        updateUI("正在初始化凤凰系统...")
        
        val success = phoenixCore.initialize("com.example.targetapp")
        
        if (success) {
            updateUI("✅ 凤凰系统初始化成功")
            // 初始化成功后开始监听系统事件
            startObservingSystemEvents()
            updateSystemStatus()
        } else {
            updateUI("❌ 凤凰系统初始化失败")
        }
    }

    private suspend fun startSystem() {
        updateUI("正在启动凤凰系统...")
        
        val success = phoenixCore.start()
        
        if (success) {
            updateUI("✅ 凤凰系统启动成功")
            updateSystemStatus()
        } else {
            updateUI("❌ 凤凰系统启动失败")
        }
    }

    private suspend fun stopSystem() {
        updateUI("正在停止凤凰系统...")
        
        val success = phoenixCore.stop()
        
        if (success) {
            updateUI("✅ 凤凰系统停止成功")
            updateSystemStatus()
        } else {
            updateUI("❌ 凤凰系统停止失败")
        }
    }

    private suspend fun submitSampleTask() {
        val task = Task(
            id = "sample_task_${System.currentTimeMillis()}",
            name = "示例任务",
            type = TaskType.APP_OPERATION,
            priority = TaskPriority.NORMAL,
            target = TaskTarget(
                selector = "com.example.targetapp:id/button",
                fallback = "com.example.targetapp:id/button_alt"
            ),
            recovery = TaskRecovery(
                onFailure = "retry",
                maxAttempts = 3
            ),
            parameters = mapOf(
                "action" to "click",
                "waitTime" to 1000L
            )
        )

        val success = phoenixCore.submitTask(task)
        
        if (success) {
            updateUI("✅ 任务提交成功: ${task.name}")
        } else {
            updateUI("❌ 任务提交失败: ${task.name}")
        }

        // 监听任务状态
        lifecycleScope.launch {
            var attempts = 0
            while (attempts < 10) {
                val status = phoenixCore.getTaskStatus(task.id)
                updateUI("任务状态: ${task.id} -> $status")
                
                if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED) {
                    break
                }
                
                kotlinx.coroutines.delay(1000)
                attempts++
            }
        }
    }

    private suspend fun performHealthCheck() {
        updateUI("正在执行健康检查...")
        
        val result = phoenixCore.performHealthCheck()
        
        updateUI("健康检查结果:")
        updateUI("- 状态: ${result.status}")
        updateUI("- 消息: ${result.message}")
        updateUI("- 指标: ${result.metrics}")
    }

    private suspend fun updateSystemStatus() {
        val status = phoenixCore.getSystemStatus()
        val config = phoenixCore.getCurrentConfig()
        
        binding.tvStatus.text = "系统状态: ${status.name}"
        
        if (config != null) {
            updateUI("系统配置:")
            updateUI("- 版本: ${config.version}")
            updateUI("- 模式: ${config.mode}")
            updateUI("- 目标应用: ${config.target.app}")
            updateUI("- 最大并发任务: ${config.performance.maxConcurrentTasks}")
            updateUI("- 监控启用: ${config.monitor.enabled}")
        }

        // 获取系统统计信息
        try {
            val stats = phoenixCore.getSystemStatistics()
            updateUI("系统统计:")
            updateUI("- 运行时间: ${formatUptime(stats.uptime)}")
            updateUI("- 调度器状态: 总任务=${stats.schedulerStats.totalTasks}, 完成=${stats.schedulerStats.completedTasks}")
            updateUI("- 内存使用: ${String.format("%.2f", stats.performanceStats.memoryUsage * 100)}%")
            updateUI("- 系统健康: ${stats.systemHealth.status}")
        } catch (e: Exception) {
            updateUI("获取统计信息失败: ${e.message}")
        }
    }

    private fun updateUI(message: String) {
        runOnUiThread {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val logMessage = "[$timestamp] $message\n"
            binding.tvLogs.append(logMessage)
            
            // 自动滚动到底部
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun formatUptime(uptime: Long): String {
        val seconds = uptime / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}天 ${hours % 24}小时"
            hours > 0 -> "${hours}小时 ${minutes % 60}分钟"
            minutes > 0 -> "${minutes}分钟 ${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            phoenixCore.stop()
        }
    }
}