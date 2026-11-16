package com.github.voxqon.xposed.clipLinkrouter.core.hook

/** 以系统写入时间识别同一次复制；同步回写只在短窗口内按匹配内容去重。 */
class ClipboardMatchPolicy(
    restored: List<Record> = emptyList(),
    private val echoWindowMillis: Long = 60_000L,
    private val capacity: Int = 128,
) {
    data class Record(
        val key: String,
        val clipboardTimestamp: Long,
        val notifiedAtElapsed: Long,
        val bootCount: Int,
    )

    enum class Decision { NOTIFY, SAME_CLIPBOARD_EVENT, SYNC_ECHO, LEGACY_ECHO }

    private val records = LinkedHashMap<String, Record>()

    init {
        require(capacity > 0 && echoWindowMillis >= 0)
        restored.takeLast(capacity).forEach { records[it.key] = it }
    }

    fun decide(
        key: String, clipboardTimestamp: Long, sourcePackage: String?,
        nowElapsed: Long, bootCount: Int,
    ): Decision {
        val previous = records[key] ?: return Decision.NOTIFY
        val sameBoot = bootCount >= 0 && previous.bootCount == bootCount
        if (sameBoot && clipboardTimestamp > 0 && clipboardTimestamp == previous.clipboardTimestamp) {
            return Decision.SAME_CLIPBOARD_EVENT
        }
        val elapsed = nowElapsed - previous.notifiedAtElapsed
        val recent = sameBoot && elapsed >= 0 && elapsed < echoWindowMillis
        if (!recent) return Decision.NOTIFY
        return when {
            sourcePackage.isNullOrBlank() -> Decision.LEGACY_ECHO
            sourcePackage in syncPackages -> Decision.SYNC_ECHO
            else -> Decision.NOTIFY
        }
    }

    /** 仅在成功通知或确认重复后提交。重复事件不延长回写抑制窗口。 */
    fun recordHandled(
        key: String, clipboardTimestamp: Long, nowElapsed: Long,
        bootCount: Int, decision: Decision,
    ): Boolean {
        val previous = records[key]
        val next = if (decision == Decision.NOTIFY) {
            Record(key, clipboardTimestamp, nowElapsed, bootCount)
        } else {
            previous?.copy(clipboardTimestamp = clipboardTimestamp) ?: return false
        }
        if (next == previous) return false
        records.remove(key)
        records[key] = next
        while (records.size > capacity) records.remove(records.keys.first())
        return true
    }

    fun snapshot(): List<Record> = records.values.toList()

    private companion object {
        val syncPackages = setOf(
            "com.microsoft.appmanager",
            "com.microsoft.deviceintegrationservice",
            "com.microsoftsdk.crossdeviceservicebroker",
        )
    }
}
