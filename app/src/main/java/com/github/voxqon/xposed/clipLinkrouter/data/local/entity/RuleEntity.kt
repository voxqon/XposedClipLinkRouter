package com.github.voxqon.xposed.clipLinkrouter.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType

@Entity(
    tableName = "rules",
    indices = [
        Index("enabled"),
        Index("priority"),
        // 内置种子幂等写入依赖 builtinKey 唯一；unique=true 让数据库层面挡住并发重复。
        Index(value = ["builtinKey"], unique = true),
    ],
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: RuleType,
    val matchType: MatchType,
    val pattern: String,
    val platform: Platform,
    val schemeTemplate: String? = null,
    val targetPackage: String? = null,
    val fallbackToBrowser: Boolean = true,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val note: String? = null,
    /** 内置规则的稳定 key，用于种子数据幂等写入；自定义规则为 null。 */
    val builtinKey: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
