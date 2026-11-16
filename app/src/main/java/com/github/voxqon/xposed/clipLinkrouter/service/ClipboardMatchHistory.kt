package com.github.voxqon.xposed.clipLinkrouter.service

import android.content.Context
import android.util.Log
import com.github.voxqon.xposed.clipLinkrouter.core.hook.ClipboardMatchPolicy
import org.json.JSONArray
import org.json.JSONObject

/** 仅保存有界的匹配摘要和事件时间；进程回收后继续识别重复事件。由监听器串行调用。 */
class ClipboardMatchHistory(context: Context) {
    private val preferences = context.getSharedPreferences("cliplink_clipboard_events", Context.MODE_PRIVATE)
    val policy = ClipboardMatchPolicy(runCatching {
        val array = JSONArray(preferences.getString("records", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ClipboardMatchPolicy.Record(
                item.getString("key"), item.getLong("clipboardTimestamp"),
                item.getLong("notifiedAtElapsed"), item.getInt("bootCount"),
            )
        }.filter { it.key.matches(Regex("[0-9a-f]{64}")) && it.clipboardTimestamp >= 0 &&
            it.notifiedAtElapsed >= 0 }
    }.getOrDefault(emptyList()))

    fun persist() {
        val array = JSONArray()
        policy.snapshot().forEach { record ->
            array.put(JSONObject().apply {
                put("key", record.key)
                put("clipboardTimestamp", record.clipboardTimestamp)
                put("notifiedAtElapsed", record.notifiedAtElapsed)
                put("bootCount", record.bootCount)
            })
        }
        // 广播结束前完成落盘，避免进程被回收后再次提醒。
        if (!preferences.edit().putString("records", array.toString()).commit()) {
            Log.w("XposedClipLinkRouter", "Clipboard event history could not be saved")
        }
    }
}
