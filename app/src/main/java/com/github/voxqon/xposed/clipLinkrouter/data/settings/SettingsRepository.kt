package com.github.voxqon.xposed.clipLinkrouter.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.voxqon.xposed.clipLinkrouter.util.ClipLinkConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "cliplink_settings")

/** 应用设置快照，UI 层一次性订阅。 */
data class AppSettings(
    val enabled: Boolean = true,
    val backgroundListening: Boolean = true,
    val maxTextLength: Int = ClipLinkConfig.DEFAULT_MAX_TEXT_LENGTH,
    val alwaysFallbackToBrowser: Boolean = true,
    val koulingEnabled: Boolean = true,
)

class SettingsRepository internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val BG_LISTENING = booleanPreferencesKey("background_listening")
        val MAX_TEXT_LENGTH = intPreferencesKey("max_text_length")
        val ALWAYS_FALLBACK = booleanPreferencesKey("always_fallback_to_browser")
        val KOULING_ENABLED = booleanPreferencesKey("kouling_enabled")
    }

    val settings: Flow<AppSettings> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { p ->
        AppSettings(
            enabled = p[Keys.ENABLED] ?: true,
            backgroundListening = p[Keys.BG_LISTENING] ?: true,
            maxTextLength = ClipLinkConfig.clampMaxTextLength(
                p[Keys.MAX_TEXT_LENGTH] ?: ClipLinkConfig.DEFAULT_MAX_TEXT_LENGTH
            ),
            alwaysFallbackToBrowser = p[Keys.ALWAYS_FALLBACK] ?: true,
            koulingEnabled = p[Keys.KOULING_ENABLED] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) = edit { it[Keys.ENABLED] = v }
    suspend fun setBackgroundListening(v: Boolean) = edit { it[Keys.BG_LISTENING] = v }
    suspend fun setMaxTextLength(v: Int) = edit { it[Keys.MAX_TEXT_LENGTH] = ClipLinkConfig.clampMaxTextLength(v) }
    suspend fun setAlwaysFallbackToBrowser(v: Boolean) = edit { it[Keys.ALWAYS_FALLBACK] = v }
    suspend fun setKoulingEnabled(v: Boolean) = edit { it[Keys.KOULING_ENABLED] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }
}
