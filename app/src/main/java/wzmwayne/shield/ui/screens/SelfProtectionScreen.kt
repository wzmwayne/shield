package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import wzmwayne.shield.core.CheckItem
import wzmwayne.shield.core.CheckState
import wzmwayne.shield.core.AppFilter
import wzmwayne.shield.core.PackageInventory
import wzmwayne.shield.protection.AccessibilityWhitelist
import wzmwayne.shield.response.PanicAppEntry
import wzmwayne.shield.ui.components.CheckRow
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldBorder
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

@Composable
fun SelfProtectionScreen(
    items: List<CheckItem>,
    busy: Boolean,
    apps: List<PackageInventory.AppEntry>,
    appFilter: AppFilter,
    appsLoading: Boolean,
    onFilterChange: (AppFilter) -> Unit,
    onRefreshApps: () -> Unit,
    panicActive: Boolean,
    panicEntries: List<PanicAppEntry>,
    sensitiveLocked: Boolean,
    onRestoreSensitive: () -> Unit,
    onApply: () -> Unit,
    onVerify: () -> Unit,
    onRepair: (String) -> Unit,
    onToggleTrusted: (String, Boolean) -> Unit,
    onTriggerPanic: () -> Unit,
    onRestorePanic: () -> Unit,
    onReopenOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("自我保护与响应", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "自我保护（第 40-45 条） + 响应对抗层（自动针对性阻断 / 手势触发全局阻断）。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton("重新应用保护", onApply, enabled = !busy, modifier = Modifier.weight(1f))
            ShieldButton("立即校验", onVerify, enabled = !busy, filled = false, modifier = Modifier.weight(1f))
        }

        if (items.isEmpty()) {
            Text("尚未校验。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
        } else {
            items.forEach { CheckRow(item = it, onAction = onRepair) }
        }

        // ── 响应层状态 ───────────────────────────────────────────
        ShieldCard(title = "响应层状态") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateChip(text = if (sensitiveLocked) "生效中" else "未生效", filled = sensitiveLocked)
                Spacer(Modifier.width(8.dp))
                Text("敏感页看护", style = MaterialTheme.typography.bodySmall, color = ShieldWhite)
            }
            Text(
                text = "进入锁盾自身页面时收紧无障碍（仅留白名单 + 本应用），离开 8 秒自动恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            if (sensitiveLocked) {
                ShieldButton(
                    text = "撤销敏感页看护（立即恢复无障碍）",
                    onClick = onRestoreSensitive,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateChip(text = if (panicActive) "生效中" else "待命", filled = panicActive)
                Spacer(Modifier.width(8.dp))
                Text("紧急处置覆盖层", style = MaterialTheme.typography.bodySmall, color = ShieldWhite)
            }
            Text(
                text = "非白名单覆盖层占用 ≥70% 屏幕 → 对该应用针对性阻断（隐藏 + 挂起 + 撤权）。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Text(
                text = "无障碍 1 分钟内被关闭 3 次 → 重启无障碍服务 + 全局阻断；阻断中被关闭 → 中止 → 禁调试 → 重跑全局阻断。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
        }

        // ── 紧急处置 ─────────────────────────────────────────────
        ShieldCard(title = if (panicActive) "紧急处置生效中（全局阻断）" else "紧急处置（全局阻断，手动触发）") {
            Text(
                text = "触发：连按音量键 3 次（间隔 ≤700ms）→ 三长音提示 + 覆盖层 → 全局阻断。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Text(
                text = "内容：收紧白名单 + 关闭其它无障碍 + 禁调试 + 处置非白名单第三方应用。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            if (panicActive) {
                Text(
                    text = "覆盖层：流式过程 + 可滚动复选列表（失败仍在运行的红标顶置）+ 处置按钮组。",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldWhite,
                )
                Text(
                    text = "已处置 ${panicEntries.size} 个应用：隐藏 ${panicEntries.count { it.hidden }}｜" +
                        "挂起 ${panicEntries.count { it.suspended }}｜" +
                        "撤权 ${panicEntries.sumOf { it.revokedPermissions.size }} 项｜" +
                        "失败 ${panicEntries.count { !it.error.isNullOrEmpty() }} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldWhite,
                )
                panicEntries.take(12).forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateChip(text = if (entry.error.isNullOrEmpty()) "已处置" else "部分失败", filled = true)
                            Spacer(Modifier.width(8.dp))
                            Text(entry.label, style = MaterialTheme.typography.bodySmall, color = ShieldWhite)
                        }
                        Text(
                            text = entry.packageName + (entry.error?.let { "｜$it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = ShieldTextDim,
                        )
                    }
                }
                if (panicEntries.size > 12) {
                    Text(
                        text = "（仅显示前 12 个，共 ${panicEntries.size} 个）",
                        style = MaterialTheme.typography.labelSmall,
                        color = ShieldTextDim,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShieldButton(
                        text = "重新打开覆盖层",
                        onClick = onReopenOverlay,
                        enabled = !busy,
                        filled = false,
                        modifier = Modifier.weight(1f),
                    )
                    ShieldButton(
                        text = "撤销处置并恢复",
                        onClick = onRestorePanic,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                ShieldButton(
                    text = "手动触发紧急处置（测试用）",
                    onClick = onTriggerPanic,
                    enabled = !busy,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── 保护对象白名单 ───────────────────────────────────────
        ShieldCard(title = "保护对象白名单（仅自身 + 勾选项）") {
            Text(
                text = "白名单 = 锁盾自身 + 勾选的应用；未勾选的第三方应用会被处置，系统应用不参与自动处置。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
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
                ShieldButton(
                    text = if (appsLoading) "刷新中…" else "刷新",
                    onClick = onRefreshApps,
                    enabled = !appsLoading,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
            }
            val thirdParty = apps
            if (thirdParty.isEmpty()) {
                Text("未检测到应用（可切换筛选或点刷新）。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
            } else {
                Text(
                    text = "共 ${thirdParty.size} 项（当前筛选：${when (appFilter) { AppFilter.USER -> "用户"; AppFilter.SYSTEM -> "系统"; AppFilter.ALL -> "所有" } }），已勾选 ${thirdParty.count { it.trusted }} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShieldTextDim,
                )
                thirdParty.take(60).forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = ShieldTextDim,
                            )
                        }
                        if (app.trusted) {
                            StateChip(text = "受保护", filled = false)
                        } else if (app.isSystem) {
                            StateChip(text = "系统应用·自动不处置", filled = false)
                        }
                    }
                }
                if (thirdParty.size > 60) {
                    Text(
                        text = "（仅显示前 60 个，共 ${thirdParty.size} 个）",
                        style = MaterialTheme.typography.labelSmall,
                        color = ShieldTextDim,
                    )
                }
            }
        }

        ShieldCard(title = "边界声明") {
            Text(
                text = "白名单硬编码自身组件：${AccessibilityWhitelist.SELF_COMPONENT}",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Text(
                text = "不做：恢复出厂、重启设备。自动处置不卸载不清数据（卸载/清理仅在覆盖层手动执行）。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
        }
    }
}
