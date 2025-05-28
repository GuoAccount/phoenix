package com.g2s.phoenix

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.g2s.phoenix.core.PhoenixCore
import com.g2s.phoenix.ui.compose.SimpleTaskManagerScreen
import com.g2s.phoenix.ui.theme.PhoenixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phoenix 主活动
 * 提供任务管理的统一入口界面
 */
class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            PhoenixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoenixApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理资源
        try {
            // 获取已初始化的PhoenixCore实例进行清理
            runBlocking {
                PhoenixCore.getInstance().shutdown()
            }
        } catch (e: Exception) {
            // 忽略清理错误
            e.printStackTrace()
        }
    }
}

@Composable
fun PhoenixApp(modifier: Modifier = Modifier) {
    // 检查 PhoenixCore 初始化状态
    val isInitialized = remember { mutableStateOf(false) }
    val errorMessage = remember { mutableStateOf<String?>(null) }
    
    // 检查 PhoenixCore 初始化状态
    LaunchedEffect(Unit) {
        try {
            // 获取 PhoenixCore 实例（不执行初始化）
            PhoenixCore.getInstance(File("")) // 检查是否已初始化
            isInitialized.value = true
        } catch (e: Exception) {
            errorMessage.value = "PhoenixCore 未正确初始化: ${e.message}"
            Log.e("PhoenixApp", "PhoenixCore 初始化错误", e)
        }
    }
    
    // 显示加载状态或错误信息
    if (errorMessage.value != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Center
        ) {
            Text(
                text = errorMessage.value!!,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    
    if (!isInitialized.value) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Center
        ) {
            CircularProgressIndicator()
        }
        return
    }
    
    // 主界面
    Scaffold(
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        SimpleTaskManagerScreen(
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PhoenixAppPreview() {
    PhoenixTheme {
        PhoenixApp()
    }
}