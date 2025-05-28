package com.g2s.phoenix.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.g2s.phoenix.core.TaskSelectionManager
import com.g2s.phoenix.ui.viewmodel.SimpleTaskManagerViewModel

/**
 * 极简任务管理界面
 * 只包含JSON任务列表、勾选、全选、启动和停止功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTaskManagerScreen(
    modifier: Modifier = Modifier,
    viewModel: SimpleTaskManagerViewModel = viewModel()
) {
    val availableAppTasks by viewModel.availableAppTasks.collectAsState()
    val selectedTasks by viewModel.selectedTasks.collectAsState()
    val executionStatus by viewModel.executionStatus.collectAsState()
    
    // 获取所有任务的平铺列表
    val allTasks = remember(availableAppTasks) {
        availableAppTasks.flatMap { (appName, taskGroups) ->
            taskGroups.map { taskGroup -> appName to taskGroup }
        }
    }
    
    // 检查是否全选
    val isAllSelected = allTasks.isNotEmpty() && 
        allTasks.all { (appName, taskGroup) ->
            selectedTasks.any { it.appName == appName && it.taskGroup.id == taskGroup.id }
        }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = "任务管理",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // 任务列表
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "JSON 任务列表 (${allTasks.size}个任务)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (allTasks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无任务")
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allTasks) { (appName, taskGroup) ->
                            val isSelected = selectedTasks.any { 
                                it.appName == appName && it.taskGroup.id == taskGroup.id 
                            }
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                viewModel.selectTaskGroup(appName, taskGroup.id)
                                            } else {
                                                // 需要实现取消选择功能
                                                viewModel.removeSelectedTask("${appName}_${taskGroup.id}")
                                            }
                                        }
                                    )
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = appName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = taskGroup.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (taskGroup.description.isNotEmpty()) {
                                            Text(
                                                text = taskGroup.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 底部控制按钮
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // 全选按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isAllSelected,
                        onCheckedChange = { checked ->
                            if (checked) {
                                // 全选
                                allTasks.forEach { (appName, taskGroup) ->
                                    viewModel.selectTaskGroup(appName, taskGroup.id)
                                }
                            } else {
                                // 取消全选
                                viewModel.clearSelectedTasks()
                            }
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = "全选 (${selectedTasks.size}/${allTasks.size})",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 执行状态显示
                if (executionStatus != TaskSelectionManager.ExecutionStatus.Idle) {
                    Text(
                        text = "执行状态: ${executionStatus.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (executionStatus) {
                            TaskSelectionManager.ExecutionStatus.Running -> MaterialTheme.colorScheme.primary
                            TaskSelectionManager.ExecutionStatus.Paused -> MaterialTheme.colorScheme.secondary
                            TaskSelectionManager.ExecutionStatus.Completed -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // 控制按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 启动服务按钮
                    Button(
                        onClick = { viewModel.startExecution() },
                        enabled = selectedTasks.isNotEmpty() && 
                            executionStatus == TaskSelectionManager.ExecutionStatus.Idle,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("启动服务")
                    }
                    
                    // 停止所有服务按钮
                    Button(
                        onClick = { viewModel.stopExecution() },
                        enabled = executionStatus != TaskSelectionManager.ExecutionStatus.Idle,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("停止所有服务")
                    }
                }
            }
        }
    }
}