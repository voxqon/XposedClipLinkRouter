package com.github.voxqon.xposed.clipLinkrouter.service

import android.app.Activity
import android.os.Bundle
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import com.github.voxqon.xposed.clipLinkrouter.core.engine.OpenStrategy
import com.github.voxqon.xposed.clipLinkrouter.core.engine.RuleEngine
import com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingResult
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 点击匹配通知后按当前规则打开目标，不保存打开记录。 */
class OpenLinkActivity : Activity() {

    private var scope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = intent?.getStringExtra(NotificationHelper.EXTRA_PAYLOAD)
        val kind = intent?.getStringExtra(NotificationHelper.EXTRA_KIND) ?: NotificationHelper.KIND_URL
        val platformHintName = intent?.getStringExtra(NotificationHelper.EXTRA_PLATFORM_HINT)
        if (payload.isNullOrBlank()) {
            finish()
            return
        }
        val app = application as ClipLinkApp
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch {
            try {
                app.rulesReady.await()
                val rules = app.rules.ruleSpecs.first()
                val settings = app.settings.settings.first()
                if (!settings.enabled || (kind == NotificationHelper.KIND_KOULING && !settings.koulingEnabled)) return@launch
                if (kind != NotificationHelper.KIND_URL && kind != NotificationHelper.KIND_KOULING) return@launch
                val strategy = OpenStrategy(
                    AndroidIntentLauncher(this@OpenLinkActivity),
                    defaultFallback = settings.alwaysFallbackToBrowser,
                )

                if (kind == NotificationHelper.KIND_KOULING) {
                    handleKouling(payload, platformHintName, rules, strategy)
                } else {
                    handleUrl(payload, rules, strategy)
                }
            } finally {
                // 无论成功失败，都要结束 Activity；若已销毁则系统会忽略
                if (!isFinishing && !isDestroyed) {
                    finish()
                }
            }
        }
    }

    private suspend fun handleKouling(
        payload: String,
        platformHintName: String?,
        rules: List<RuleSpec>,
        strategy: OpenStrategy,
    ) {
        val platformHint = platformHintName?.let {
            runCatching { Platform.valueOf(it) }.getOrDefault(Platform.GENERIC)
        } ?: Platform.GENERIC
        val probe = KoulingResult(
            token = payload, openChar = ' ', platformHint = platformHint,
            startIndex = 0, endIndex = 0,
        )
        val rule = withContext(Dispatchers.Default) { RuleEngine.matchKouling(probe, rules) } ?: return
        strategy.openKouling(payload, rule)
    }

    private suspend fun handleUrl(
        payload: String,
        rules: List<RuleSpec>,
        strategy: OpenStrategy,
    ) {
        val rule = withContext(Dispatchers.Default) { RuleEngine.match(payload, rules) }
        if (!rule.enabled) return
        strategy.open(payload, rule)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
