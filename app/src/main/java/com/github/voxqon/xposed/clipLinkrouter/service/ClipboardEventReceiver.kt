package com.github.voxqon.xposed.clipLinkrouter.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.github.voxqon.xposed.clipLinkrouter.BuildConfig
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 接收系统的无内容事件，goAsync 保证本次读取和通知完成前进程不会成为空进程。 */
class ClipboardEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHANGED) return
        val pending = goAsync()
        val app = context.applicationContext as ClipLinkApp
        app.appScope.launch {
            try {
                withTimeout(8_000L) {
                    app.monitor.checkBackground(
                        intent.getStringExtra(EXTRA_SOURCE_PACKAGE),
                        intent.getLongExtra(EXTRA_CLIP_TIMESTAMP, 0L),
                    )
                }
            } catch (_: Exception) {
                // 不输出可能含剪贴板内容的异常消息。
                Log.w("XposedClipLinkRouter", "Clipboard event processing did not complete")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHANGED = BuildConfig.APPLICATION_ID + ".action.CLIPBOARD_CHANGED"
        const val CLASS_NAME = BuildConfig.APPLICATION_ID + ".service.ClipboardEventReceiver"
        const val EXTRA_SOURCE_PACKAGE = "cliplink.sourcePackage"
        const val EXTRA_CLIP_TIMESTAMP = "cliplink.clipTimestamp"

        fun setEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, ClipboardEventReceiver::class.java)
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != state) {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
