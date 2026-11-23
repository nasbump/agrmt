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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agrmt.app.model.SessionStatus
import com.agrmt.app.term.Keys
import com.agrmt.app.ui.MainViewModel

/**
 * 终端页：上部 80% 为 xterm.js 渲染的 agent 输出，下部 20% 为提示词输入与快捷按键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = vm.attachedName ?: "会话"
                    val st = when (vm.sessionStatus) {
                        SessionStatus.ONLINE -> "在线"
                        SessionStatus.EXITED -> "已退出" + (vm.exitCode?.let { "($it)" } ?: "")
                        SessionStatus.OFFLINE -> "离线"
                        else -> ""
                    }
                    Text(if (st.isEmpty()) name else "$name · $st")
                },
                actions = {
                    TextButton(onClick = { vm.closeSession() }) { Text("返回") }
                },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            Box(modifier = Modifier.fillMaxWidth().weight(0.8f)) {
                TerminalWebView(vm)
            }
            PromptPanel(vm, modifier = Modifier.fillMaxWidth().weight(0.2f))
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
private fun PromptPanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
        }

        // 快捷按键：方向键、斜杠、tab、shift+tab、回车。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Key("↑") { vm.sendArrow('u') }
            Key("↓") { vm.sendArrow('d') }
            Key("←") { vm.sendArrow('l') }
            Key("→") { vm.sendArrow('r') }
            Key("/") { vm.sendQuick(Keys.SLASH) }
            Key("Tab") { vm.sendQuick(Keys.TAB) }
            Key("⇧Tab") { vm.sendQuick(Keys.SHIFT_TAB) }
            Key("Enter") { vm.sendQuick(Keys.ENTER) }
            Button(onClick = {
                vm.sendQuick(text + Keys.ENTER)
                text = ""
            }) { Text("发送") }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}
