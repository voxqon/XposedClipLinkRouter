package com.github.voxqon.xposed.clipLinkrouter.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkExtractorTest {

    @Test
    fun `京东长链`() {
        val r = LinkExtractor.extractFirst("复制 https://item.jd.com/100012043978.html?cu=true&utm_source=app 看看")
        assertEquals("https://item.jd.com/100012043978.html?cu=true&utm_source=app", r.firstUrl)
    }

    @Test
    fun `u点jd短链`() {
        val r = LinkExtractor.extractFirst("https://u.jd.com/abc123")
        assertEquals("https://u.jd.com/abc123", r.firstUrl)
    }

    @Test
    fun `3点cn短链`() {
        val r = LinkExtractor.extractFirst("3.cn/Ab12Cd")
        assertEquals("https://3.cn/Ab12Cd", r.firstUrl)
    }

    @Test
    fun `淘宝短链`() {
        val r = LinkExtractor.extractFirst("https://m.tb.cn/h.fAbCdEf?tk=abc")
        assertEquals("https://m.tb.cn/h.fAbCdEf?tk=abc", r.firstUrl)
    }

    @Test
    fun `天猫链接`() {
        val r = LinkExtractor.extractFirst("https://detail.tmall.com/item.htm?id=123")
        assertEquals("https://detail.tmall.com/item.htm?id=123", r.firstUrl)
    }

    @Test
    fun `普通链接`() {
        val r = LinkExtractor.extractFirst("参考 https://developer.android.com/guide 文档")
        assertEquals("https://developer.android.com/guide", r.firstUrl)
    }

    @Test
    fun `中文包裹链接`() {
        val r = LinkExtractor.extractFirst("【京东】https://item.jd.com/123.html（给我看看）")
        assertEquals("https://item.jd.com/123.html", r.firstUrl)
    }

    @Test
    fun `尾随中文标点`() {
        val r = LinkExtractor.extractFirst("https://item.jd.com/123.html，好用")
        assertEquals("https://item.jd.com/123.html", r.firstUrl)
    }

    @Test
    fun `裸域名自动补协议`() {
        val r = LinkExtractor.extractFirst("item.jd.com/1000.html")
        assertEquals("https://item.jd.com/1000.html", r.firstUrl)
    }

    @Test
    fun `非链接文本`() {
        val r = LinkExtractor.extractFirst("今天天气不错，去公园走走")
        assertNull(r.firstUrl)
    }

    @Test
    fun `取第一个有效链接`() {
        val r = LinkExtractor.extractFirst("https://a.com/1 https://b.com/2")
        assertEquals("https://a.com/1", r.firstUrl)
    }

    @Test
    fun `超出上限的链接不当作完整链接打开`() {
        val long = "https://example.com/" + "x".repeat(3000)
        val r = LinkExtractor.extractFirst(long, maxLength = 2000)
        assertNull(r.firstUrl)
    }
}
