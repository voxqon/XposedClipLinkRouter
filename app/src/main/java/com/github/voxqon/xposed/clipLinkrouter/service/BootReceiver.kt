package com.github.voxqon.xposed.clipLinkrouter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 只恢复用户已开启的监听；首次安装仍需打开应用授权并开启开关。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        val app = context.applicationContext as ClipLinkApp
        app.appScope.launch {
            try {
                withTimeoutOrNull(8_000L) {
                    val settings = app.settings.settings.first()
                    ClipboardEventReceiver.setEnabled(app, settings.enabled && settings.backgroundListening)
                }
            } finally {
                result.finish()
            }
        }
    }
}
