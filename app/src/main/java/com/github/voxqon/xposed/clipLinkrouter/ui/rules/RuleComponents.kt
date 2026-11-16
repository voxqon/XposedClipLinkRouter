package com.github.voxqon.xposed.clipLinkrouter.ui.rules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.voxqon.xposed.clipLinkrouter.ClipLinkApp
import com.github.voxqon.xposed.clipLinkrouter.core.model.MatchType
import com.github.voxqon.xposed.clipLinkrouter.core.model.Platform
import com.github.voxqon.xposed.clipLinkrouter.core.model.RuleType
import com.github.voxqon.xposed.clipLinkrouter.data.local.entity.RuleEntity
import com.github.voxqon.xposed.clipLinkrouter.data.repository.RuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class RulesUiState(
    val loading: Boolean = true,
    val custom: List<RuleEntity> = emptyList(),
    val builtin: List<RuleEntity> = emptyList(),
    val error: String? = null,
)

class RulesViewModel(private val repo: RuleRepository) : ViewModel() {

    private val _ui = MutableStateFlow(RulesUiState())
    val ui: StateFlow<RulesUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.rules
                .catch { e -> _ui.update { it.copy(loading = false, error = e.message) } }
                .collect { list ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            custom = list.filter { r -> r.type == RuleType.CUSTOM },
                            builtin = list.filter { r -> r.type == RuleType.BUILTIN },
                            error = null,
                        )
                    }
                }
        }
    }

    fun toggle(rule: RuleEntity, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repo.setEnabled(rule.id, enabled, System.currentTimeMillis())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _ui.update { it.copy(error = "保存规则开关失败") }
            }
        }
    }

    companion object {
        fun factory(app: ClipLinkApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RulesViewModel(app.rules) as T
        }
    }
}

@Composable
internal fun GroupHeader(title: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
internal fun RuleRow(
    rule: RuleEntity,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val isEnabled = rule.enabled

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .alpha(if (isEnabled) 1.0f else 0.65f),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.surfaceContainerLowest
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isEnabled) {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header Row: Badges (Platform, MatchType, Builtin, Priority) + Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                PlatformBadge(rule.platform)
                Spacer(Modifier.width(6.dp))
                MatchTypeBadge(rule.matchType)

                if (rule.type == RuleType.BUILTIN) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "内置",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "内置",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                if (rule.priority != 0) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "P${rule.priority}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }

            // Title
            Text(
                text = rule.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Pattern Box (monospace snippet)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = rule.pattern.ifBlank { "（无匹配 Pattern，兜底规则）" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    color = if (rule.pattern.isBlank()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }

            // Optional note or target package footer
            if (!rule.targetPackage.isNullOrBlank() || !rule.note.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!rule.targetPackage.isNullOrBlank()) {
                        Text(
                            text = "目标: ${rule.targetPackage}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (!rule.note.isNullOrBlank()) {
                        if (!rule.targetPackage.isNullOrBlank()) {
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            text = rule.note ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlatformBadge(platform: Platform) {
    val (bg, fg, label) = when (platform) {
        Platform.JD -> Triple(Color(0xFFFFECEB), Color(0xFFD32F2F), "京东")
        Platform.TAOBAO -> Triple(Color(0xFFFFF0E6), Color(0xFFFF5722), "淘宝")
        Platform.TMALL -> Triple(Color(0xFFFFEBEE), Color(0xFFC2185B), "天猫")
        Platform.DIDI -> Triple(Color(0xFFFFF1E5), Color(0xFFDF6900), "滴滴")
        Platform.GENERIC -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "通用",
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun MatchTypeBadge(type: MatchType) {
    val label = when (type) {
        MatchType.EXACT_HOST -> "精确"
        MatchType.SUFFIX_HOST -> "后缀"
        MatchType.REGEX -> "正则"
        MatchType.KEYWORD -> "关键词"
        MatchType.KOULING -> "口令"
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
