package com.github.voxqon.xposed.clipLinkrouter.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.voxqon.xposed.clipLinkrouter.BuildConfig
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import com.github.voxqon.xposed.clipLinkrouter.R
import com.github.voxqon.xposed.clipLinkrouter.data.settings.AppSettings
import com.github.voxqon.xposed.clipLinkrouter.data.settings.ClipboardToastPreference
import com.github.voxqon.xposed.clipLinkrouter.data.settings.SettingsRepository
import com.github.voxqon.xposed.clipLinkrouter.notification.NotificationHelper
import com.github.voxqon.xposed.clipLinkrouter.ui.rules.GroupHeader
import com.github.voxqon.xposed.clipLinkrouter.ui.rules.RuleRow
import com.github.voxqon.xposed.clipLinkrouter.ui.rules.RulesViewModel
import com.github.voxqon.xposed.clipLinkrouter.util.ClipLinkConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val error: String? = null,
    val hideClipboardReadToast: Boolean? = null,
    val savingClipboardToastPreference: Boolean = false,
)

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val packageManager: PackageManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val hidden = withContext(Dispatchers.IO) { ClipboardToastPreference.isHidden(packageManager) }
                _ui.update { it.copy(hideClipboardReadToast = hidden) }
            } catch (e: RuntimeException) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(error = "无法读取剪贴板提示设置") }
            }
        }
        viewModelScope.launch {
            repo.settings
                .catch { e -> _ui.update { it.copy(loading = false, error = e.message) } }
                .collect { s -> _ui.update { it.copy(loading = false, settings = s) } }
        }
    }

    fun setEnabled(v: Boolean) = updateSetting { repo.setEnabled(v) }
    fun setBackgroundListening(v: Boolean) = updateSetting { repo.setBackgroundListening(v) }
    fun setMaxTextLength(v: Int) = updateSetting { repo.setMaxTextLength(v) }
    fun setAlwaysFallback(v: Boolean) = updateSetting { repo.setAlwaysFallbackToBrowser(v) }
    fun setKoulingEnabled(v: Boolean) = updateSetting { repo.setKoulingEnabled(v) }

    fun setHideClipboardReadToast(hidden: Boolean) {
        if (_ui.value.hideClipboardReadToast == null || _ui.value.savingClipboardToastPreference) return
        _ui.update { it.copy(savingClipboardToastPreference = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ClipboardToastPreference.setHidden(packageManager, hidden) }
                _ui.update { it.copy(hideClipboardReadToast = hidden) }
            } catch (e: RuntimeException) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(error = "保存剪贴板提示设置失败") }
            } finally {
                _ui.update { it.copy(savingClipboardToastPreference = false) }
            }
        }
    }

    private fun updateSetting(block: suspend () -> Unit) {
        if (_ui.value.loading) return
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(error = "保存设置失败") }
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    companion object {
        fun factory(app: ClipLinkApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(app.settings, app.packageManager) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: ClipLinkApp,
    onAddRule: () -> Unit = {},
    onEditRule: (Long) -> Unit = {},
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val launcherComponent = remember(context) { ComponentName(context.packageName, "${context.packageName}.LauncherActivity") }
    fun isLauncherHidden(): Boolean = context.packageManager.getComponentEnabledSetting(launcherComponent) ==
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    var launcherHidden by remember { mutableStateOf(isLauncherHidden()) }
    fun setLauncherHidden(hidden: Boolean) {
        try {
            context.packageManager.setComponentEnabledSetting(
                launcherComponent,
                if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            launcherHidden = isLauncherHidden()
        } catch (_: RuntimeException) {
            launcherHidden = isLauncherHidden()
        }
    }

    val notifGranted = remember { mutableStateOf(NotificationHelper.canPostNotifications(context)) }

    // 每次回到本页刷新一次权限状态（用户可能去系统设置里改了）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                launcherHidden = isLauncherHidden()
                notifGranted.value = NotificationHelper.canPostNotifications(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    var showModuleHelp by remember { mutableStateOf(false) }
    if (showModuleHelp) {
        LsposedHelpDialog(onDismiss = { showModuleHelp = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Group 1: 核心服务与后台状态
            SettingsGroup(title = "服务运行控制") {
                SettingSwitchItem(
                    title = "启用路由总开关",
                    subtitle = "全局控制剪贴板分析与分流跳转",
                    checked = ui.settings.enabled,
                    onCheckedChange = { vm.setEnabled(it) },
                    enabled = !ui.loading,
                )
                SettingSwitchItem(
                    title = "Xposed 后台监听",
                    subtitle = "接收系统剪贴板事件，关闭后停止识别和通知",
                    checked = ui.settings.backgroundListening,
                    onCheckedChange = { vm.setBackgroundListening(it) },
                    enabled = !ui.loading,
                )
                SettingSwitchItem(
                    title = "隐藏剪贴板读取提示",
                    subtitle = "仅隐藏本应用读取剪贴板时的系统 Toast；需要启用 Xposed 模块",
                    checked = ui.hideClipboardReadToast == true,
                    onCheckedChange = vm::setHideClipboardReadToast,
                    enabled = ui.hideClipboardReadToast != null && !ui.savingClipboardToastPreference,
                )

                // Notification Permission Status Banner
                if (!notifGranted.value) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "通知权限未授予",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "后台识别需要系统通知权限以唤醒目标 App",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("去授权", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Group 2: 分流与匹配策略
            SettingsGroup(title = "分流与匹配策略") {
                SettingSwitchItem(
                    title = "识别淘宝 / 京东 / 滴滴等口令",
                    subtitle = "识别 ￥xxxx￥、(xxxx) 等文本口令；关闭后仅解析标准链接",
                    checked = ui.settings.koulingEnabled,
                    onCheckedChange = { vm.setKoulingEnabled(it) },
                    enabled = !ui.loading,
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                SettingSwitchItem(
                    title = "目标应用未安装时回退浏览器",
                    subtitle = "唤起第三方客户端失败时，自动切换系统默认浏览器打开",
                    checked = ui.settings.alwaysFallbackToBrowser,
                    onCheckedChange = { vm.setAlwaysFallback(it) },
                    enabled = !ui.loading,
                )
            }

            // Group 3: 识别设置
            SettingsGroup(title = "识别设置") {
                IntSettingField(
                    title = "文本长度上限（字符）",
                    subtitle = "识别剪贴板文本的前指定字符数",
                    value = ui.settings.maxTextLength,
                    range = ClipLinkConfig.MIN_MAX_TEXT_LENGTH..ClipLinkConfig.MAX_MAX_TEXT_LENGTH,
                    onValue = { vm.setMaxTextLength(it) },
                )
            }

            // Group 4: 桌面图标
            SettingsGroup(title = "桌面图标") {
                SettingSwitchItem(
                    title = "隐藏桌面图标",
                    subtitle = "隐藏后可从 LSPosed 打开应用并恢复图标",
                    checked = launcherHidden,
                    onCheckedChange = { hidden -> setLauncherHidden(hidden) },
                )
            }

            // Group 5: 规则模块（位于桌面图标下方，采用手风琴模式可折叠）
            RulesAccordionGroup(
                app = app,
                onAddRule = onAddRule,
                onEditRule = onEditRule,
            )

            // Group 6: 使用指南与关于
            SettingsGroup(title = "指南与关于") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showModuleHelp = true }
                        .padding(vertical = 8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_help_outline),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Xposed 模块配置说明",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "静态作用域、重启生效与疑难解答",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    modifier = Modifier.padding(vertical = 6.dp),
                )

                // About Entry
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "XposedClipLinkRouter",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "🔒 隐私保护：去重记录仅保存匹配摘要和时间，不保存剪贴板明文。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RulesAccordionGroup(
    app: ClipLinkApp,
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
) {
    val rulesVm: RulesViewModel = viewModel(factory = RulesViewModel.factory(app))
    val rulesUi by rulesVm.ui.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    val totalRules = rulesUi.custom.size + rulesUi.builtin.size
    val enabledCount = rulesUi.custom.count { it.enabled } + rulesUi.builtin.count { it.enabled }

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "chevronRotation",
    )

    OutlinedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // 手风琴头部：点击展开 / 收起
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = "规则管理",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = "共 $totalRules 条（$enabledCount 启用）",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )

                    if (rulesUi.loading) {
                        Text("正在读取规则…", style = MaterialTheme.typography.bodySmall)
                    }
                    rulesUi.error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }

                    // 顶部操作栏：新建规则
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "点击规则可编辑，开关控制启用",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onAddRule,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("新建规则", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // 自定义规则分组
                    GroupHeader(title = "自定义规则", count = rulesUi.custom.size)
                    if (rulesUi.custom.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = "暂无自定义规则",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = onAddRule,
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加第一条自定义规则", fontSize = 11.5.sp)
                                }
                            }
                        }
                    } else {
                        rulesUi.custom.forEach { rule ->
                            RuleRow(
                                rule = rule,
                                onToggle = { rulesVm.toggle(rule, it) },
                                onClick = { onEditRule(rule.id) },
                            )
                        }
                    }

                    // 内置规则分组
                    if (rulesUi.builtin.isNotEmpty()) {
                        GroupHeader(title = "内置规则", count = rulesUi.builtin.size)
                        rulesUi.builtin.forEach { rule ->
                            RuleRow(
                                rule = rule,
                                onToggle = { rulesVm.toggle(rule, it) },
                                onClick = { onEditRule(rule.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
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
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}

@Composable
private fun IntSettingField(
    title: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val isValid = text.toIntOrNull() != null && text.toIntOrNull() in range
    val isChanged = text != value.toString()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "$subtitle（允许范围：${range.first} ~ ${range.last}）",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            )
            if (isChanged && isValid) {
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        text.toIntOrNull()?.coerceIn(range)?.let {
                            text = it.toString()
                            onValue(it)
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun LsposedHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_help_outline),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Xposed 模块配置指南", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StepCard(
                    step = "1",
                    title = "启用模块",
                    desc = "在支持静态作用域的框架管理器（如 Vector）中启用 XposedClipLinkRouter。",
                )
                StepCard(
                    step = "2",
                    title = "确认静态作用域",
                    desc = "模块仅允许作用于「系统框架」；如果该项尚未勾选，请勾选。",
                )
                StepCard(
                    step = "3",
                    title = "重启手机",
                    desc = "作用域生效需软重启或完整重启系统一次。",
                )
                StepCard(
                    step = "4",
                    title = "授予通知权限",
                    desc = "打开本应用授予系统通知权限，剪贴板事件即可在后台自动识别并分发。",
                )

                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = "💡 提示：后台监听在锁屏时自动休眠，解锁后唤醒；复制链接时无感知毫秒级分流，不常驻后台服务。",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(22.dp),
    )
}

@Composable
private fun StepCard(step: String, title: String, desc: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = step,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
