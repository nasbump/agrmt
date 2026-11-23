package com.agrmt.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 用户保存的一组「srv 地址 + 分组」。 */
@Serializable
data class ServerConfig(val addr: String, val group: String)

/** 用 SharedPreferences 持久化服务器列表，首次启动为空。 */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("agrmt", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ServerConfig.serializer())

    fun load(): List<ServerConfig> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun save(list: List<ServerConfig>) {
        prefs.edit().putString(KEY, json.encodeToString(serializer, list)).apply()
    }

    fun addOrReplace(cfg: ServerConfig): List<ServerConfig> {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.addr == cfg.addr && it.group == cfg.group }
        if (idx >= 0) list[idx] = cfg else list.add(cfg)
        save(list)
        return list
    }

    fun remove(cfg: ServerConfig): List<ServerConfig> {
        val list = load().filterNot { it.addr == cfg.addr && it.group == cfg.group }
        save(list)
        return list
    }

    private companion object {
        const val KEY = "servers"
    }
}
