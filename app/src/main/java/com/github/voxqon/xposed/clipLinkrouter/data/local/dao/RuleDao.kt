package com.github.voxqon.xposed.clipLinkrouter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.RuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Query("SELECT * FROM rules ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: Long): RuleEntity?

    @Query("SELECT * FROM rules WHERE builtinKey = :key LIMIT 1")
    suspend fun getByBuiltinKey(key: String): RuleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RuleEntity): Long

    @Update
    suspend fun update(entity: RuleEntity)

    @Query("UPDATE rules SET enabled = :enabled, updatedAt = :nowMs WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, nowMs: Long)

}
