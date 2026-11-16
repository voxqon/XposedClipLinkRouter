package com.github.voxqon.xposed.clipLinkrouter.core.extract

import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform

/** 有界扫描配对口令。短数字、日期和普通中文括号不会生成口令。 */
object KoulingExtractor {
    const val MAX_TOKEN_LENGTH = 64
    private const val CONTEXT_WINDOW = 30
    private const val CURRENCIES = "￥¥₴¢$"
    // 滴滴分享文案使用固定标记及两段代码，不适用于普通配对分隔符扫描。
    private val didiPattern = Regex("""%\[滴滴出行\]%\\?~[A-Za-z0-9]{6,32}\]\?2\[w\\?~[A-Fa-f0-9]{8,64}(?![A-Za-z0-9])""")
    private val keywords = listOf(
        "京东" to Platform.JD, "jd.com" to Platform.JD, "京喜" to Platform.JD,
        "天猫" to Platform.TMALL, "tmall" to Platform.TMALL, "聚划算" to Platform.TMALL,
        "淘宝" to Platform.TAOBAO, "taobao" to Platform.TAOBAO,
        "淘特" to Platform.TAOBAO, "一淘" to Platform.TAOBAO,
    )

    fun extractFirst(raw: String, maxLength: Int = 2000): KoulingResult? {
        if (raw.isEmpty() || maxLength <= 0) return null
        val source = raw.take(maxLength)
        val didi = didiPattern.find(source)?.takeUnless { match ->
            // 滴滴末段是变长代码，扫描边界不能把未读取的后半段当成代码结束。
            match.range.last == source.lastIndex && raw.length > source.length &&
                raw[source.length].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        }
        var generic: KoulingResult? = null
        var i = 0
        while (i < source.length) {
            if (didi != null && i >= didi.range.first) {
                return KoulingResult(didi.value, '%', Platform.DIDI, didi.range.first, didi.range.last + 1)
            }
            val open = source[i]
            val closers = when (open) {
                in CURRENCIES -> CURRENCIES
                '!', '！' -> "!！"
                '(', '（' -> ")）"
                '【' -> "】"
                else -> null
            }
            if (closers != null) {
                var j = i + 1
                while (j < source.length && j - i - 1 <= MAX_TOKEN_LENGTH) {
                    val ch = source[j]
                    if (ch in closers) {
                        val token = source.substring(i + 1, j).trim()
                        if (plausible(token)) {
                            val platform = detectPlatform(source, token, i, j + 1)
                            val result = KoulingResult(token, open, platform, i, j + 1)
                            if (platform != Platform.GENERIC) return result
                            // 强币符形式可独立识别淘口令，但继续寻找后续有明确平台上下文的口令。
                            if (generic == null) generic = if (open in "￥¥₴¢")
                                result.copy(platformHint = Platform.TAOBAO) else result
                        }
                        i = j
                        break
                    }
                    if (ch in "￥¥₴¢$!！（）()【】\n\r") break
                    j++
                }
            }
            i++
        }
        return generic
    }

    private fun plausible(token: String): Boolean {
        val body = token.removePrefix("16:/")
        if (body.length !in 6..MAX_TOKEN_LENGTH || token.length > MAX_TOKEN_LENGTH) return false
        var letter = false
        var digit = false
        for (ch in body) {
            when (ch) {
                in 'a'..'z', in 'A'..'Z' -> letter = true
                in '0'..'9' -> digit = true
                '-', '_' -> Unit
                else -> return false
            }
        }
        return letter && digit
    }

    private fun detectPlatform(source: String, token: String, start: Int, end: Int): Platform {
        if (token.startsWith("16:/")) return Platform.JD
        val from = (start - CONTEXT_WINDOW).coerceAtLeast(0)
        val to = (end + CONTEXT_WINDOW).coerceAtMost(source.length)
        var best = Platform.GENERIC
        var distance = Int.MAX_VALUE
        var bestAfter = false
        for ((keyword, platform) in keywords) {
            val after = source.indexOf(keyword, end, ignoreCase = true)
            if (after >= end && after + keyword.length <= to) {
                val d = after - end
                if (d < distance || (d == distance && !bestAfter)) {
                    distance = d
                    best = platform
                    bestAfter = true
                }
            }
            val before = source.lastIndexOf(keyword, start - keyword.length, ignoreCase = true)
            if (before >= from && before + keyword.length <= start) {
                val d = start - before - keyword.length
                if (d < distance) {
                    distance = d
                    best = platform
                    bestAfter = false
                }
            }
        }
        return best
    }
}

data class KoulingResult(
    val token: String,
    val openChar: Char,
    val platformHint: Platform,
    val startIndex: Int,
    val endIndex: Int,
)
