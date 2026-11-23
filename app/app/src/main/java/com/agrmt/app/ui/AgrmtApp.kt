package com.agrmt.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrmt.app.ui.screens.ServerScreen
import com.agrmt.app.ui.screens.SessionScreen
import com.agrmt.app.ui.screens.TerminalScreen

/** 按阶段切换三个页面：服务器列表 → 会话列表 → 终端。 */
@Composable
fun AgrmtApp(vm: MainViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (vm.stage) {
            Stage.SERVERS -> ServerScreen(vm)
            Stage.SESSIONS -> SessionScreen(vm)
            Stage.TERMINAL -> TerminalScreen(vm)
        }
        vm.notice?.let { text ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                NoticeBar(text = text, onDismiss = { vm.dismissNotice() })
            }
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Surface(tonalElevation = 6.dp, color = MaterialTheme.colorScheme.errorContainer) {
        Column {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}
