package com.github.voxqon.xposed.clipLinkrouter.ui.rules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import com.github.voxqon.xposed.clipLinkrouter.core.engine.RuleEngine
import com.github.voxqon.xposed.clipLinkrouter.core.extract.KoulingExtractor
import com.github.voxqon.xposed.clipLinkrouter.core.extract.LinkExtractor
import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleSpec
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.RuleEntity
import com.github.voxqon.xposed.clipLinkrouter.data.repository.RuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 编辑页 UI 状态；id == null 表示新建。 */
data class RuleEditUiState(
    val loading: Boolean = true,
    val id: Long? = null,
    val isBuiltin: Boolean = false,
    val name: String = "",
    val matchType: MatchType = MatchType.SUFFIX_HOST,
    val pattern: String = "",
    val platform: Platform = Platform.GENERIC,
    val schemeTemplate: String = "",
    val targetPackage: String = "",
    val fallbackToBrowser: Boolean = true,
    val priority: String = "0",
    val enabled: Boolean = true,
    val note: String = "",
    val testUrl: String = "",
    val patternError: String? = null,
    val priorityError: String? = null,
    val testResult: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

class RuleEditViewModel(
    private val repo: RuleRepository,
    ruleId: Long?,
) : ViewModel() {

    private val _ui = MutableStateFlow(RuleEditUiState(id = ruleId))
    val ui: StateFlow<RuleEditUiState> = _ui.asStateFlow()

    init {
        if (ruleId == null) {
            _ui.update { it.copy(loading = false) }
            validate()
        } else {
            viewModelScope.launch {
                try {
                    val e = repo.getById(ruleId)
                    if (e == null) {
                        _ui.update { it.copy(loading = false, error = "规则不存在") }
                    } else {
                        _ui.update {
                            it.copy(
                                loading = false,
                                id = e.id,
                                isBuiltin = e.type == RuleType.BUILTIN,
                                name = e.name,
                                matchType = e.matchType,
                                pattern = e.pattern,
                                platform = e.platform,
                                schemeTemplate = e.schemeTemplate.orEmpty(),
                                targetPackage = e.targetPackage.orEmpty(),
                                fallbackToBrowser = e.fallbackToBrowser,
                                priority = e.priority.toString(),
                                enabled = e.enabled,
                                note = e.note.orEmpty(),
                            )
                        }
                        validate()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _ui.update { it.copy(loading = false, error = e.message ?: "读取规则失败") }
                }
            }
        }
    }

    fun update(block: (RuleEditUiState) -> RuleEditUiState) {
        if (_ui.value.loading || _ui.value.saving || _ui.value.saved) return
        _ui.update(block)
        validate()
    }

    /** 严格校验 priority 输入：只允许可选前导 `-` + 数字，否则报错而非静默落 0。 */
    private fun parsePriority(raw: String): Pair<Int?, String?> {
        if (raw.isBlank()) return 0 to null
        val normalized = raw.trim()
        return if (normalized.matches(Regex("^-?\\d+$"))) {
            normalized.toIntOrNull()?.let { it to null } ?: (null to "优先级超出整数范围")
        } else {
            null to "优先级必须是整数"
        }
    }

    private fun validate() {
        _ui.update { s ->
            val (priorityValue, priorityErr) = parsePriority(s.priority)
            val patternErr = when {
                s.isBuiltin -> null // 内置规则 pattern 不可编辑，无需校验
                s.matchType == MatchType.KOULING && s.pattern.isBlank() -> null // 口令规则允许空 pattern
                s.pattern.isBlank() -> "匹配表达式不能为空"
                s.matchType == MatchType.REGEX || s.matchType == MatchType.KOULING -> runCatching { Regex(s.pattern.trim()) }.fold(
                    onSuccess = { null },
                    onFailure = { "正则非法：${it.message}" },
                )
                else -> null
            }
            val testResult = if (s.testUrl.isNotBlank() && priorityErr == null && patternErr == null) {
                val spec = RuleSpec(
                    id = s.id ?: 0,
                    name = s.name.ifBlank { "未命名" },
                    type = if (s.isBuiltin) RuleType.BUILTIN else RuleType.CUSTOM,
                    matchType = s.matchType,
                    pattern = s.pattern.trim(),
                    platform = s.platform,
                    priority = priorityValue ?: 0,
                    enabled = s.enabled,
                )
                if (s.matchType == MatchType.KOULING) {
                    val probe = KoulingExtractor.extractFirst(s.testUrl)
                    if (probe == null) "示例中未检测到口令"
                    else {
                        val hit = RuleEngine.matchKouling(probe, listOf(spec))
                        if (hit != null) "命中当前规则，平台=${s.platform}，口令=${probe.token}"
                        else "未命中当前规则（平台提示=${probe.platformHint}）"
                    }
                } else {
                    val url = LinkExtractor.extractFirst(s.testUrl).firstUrl
                    if (url == null) "示例中未检测到链接"
                    else if (RuleEngine.matches(spec, url)) "命中当前规则，平台=${s.platform}"
                    else "未命中当前规则"
                }
            } else null
            s.copy(patternError = patternErr, priorityError = priorityErr, testResult = testResult)
        }
    }

    fun save(onDone: () -> Unit) {
        if (_ui.value.loading || _ui.value.saving || _ui.value.saved) return
        validate()
        val s = _ui.value
        if (s.patternError != null || s.priorityError != null) return
        val priorityValue = parsePriority(s.priority).first ?: 0
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            try {
                if (s.id == null) {
                    repo.insertCustom(
                        RuleEntity(
                            name = s.name.ifBlank { "未命名规则" },
                            type = RuleType.CUSTOM,
                            matchType = s.matchType,
                            pattern = s.pattern.trim(),
                            platform = s.platform,
                            schemeTemplate = s.schemeTemplate.trim().ifBlank { null },
                            targetPackage = s.targetPackage.trim().ifBlank { null },
                            fallbackToBrowser = s.fallbackToBrowser,
                            priority = priorityValue,
                            enabled = s.enabled,
                            note = s.note.ifBlank { null },
                        ),
                        nowMs = now,
                    )
                } else {
                    val existing = repo.getById(s.id) ?: error("规则不存在")
                    val updated = if (existing.type == RuleType.BUILTIN) {
                        // 内置规则：只允许改 enabled / priority / fallback / note，pattern/platform 锁死
                        existing.copy(
                            enabled = s.enabled,
                            priority = priorityValue,
                            fallbackToBrowser = s.fallbackToBrowser,
                            note = s.note.ifBlank { null },
                        )
                    } else {
                        existing.copy(
                            name = s.name.ifBlank { "未命名规则" },
                            matchType = s.matchType,
                            pattern = s.pattern.trim(),
                            platform = s.platform,
                            schemeTemplate = s.schemeTemplate.trim().ifBlank { null },
                            targetPackage = s.targetPackage.trim().ifBlank { null },
                            fallbackToBrowser = s.fallbackToBrowser,
                            priority = priorityValue,
                            enabled = s.enabled,
                            note = s.note.ifBlank { null },
                        )
                    }
                    repo.update(updated, now)
                }
                _ui.update { it.copy(saved = true) }
                onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(error = e.message ?: "保存失败") }
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        fun factory(app: ClipLinkApp, ruleId: Long?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RuleEditViewModel(app.rules, ruleId) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    app: ClipLinkApp,
    ruleId: Long?,
    onBack: () -> Unit,
) {
    val vm: RuleEditViewModel = viewModel(factory = RuleEditViewModel.factory(app, ruleId))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmSave by remember { mutableStateOf(false) }

    LaunchedEffect(ui.error) { ui.error?.let { snackbar.showSnackbar(it) } }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("确认保存") },
            text = { Text(if (ui.isBuiltin) "内置规则仅保存启用状态、优先级、回退与备注" else "确定保存此规则配置？") },
            confirmButton = {
                TextButton(onClick = { confirmSave = false; vm.save(onBack) }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (ruleId == null) "新增路由规则" else if (ui.isBuiltin) "内置规则详情" else "编辑规则",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { confirmSave = true },
                        enabled = !ui.loading && !ui.saving && !ui.saved && ui.patternError == null && ui.priorityError == null,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Built-in banner warning
            if (ui.isBuiltin) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "内置系统规则：匹配模式与目标平台已预置锁定；可自由调整优先级、回退策略、启用状态与备注。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            // Section 1: 基本属性
            FormSection(title = "基本信息") {
                OutlinedTextField(
                    value = ui.name,
                    onValueChange = { v -> vm.update { it.copy(name = v) } },
                    label = { Text("规则名称") },
                    placeholder = { Text("如：京东商品详情页") },
                    enabled = !ui.isBuiltin,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))

                EnumDropdown(
                    label = "目标平台",
                    value = ui.platform.name,
                    displayNames = mapOf(
                        Platform.JD.name to "京东 (JD)",
                        Platform.TAOBAO.name to "淘宝 (Taobao)",
                        Platform.TMALL.name to "天猫 (Tmall)",
                        Platform.DIDI.name to "滴滴 (DiDi)",
                        Platform.GENERIC.name to "通用 (Generic)",
                    ),
                    options = Platform.entries.map { it.name },
                    enabled = !ui.isBuiltin,
                    onSelected = { v -> vm.update { it.copy(platform = Platform.valueOf(v)) } },
                )

                Spacer(Modifier.height(10.dp))

                EnumDropdown(
                    label = "匹配类型",
                    value = ui.matchType.name,
                    displayNames = mapOf(
                        MatchType.SUFFIX_HOST.name to "域名后缀 (SUFFIX_HOST)",
                        MatchType.EXACT_HOST.name to "完整域名 (EXACT_HOST)",
                        MatchType.REGEX.name to "正则表达式 (REGEX)",
                        MatchType.KEYWORD.name to "关键词包含 (KEYWORD)",
                        MatchType.KOULING.name to "应用口令 (KOULING)",
                    ),
                    options = MatchType.entries.map { it.name },
                    enabled = !ui.isBuiltin,
                    onSelected = { v -> vm.update { it.copy(matchType = MatchType.valueOf(v)) } },
                )
            }

            // Section 2: 匹配规则与深链调起
            FormSection(title = "匹配与调起") {
                OutlinedTextField(
                    value = ui.pattern,
                    onValueChange = { v -> vm.update { it.copy(pattern = v) } },
                    label = {
                        Text(
                            if (ui.matchType == MatchType.KOULING) "口令正则（留空匹配全部口令）"
                            else "匹配表达式 (Pattern)",
                        )
                    },
                    placeholder = {
                        Text(
                            when (ui.matchType) {
                                MatchType.SUFFIX_HOST -> "如：jd.com 或 item.taobao.com"
                                MatchType.REGEX -> "如：^https://.*\\.jd\\.com/.*"
                                MatchType.KOULING -> "如：[a-zA-Z0-9]+（不含分隔符）"
                                else -> "输入域名或规则内容"
                            },
                        )
                    },
                    enabled = !ui.isBuiltin,
                    isError = ui.patternError != null,
                    supportingText = {
                        ui.patternError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        } ?: Text("支持域名后缀、完整 URL 正则或口令规则")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = ui.schemeTemplate,
                    onValueChange = { v -> vm.update { it.copy(schemeTemplate = v) } },
                    label = { Text("深链 Scheme 模板（可选）") },
                    placeholder = { Text("如：openapp.jdmobile://virtual?params={url}") },
                    supportingText = { Text(if (ui.matchType == MatchType.KOULING)
                        "使用 {token} 代替口令，留空直接打开目标应用"
                        else "使用 {url} 代替目标地址，留空通过目标应用打开原链接") },
                    enabled = !ui.isBuiltin,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = ui.targetPackage,
                    onValueChange = { v -> vm.update { it.copy(targetPackage = v) } },
                    label = { Text("目标应用包名（可选）") },
                    placeholder = { Text("如：com.jingdong.app.mall") },
                    supportingText = { Text("指定调起的 Android 包名，防止唤醒错误应用") },
                    enabled = !ui.isBuiltin,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Section 3: 控制与策略
            FormSection(title = "策略与优先级") {
                OutlinedTextField(
                    value = ui.priority,
                    onValueChange = { v ->
                        val filtered = buildString {
                            v.forEachIndexed { idx, ch ->
                                when {
                                    ch.isDigit() -> append(ch)
                                    ch == '-' && idx == 0 -> append(ch)
                                }
                            }
                        }
                        vm.update { it.copy(priority = filtered) }
                    },
                    label = { Text("优先级") },
                    supportingText = {
                        ui.priorityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            ?: Text("整数值，越大越优先匹配，兜底规则建议设为 0 或负数")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = ui.priorityError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("应用未安装时回退浏览器", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("唤起目标 App 失败时，无缝切换系统浏览器打开", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = ui.fallbackToBrowser,
                        onCheckedChange = { v -> vm.update { it.copy(fallbackToBrowser = v) } },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用此规则", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("关闭后本条规则将被跳过", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = ui.enabled,
                        onCheckedChange = { v -> vm.update { it.copy(enabled = v) } },
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = ui.note,
                    onValueChange = { v -> vm.update { it.copy(note = v) } },
                    label = { Text("备注说明（可选）") },
                    placeholder = { Text("记录规则用途或说明") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Section 4: 实时验证沙盒
            FormSection(title = "实时匹配测试") {
                OutlinedTextField(
                    value = ui.testUrl,
                    onValueChange = { v -> vm.update { it.copy(testUrl = v) } },
                    label = { Text("输入测试链接或口令") },
                    placeholder = { Text("粘贴待测链接检验规则匹配结果...") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!ui.testResult.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    val isSuccess = ui.testResult?.startsWith("命中") == true
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSuccess) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isSuccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = ui.testResult ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (isSuccess) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Button(
                onClick = { confirmSave = true },
                enabled = !ui.saving && !ui.saved && ui.patternError == null && ui.priorityError == null,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("保存规则", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormSection(
    title: String,
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(
    label: String,
    value: String,
    displayNames: Map<String, String>,
    options: List<String>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = displayNames[value] ?: value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayNames[opt] ?: opt,
                            fontWeight = if (opt == value) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelected(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}
