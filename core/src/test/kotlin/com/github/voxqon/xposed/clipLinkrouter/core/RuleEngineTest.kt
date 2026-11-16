package com.github.voxqon.xposed.clipLinkrouter.core.engine

import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private fun rule(
        name: String,
        matchType: MatchType,
        pattern: String,
        platform: Platform = Platform.GENERIC,
        type: RuleType = RuleType.CUSTOM,
        priority: Int = 0,
        enabled: Boolean = true,
        scheme: String? = null,
        targetPkg: String? = null,
    ) = RuleSpec(
        name = name,
        type = type,
        matchType = matchType,
        pattern = pattern,
        platform = platform,
        schemeTemplate = scheme,
        targetPackage = targetPkg,
        priority = priority,
        enabled = enabled,
    )

    private fun assertHit(expected: RuleSpec, url: String, rules: List<RuleSpec>) {
        assertEquals(expected, RuleEngine.match(url, rules))
    }

    @Test
    fun `优先级高者先命中`() {
        val low = rule("低", MatchType.KEYWORD, "jd.com", priority = 1)
        val high = rule("高", MatchType.SUFFIX_HOST, "jd.com", priority = 5)
        val url = "https://item.jd.com/1"
        assertHit(high, url, listOf(low, high))
    }

    @Test
    fun `优先级相同时自定义覆盖内置`() {
        val builtin = rule("内置", MatchType.SUFFIX_HOST, "jd.com", type = RuleType.BUILTIN, priority = 3)
        val custom = rule("自定义", MatchType.KEYWORD, "jd.com", priority = 3)
        val url = "https://item.jd.com/1"
        assertHit(custom, url, listOf(builtin, custom))
    }

    @Test
    fun `禁用规则不命中`() {
        val disabled = rule("禁用", MatchType.SUFFIX_HOST, "jd.com", enabled = false)
        val url = "https://item.jd.com/1"
        assertEquals(RuleEngine.BUILTIN_GENERIC, RuleEngine.match(url, listOf(disabled)))
    }

    @Test
    fun `正则非法时降级为不命中该规则`() {
        val bad = rule("坏正则", MatchType.REGEX, "(unclosed", priority = 9)
        val url = "https://item.jd.com/1"
        assertEquals(RuleEngine.BUILTIN_GENERIC, RuleEngine.match(url, listOf(bad)))
    }

    @Test
    fun `正则命中`() {
        val r = rule("正则", MatchType.REGEX, "item\\.jd\\.com/\\d+")
        val url = "https://item.jd.com/123"
        assertHit(r, url, listOf(r))
    }

    @Test
    fun `关键词命中`() {
        val r = rule("关键词", MatchType.KEYWORD, "jd.com")
        assertHit(r, "https://item.jd.com/1", listOf(r))
        assertFalse(RuleEngine.matches(r, "https://taobao.com/1"))
    }

    @Test
    fun `精确主机命中`() {
        val r = rule("精确", MatchType.EXACT_HOST, "item.jd.com")
        assertHit(r, "https://item.jd.com/x", listOf(r))
        assertTrue(RuleEngine.matches(r, "https://www.item.jd.com/x")) // www. 前缀规范化后视为同一主机
    }

    @Test
    fun `未命中时兜底通用规则`() {
        val r = rule("淘宝", MatchType.SUFFIX_HOST, "taobao.com")
        assertEquals(RuleEngine.BUILTIN_GENERIC, RuleEngine.match("https://example.com/1", listOf(r)))
        assertTrue(RuleEngine.BUILTIN_GENERIC.enabled)
        assertEquals(Platform.GENERIC, RuleEngine.BUILTIN_GENERIC.platform)
    }

    // ---- 口令规则 ----

    @Test
    fun `口令规则按平台提示命中`() {
        val kouling = com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingResult(
            token = "AbC123", openChar = '￥', platformHint = Platform.TAOBAO, startIndex = 0, endIndex = 9,
        )
        val tb = rule("淘口令", MatchType.KOULING, "", platform = Platform.TAOBAO, priority = 30)
        val jd = rule("京口令", MatchType.KOULING, "", platform = Platform.JD, priority = 30)
        assertEquals(tb, RuleEngine.matchKouling(kouling, listOf(jd, tb)))
    }

    @Test
    fun `口令规则同优先级自定义覆盖内置`() {
        val kouling = com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingResult(
            token = "AbC123", openChar = '￥', platformHint = Platform.JD, startIndex = 0, endIndex = 9,
        )
        val builtin = rule("内置京口令", MatchType.KOULING, "", platform = Platform.JD,
            type = RuleType.BUILTIN, priority = 30)
        val custom = rule("自定义京口令", MatchType.KOULING, "", platform = Platform.JD, priority = 30)
        assertEquals(custom, RuleEngine.matchKouling(kouling, listOf(builtin, custom)))
    }

    @Test
    fun `禁用的口令规则不命中`() {
        val kouling = com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingResult(
            token = "AbC123", openChar = '￥', platformHint = Platform.TAOBAO, startIndex = 0, endIndex = 9,
        )
        val tb = rule("淘口令", MatchType.KOULING, "", platform = Platform.TAOBAO, enabled = false)
        assertEquals(null, RuleEngine.matchKouling(kouling, listOf(tb)))
    }

    @Test
    fun `口令pattern作为正则过滤`() {
        val kouling = com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingResult(
            token = "AbC123", openChar = '￥', platformHint = Platform.TAOBAO, startIndex = 0, endIndex = 9,
        )
        val filtered = rule("仅数字口令", MatchType.KOULING, "^\\d+$", platform = Platform.TAOBAO)
        assertEquals(null, RuleEngine.matchKouling(kouling, listOf(filtered)))
        val all = rule("全部口令", MatchType.KOULING, "", platform = Platform.TAOBAO, priority = 10)
        assertEquals(all, RuleEngine.matchKouling(kouling, listOf(filtered, all)))
    }

    @Test
    fun `KOULING规则不参与URL匹配`() {
        val koulingRule = rule("口令", MatchType.KOULING, "", platform = Platform.JD, priority = 99)
        assertEquals(RuleEngine.BUILTIN_GENERIC, RuleEngine.match("https://item.jd.com/1", listOf(koulingRule)))
    }
}