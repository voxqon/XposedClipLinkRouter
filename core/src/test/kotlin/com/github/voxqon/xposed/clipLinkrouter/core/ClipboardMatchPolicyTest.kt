package com.github.voxqon.xposed.clipLinkrouter.core

import com.github.voxqon.xposed.clipLinkrouter.core.hook.ClipboardMatchPolicy
import com.github.voxqon.xposed.clipLinkrouter.core.hook.ClipboardMatchPolicy.Decision
import org.junit.Assert.*
import org.junit.Test

class ClipboardMatchPolicyTest {
    private val phoneLink = "com.microsoft.appmanager"
    private val localApp = "com.jingdong.app.mall"
    private val timestamp = 1_794_549_008_150L
    private val boot = 38

    private fun handled(
        policy: ClipboardMatchPolicy, key: String = "link", clip: Long = timestamp,
        source: String? = localApp, now: Long = 1_000L, bootCount: Int = boot,
    ): Decision {
        val decision = policy.decide(key, clip, source, now, bootCount)
        policy.recordHandled(key, clip, now, bootCount, decision)
        return decision
    }

    @Test fun `首次从Windows复制的新内容正常通知`() {
        assertEquals(Decision.NOTIFY, handled(ClipboardMatchPolicy(), source = phoneLink))
    }

    @Test fun `复现二十秒后Windows回写相同链接不重复弹窗`() {
        val policy = ClipboardMatchPolicy()
        assertEquals(Decision.NOTIFY, handled(policy))
        assertEquals(Decision.SYNC_ECHO, handled(policy, clip = timestamp + 20_000,
            source = phoneLink, now = 21_000))
    }

    @Test fun `三个微软同步组件都能过滤回写且不匹配相似包名`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        for (source in listOf(phoneLink, "com.microsoft.deviceintegrationservice", "com.microsoftsdk.crossdeviceservicebroker")) {
            assertEquals(source, Decision.SYNC_ECHO,
                policy.decide("link", timestamp + 20_000, source, 21_000, boot))
        }
        assertEquals(Decision.NOTIFY,
            policy.decide("link", timestamp + 20_000, "$phoneLink.fake", 21_000, boot))
    }

    @Test fun `用户主动重复复制相同链接仍然通知`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        assertEquals(Decision.NOTIFY, handled(policy, clip = timestamp + 20_000, now = 21_000))
    }

    @Test fun `解锁回查和多个广播读取同一次写入只通知一次`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        assertEquals(Decision.SAME_CLIPBOARD_EVENT,
            handled(policy, source = null, now = 600_000))
        assertEquals(Decision.SAME_CLIPBOARD_EVENT,
            handled(policy, source = phoneLink, now = 600_100))
    }

    @Test fun `旧Hook没有来源时短窗口仍能过滤同步回写`() {
        val policy = ClipboardMatchPolicy()
        handled(policy, source = null)
        assertEquals(Decision.LEGACY_ECHO,
            handled(policy, clip = timestamp + 20_000, source = null, now = 21_000))
    }

    @Test fun `窗口届满后再次从Windows复制相同内容正常通知`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        assertEquals(Decision.NOTIFY,
            handled(policy, clip = timestamp + 60_000, source = phoneLink, now = 61_000))
    }

    @Test fun `持续同步回写不会把去重窗口无限延长`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        assertEquals(Decision.SYNC_ECHO,
            handled(policy, clip = timestamp + 59_000, source = phoneLink, now = 60_000))
        assertEquals(Decision.NOTIFY,
            handled(policy, clip = timestamp + 61_000, source = phoneLink, now = 62_000))
    }

    @Test fun `回写被过滤后晚于窗口的解锁检查也不会再次通知`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        handled(policy, clip = timestamp + 20_000, source = phoneLink, now = 21_000)
        assertEquals(Decision.SAME_CLIPBOARD_EVENT,
            handled(policy, clip = timestamp + 20_000, source = null, now = 600_000))
    }

    @Test fun `进程重建恢复去重状态`() {
        val original = ClipboardMatchPolicy()
        handled(original)
        val restored = ClipboardMatchPolicy(original.snapshot())
        assertEquals(Decision.SAME_CLIPBOARD_EVENT,
            restored.decide("link", timestamp, null, 10_000, boot))
        assertEquals(Decision.SYNC_ECHO,
            restored.decide("link", timestamp + 20_000, phoneLink, 21_000, boot))
    }

    @Test fun `同步不同链接或不同平台口令不会相互抑制`() {
        val policy = ClipboardMatchPolicy()
        handled(policy, key = "url:JD:link")
        assertEquals(Decision.NOTIFY,
            handled(policy, key = "url:TAOBAO:other", source = phoneLink, now = 2_000))
        assertEquals(Decision.NOTIFY,
            handled(policy, key = "kouling:JD:link", source = phoneLink, now = 3_000))
        assertEquals(Decision.SYNC_ECHO,
            handled(policy, key = "url:JD:link", clip = timestamp + 20_000, source = phoneLink, now = 21_000))
    }

    @Test fun `重启后不复用上次启动的单调时间窗口`() {
        val policy = ClipboardMatchPolicy()
        handled(policy, now = 100_000)
        assertEquals(Decision.NOTIFY,
            policy.decide("link", timestamp + 1, phoneLink, 110_000, boot + 1))
        assertEquals(Decision.NOTIFY,
            policy.decide("link", timestamp + 1, phoneLink, 1_000, boot))
    }

    @Test fun `重启后即使剪贴板时间戳相同也不复用旧事件`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        assertEquals(Decision.NOTIFY,
            policy.decide("link", timestamp, localApp, 2_000, boot + 1))
    }

    @Test fun `无法获取启动次数时不永久抑制相同时间戳`() {
        val policy = ClipboardMatchPolicy()
        handled(policy, bootCount = -1)
        assertEquals(Decision.NOTIFY,
            policy.decide("link", timestamp, null, 100_000, -1))
    }

    @Test fun `系统时间回拨仍按单调时钟识别同步回写`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        assertEquals(Decision.SYNC_ECHO,
            policy.decide("link", timestamp - 100_000, phoneLink, 21_000, boot))
    }

    @Test fun `无有效剪贴板时间戳时不永久当成同一事件`() {
        val policy = ClipboardMatchPolicy()
        handled(policy, clip = 0)
        assertEquals(Decision.LEGACY_ECHO, policy.decide("link", 0, null, 21_000, boot))
        assertEquals(Decision.NOTIFY, policy.decide("link", 0, null, 61_000, boot))
    }

    @Test fun `通知失败未提交状态时下次可重试`() {
        val policy = ClipboardMatchPolicy()
        assertEquals(Decision.NOTIFY, policy.decide("link", timestamp, null, 1_000, boot))
        assertTrue(policy.snapshot().isEmpty())
        assertEquals(Decision.NOTIFY, policy.decide("link", timestamp, null, 2_000, boot))
    }

    @Test fun `去重记录上限与恢复上限一致`() {
        val policy = ClipboardMatchPolicy(capacity = 2)
        handled(policy, key = "a")
        handled(policy, key = "b")
        handled(policy, key = "c")
        assertEquals(listOf("b", "c"), policy.snapshot().map { it.key })
        assertEquals(Decision.NOTIFY, policy.decide("a", timestamp, null, 2_000, boot))
        assertEquals(listOf("c"), ClipboardMatchPolicy(policy.snapshot(), capacity = 1).snapshot().map { it.key })
    }

    @Test fun `相同事件无需重复写入持久化记录`() {
        val policy = ClipboardMatchPolicy()
        handled(policy)
        assertFalse(policy.recordHandled("link", timestamp, 2_000, boot, Decision.SAME_CLIPBOARD_EVENT))
    }
}
