package com.github.voxqon.xposed.clipLinkrouter.service

import android.app.KeyguardManager
import android.os.PowerManager
import android.provider.Settings
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import com.github.voxqon.xposed.clipLinkrouter.core.engine.RuleEngine
import com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingExtractor
import com.github.voxqon.xposed.clipLinkrouter.core.extract.LinkExtractor
import com.github.voxqon.xposed.clipLinkrouter.core.extract.Hasher
import com.github.voxqon.xposed.clipLinkrouter.core.hook.ClipboardMatchPolicy
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.data.settings.AppSettings
import com.github.voxqon.xposed.clipLinkrouter.notification.NotificationHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 处理 LSPosed 系统事件，无常驻服务、轮询或唤醒锁。 */
class ClipboardMonitor(private val app: ClipLinkApp) {
    private data class Snapshot(val settings: AppSettings, val rules: List<RuleSpec>)
    @Volatile private var snapshot: Snapshot? = null
    private val ready = CompletableDeferred<Unit>()
    private val processing = Mutex()
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val source = SystemClipboardSource(app) { (snapshot?.settings?.maxTextLength ?: 2000) + 1 }
    private val history by lazy { ClipboardMatchHistory(app) }
    private val bootCount by lazy {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }
    init {
        app.appScope.launch(Dispatchers.Main.immediate) {
            app.rulesReady.await()
            combine(app.settings.settings, app.rules.ruleSpecs) { settings, rules ->
                Snapshot(settings, rules)
            }.collect { next ->
                snapshot = next
                ClipboardEventReceiver.setEnabled(app, next.settings.enabled && next.settings.backgroundListening)
                ready.complete(Unit)
            }
        }
    }

    /** 广播在读取设置和发出通知之后才完成；后台进程可随时被系统回收。 */
    suspend fun checkBackground(sourcePackage: String? = null, eventTimestamp: Long = 0L) = processing.withLock {
        ready.await()
        // 将读取和处理一起串行化；排队事件始终按当前剪贴板版本判断。
        val clip = withContext(Dispatchers.Main.immediate) {
            val state = snapshot ?: return@withContext null
            if (canProcess(state)) source.currentSnapshot() else null
        } ?: return@withLock
        val state = snapshot ?: return@withLock
        if (!canProcess(state)) return@withLock
        val text = clip.text
        val url = LinkExtractor.extractFirst(text, state.settings.maxTextLength).firstUrl
        val kind: String
        val payload: String
        val rule: RuleSpec
        if (url != null) {
            kind = NotificationHelper.KIND_URL
            payload = url
            rule = RuleEngine.match(url, state.rules)
            if (!rule.enabled) return@withLock
        } else {
            if (!state.settings.koulingEnabled) return@withLock
            val token = KoulingExtractor.extractFirst(text, state.settings.maxTextLength) ?: return@withLock
            rule = RuleEngine.matchKouling(token, state.rules) ?: return@withLock
            kind = NotificationHelper.KIND_KOULING
            payload = token.token
        }
        // 异步广播可能落后于下一次复制，只有时间戳一致时才使用该事件的来源。
        val verifiedSource = sourcePackage?.takeIf { eventTimestamp > 0 && eventTimestamp == clip.timestamp }
        val key = Hasher.sha256Hex("$kind:${rule.platform.name}:$payload")
        if (key.isEmpty()) return@withLock
        val policy = withContext(Dispatchers.IO) { history.policy }
        val now = SystemClock.nowMillis()
        val decision = policy.decide(key, clip.timestamp, verifiedSource, now, bootCount)
        if (decision != ClipboardMatchPolicy.Decision.NOTIFY) {
            if (policy.recordHandled(key, clip.timestamp, now, bootCount, decision)) {
                withContext(Dispatchers.IO) { history.persist() }
            }
            return@withLock
        }
        val notified = withContext(Dispatchers.Main.immediate) {
            // 关闭开关、锁屏或规则已变化时，丢弃在途识别结果。
            snapshot == state && canProcess(state) && NotificationHelper.showMatchNotification(
                app, payload, kind, platformLabel(rule.platform), rule.name, rule.platform.name,
                targetPackage = rule.targetPackage)
        }
        if (notified) {
            if (policy.recordHandled(key, clip.timestamp, now, bootCount, decision)) {
                withContext(Dispatchers.IO) { history.persist() }
            }
        }
    }

    private fun canProcess(state: Snapshot): Boolean {
        return state.settings.enabled && state.settings.backgroundListening &&
            power.isInteractive && !keyguard.isKeyguardLocked
    }

    companion object {
        fun platformLabel(platform: Platform): String = when (platform) {
            Platform.JD -> "京东"
            Platform.TAOBAO -> "淘宝"
            Platform.TMALL -> "天猫"
            Platform.DIDI -> "滴滴"
            Platform.GENERIC -> "通用"
        }
    }
}
