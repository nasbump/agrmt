package com.agrmt.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 用户保存的一组「srv 地址 + 分组」。 */
@Serializable
data class ServerConfig(val addr: String, val group: String)

/**
 * 服务器列表的持久化。
 *
 * 卸载 app 会清空应用私有目录里的 SharedPreferences，仅为它配置
 * `hasFragileUserData` 还要用户在同意的卸载对话框里手动勾选。这里再把同一份
 * 配置备份到公共下载目录（`Download/agrmt/agrmt-servers.json`，MediaStore，
 * Android 10+ 写入自己的文件不需要存储权限），重装后首次读取时自动恢复。
 */
class ConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("agrmt", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ServerConfig.serializer())

    fun load(): List<ServerConfig> {
        // 私有目录里已有记录就以它为准（空列表也是合法状态，避免把已删除的配置从备份里复活）。
        prefs.getString(KEY, null)?.let { return decode(it) }
        val backup = readBackup() ?: return emptyList()
        val restored = decode(backup)
        if (restored.isNotEmpty()) prefs.edit().putString(KEY, backup).apply()
        return restored
    }

    fun save(list: List<ServerConfig>) {
        val raw = json.encodeToString(serializer, list)
        prefs.edit().putString(KEY, raw).apply()
        writeBackup(raw)
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

    private fun decode(raw: String): List<ServerConfig> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    // 备份失败不应影响配置保存本身（例如厂商 ROM 限制了 MediaStore），只放弃这次备份。
    private fun writeBackup(raw: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val resolver = appContext.contentResolver
            val uri = findBackupUri() ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, BACKUP_DIR)
                },
            ) ?: return
            // "wt" 截断写入，避免新内容比旧内容短时留下残余字节。
            resolver.openOutputStream(uri, "wt")?.use { it.write(raw.toByteArray()) }
        }
    }

    private fun readBackup(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val uri = findBackupUri() ?: return null
            appContext.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun findBackupUri(): Uri? {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(BACKUP_NAME, BACKUP_DIR),
            null,
        )?.use { c ->
            if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getLong(0).toString())
        }
        return null
    }

    private companion object {
        const val KEY = "servers"
        const val BACKUP_NAME = "agrmt-servers.json"
        const val BACKUP_DIR = "Download/agrmt/"
    }
}
