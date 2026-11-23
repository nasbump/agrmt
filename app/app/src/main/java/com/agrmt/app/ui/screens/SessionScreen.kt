package com.agrmt.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agrmt.app.model.SessionInfo
import com.agrmt.app.model.SessionStatus
import com.agrmt.app.ui.MainViewModel

/** 连接成功后的会话列表：展示分组内全部会话名与状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: MainViewModel) {
    val cfg = vm.activeConfig
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话组 ${cfg?.group ?: ""}") },
                actions = {
                    TextButton(onClick = { vm.disconnect() }) { Text("断开") }
                },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Text(
                cfg?.addr ?: "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                if (vm.connected) "已连接" else "连接中…",
                style = MaterialTheme.typography.labelMedium,
                color = if (vm.connected) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (vm.sessions.isEmpty()) {
                Text("该分组下暂无会话，请先在电脑上启动 agrmt-cli。")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.sessions, key = { it.name }) { info ->
                        SessionRow(info, onOpen = { vm.attach(info.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(info: SessionInfo, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(info.name, style = MaterialTheme.typography.titleMedium)
                Text("${info.cols}x${info.rows}", style = MaterialTheme.typography.bodySmall)
            }
            val (label, color) = when (info.status) {
                SessionStatus.ONLINE -> "在线" to Color(0xFF2E7D32)
                SessionStatus.EXITED -> "已退出" to Color(0xFFC62828)
                else -> "离线" to Color(0xFF9E9E9E)
            }
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}
