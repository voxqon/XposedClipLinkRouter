package com.github.voxqon.xposed.clipLinkrouter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.LogEntity

@Dao
interface LogDao {

    @Insert
    suspend fun insert(entity: LogEntity): Long

    /** 插入与裁剪在同一事务里完成，保证上限严格生效。 */
    @Transaction
    suspend fun insertAndTrim(entity: LogEntity, keepCount: Int) {
        insert(entity)
        trimToLimit(keepCount)
    }

    @Query(
        "DELETE FROM logs WHERE id IN (" +
            "SELECT id FROM logs ORDER BY timestamp DESC, id DESC LIMIT -1 OFFSET :keepCount" +
            ")"
    )
    suspend fun trimToLimit(keepCount: Int): Int

}
