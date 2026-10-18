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
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

/** 白名单配置页：白名单 = 锁盾自身（硬编码） + 这里勾选的应用 */
@Composable
fun WhitelistScreen(
    apps: List<PackageInventory.AppEntry>,
    appFilter: AppFilter,
    appsLoading: Boolean,
    busy: Boolean,
    onFilterChange: (AppFilter) -> Unit,
    onRefreshApps: () -> Unit,
    onToggleTrusted: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 已勾选（受保护）的应用自动顶置，其余按名称排序
    val ordered = apps.sortedWith(
        compareBy<PackageInventory.AppEntry> { if (it.trusted) 0 else 1 }
            .thenBy { it.label },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("白名单配置", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "白名单 = 锁盾自身（硬编码，永不排除） + 下面勾选的应用。自动处置与全局阻断只处置**未勾选的第三方应用**；" +
                "系统应用不参与自动处置。",
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

        ShieldCard(title = "应用清单（${apps.size} 项，已选 ${apps.count { it.trusted }}）") {
            if (apps.isEmpty()) {
                Text("未检测到应用（点刷新或切换筛选）。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
            }
            ordered.forEach { app ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = app.trusted,
                        onCheckedChange = { checked -> onToggleTrusted(app.packageName, checked) },
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
                        app.trusted -> StateChip(text = "受保护", filled = true)
                        app.isSystem -> StateChip(text = "系统·不自动处置", filled = false)
                    }
                }
            }
        }
    }
}
