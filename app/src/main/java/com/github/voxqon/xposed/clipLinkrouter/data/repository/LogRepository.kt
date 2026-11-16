package com.github.voxqon.xposed.clipLinkrouter.data.repository

import com.github.voxqon.xposed.clipLinkrouter.data.local.dao.LogDao
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.LogEntity

class LogRepository(private val dao: LogDao) {

    suspend fun append(entity: LogEntity, keepCount: Int) = dao.insertAndTrim(entity, keepCount)
}
