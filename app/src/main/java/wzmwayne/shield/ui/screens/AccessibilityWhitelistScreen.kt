package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import wzmwayne.shield.core.AppFilter
import wzmwayne.shield.core.PackageInventory
import wzmwayne.shield.protection.AccessibilityWhitelist
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

/**
 * 无障碍白名单（设备所有者 `setPermittedAccessibilityServices`）：
 * 复选列表 + 一键勾选 + 下发 / 清除。勾选中的应用**自动顶置**。
 */
@Composable
fun AccessibilityWhitelistScreen(
    apps: List<PackageInventory.AppEntry>,
    selected: Set<String>,
    appFilter: AppFilter,
    appsLoading: Boolean,
    busy: Boolean,
    current: String,
    onFilterChange: (AppFilter) -> Unit,
    onRefreshApps: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = apps.sortedWith(
        compareBy<PackageInventory.AppEntry> { if (it.packageName in selected) 0 else 1 }
            .thenBy { it.label },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("无障碍白名单", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "当前：$current",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton("返回设置", onBack, filled = false, modifier = Modifier.weight(1f))
            ShieldButton(
                text = if (appsLoading) "刷新中…" else "刷新",
                onClick = onRefreshApps,
                enabled = !appsLoading,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                AppFilter.USER to "用户",
                AppFilter.SYSTEM to "系统",
                AppFilter.ALL to "所有",
            ).forEach { (filter, label) ->
                ShieldButton(
                    text = label,
                    onClick = { onFilterChange(filter) },
                    filled = filter != appFilter,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton(
                text = "一键勾选应用白名单",
                onClick = onSelectAll,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            ShieldButton(
                text = "清除限制",
                onClick = onClear,
                enabled = !busy,
                filled = false,
                modifier = Modifier.weight(1f),
            )
        }

        ShieldButton(
            text = "下发白名单（${selected.size + 1} 个包名，含锁盾自身）",
            onClick = onApply,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        ShieldCard(title = "应用清单（${apps.size} 项，已选 ${selected.size}）") {
            if (apps.isEmpty()) {
                Text("未检测到应用（点刷新或切换筛选）。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
            }
            ordered.forEach { app ->
                val isSelf = app.packageName == AccessibilityWhitelist.SELF_PACKAGE
                val checked = isSelf || app.packageName in selected
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value -> if (!isSelf) onToggle(app.packageName, value) },
                        enabled = !isSelf,
                        colors = CheckboxDefaults.colors(
                            checkedColor = ShieldWhite,
                            checkmarkColor = ShieldBlack,
                            uncheckedColor = ShieldTextDim,
                        ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodySmall, color = ShieldWhite)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = ShieldTextDim)
                    }
                    when {
                        isSelf -> StateChip(text = "锁盾自身·必选", filled = true)
                        checked -> StateChip(text = "已加入", filled = true)
                        app.isSystem -> StateChip(text = "系统", filled = false)
                    }
                }
            }
        }
    }
}
