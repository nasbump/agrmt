package com.agrmt.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agrmt.app.model.SessionStatus
import com.agrmt.app.term.Keys
import com.agrmt.app.ui.MainViewModel
import kotlinx.coroutines.delay

/**
 * 终端页：xterm.js 渲染 agent 输出，提示词输入与快捷按键放在终端下方。
 *
 * 目前只支持竖屏：输入区按内容取高，剩余高度全部留给终端。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel) {
    var showHistory by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TerminalHeader(vm, onHistory = { showHistory = true }) },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            // 输入区按内容取高：两行快捷键比 20% 高度更高，固定比例会把按键挤出屏幕。
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TerminalWebView(vm)
            }
            PromptPanel(vm, modifier = Modifier.fillMaxWidth())
        }
    }
    if (showHistory) {
        HistoryDialog(vm, onDismiss = { showHistory = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalHeader(vm: MainViewModel, onHistory: () -> Unit) {
    val name = vm.attachedName ?: "会话"
    val st = when (vm.sessionStatus) {
        SessionStatus.ONLINE -> "在线"
        SessionStatus.EXITED -> "已退出" + (vm.exitCode?.let { "($it)" } ?: "")
        SessionStatus.OFFLINE -> "离线"
        else -> ""
    }
    val title = if (st.isEmpty()) name else "$name · $st"

    TopAppBar(
        title = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        actions = {
            TextButton(onClick = onHistory) { Text("历史") }
            TextButton(onClick = { vm.closeSession() }) { Text("返回") }
        },
    )
}

/**
 * 历史输出页：展示从原始输出还原的纯文本。
 *
 * 全屏 TUI（如 codex）使用备用屏幕，xterm 没有可回滚的内容，滑动只会发方向键；
 * 这个页面直接读取 app 端累积的文本历史，任何 agent 都能回溯查看。
 */
@Composable
private fun HistoryDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var lines by remember { mutableStateOf(vm.terminal.historyLines()) }
    // 打开时先定位到末尾；行还没加载完时由下面的 effect 在首次刷新后补位。
    val listState = rememberLazyListState()
    var jumpedToBottom by remember { mutableStateOf(false) }

    // 打开期间定时刷新；停在底部时跟随最新输出，用户上翻后保持当前位置。
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val latest = vm.terminal.historyLines()
            if (latest == lines) continue
            val atBottom = listState.layoutInfo.let { info ->
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= info.totalItemsCount - 1
            }
            lines = latest
            if ((!jumpedToBottom || atBottom) && latest.isNotEmpty()) {
                jumpedToBottom = true
                listState.scrollToItem(latest.size - 1)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101014)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "历史输出（${lines.size} 行）",
                        color = Color(0xFFE0E0E0),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (lines.isEmpty()) {
                    Text(
                        "暂无历史输出",
                        color = Color(0xFF9E9E9E),
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                line,
                                color = Color(0xFFE0E0E0),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                softWrap = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalWebView(vm: MainViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color(0xFF101014)),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(AndroidColor.parseColor("#101014"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = false
                settings.textZoom = 100
                webViewClient = WebViewClient()
                addJavascriptInterface(vm.terminal.Bridge(), "AndroidTerm")
                vm.terminal.bind(this)
                loadUrl("file:///android_asset/terminal.html")
            }
        },
        onRelease = { wv ->
            vm.terminal.unbind()
            wv.destroy()
        },
    )
}

@Composable
private fun PromptPanel(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入提示词…") },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    vm.sendQuick(text + Keys.ENTER)
                    text = ""
                }),
            )
            Button(
                onClick = {
                    vm.sendQuick(text + Keys.ENTER)
                    text = ""
                },
                modifier = Modifier.padding(start = 6.dp),
            ) { Text("发送") }
        }

        // 快捷按键分两行：第一行方向键，第二行常用控制键。
        KeyRow {
            Key("↑") { vm.sendArrow('u') }
            Key("↓") { vm.sendArrow('d') }
            Key("←") { vm.sendArrow('l') }
            Key("→") { vm.sendArrow('r') }
        }
        KeyRow {
            Key("/") { vm.sendQuick(Keys.SLASH) }
            Key("Esc") { vm.sendQuick(Keys.ESC) }
            Key("Tab") { vm.sendQuick(Keys.TAB) }
            Key("⇧Tab") { vm.sendQuick(Keys.SHIFT_TAB) }
            Key("Enter") { vm.sendQuick(Keys.ENTER) }
        }
    }
}

/** 单行快捷键容器；窄屏放不下时横向滚动，避免按键被裁掉。 */
@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}
