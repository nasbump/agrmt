package com.agrmt.app.model

import kotlinx.serialization.Serializable

/** 与 Go 端 internal/proto 对齐的控制消息。 */
@Serializable
data class Message(
    val type: String,
    val group: String? = null,
    val name: String? = null,
    val gen: String? = null,
    val cols: Int = 0,
    val rows: Int = 0,
    val code: Int? = null,
    val status: String? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val err: String? = null,
)

@Serializable
data class SessionInfo(
    val name: String,
    val status: String,
    val cols: Int,
    val rows: Int,
    val updated: Long = 0,
)

object MsgType {
    const val ATTACH = "attach"
    const val DETACH = "detach"
    const val RESET = "reset"
    const val STATUS = "status"
    const val SESSIONS = "sessions"
    const val ERROR = "error"
}

object SessionStatus {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
    const val EXITED = "exited"
}
