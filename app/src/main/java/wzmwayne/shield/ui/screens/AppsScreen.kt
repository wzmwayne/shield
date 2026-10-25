package wzmwayne.shield.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import wzmwayne.shield.control.AppActions
import wzmwayne.shield.core.AppFilter
import wzmwayne.shield.ui.ShieldViewModel
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

/** 应用管理：全部应用按风险评分降序，点击可对单个应用执行操作 */
@Composable
fun AppsScreen(
    apps: List<ShieldViewModel.AppRiskRow>,
    loading: Boolean,
    appFilter: AppFilter,
    actionTarget: String?,
    onFilterChange: (AppFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpenActions: (String) -> Unit,
    onCloseActions: () -> Unit,
    onRunAction: (String, AppActions.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("应用管理", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "按风险评分降序；点击应用可隐藏/禁用/禁止卸载/撤销权限/卸载/打开详情",
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
        }
        ShieldButton(
            text = if (loading) "扫描中…" else "重新扫描（${apps.size} 个应用）",
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        )

        // 选中的应用操作面板
        if (actionTarget != null) {
            val row = apps.firstOrNull { it.packageName == actionTarget }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "操作：${row?.label ?: actionTarget}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShieldWhite,
                )
                Text(actionTarget, style = MaterialTheme.typography.labelSmall, color = ShieldTextDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldButton("隐藏", { onRunAction(actionTarget, AppActions.Action.HIDE) }, modifier = Modifier.weight(1f))
                    ShieldButton("取消隐藏", { onRunAction(actionTarget, AppActions.Action.UNHIDE) }, filled = false, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldButton("禁用(停止)", { onRunAction(actionTarget, AppActions.Action.SUSPEND) }, modifier = Modifier.weight(1f))
                    ShieldButton("恢复运行", { onRunAction(actionTarget, AppActions.Action.UNSUSPEND) }, filled = false, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldButton("禁止卸载", { onRunAction(actionTarget, AppActions.Action.BLOCK_UNINSTALL) }, modifier = Modifier.weight(1f))
                    ShieldButton("允许卸载", { onRunAction(actionTarget, AppActions.Action.ALLOW_UNINSTALL) }, filled = false, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldButton("撤销所有权限", { onRunAction(actionTarget, AppActions.Action.REVOKE_ALL) }, modifier = Modifier.weight(1f))
                    ShieldButton("恢复权限", { onRunAction(actionTarget, AppActions.Action.RESTORE_PERMISSIONS) }, filled = false, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShieldButton("应用详情", { onRunAction(actionTarget, AppActions.Action.DETAILS) }, modifier = Modifier.weight(1f))
                }
                ShieldButton("卸载应用（危险）", { onRunAction(actionTarget, AppActions.Action.UNINSTALL) }, modifier = Modifier.fillMaxWidth())
                ShieldButton("收起操作面板", onCloseActions, filled = false, modifier = Modifier.fillMaxWidth())
            }
        }

        apps.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenActions(row.packageName) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${row.score}",
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            row.score >= 60 -> Color(0xFFFF5555)
                            row.score >= 30 -> Color(0xFFFFC107)
                            else -> ShieldTextSecondary
                        },
                        modifier = Modifier.weight(0.12f),
                    )
                    Column(modifier = Modifier.weight(0.88f)) {
                        Text(row.label, style = MaterialTheme.typography.bodySmall, color = ShieldWhite)
                        Text(row.packageName, style = MaterialTheme.typography.labelSmall, color = ShieldTextDim)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.tags.forEach { tag ->
                        StateChip(
                            text = tag,
                            filled = tag.startsWith("无障碍") || tag == "设备管理器",
                        )
                    }
                }
            }
        }
    }
}
