package com.github.voxqon.xposed.clipLinkrouter.util

/** 统一管理用户设置的默认值与合法范围。 */
object ClipLinkConfig {

    const val DEFAULT_MAX_TEXT_LENGTH = 2000
    const val MIN_MAX_TEXT_LENGTH = 200
    const val MAX_MAX_TEXT_LENGTH = 10_000

    const val DEFAULT_LOG_KEEP_COUNT = 1000
    const val MIN_LOG_KEEP_COUNT = 100
    const val MAX_LOG_KEEP_COUNT = 10_000

    fun clampMaxTextLength(v: Int): Int = v.coerceIn(MIN_MAX_TEXT_LENGTH, MAX_MAX_TEXT_LENGTH)
    fun clampLogKeepCount(v: Int): Int = v.coerceIn(MIN_LOG_KEEP_COUNT, MAX_LOG_KEEP_COUNT)
}
