package com.github.voxqon.xposed.clipLinkrouter.core.engine

import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

open class FakeIntentLauncher : IntentLauncher {
    var installed = false
    var schemeLaunched = false
    var browserLaunched = false
    var lastScheme: String? = null
    var lastBrowserUrl: String? = null

    override fun isPackageInstalled(packageName: String): Boolean = installed

    override fun launchScheme(uri: String, packageName: String): Boolean {
        schemeLaunched = true
        lastScheme = uri
        return true
    }

    override fun launchBrowser(url: String): Boolean {
        browserLaunched = true
        lastBrowserUrl = url
        return true
    }
}

class OpenStrategyTest {

    private fun jdRule(
        scheme: String? = "openApp.jdMobile://virtual?params={url}",
        targetPkg: String = "com.jingdong.app.mall",
        fallback: Boolean = true,
    ) = RuleSpec(
        name = "京东",
        type = RuleType.BUILTIN,
        matchType = MatchType.SUFFIX_HOST,
        pattern = "jd.com",
        platform = Platform.JD,
        schemeTemplate = scheme,
        targetPackage = targetPkg,
        fallbackToBrowser = fallback,
    )

    @Test
    fun 目标已安装则用Scheme打开并URL编码() {
        val launcher = FakeIntentLauncher().apply { installed = true }
        val strategy = OpenStrategy(launcher)
        val url = "https://item.jd.com/100.html?cu=true"
        val outcome = strategy.open(url, jdRule())
        assertTrue(outcome is OpenOutcome.Launched)
        assertEquals("openApp.jdMobile://virtual?params=" + java.net.URLEncoder.encode(url, "UTF-8"), launcher.lastScheme)
    }

    @Test
    fun 目标未安装且允许回退则浏览器() {
        val launcher = FakeIntentLauncher().apply { installed = false }
        val strategy = OpenStrategy(launcher)
        val url = "https://item.jd.com/1"
        val outcome = strategy.open(url, jdRule())
        assertTrue(outcome is OpenOutcome.Fallback)
        assertEquals(url, launcher.lastBrowserUrl)
    }

    @Test
    fun 目标未安装且不允许回退则失败() {
        val launcher = FakeIntentLauncher().apply { installed = false }
        val strategy = OpenStrategy(launcher)
        val url = "https://item.jd.com/1"
        val outcome = strategy.open(url, jdRule(fallback = false))
        assertTrue(outcome is OpenOutcome.Failed)
        assertTrue(!launcher.browserLaunched)
    }

    @Test
    fun 无scheme的通用规则直接浏览器() {
        val launcher = FakeIntentLauncher()
        val strategy = OpenStrategy(launcher)
        val generic = RuleSpec(
            name = "通用",
            type = RuleType.BUILTIN,
            matchType = MatchType.KEYWORD,
            pattern = "",
            platform = Platform.GENERIC,
            schemeTemplate = null,
            fallbackToBrowser = true,
        )
        val outcome = strategy.open("https://example.com/x", generic)
        assertEquals(OpenOutcome.Launched("BROWSER"), outcome)
        assertEquals("https://example.com/x", launcher.lastBrowserUrl)
    }

    @Test
    fun 深链调起异常时回退浏览器() {
        val launcher = object : FakeIntentLauncher() {
            override fun launchScheme(uri: String, packageName: String): Boolean = throw SecurityException("denied")
        }.apply { installed = true }
        val strategy = OpenStrategy(launcher)
        val outcome = strategy.open("https://item.jd.com/1", jdRule())
        assertTrue(outcome is OpenOutcome.Fallback)
    }
    // ---- 口令打开 ----

    @Test
    fun `口令有token模板时深链打开`() {
        val launcher = object : FakeIntentLauncher() {
            override fun launchApp(packageName: String): Boolean = true
        }.apply { installed = true }
        val strategy = OpenStrategy(launcher)
        val koulingRule = RuleSpec(
            name = "京口令",
            type = RuleType.BUILTIN,
            matchType = MatchType.KOULING,
            pattern = "",
            platform = Platform.JD,
            schemeTemplate = "openApp.jdMobile://virtual?params={token}",
            targetPackage = "com.jingdong.app.mall",
        )
        val outcome = strategy.openKouling("AbC123", koulingRule)
        assertTrue(outcome is OpenOutcome.Launched)
        assertEquals("openApp.jdMobile://virtual?params=AbC123", launcher.lastScheme)
    }

    @Test
    fun `口令无模板时直接拉起App`() {
        var launchedApp: String? = null
        val launcher = object : FakeIntentLauncher() {
            override fun launchApp(packageName: String): Boolean {
                launchedApp = packageName
                return true
            }
        }.apply { installed = true }
        val strategy = OpenStrategy(launcher)
        val koulingRule = RuleSpec(
            name = "淘口令",
            type = RuleType.BUILTIN,
            matchType = MatchType.KOULING,
            pattern = "",
            platform = Platform.TAOBAO,
            schemeTemplate = null,
            targetPackage = "com.taobao.taobao",
        )
        val outcome = strategy.openKouling("AbC123", koulingRule)
        assertTrue(outcome is OpenOutcome.Launched)
        assertEquals("com.taobao.taobao", launchedApp)
    }

    @Test
    fun `口令目标App未安装时失败且不回退浏览器`() {
        val launcher = FakeIntentLauncher().apply { installed = false }
        val strategy = OpenStrategy(launcher)
        val koulingRule = RuleSpec(
            name = "淘口令",
            type = RuleType.BUILTIN,
            matchType = MatchType.KOULING,
            pattern = "",
            platform = Platform.TAOBAO,
            schemeTemplate = null,
            targetPackage = "com.taobao.taobao",
        )
        val outcome = strategy.openKouling("AbC123", koulingRule)
        assertTrue(outcome is OpenOutcome.Failed)
        assertTrue(!launcher.browserLaunched)
    }
}
