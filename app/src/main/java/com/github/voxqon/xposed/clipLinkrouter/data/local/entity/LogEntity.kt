package com.github.voxqon.xposed.clipLinkrouter.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.github.voxqon.xposed.clipLinkrouter.core.model.Action
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.Result

@Entity(
    tableName = "logs",
    indices = [Index("timestamp"), Index("platform"), Index("result")],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val platform: Platform,
    /** 仅域名，不存完整 URL */
    val host: String,
    /** SHA-256 前 16 位，不含明文 */
    val urlHash: String,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val action: Action,
    val result: Result,
    val elapsedMs: Long = 0,
    /** 错误码或简短说明，绝不记录剪贴板全文 */
    val extra: String? = null,
)
