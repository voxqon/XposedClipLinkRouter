package com.github.voxqon.xposed.clipLinkrouter.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.github.voxqon.xposed.clipLinkrouter.R
import com.github.voxqon.xposed.clipLinkrouter.core.extract.Hasher
import com.github.voxqon.xposed.clipLinkrouter.service.OpenLinkActivity

object NotificationHelper {
    const val CHANNEL_MATCH = "cliplink_match"
    private const val ID_MATCH = 2000
    const val ACTION_OPEN_LINK = "com.github.voxqon.xposed.clipLinkrouter.action.OPEN_LINK"
    const val EXTRA_PAYLOAD = "extra_payload"
    const val EXTRA_KIND = "extra_kind"
    const val EXTRA_PLATFORM_HINT = "extra_platform_hint"
    const val KIND_URL = "url"
    const val KIND_KOULING = "kouling"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // 覆盖安装后清理旧版的监听通知和通道。
        if (manager.getNotificationChannel("cliplink_foreground") != null) {
            manager.cancel(1001)
            manager.deleteNotificationChannel("cliplink_foreground")
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MATCH, "链接匹配",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "识别链接或口令后，点击打开"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun canPostNotifications(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun showMatchNotification(
        context: Context, payload: String, kind: String, platformLabel: String,
        platformHint: String,
        targetPackage: String? = null,
    ): Boolean {
        if (!canPostNotifications(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_MATCH)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val key = Hasher.sha256Hex("$kind:$platformHint:$payload")
        val intent = Intent(context, OpenLinkActivity::class.java).apply {
            action = ACTION_OPEN_LINK
            // data 参与 PendingIntent 身份比较，避免短哈希碰撞与旧通知打开错误内容。
            data = "cliplink://open/$key".toUri()
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_PLATFORM_HINT, platformHint)
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val largeIcon = targetPackage?.let { loadAppIcon(context, it) }
        val notification = NotificationCompat.Builder(context, CHANNEL_MATCH)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setLargeIcon(largeIcon)
            .setContentTitle("检测到 $platformLabel ${if (kind == KIND_KOULING) "口令" else "链接"}")
            .setContentText("点击打开")
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setTimeoutAfter(10 * 60_000L)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        return try {
            val old = manager.activeNotifications.filter { it.id == ID_MATCH && it.tag != key }
                .sortedBy { it.postTime }
            old.take((old.size - 4).coerceAtLeast(0)).forEach { manager.cancel(it.tag, it.id) }
            manager.notify(key, ID_MATCH, notification)
            true
        } catch (_: SecurityException) { false }
    }

    /** 解析目标应用图标；应用未安装或解析失败时返回 null，通知照常弹出。 */
    private fun loadAppIcon(context: Context, packageName: String): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            .coerceAtLeast(96)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()
}
