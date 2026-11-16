package com.github.voxqon.xposed.clipLinkrouter.core.extract

import java.security.MessageDigest

/**
 * SHA-256 摘要工具：正常返回完整 64 位十六进制，异常时返回空串，不抛给调用方。
 *
 * MessageDigest 不是线程安全的，使用 ThreadLocal 复用摘要实例。
 */
object Hasher {

    private val DIGEST = ThreadLocal.withInitial {
        runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(input: String): String {
        val md = DIGEST.get() ?: return ""
        return runCatching {
            md.reset()
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val out = CharArray(bytes.size * 2)
            var i = 0
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out[i++] = HEX[v ushr 4]
                out[i++] = HEX[v and 0x0F]
            }
            String(out)
        }.getOrDefault("")
    }

    fun shortHash(input: String, length: Int = 16): String {
        if (length <= 0) return ""
        val full = sha256Hex(input)
        return if (full.length >= length) full.substring(0, length) else full
    }
}
