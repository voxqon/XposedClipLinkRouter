package com.github.voxqon.xposed.clipLinkrouter.core

import com.github.voxqon.xposed.clipLinkrouter.core.engine.*
import com.github.voxqon.xposed.clipLinkrouter.core.extract.*
import com.github.voxqon.xposed.clipLinkrouter.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ReliabilityRegressionTest {
    private fun rule() = RuleSpec(id = 900, name = "规则", type = RuleType.CUSTOM,
        matchType = MatchType.SUFFIX_HOST, pattern = "jd.com", platform = Platform.JD,
        targetPackage = "com.jingdong.app.mall")

    @Test fun `只修改打开配置后不复用旧规则`() {
        val original = rule()
        RuleEngine.match("https://jd.com/1", listOf(original))
        val edited = original.copy(schemeTemplate = "new://?url={url}", targetPackage = "other.app",
            fallbackToBrowser = false, platform = Platform.TAOBAO, name = "新规则")
        assertEquals(edited, RuleEngine.match("https://jd.com/1", listOf(edited)))
    }

    @Test fun `修改匹配类型后立即生效`() {
        val original = rule()
        RuleEngine.match("https://item.jd.com/1", listOf(original))
        assertEquals(RuleEngine.BUILTIN_GENERIC,
            RuleEngine.match("https://item.jd.com/1", listOf(original.copy(matchType = MatchType.EXACT_HOST))))
    }

    @Test fun `缓存保护调用方可变列表`() {
        val rules = mutableListOf(rule())
        RuleEngine.match("https://jd.com", rules)
        rules[0] = rules[0].copy(targetPackage = "edited.app")
        assertEquals("edited.app", RuleEngine.match("https://jd.com", rules).targetPackage)
    }

    @Test fun `精确域名规范化规则与链接两端`() {
        assertTrue(RuleEngine.matches(rule().copy(matchType = MatchType.EXACT_HOST,
            pattern = " WWW.JD.COM. "), "https://www.jd.com./1"))
    }

    @Test fun `恶意相似域名和参数中的平台域名不命中`() {
        for (url in listOf("https://eviljd.com", "https://jd.com.evil.com", "https://evil.com/?url=https://jd.com")) {
            assertFalse(url, RuleEngine.matches(rule(), url))
        }
    }

    @Test fun `域名判断不受土耳其语环境影响`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(RuleEngine.matches(rule().copy(pattern = "ITEM.JD.COM"), "https://ITEM.JD.COM/1"))
        } finally { Locale.setDefault(old) }
    }

    @Test fun `邮箱不当成链接`() {
        assertNull(LinkExtractor.extractFirst("联系 foo@jd.com 或 user@www.example.com").firstUrl)
    }

    @Test fun `其他协议中的域名不当成HTTPS链接`() {
        for (text in listOf("ftp://jd.com/file", "mailto:user@jd.com", "file:///jd.com/file")) {
            assertNull(text, LinkExtractor.extractFirst(text).firstUrl)
        }
    }

    @Test fun `裸域名支持查询和片段`() {
        assertEquals("https://jd.com?id=123#detail", LinkExtractor.extractFirst("jd.com?id=123#detail").firstUrl)
    }

    @Test fun `裸域名保留根域名点后的路径并忽略句末点`() {
        assertEquals("https://example.com", LinkExtractor.extractFirst("访问 example.com.").firstUrl)
        assertEquals("https://example.com/path", LinkExtractor.extractFirst("example.com./path").firstUrl)
        assertNull(LinkExtractor.extractFirst("example.com..invalid").firstUrl)
    }

    @Test fun `自定义国际域名规则匹配规范化链接`() {
        val custom = rule().copy(pattern = "例子.测试")
        assertTrue(RuleEngine.matches(custom, "https://xn--fsqu00a.xn--0zwm56d/商品"))
        assertTrue(RuleEngine.matches(custom.copy(matchType = MatchType.EXACT_HOST),
            "https://xn--fsqu00a.xn--0zwm56d/商品"))
    }

    @Test fun `长度上限截断滴滴口令时不能把前缀当完整口令`() {
        val prefix = "%[滴滴出行]%~AbC123]?2[w~"
        val raw = prefix + "abcdef1234567890"
        assertNull(KoulingExtractor.extractFirst(raw, prefix.length + 8))
        assertEquals(raw, KoulingExtractor.extractFirst(raw, raw.length)?.token)
        assertEquals(raw, KoulingExtractor.extractFirst("$raw 后续文案", raw.length)?.token)
    }

    @Test fun `保留URL路径中配对的括号`() {
        assertEquals("https://example.com/wiki/Function_(math)",
            LinkExtractor.extractFirst("(https://example.com/wiki/Function_(math))").firstUrl)
    }

    @Test fun `中文引号和零宽字符作为边界`() {
        assertEquals("https://jd.com/123", LinkExtractor.extractFirst("“https://jd.com/123”").firstUrl)
        assertEquals("https://jd.com/123", LinkExtractor.extractFirst("https://jd.com/123\u200B复制").firstUrl)
    }

    @Test fun `含凭据URL不识别`() {
        assertNull(LinkExtractor.extractFirst("https://jd.com@evil.com/item").firstUrl)
    }

    @Test fun `非法端口URL不识别`() {
        assertNull(LinkExtractor.extractFirst("https://jd.com:99999/item").firstUrl)
    }

    @Test fun `不截取非法裸域名的合法前缀`() {
        for (text in listOf("example.com:abc", "example.co1", "example.com%2Eevil")) {
            assertNull(text, LinkExtractor.extractFirst(text).firstUrl)
        }
    }

    @Test fun `国际域名转IDNA保留中文路径`() {
        assertEquals("https://xn--fsqu00a.xn--0zwm56d/商品",
            LinkExtractor.extractFirst("https://例子.测试/商品").firstUrl)
    }

    @Test fun `IPv6链接保持完整`() {
        assertEquals("http://[::1]:8080/a", LinkExtractor.extractFirst("http://[::1]:8080/a").firstUrl)
    }

    @Test fun `超长文案中的完整链接仍可识别`() {
        assertEquals("https://jd.com/1", LinkExtractor.extractFirst("https://jd.com/1 " + "x".repeat(5000), 40).firstUrl)
    }

    @Test fun `长度上限非正数时安全返回`() {
        assertNull(LinkExtractor.extractFirst("https://jd.com", -1).firstUrl)
        assertNull(KoulingExtractor.extractFirst("￥AbC123￥", -1))
    }

    @Test fun `短数字日期与短编号不是口令`() {
        for (text in listOf("(123456) 打开京东", "(2026-11-12) 淘宝", "(A1) 淘宝", "(版本123456) 淘宝")) {
            assertNull(text, KoulingExtractor.extractFirst(text))
        }
    }

    @Test fun `混合货币符号与混合括号可识别`() {
        assertEquals("AbC123", KoulingExtractor.extractFirst("¥AbC123₴ 打开淘宝")?.token)
        assertEquals("AbC123", KoulingExtractor.extractFirst("（AbC123) 打开京东")?.token)
    }

    @Test fun `返回trim后的口令并识别JD前缀`() {
        val result = KoulingExtractor.extractFirst("( 16:/AbC123 )")
        assertEquals("16:/AbC123", result?.token)
        assertEquals(Platform.JD, result?.platformHint)
    }

    @Test fun `其他数字前缀不冒充JD口令`() {
        assertNull(KoulingExtractor.extractFirst("(99:/AbC123)"))
    }

    @Test fun `前置关键词距离按词尾计算`() {
        assertEquals(Platform.TAOBAO, KoulingExtractor.extractFirst("淘宝￥AbC123￥xx京东")?.platformHint)
    }

    @Test fun `等距时后文优先跨关键词比较`() {
        assertEquals(Platform.TAOBAO, KoulingExtractor.extractFirst("京东￥AbC123￥淘宝")?.platformHint)
    }

    @Test fun `通用浏览器规则不受失败回退开关阻止`() {
        val launcher = FakeIntentLauncher()
        assertEquals(OpenOutcome.Launched("BROWSER"), OpenStrategy(launcher, false)
            .open("https://example.com", RuleEngine.BUILTIN_GENERIC))
    }

    @Test fun `浏览器异常转失败且不重试`() {
        var calls = 0
        val launcher = object : FakeIntentLauncher() {
            override fun launchBrowser(url: String): Boolean { calls++; throw SecurityException() }
        }
        assertTrue(OpenStrategy(launcher).open("https://jd.com", rule()) is OpenOutcome.Failed)
        assertEquals(1, calls)
    }

    @Test fun `指定包名打开原始HTTP链接`() {
        var target: String? = null
        val launcher = object : FakeIntentLauncher() {
            override fun launchUrlInApp(url: String, packageName: String): Boolean {
                target = packageName
                return true
            }
        }.apply { installed = true }
        assertEquals(OpenOutcome.Launched("APP_LINK"), OpenStrategy(launcher).open("https://jd.com", rule()))
        assertEquals(rule().targetPackage, target)
    }

    @Test fun `深链限定目标包名`() {
        var target: String? = null
        val launcher = object : FakeIntentLauncher() {
            override fun launchScheme(uri: String, packageName: String): Boolean { target = packageName; return true }
        }.apply { installed = true }
        OpenStrategy(launcher).open("https://jd.com", rule().copy(schemeTemplate = "jd://?url={url}"))
        assertEquals(rule().targetPackage, target)
    }

    @Test fun `非HTTP链接不能打开浏览器`() {
        val launcher = FakeIntentLauncher()
        assertTrue(OpenStrategy(launcher).open("javascript:alert(1)", rule()) is OpenOutcome.Failed)
        assertFalse(launcher.browserLaunched)
    }

    @Test fun `禁用通用规则后保留禁用状态以停止兜底通知`() {
        val generic = RuleEngine.BUILTIN_GENERIC.copy(id = 123, enabled = false)
        assertEquals(generic, RuleEngine.match("https://example.com", listOf(rule(), generic)))
    }
}
