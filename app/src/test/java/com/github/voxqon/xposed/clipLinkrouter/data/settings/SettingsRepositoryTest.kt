package com.github.voxqon.xposed.clipLinkrouter.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun `关闭后台监听后读取保存的状态`() = runTest {
        val repo = SettingsRepository(MemoryStore())
        assertTrue(repo.settings.first().backgroundListening)
        repo.setBackgroundListening(false)
        assertFalse(repo.settings.first().backgroundListening)
        repo.setBackgroundListening(true)
        assertTrue(repo.settings.first().backgroundListening)
    }

    @Test fun `重新创建仓库后保留旧版本关闭监听的设置`() = runTest {
        val store = MemoryStore(preferencesOf(booleanPreferencesKey("background_listening") to false))
        assertFalse(SettingsRepository(store).settings.first().backgroundListening)
        assertFalse(SettingsRepository(store).settings.first().backgroundListening)
    }
}
