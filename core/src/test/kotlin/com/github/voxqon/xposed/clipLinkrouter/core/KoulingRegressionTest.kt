package com.github.voxqon.xposed.clipLinkrouter.core.extract

import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 针对本次修复点的回归测试。 */
class KoulingRegressionTest {

    @Test
    fun `GENERIC 口令在前但后面口令带近距平台信号时优先返回带信号的`() {
        // 修复前 bug：第一个 GENERIC 口令会被直接 return，错过后面带平台信号的口令。
        // 两个口令距离拉开后，「打开淘宝」的引导语只落在第一个口令的窗口内。
        val filler = "x".repeat(40)
        val r = KoulingExtractor.extractFirst("￥AbC123￥ 打开淘宝" + filler + "(X9abc12) 复制打开京东")
        // 第一个口令带「打开淘宝」信号，距离更近，先命中并立即返回
        assertEquals("AbC123", r?.token)
        assertEquals(Platform.TAOBAO, r?.platformHint)
    }

    @Test
    fun `纯 GENERIC 开头时不会提前返回`() {
        // 核心回归：第一个口令完全无信号，继续扫描找到带信号的
        val filler = "x".repeat(40)
        val r = KoulingExtractor.extractFirst("￥AbC123￥" + filler + "(X9abc12) 复制打开京东")
        assertEquals("X9abc12", r?.token)
        assertEquals(Platform.JD, r?.platformHint)
    }

    @Test
    fun `无上下文时返回首个强币符口令`() {
        val r = KoulingExtractor.extractFirst("￥First1￥ 然后 (Second2)")
        assertEquals("First1", r?.token)
        assertEquals(Platform.TAOBAO, r?.platformHint)
    }

    @Test
    fun `全角方括号口令`() {
        val r = KoulingExtractor.extractFirst("【Abc123xyz】复制打开京东")
        assertEquals("Abc123xyz", r?.token)
        assertEquals(Platform.JD, r?.platformHint)
    }

    @Test
    fun `京东16前缀带配对分隔符`() {
        val r = KoulingExtractor.extractFirst("(16:/X9ABCdef) 打开京东")
        assertEquals("16:/X9ABCdef", r?.token)
        assertEquals(Platform.JD, r?.platformHint)
    }

    @Test
    fun `书名号不误判为口令`() {
        assertNull(KoulingExtractor.extractFirst("推荐《三体》这本书不错"))
    }

    @Test
    fun `含下划线连字符的口令`() {
        val r = KoulingExtractor.extractFirst("￥Ab_C-123￥ 打开淘宝")
        assertEquals("Ab_C-123", r?.token)
        assertEquals(Platform.TAOBAO, r?.platformHint)
    }
}
