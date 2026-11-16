package com.github.voxqon.xposed.clipLinkrouter.core.engine

import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.core.extract.LinkExtractor
import java.net.URLEncoder

/** 系统 Intent 启动能力抽象，便于测试。 */
interface IntentLauncher {
    fun isPackageInstalled(packageName: String): Boolean
    fun launchScheme(uri: String, packageName: String): Boolean
    fun launchUrlInApp(url: String, packageName: String): Boolean = false
    fun launchBrowser(url: String): Boolean
    /** 直接拉起某个 App 的 Launcher 页（用于口令场景，让目标 App 自己读剪贴板）。 */
    fun launchApp(packageName: String): Boolean = false
}

/** 打开结果。 */
sealed interface OpenOutcome {
    data class Launched(val method: String) : OpenOutcome
    data class Fallback(val reason: String) : OpenOutcome
    data class Failed(val reason: String) : OpenOutcome
}

/**
 * 打开策略：决定用 Scheme/浏览器/直接拉起 App，并负责模板占位符替换。
 * - {url}   完整链接，URL 编码后替换
 * - {token} 口令主体，URL 编码后替换
 */
class OpenStrategy(
    private val launcher: IntentLauncher,
    private val defaultFallback: Boolean = true,
) {

    /** 打开链接：Scheme -> 浏览器回退。 */
    fun open(url: String, rule: RuleSpec): OpenOutcome {
        if (!LinkExtractor.isValidHttpUrl(url)) return OpenOutcome.Failed("链接无效")
        val scheme = rule.schemeTemplate
        val pkg = rule.targetPackage
        return try {
            when {
                scheme.isNullOrBlank() && pkg.isNullOrBlank() -> openBrowser(url, null)
                pkg.isNullOrBlank() -> launchBrowserOrFail(url, rule, "规则未配置目标包名")
                !launcher.isPackageInstalled(pkg) -> launchBrowserOrFail(url, rule, "目标应用未安装")
                scheme.isNullOrBlank() -> {
                    if (launcher.launchUrlInApp(url, pkg)) OpenOutcome.Launched("APP_LINK")
                    else launchBrowserOrFail(url, rule, "应用链接调起失败")
                }
                else -> {
                    val uri = fillTemplate(scheme, url = url)
                    if (launcher.launchScheme(uri, pkg)) OpenOutcome.Launched("APP_SCHEME")
                    else launchBrowserOrFail(url, rule, "深链调起失败")
                }
            }
        } catch (e: Exception) {
            launchBrowserOrFail(url, rule, e.message ?: "未知异常")
        }
    }

    /**
     * 打开口令：
     * 1. 规则配置了 {token} 深链模板且目标 App 已安装 -> 尝试深链；
     * 2. 无模板 -> 直接拉起目标 App（口令留在剪贴板，App 自行解析）；
     * 3. 目标 App 未安装或调起失败 -> 无浏览器可回退（口令不是 URL），返回 Failed。
     */
    fun openKouling(token: String, rule: RuleSpec): OpenOutcome {
        val pkg = rule.targetPackage
        return try {
            when {
                pkg.isNullOrBlank() -> OpenOutcome.Failed("口令规则未配置目标包名")
                !launcher.isPackageInstalled(pkg) -> OpenOutcome.Failed("目标应用未安装")
                else -> {
                    val scheme = rule.schemeTemplate
                    if (!scheme.isNullOrBlank()) {
                        val uri = fillTemplate(scheme, token = token)
                        if (launcher.launchScheme(uri, pkg)) OpenOutcome.Launched("KOULING_SCHEME")
                        else if (launcher.launchApp(pkg)) OpenOutcome.Launched("KOULING_APP")
                        else OpenOutcome.Failed("口令深链调起失败")
                    } else {
                        if (launcher.launchApp(pkg)) OpenOutcome.Launched("KOULING_APP")
                        else OpenOutcome.Failed("拉起目标应用失败")
                    }
                }
            }
        } catch (e: Exception) {
            OpenOutcome.Failed(e.message ?: "未知异常")
        }
    }

    private fun launchBrowserOrFail(url: String, rule: RuleSpec, reason: String): OpenOutcome {
        val canFallback = rule.fallbackToBrowser && defaultFallback
        return if (canFallback) {
            openBrowser(url, reason)
        } else {
            OpenOutcome.Failed(reason)
        }
    }

    private fun openBrowser(url: String, reason: String?): OpenOutcome = try {
        if (launcher.launchBrowser(url)) {
            if (reason == null) OpenOutcome.Launched("BROWSER") else OpenOutcome.Fallback(reason)
        } else OpenOutcome.Failed(reason?.let { "$it；浏览器回退也失败" } ?: "浏览器打开失败")
    } catch (_: Exception) {
        OpenOutcome.Failed("浏览器打开失败")
    }

    private fun fillTemplate(template: String, url: String? = null, token: String? = null): String {
        var out = template
        url?.let { out = out.replace("{url}", URLEncoder.encode(it, "UTF-8")) }
        token?.let { out = out.replace("{token}", URLEncoder.encode(it, "UTF-8")) }
        return out
    }
}
