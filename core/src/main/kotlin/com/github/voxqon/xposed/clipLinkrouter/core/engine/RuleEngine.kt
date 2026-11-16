package com.github.voxqon.xposed.clipLinkrouter.core.engine

import com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingResult
import com.github.voxqon.xposed.clipLinkrouter.core.extract.LinkExtractor
import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import java.net.IDN
import java.util.Locale

/**
 * 规则引擎：按优先级 + 类型权重决定命中的规则。
 *
 * 性能说明：
 * - 正则（含非法正则）用最多 64 项的 LRU 缓存，避免重复编译与无界内存增长。
 * - 对完整规则列表做内容比较，以一个不可变快照发布排序结果。
 * - 每次 URL 匹配仅解析一次域名。
 */
object RuleEngine {

    val BUILTIN_GENERIC = RuleSpec(
        name = "通用浏览器",
        type = RuleType.BUILTIN,
        matchType = MatchType.KEYWORD,
        pattern = "",
        platform = Platform.GENERIC,
        priority = -100,
        enabled = true,
        note = "未命中任何规则时回退到浏览器打开",
    )

    /** null 表示编译失败；有效和无效 pattern 共用容量上限。 */
    private val regexCache = object : LinkedHashMap<String, Regex?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>): Boolean = size > 64
    }
    /** 以单个 volatile 快照发布，避免列表和缓存键被不同线程读成两代数据。 */
    private data class RuleSnapshot(val source: List<RuleSpec>, val sorted: List<RuleSpec>)
    @Volatile private var snapshot = RuleSnapshot(emptyList(), emptyList())

    /** 编译并缓存 pattern；非法 pattern 返回 null 并记入失效集合。 */
    private fun compileOrNull(pattern: String): Regex? = synchronized(regexCache) {
        if (regexCache.containsKey(pattern)) {
            regexCache[pattern]
        } else {
            runCatching { Regex(pattern) }.getOrNull().also { regexCache[pattern] = it }
        }
    }

    private val RULE_COMPARATOR =
        compareByDescending<RuleSpec> { it.priority }
            .thenBy { if (it.type == RuleType.CUSTOM) 0 else 1 }

    private fun sortedRules(allRules: List<RuleSpec>): List<RuleSpec> {
        val cached = snapshot
        if (cached.source == allRules) return cached.sorted
        val source = allRules.toList()
        val sorted = source.asSequence()
            .filter { it.enabled }
            .sortedWith(RULE_COMPARATOR)
            .toList()
        snapshot = RuleSnapshot(source, sorted)
        return sorted
    }

    /**
     * URL 匹配：返回第一条命中的规则；未命中任何规则时返回内置兜底 GENERIC。
     * priority 相同时 CUSTOM 优先于 BUILTIN。
     *
     * pattern 为空的规则被跳过——内置"通用浏览器"兜底靠 BUILTIN_GENERIC 返回，
     * 不需要在遍历里命中。用户若想建"匹配所有"规则，应使用 REGEX ".*"。
     */
    fun match(url: String, allRules: List<RuleSpec>): RuleSpec {
        if (url.isEmpty()) return BUILTIN_GENERIC
        val host = normalizedHost(LinkExtractor.hostOf(url))
        return sortedRules(allRules)
            .asSequence()
            .filter { it.pattern.isNotBlank() }
            .firstOrNull { matches(it, url, host) }
            ?: allRules.firstOrNull {
                it.type == RuleType.BUILTIN && it.platform == Platform.GENERIC &&
                    it.matchType == MatchType.KEYWORD && it.pattern.isBlank()
            } ?: BUILTIN_GENERIC
    }

    /**
     * 口令匹配：只考虑 KOULING 类型的启用规则；
     * 平台提示与规则平台一致（或规则为 GENERIC 通配）时命中；
     * 无命中返回 null（口令没有通用兜底，避免普通括号文本被乱弹通知）。
     */
    fun matchKouling(kouling: KoulingResult, allRules: List<RuleSpec>): RuleSpec? =
        sortedRules(allRules)
            .asSequence()
            .filter { it.matchType == MatchType.KOULING }
            .filter { it.platform == kouling.platformHint || it.platform == Platform.GENERIC }
            .firstOrNull { matchesKoulingRule(it, kouling) }

    fun matchesKoulingRule(rule: RuleSpec, kouling: KoulingResult): Boolean {
        if (rule.matchType != MatchType.KOULING) return false
        val p = rule.pattern.trim()
        if (p.isEmpty()) return true
        // pattern 非空时视为针对口令内容的正则；非法正则视为不命中
        val regex = compileOrNull(p) ?: return false
        return runCatching { regex.containsMatchIn(kouling.token) }.getOrDefault(false)
    }

    fun matches(rule: RuleSpec, url: String): Boolean =
        matches(rule, url, normalizedHost(LinkExtractor.hostOf(url)))

    private fun normalizedHost(host: String): String {
        val normalized = host.trim().lowercase(Locale.ROOT).trimEnd('.').removePrefix("www.")
        if (normalized.startsWith('[') && normalized.endsWith(']')) return normalized
        return runCatching { IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES) }.getOrDefault("")
    }

    private fun matches(rule: RuleSpec, url: String, host: String): Boolean {
        return when (rule.matchType) {
            MatchType.EXACT_HOST -> {
                host.isNotEmpty() && host == normalizedHost(rule.pattern)
            }
            MatchType.SUFFIX_HOST -> {
                val suffix = normalizedHost(rule.pattern.trim().removePrefix("."))
                suffix.isNotEmpty() && host.isNotEmpty() && (host == suffix || host.endsWith(".$suffix"))
            }
            MatchType.REGEX -> {
                val regex = compileOrNull(rule.pattern) ?: return false
                runCatching { regex.containsMatchIn(url) }.getOrDefault(false)
            }
            MatchType.KEYWORD -> rule.pattern.isNotBlank() &&
                url.contains(rule.pattern, ignoreCase = true)
            // KOULING 规则不参与 URL 匹配
            MatchType.KOULING -> false
        }
    }
}
