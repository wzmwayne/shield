package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import wzmwayne.shield.control.DeviceOpState
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.port.owndroid.Restriction
import wzmwayne.shield.port.owndroid.UserRestrictions
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

@Composable
fun DeviceOpsScreen(
    ops: List<DeviceOpState>,
    restrictions: Map<String, Boolean>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onToggle: (DeviceOpState, Boolean) -> Unit,
    onSetRestriction: (Restriction, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设备管理面板", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "全部开关可逆；不含恢复出厂与重启。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton("返回设置", onBack, filled = false, modifier = Modifier.weight(1f))
            ShieldButton("刷新状态", onRefresh, enabled = !busy, modifier = Modifier.weight(1f))
        }

        ShieldCard(title = "能力开关") {
            if (ops.isEmpty()) {
                Text("尚未读取状态，点「刷新状态」。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
            }
            ops.forEach { state ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateChip(text = if (state.enabled) "已开启" else "已关闭", filled = state.enabled)
                        Spacer(Modifier.width(8.dp))
                        Text(state.op.title, style = MaterialTheme.typography.bodyMedium, color = ShieldWhite)
                        Spacer(Modifier.weight(1f))
                        ShieldButton(
                            text = when {
                                state.op.danger -> "执行"
                                state.enabled -> "关闭"
                                else -> "开启"
                            },
                            onClick = { onToggle(state, !state.enabled) },
                            enabled = !busy,
                            filled = !state.enabled,
                        )
                    }
                    Text(state.op.description, style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
                    Text(state.detail, style = MaterialTheme.typography.labelSmall, color = ShieldTextDim)
                }
            }
        }

        ShieldCard(title = "用户限制（移植自 OwnDroid）") {
            Text(
                text = "当前系统可用 ${UserRestrictions.all().size} 项，执行后立即回读状态。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            if (restrictions.isEmpty()) {
                Text("点「刷新状态」读取当前限制。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
            }
            UserRestrictions.available(android.os.Build.VERSION.SDK_INT).forEach { (category, list) ->
                if (list.isEmpty()) return@forEach
                Text(
                    text = "— ${category.title}（${list.size}）—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShieldWhite,
                )
                list.forEach { restriction ->
                    val enabled = restrictions[restriction.key] == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = restriction.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) ShieldWhite else ShieldTextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (enabled) "已限制" else "未限制",
                            style = MaterialTheme.typography.labelSmall,
                            color = ShieldTextDim,
                        )
                        Spacer(Modifier.width(8.dp))
                        ShieldButton(
                            text = if (enabled) "解除" else "限制",
                            onClick = { onSetRestriction(restriction, !enabled) },
                            enabled = !busy,
                            filled = !enabled,
                        )
                    }
                }
            }
        }

        ShieldCard(title = "说明") {
            Text(
                text = "用户限制目录移植自 OwnDroid（GPL-3.0）；详见 LICENSE / NOTICE。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
        }
    }
}
