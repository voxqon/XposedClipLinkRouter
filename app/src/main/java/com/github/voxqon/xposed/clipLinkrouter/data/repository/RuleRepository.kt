package com.github.voxqon.xposed.clipLinkrouter.data.repository

import com.github.voxqon.xposed.clipLinkrouter.core.model.BuiltinSeed
import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import com.github.voxqon.xposed.clipLinkrouter.data.local.dao.RuleDao
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.RuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 内置规则种子：首次启动写入，用户可禁用/调优先级，不可删除或改 pattern/platform。 */
object BuiltinRuleSeeds {
    val all: List<BuiltinSeed> = listOf(
        BuiltinSeed(
            key = "jd_main",
            name = "京东主站",
            platform = Platform.JD,
            matchType = MatchType.SUFFIX_HOST,
            pattern = "jd.com",
            schemeTemplate = null,
            targetPackage = "com.jingdong.app.mall",
            note = "京东主站及子域名（item.jd.com / u.jd.com 等）",
        ),
        BuiltinSeed(
            key = "jd_short",
            name = "京东短链",
            platform = Platform.JD,
            matchType = MatchType.SUFFIX_HOST,
            pattern = "3.cn",
            schemeTemplate = null,
            targetPackage = "com.jingdong.app.mall",
            note = "京东短链域名 3.cn",
        ),
        BuiltinSeed(
            key = "taobao_main",
            name = "淘宝",
            platform = Platform.TAOBAO,
            matchType = MatchType.SUFFIX_HOST,
            pattern = "taobao.com",
            schemeTemplate = null,
            targetPackage = "com.taobao.taobao",
            note = "淘宝主站及子域名",
        ),
        BuiltinSeed(
            key = "taobao_short",
            name = "淘宝短链",
            platform = Platform.TAOBAO,
            matchType = MatchType.SUFFIX_HOST,
            pattern = "tb.cn",
            schemeTemplate = null,
            targetPackage = "com.taobao.taobao",
            note = "淘宝短链 tb.cn / m.tb.cn",
        ),
        BuiltinSeed(
            key = "tmall_main",
            name = "天猫",
            platform = Platform.TMALL,
            matchType = MatchType.SUFFIX_HOST,
            pattern = "tmall.com",
            schemeTemplate = null,
            targetPackage = "com.tmall.wireless",
            note = "天猫主站及子域名",
        ),
        BuiltinSeed(
            key = "jd_kouling",
            name = "京东口令",
            platform = Platform.JD,
            matchType = MatchType.KOULING,
            pattern = "",
            schemeTemplate = null,
            targetPackage = "com.jingdong.app.mall",
            note = "识别（xxxx）形式的京东口令，拉起京东 App 自行解析剪贴板",
        ),
        BuiltinSeed(
            key = "taobao_kouling",
            name = "淘口令",
            platform = Platform.TAOBAO,
            matchType = MatchType.KOULING,
            pattern = "",
            schemeTemplate = null,
            targetPackage = "com.taobao.taobao",
            note = "识别 ￥xxxx￥ 形式的淘口令，拉起淘宝 App 自行解析剪贴板",
        ),
        BuiltinSeed(
            key = "tmall_hk",
            name = "天猫国际",
            platform = Platform.TMALL,
            matchType = MatchType.SUFFIX_HOST,
            pattern = "tmall.hk",
            schemeTemplate = null,
            targetPackage = "com.tmall.wireless",
            note = "天猫国际域名及子域名",
        ),
        BuiltinSeed(
            key = "tmall_kouling",
            name = "天猫口令",
            platform = Platform.TMALL,
            matchType = MatchType.KOULING,
            pattern = "",
            schemeTemplate = null,
            targetPackage = "com.tmall.wireless",
            note = "识别带天猫上下文的口令，交由天猫 App 解析",
        ),
        BuiltinSeed(
            key = "didi_kouling",
            name = "滴滴口令",
            platform = Platform.DIDI,
            matchType = MatchType.KOULING,
            pattern = "",
            schemeTemplate = null,
            targetPackage = "com.sdu.didi.psnger",
            note = "识别滴滴分享口令，拉起滴滴出行读取剪贴板",
        ),
        BuiltinSeed(
            key = "generic_browser",
            name = "通用浏览器",
            platform = Platform.GENERIC,
            matchType = MatchType.KEYWORD,
            pattern = "",
            schemeTemplate = null,
            targetPackage = null,
            note = "未命中任何规则时使用系统浏览器打开",
        ),
    )

    /** 内置默认优先级：短链略高于主站，通用兜底最低。 */
    fun defaultPriority(key: String): Int = when (key) {
        "jd_kouling", "taobao_kouling", "tmall_kouling", "didi_kouling" -> 40
        "jd_short", "taobao_short" -> 20
        "jd_main", "taobao_main", "tmall_main", "tmall_hk" -> 10
        else -> -100
    }
}

class RuleRepository(private val dao: RuleDao) {

    val rules: Flow<List<RuleEntity>> = dao.observeAll()
    val ruleSpecs: Flow<List<RuleSpec>> =
        dao.observeAll().map { list -> list.map { it.toSpec() } }

    suspend fun getById(id: Long): RuleEntity? = dao.getById(id)

    /** 幂等写入内置种子：已存在则跳过，不覆盖用户的禁用/优先级修改。 */
    suspend fun seedBuiltinIfNeeded(nowMs: Long) {
        BuiltinRuleSeeds.all.forEach { seed ->
            val existing = dao.getByBuiltinKey(seed.key)
            if (existing == null) {
                dao.insert(
                    RuleEntity(
                        name = seed.name,
                        type = RuleType.BUILTIN,
                        matchType = seed.matchType,
                        pattern = seed.pattern,
                        platform = seed.platform,
                        schemeTemplate = seed.schemeTemplate,
                        targetPackage = seed.targetPackage,
                        fallbackToBrowser = true,
                        priority = BuiltinRuleSeeds.defaultPriority(seed.key),
                        enabled = true,
                        note = seed.note,
                        builtinKey = seed.key,
                        createdAt = nowMs,
                        updatedAt = nowMs,
                    )
                )
            } else if (existing.matchType != MatchType.KOULING && existing.schemeTemplate in setOf(
                    "openApp.jdMobile://virtual?params={url}", "taobao://{url}", "tmall://page.tm/appLink?url={url}")) {
                // 仅修正旧版内置错误模板，保留用户的开关、优先级和备注。
                dao.update(existing.copy(schemeTemplate = seed.schemeTemplate, updatedAt = nowMs))
            }
        }
    }

    suspend fun insertCustom(entity: RuleEntity, nowMs: Long): Long =
        dao.insert(entity.copy(type = RuleType.CUSTOM, builtinKey = null, createdAt = nowMs, updatedAt = nowMs))

    suspend fun update(entity: RuleEntity, nowMs: Long) = dao.update(entity.copy(updatedAt = nowMs))

    suspend fun setEnabled(id: Long, enabled: Boolean, nowMs: Long) = dao.setEnabled(id, enabled, nowMs)
}

fun RuleEntity.toSpec(): RuleSpec = RuleSpec(
    id = id,
    name = name,
    type = type,
    matchType = matchType,
    pattern = pattern,
    platform = platform,
    schemeTemplate = schemeTemplate,
    targetPackage = targetPackage,
    fallbackToBrowser = fallbackToBrowser,
    priority = priority,
    enabled = enabled,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
