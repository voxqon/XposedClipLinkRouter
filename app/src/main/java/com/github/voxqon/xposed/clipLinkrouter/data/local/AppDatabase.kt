package com.github.voxqon.xposed.clipLinkrouter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.github.voxqon.xposed.clipLinkrouter.core.model.Action
import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.Result
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import com.github.voxqon.xposed.clipLinkrouter.data.local.dao.LogDao
import com.github.voxqon.xposed.clipLinkrouter.data.local.dao.RuleDao
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.LogEntity
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.RuleEntity

/** Room 的枚举 <-> String 转换器，避免依赖 ordinal 带来的顺序脆弱性。 */
class Converters {
    @TypeConverter fun ruleTypeToString(v: RuleType): String = v.name
    @TypeConverter fun stringToRuleType(v: String): RuleType =
        runCatching { RuleType.valueOf(v) }.getOrDefault(RuleType.CUSTOM)

    @TypeConverter fun matchTypeToString(v: MatchType): String = v.name
    @TypeConverter fun stringToMatchType(v: String): MatchType =
        runCatching { MatchType.valueOf(v) }.getOrDefault(MatchType.KEYWORD)

    @TypeConverter fun platformToString(v: Platform): String = v.name
    @TypeConverter fun stringToPlatform(v: String): Platform =
        runCatching { Platform.valueOf(v) }.getOrDefault(Platform.GENERIC)

    @TypeConverter fun actionToString(v: Action): String = v.name
    @TypeConverter fun stringToAction(v: String): Action =
        runCatching { Action.valueOf(v) }.getOrDefault(Action.BROWSER)

    @TypeConverter fun resultToString(v: Result): String = v.name
    @TypeConverter fun stringToResult(v: String): Result =
        runCatching { Result.valueOf(v) }.getOrDefault(Result.FAILED)
}

@Database(
    entities = [RuleEntity::class, LogEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun logDao(): LogDao
}
