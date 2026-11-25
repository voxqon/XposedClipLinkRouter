package com.github.voxqon.xposed.clipLinkrouter.core.extract

import java.net.IDN
import java.net.URI
import java.util.Locale

/** 有界扫描 HTTP(S) 与裸域名；跳过邮箱、其他协议、凭据 URL 和被截断的链接。 */
object LinkExtractor {
    private const val STOP = "\\s<>\"'`\\u200B-\\u200F\\uFEFF，。；！？、（）《》【】“”‘’"
    private val candidates = Regex(
        "https?://[^$STOP]+|" +
            "(?<![\\p{L}\\p{N}_@./:+-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
            "[a-z]{2,63}\\.?(?::[0-9]{1,5})?(?![a-z0-9_.:%@-])(?:[/?#][^$STOP]*)?",
        RegexOption.IGNORE_CASE,
    )
    private val scheme = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun extractFirst(raw: String, maxLength: Int = 2000): ExtractResult {
        if (raw.isEmpty() || maxLength <= 0) return ExtractResult(null)
        val source = raw.take(maxLength)
        for (match in candidates.findAll(source)) {
            // 不把长度上限截断的签名、参数或路径当成完整链接打开。
            if (raw.length > source.length && match.range.last == source.lastIndex &&
                !isBoundary(raw[source.length])) continue
            val url = normalize(match.value) ?: continue
            return ExtractResult(url)
        }
        return ExtractResult(null)
    }

    private fun isBoundary(ch: Char): Boolean = ch.isWhitespace() || ch in
        "<>\"'`，。；！？、（）《》【】“”‘’\u200B\u200C\u200D\u200E\u200F\uFEFF"

    private fun normalize(candidate: String): String? = runCatching {
        var text = candidate.trimEnd('.', ',', ';', ':')
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            while (text.endsWith(close) && text.count { it == close } > text.count { it == open }) {
                text = text.dropLast(1)
            }
        }
        if (!scheme.containsMatchIn(text)) text = "https://$text"
        val uri = URI(text)
        if (!uri.rawUserInfo.isNullOrEmpty() || uri.rawAuthority.isNullOrEmpty() || '@' in uri.rawAuthority) return null
        val authority = uri.rawAuthority
        // URI.host 不接受 Unicode 域名，先转为 IDNA；IPv6 地址保留方括号。
        val asciiAuthority = if (authority.startsWith('[')) authority else {
            val host = authority.substringBefore(':').trimEnd('.')
            val port = authority.substringAfter(':', "")
            val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            ascii + if (':' in authority) ":$port" else ""
        }
        val normalized = "${uri.scheme.lowercase(Locale.ROOT)}://$asciiAuthority" +
            text.substring(text.indexOf("://") + 3 + authority.length)
        if (isValidHttpUrl(normalized)) normalized else null
    }.getOrNull()

    fun isValidHttpUrl(text: String): Boolean = runCatching {
        val uri = URI(text)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535
    }.getOrDefault(false)

    fun hostOf(url: String): String = runCatching {
        val uri = URI(url)
        if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
            uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: "" else ""
    }.getOrDefault("")

}

data class ExtractResult(val firstUrl: String?)
