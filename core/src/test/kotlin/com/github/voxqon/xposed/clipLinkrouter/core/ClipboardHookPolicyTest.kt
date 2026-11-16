package com.github.voxqon.xposed.clipLinkrouter.core

import com.github.voxqon.xposed.clipLinkrouter.core.hook.ClipboardHookPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHookPolicyTest {
    private val modulePackage = "com.github.voxqon.xposed.clipLinkrouter"
    private val readOperation = 29
    private val signature = listOf("int", "java.lang.String", "java.lang.String", "int", "int", "int")

    private fun request(
        packageName: String = modulePackage,
        operation: Int = readOperation,
        uid: Int = 10_123,
        userId: Int = 0,
        deviceId: Int = 0,
    ): Array<Any?> = arrayOf(operation, packageName, null, uid, userId, deviceId)

    private fun allowedUid(args: Array<out Any?>) =
        ClipboardHookPolicy.backgroundUid(args, readOperation, modulePackage)

    @Test fun `支持Android15和16的两个访问重载`() {
        assertTrue(ClipboardHookPolicy.supportsSignature(signature))
        assertTrue(ClipboardHookPolicy.supportsSignature(signature + "boolean"))
        assertEquals(10_123, allowedUid(request()))
        assertEquals(10_123, allowedUid(request() + true))
        assertEquals(10_123, allowedUid(request() + false))
    }

    @Test fun `覆盖HyperOS直接调用的八参数和九参数重载`() {
        assertTrue(ClipboardHookPolicy.supportsSignature(signature + listOf("boolean", "boolean")))
        assertTrue(ClipboardHookPolicy.supportsSignature(signature + List(3) { "boolean" }))
        for (note in listOf(false, true)) {
            for (ignoreAsk in listOf(false, true)) {
                assertEquals(10_123, allowedUid((request().toList() + listOf(note, ignoreAsk)).toTypedArray()))
                for (ignoreDeny in listOf(false, true)) {
                    assertEquals(10_123, allowedUid((request().toList() + listOf(note, ignoreAsk, ignoreDeny)).toTypedArray()))
                }
            }
        }
    }

    @Test fun `不对未知系统签名套用参数位置`() {
        assertFalse(ClipboardHookPolicy.supportsSignature(signature.dropLast(1)))
        assertFalse(ClipboardHookPolicy.supportsSignature(signature + "int"))
        assertFalse(ClipboardHookPolicy.supportsSignature(signature + listOf("boolean", "int")))
        assertFalse(ClipboardHookPolicy.supportsSignature(signature + List(4) { "boolean" }))
        assertFalse(ClipboardHookPolicy.supportsSignature(listOf("java.lang.String") + signature.drop(1)))
    }

    @Test fun `其他应用和相似包名不能获得例外`() {
        for (name in listOf("com.taobao.taobao", "com.jingdong.app.mall", "com.github.voxqon.xposed.clipLinkrouter.fake", "android")) {
            assertNull(name, allowedUid(request(packageName = name)))
        }
    }

    @Test fun `只放宽读取操作不处理写入或其他操作`() {
        assertNull(allowedUid(request(operation = 30)))
        assertNull(allowedUid(request(operation = -1)))
    }

    @Test fun `不跨用户或虚拟设备放宽访问`() {
        assertNull(allowedUid(request(userId = 10)))
        assertNull(allowedUid(request(deviceId = -1)))
        assertNull(allowedUid(request(deviceId = 1)))
        assertEquals(1_010_123, allowedUid(request(uid = 1_010_123, userId = 10)))
        assertNull(allowedUid(request(uid = 1_010_123, userId = 0)))
    }

    @Test fun `无效UID和系统UID不能获得应用例外`() {
        assertNull(allowedUid(request(uid = -1)))
        assertNull(allowedUid(request(uid = 1000)))
        assertNull(allowedUid(request(uid = 1_001_000, userId = 10)))
    }

    @Test fun `允许合法归因标签且拒绝参数类型错位`() {
        assertEquals(10_123, allowedUid(request().also { it[2] = "clipboard" }))
        assertNull(allowedUid(request().also { it[2] = 123 }))
        assertNull(allowedUid(request().also { it[3] = "10123" }))
        assertNull(allowedUid(request().also { it[4] = null }))
        assertNull(allowedUid(request().also { it[5] = 0L }))
        assertNull(allowedUid(request() + "false"))
        assertNull(allowedUid((request().toList() + listOf(true, 1)).toTypedArray()))
        assertNull(allowedUid((request().toList() + listOf(true, false, null)).toTypedArray()))
        assertNull(allowedUid((request().toList() + List(4) { true }).toTypedArray()))
        assertNull(allowedUid(emptyArray()))
    }
}
