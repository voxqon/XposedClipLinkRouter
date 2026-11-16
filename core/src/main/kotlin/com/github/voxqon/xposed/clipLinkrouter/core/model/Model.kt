package com.github.voxqon.xposed.clipLinkrouter.core.model

/** 规则类型：内置 / 自定义 */
enum class RuleType { BUILTIN, CUSTOM }

/** 匹配方式：精确主机 / 主机后缀 / 正则 / 关键词 / 口令 */
enum class MatchType { EXACT_HOST, SUFFIX_HOST, REGEX, KEYWORD, KOULING }

/** 平台 */
enum class Platform { JD, TAOBAO, TMALL, DIDI, GENERIC }

/** 打开动作 */
enum class Action { APP_SCHEME, BROWSER, FALLBACK_BROWSER, IGNORED, FAILED, KOULING_APP }

/** 打开结果 */
enum class Result { SUCCESS, FAILED, FALLBACK }

/** 内置规则的种子定义 */
data class BuiltinSeed(
    val key: String,
    val name: String,
    val platform: Platform,
    val matchType: MatchType,
    val pattern: String,
    val schemeTemplate: String?,
    val targetPackage: String?,
    val note: String,
)

/** 规则模板：内存与 UI 使用，落库为 RuleEntity */
data class RuleSpec(
    val id: Long = 0,
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
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
