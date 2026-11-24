package com.agrmt.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agrmt.app.BuildConfig
import com.agrmt.app.data.ServerConfig
import com.agrmt.app.ui.MainViewModel

/** 首次启动或断开连接后的服务器列表页：可新增配置并点击连接。 */
@Composable
fun ServerScreen(vm: MainViewModel) {
    var addr by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "agrmt 服务器",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            "填写 agrmt-srv 的地址与分组，保存后点击列表项连接。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        OutlinedTextField(
            value = addr,
            onValueChange = { addr = it },
            label = { Text("srv 地址，例如 10.0.0.2:8989") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = group,
            onValueChange = { group = it },
            label = { Text("分组（group）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(
            onClick = {
                if (vm.saveConfig(addr, group)) {
                    addr = ""
                    group = ""
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("保存配置") }

        Text(
            "已保存的服务器",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )

        if (vm.configs.isEmpty()) {
            Text("暂无配置", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.configs, key = { it.addr + "|" + it.group }) { cfg ->
                    ServerRow(cfg, onConnect = { vm.connect(cfg) }, onDelete = { vm.removeConfig(cfg) })
                }
            }
        }
    }
}

@Composable
private fun ServerRow(cfg: ServerConfig, onConnect: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(cfg.addr, style = MaterialTheme.typography.titleSmall)
                Text("分组: ${cfg.group}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onConnect) { Text("连接") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}
