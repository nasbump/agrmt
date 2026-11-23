package com.agrmt.app.ui.screens

import android.annotation.SuppressLint
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agrmt.app.model.SessionStatus
import com.agrmt.app.term.Keys
import com.agrmt.app.ui.MainViewModel

/**
 * 终端页：xterm.js 渲染 agent 输出，提示词输入与快捷按键放在终端旁。
 *
 * 竖屏时输入区在终端下方；横屏时移到右侧并收紧标题栏，把高度留给终端，
 * 保证 24 行终端与两行快捷键能同时放下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = { TerminalHeader(vm, compact = landscape) },
    ) { pad ->
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize().padding(pad)) {
                Box(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                    TerminalWebView(vm)
                }
                PromptPanel(
                    vm,
                    compact = true,
                    modifier = Modifier.weight(0.38f).fillMaxHeight(),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(pad)) {
                // 输入区按内容取高：两行快捷键比 20% 高度更高，固定比例会把按键挤出屏幕。
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    TerminalWebView(vm)
                }
                PromptPanel(vm, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalHeader(vm: MainViewModel, compact: Boolean) {
    val name = vm.attachedName ?: "会话"
    val st = when (vm.sessionStatus) {
        SessionStatus.ONLINE -> "在线"
        SessionStatus.EXITED -> "已退出" + (vm.exitCode?.let { "($it)" } ?: "")
        SessionStatus.OFFLINE -> "离线"
        else -> ""
    }
    val title = if (st.isEmpty()) name else "$name · $st"

    if (compact) {
        // 横屏高度紧张，用 44dp 的窄标题栏替代默认 64dp 的 TopAppBar。
        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.closeSession() }) { Text("返回") }
            }
        }
    } else {
        TopAppBar(
            title = { Text(title) },
            actions = {
                TextButton(onClick = { vm.closeSession() }) { Text("返回") }
            },
        )
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
    compact: Boolean = false,
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
                maxLines = if (compact) 2 else 3,
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
