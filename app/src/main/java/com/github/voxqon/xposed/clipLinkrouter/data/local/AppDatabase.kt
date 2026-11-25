package com.github.voxqon.xposed.clipLinkrouter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import com.github.voxqon.xposed.clipLinkrouter.data.local.dao.RuleDao
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
}

@Database(
    entities = [RuleEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    companion object {
        const val NAME = "cliplink.db"

        // 保留同库的规则，只删除不再使用的打开记录。
        val REMOVE_OPEN_HISTORY = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 此 PRAGMA 会返回一行结果；必须读取游标才会执行设置。
                db.query("PRAGMA secure_delete = ON").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                        "Could not enable SQLite secure_delete before removing open history"
                    }
                }
                db.execSQL("DROP TABLE IF EXISTS logs")
            }
        }
    }
}
