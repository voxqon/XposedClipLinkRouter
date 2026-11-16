package com.github.voxqon.xposed.clipLinkrouter.core.extract

import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoulingExtractorTest {

    /** 用字符码避开 Kotlin 字符串模板对 $ 的吞并 */
    private val dollar: Char = 36.toChar()

    @Test
    fun `淘宝口令带上下文`() {
        val r = KoulingExtractor.extractFirst("复制这条信息￥ABC123￥打开淘宝")
        assertEquals("ABC123", r?.token)
        assertEquals(Platform.TAOBAO, r?.platformHint)
    }

    @Test
    fun `不对称淘口令分隔符`() {
        val r = KoulingExtractor.extractFirst("₴XYZ789￥ 手机淘宝")
        assertEquals("XYZ789", r?.token)
        assertEquals(Platform.TAOBAO, r?.platformHint)
    }

    @Test
    fun `美元分隔符口令`() {
        val text = "打开" + dollar + "Taobao2024" + dollar + "去淘宝看看"
        val r = KoulingExtractor.extractFirst(text)
        assertEquals("Taobao2024", r?.token)
        assertEquals(Platform.TAOBAO, r?.platformHint)
    }

    @Test
    fun `京东口令带16前缀但无配对分隔符`() {
        val r = KoulingExtractor.extractFirst("16:/X9ABCdef(京东) ", maxLength = 2000)
        // (京东) 是纯中文不算口令，16:/ 前缀本身不是口令，应返回 null
        assertNull(r)
    }

    @Test
    fun `京东括号口令`() {
        val r = KoulingExtractor.extractFirst("(X9ABCdef12) 复制打开京东")
        assertEquals("X9ABCdef12", r?.token)
        assertEquals(Platform.JD, r?.platformHint)
    }

    @Test
    fun `全角括号京东口令`() {
        val r = KoulingExtractor.extractFirst("（A1b2C3d4E5）复制整段打开京东APP")
        assertEquals("A1b2C3d4E5", r?.token)
        assertEquals(Platform.JD, r?.platformHint)
    }

    @Test
    fun `纯中文括号内容不算口令`() {
        assertNull(KoulingExtractor.extractFirst("【京东】https://item.jd.com/1.html（给我看看）"))
    }

    @Test
    fun `纯中文方括号不算口令`() {
        assertNull(KoulingExtractor.extractFirst("【京东】好物推荐"))
    }

    @Test
    fun `天猫上下文`() {
        val r = KoulingExtractor.extractFirst("￥TMALL88￥ 打开手机天猫")
        assertEquals("TMALL88", r?.token)
        assertEquals(Platform.TMALL, r?.platformHint)
    }

    @Test
    fun `独立人民币口令默认淘宝`() {
        val r = KoulingExtractor.extractFirst("￥AbC123xY￥")
        assertEquals("AbC123xY", r?.token)
        assertEquals(Platform.TAOBAO, r?.platformHint)
    }

    @Test
    fun `滴滴分享口令识别完整标记并保留位置`() {
        val text = "18:/ 💰~%@复制@关注滴滴了解更多活动，|消|息，❤️打开%[滴滴出行]%~AbC123]?2[w~abcdef1234567890"
        val result = KoulingExtractor.extractFirst(text)!!
        assertEquals(Platform.DIDI, result.platformHint)
        assertEquals("%[滴滴出行]%~AbC123]?2[w~abcdef1234567890", result.token)
        assertEquals(result.token, text.substring(result.startIndex, result.endIndex))
        assertEquals(result.token, KoulingExtractor.extractFirst(text.replace("~", "\\~"))?.token?.replace("\\~", "~"))
    }

    @Test
    fun `滴滴提示或残缺标记不应识别为口令`() {
        assertNull(KoulingExtractor.extractFirst("打开滴滴出行了解活动"))
        assertNull(KoulingExtractor.extractFirst("%[滴滴出行]%~AbC123]?2[w~123"))
        assertNull(KoulingExtractor.extractFirst("%[其他应用]%~AbC123]?2[w~abcdef1234567890"))
    }

    @Test
    fun `无口令返回null`() {
        assertNull(KoulingExtractor.extractFirst("今天天气不错，去公园走走"))
    }

    @Test
    fun `超长文本截断`() {
        val long = "￥AbC123￥" + "x".repeat(3000)
        val r = KoulingExtractor.extractFirst(long, maxLength = 20)
        assertEquals("AbC123", r?.token)
    }

    @Test
    fun `取第一个有效口令`() {
        val r = KoulingExtractor.extractFirst("￥First1￥ 然后 ￥Second2￥ 打开淘宝")
        assertEquals("First1", r?.token)
    }
}
