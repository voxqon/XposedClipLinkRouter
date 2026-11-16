package com.github.voxqon.xposed.clipLinkrouter.service

import android.app.Activity
import android.os.Bundle
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import com.github.voxqon.xposed.clipLinkrouter.core.engine.OpenOutcome
import com.github.voxqon.xposed.clipLinkrouter.core.engine.OpenStrategy
import com.github.voxqon.xposed.clipLinkrouter.core.engine.RuleEngine
import com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingResult
import com.github.voxqon.xposed.clipLinkrouter.core.extract.LinkExtractor
import com.github.voxqon.xposed.clipLinkrouter.core.model.Action
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.Result
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.LogEntity
import com.github.voxqon.xposed.clipLinkrouter.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 点击通知后的"跳板" Activity：无 UI，收到 URL 后立即调起并写日志，然后 finish。
 * 使用单独的 Activity 处理通知点击，直接打开目标应用。
 *
 * 生命周期说明：协程 scope 与 Activity 绑定，onDestroy 时自动取消；
 * finish() 在协程完成后调用，若 Activity 已销毁则系统会忽略。
 *
 * 隐私说明：extra 字段只记录结构化的失败原因标签（如 "目标应用未安装"），
 * 绝不写入完整 URL 或剪贴板内容。
 */
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
                val started = SystemClock.nowMillis()
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
                    handleKouling(app, payload, platformHintName, rules, strategy, started, settings.logKeepCount)
                } else {
                    handleUrl(app, payload, rules, strategy, started, settings.logKeepCount)
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
        app: ClipLinkApp,
        payload: String,
        platformHintName: String?,
        rules: List<RuleSpec>,
        strategy: OpenStrategy,
        started: Long,
        logKeepCount: Int,
    ) {
        val platformHint = platformHintName?.let {
            runCatching { Platform.valueOf(it) }.getOrDefault(Platform.GENERIC)
        } ?: Platform.GENERIC
        val probe = KoulingResult(
            token = payload, openChar = ' ', platformHint = platformHint,
            startIndex = 0, endIndex = 0,
        )
        val rule = withContext(Dispatchers.Default) { RuleEngine.matchKouling(probe, rules) } ?: return
        val outcome = strategy.openKouling(payload, rule)
        val elapsed = SystemClock.nowMillis() - started
        val (action, result, extra) = when (outcome) {
            is OpenOutcome.Launched -> Triple(Action.KOULING_APP, Result.SUCCESS, null)
            is OpenOutcome.Fallback -> Triple(Action.FALLBACK_BROWSER, Result.FALLBACK, sanitizeReason(outcome.reason))
            is OpenOutcome.Failed -> Triple(Action.FAILED, Result.FAILED, sanitizeReason(outcome.reason))
        }
        app.appScope.launch { app.logs.append(
            LogEntity(
                timestamp = System.currentTimeMillis(),
                platform = rule.platform,
                host = "口令",
                urlHash = LinkExtractor.shortHash(payload, 16),
                ruleId = rule.id.takeIf { it > 0 },
                ruleName = rule.name,
                action = action,
                result = result,
                elapsedMs = elapsed,
                extra = extra,
            ),
            keepCount = logKeepCount,
        ) }
    }

    private suspend fun handleUrl(
        app: ClipLinkApp,
        payload: String,
        rules: List<RuleSpec>,
        strategy: OpenStrategy,
        started: Long,
        logKeepCount: Int,
    ) {
        val rule = withContext(Dispatchers.Default) { RuleEngine.match(payload, rules) }
        if (!rule.enabled) return
        val outcome = strategy.open(payload, rule)
        val elapsed = SystemClock.nowMillis() - started
        val (action, result, extra) = when (outcome) {
            is OpenOutcome.Launched -> Triple(if (outcome.method == "BROWSER") Action.BROWSER else Action.APP_SCHEME, Result.SUCCESS, null)
            is OpenOutcome.Fallback -> Triple(Action.FALLBACK_BROWSER, Result.FALLBACK, sanitizeReason(outcome.reason))
            is OpenOutcome.Failed -> Triple(Action.FAILED, Result.FAILED, sanitizeReason(outcome.reason))
        }
        app.appScope.launch { app.logs.append(
            LogEntity(
                timestamp = System.currentTimeMillis(),
                platform = rule.platform,
                host = LinkExtractor.hostOf(payload),
                urlHash = LinkExtractor.shortHash(payload, 16),
                ruleId = rule.id.takeIf { it > 0 },
                ruleName = rule.name,
                action = action,
                result = result,
                elapsedMs = elapsed,
                extra = extra,
            ),
            keepCount = logKeepCount,
        ) }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        /**
         * 把失败原因映射成固定标签，避免 exception.message 里夹带 URL 明文落库。
         * 已知原因原样保留；未知异常统一归为 "打开失败"。
         */
        private val KNOWN_REASONS = setOf(
            "规则未配置目标包名",
            "目标应用未安装",
            "深链调起失败",
            "口令规则未配置目标包名",
            "口令深链调起失败",
            "拉起目标应用失败",
            "应用链接调起失败",
            "链接无效",
            "浏览器打开失败",
        )

        internal fun sanitizeReason(reason: String): String =
            if (reason in KNOWN_REASONS) reason else "打开失败"
    }
}
