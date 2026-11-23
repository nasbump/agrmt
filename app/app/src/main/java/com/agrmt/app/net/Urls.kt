package com.agrmt.app.net

import java.net.URLEncoder

/**
 * 把用户输入的 srv 地址转换成 app 端 WebSocket 端点。
 *
 * OkHttp 的 WebSocket 只接受 http/https 形式的 URL（握手时自动升级），
 * 因此这里把 ws/wss 归一化为 http/https；group 做 URL 编码后放入查询参数。
 */
fun buildAppUrl(addr: String, group: String): String {
    val trimmed = addr.trim()
    require(trimmed.isNotEmpty()) { "srv 地址不能为空" }
    require(group.isNotBlank()) { "分组不能为空" }

    val base = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
        trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
        "://" in trimmed -> throw IllegalArgumentException("不支持的地址协议: $trimmed")
        else -> "http://$trimmed"
    }
    val encoded = URLEncoder.encode(group, "UTF-8")
    return "${base.trimEnd('/')}/ws/app?group=$encoded"
}
