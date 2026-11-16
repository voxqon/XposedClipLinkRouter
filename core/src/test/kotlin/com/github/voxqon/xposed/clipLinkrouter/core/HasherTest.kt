
package com.github.voxqon.xposed.clipLinkrouter.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HasherTest {

    @Test
    fun `sha256Hex 输出 64 位小写十六进制`() {
        val h = Hasher.sha256Hex("abc")
        assertEquals(64, h.length)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
    }

    @Test
    fun `重复调用结果一致`() {
        repeat(50) {
            assertEquals(Hasher.sha256Hex("hello"), Hasher.sha256Hex("hello"))
        }
    }

    @Test
    fun `不同输入哈希不同`() {
        assertNotEquals(Hasher.sha256Hex("a"), Hasher.sha256Hex("b"))
    }

    @Test
    fun `shortHash 长度截断`() {
        assertEquals(16, Hasher.shortHash("abc", 16).length)
        assertEquals(4, Hasher.shortHash("abc", 4).length)
        assertEquals("", Hasher.shortHash("abc", 0))
    }

    @Test
    fun `空输入不抛异常`() {
        assertEquals(64, Hasher.sha256Hex("").length)
    }
}
