package com.github.voxqon.xposed.clipLinkrouter.service

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.github.voxqon.xposed.clipLinkrouter.core.engine.IntentLauncher

/** 在主线程读取剪贴板，并限制读取长度。 */
class SystemClipboardSource(context: Context, private val textLimit: () -> Int = { 2001 }) {
    data class Snapshot(val text: String, val timestamp: Long)

    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    fun currentSnapshot(): Snapshot? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).text?.take(textLimit().coerceAtLeast(1))?.toString() ?: return null
        Snapshot(text, clip.description.timestamp)
    }.getOrNull()

}

object SystemClock {
    fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

class AndroidIntentLauncher(private val context: Context) : IntentLauncher {
    override fun isPackageInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    override fun launchScheme(uri: String, packageName: String): Boolean =
        launch(Intent(Intent.ACTION_VIEW, uri.toUri()).setPackage(packageName))

    override fun launchUrlInApp(url: String, packageName: String): Boolean =
        launch(Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(packageName))

    override fun launchBrowser(url: String): Boolean = launch(Intent(Intent.ACTION_VIEW, url.toUri()))

    override fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return launch(intent)
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
