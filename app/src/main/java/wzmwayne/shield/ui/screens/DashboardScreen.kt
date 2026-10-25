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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import wzmwayne.shield.core.ActivationState
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.OwnerMode
import wzmwayne.shield.core.ShieldEvent
import wzmwayne.shield.ui.components.EventRow
import wzmwayne.shield.ui.components.KeyValueRow
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

@Composable
fun DashboardScreen(
    activationState: ActivationState,
    ownerMode: OwnerMode,
    keepAliveEnabled: Boolean,
    serviceConnected: Boolean,
    foregroundApp: String,
    lastKillGapMs: Long,
    fixCount: Int,
    protectionIssues: Int,
    events: List<ShieldEvent>,
    busy: Boolean,
    selfDestructed: Boolean,
    sensitiveLocked: Boolean,
    panicActive: Boolean,
    onOpenWizard: () -> Unit,
    onCheckNow: () -> Unit,
    onRefresh: () -> Unit,
    onSelfDestruct: () -> Unit,
    onBlockDebug: () -> Unit,
    killUiShown: Boolean,
    onTitleTaps: () -> Unit,
    onAllowDebug: () -> Unit,
    onResumeProtection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSuicideDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var titleTaps by remember { mutableIntStateOf(0) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "锁盾",
                style = MaterialTheme.typography.headlineMedium,
                color = ShieldWhite,
                modifier = Modifier.clickable {
                    titleTaps += 1
                    if (titleTaps >= 10) {
                        titleTaps = 0
                        onTitleTaps()
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            StateChip(
                text = activationState.label,
                filled = activationState != ActivationState.PROTECTING,
            )
            Spacer(Modifier.weight(1f))
            ShieldButton(
                text = if (busy) "检测中…" else "刷新",
                onClick = onRefresh,
                enabled = !busy,
                filled = false,
            )
        }

        if (selfDestructed) {
            ShieldCard(title = "应用已停摆") {
                Text(
                    text = "自保护已关闭、无障碍已移除、设备所有者已释放。需要重新激活并点击下方按钮才会恢复防护。",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldTextSecondary,
                )
                ShieldButton(
                    text = "重新启用防护",
                    onClick = onResumeProtection,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ShieldCard(title = "防护状态") {
            KeyValueRow("设备所有者", ownerMode.label)
            KeyValueRow("保活层", if (keepAliveEnabled) "已开启" else "已关闭")
            KeyValueRow("无障碍服务", if (serviceConnected) "已连接" else "未连接")
            KeyValueRow(
                "自我保护",
                if (protectionIssues == 0) "全部正常（第 40-45 条）" else "$protectionIssues 项需要关注",
            )
            KeyValueRow(
                "响应层",
                when {
                    panicActive -> "紧急处置生效中"
                    sensitiveLocked -> "敏感页看护生效中"
                    else -> "待命（连按音量键 3 次触发全局阻断）"
                },
            )
            KeyValueRow("当前前台应用", foregroundApp.ifEmpty { "未知" })
            KeyValueRow(
                "进程中断记录",
                if (lastKillGapMs > 0) {
                    "上次中断约 ${lastKillGapMs / 1000} 秒，累计修复 $fixCount 次"
                } else {
                    "无强杀记录"
                },
            )
        }

        ShieldCard(title = "最近事件") {
            if (events.isEmpty()) {
                Text(
                    text = "暂无事件",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldTextSecondary,
                )
            } else {
                events.take(5).forEach { EventRow(it) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton("进入激活向导", onOpenWizard, modifier = Modifier.weight(1f))
            ShieldButton("立即保活检测", onCheckNow, modifier = Modifier.weight(1f), filled = false)
        }

        ShieldCard(title = "调试通道") {
            Text(
                text = "禁止调试 = 限制开发者选项 + 关闭 ADB（全局阻断会自动执行）。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShieldButton("禁止调试", onBlockDebug, enabled = !busy, modifier = Modifier.weight(1f))
                ShieldButton("恢复调试", onAllowDebug, enabled = !busy, filled = false, modifier = Modifier.weight(1f))
            }
        }

        if (killUiShown) ShieldCard(title = "危险操作") {
            Text(
                text = "释放设备所有者 → 关闭自保护 → 移出无障碍 → 停止服务 → 退出进程（需重新激活）。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            ShieldButton(
                text = "一键自杀",
                onClick = { showSuicideDialog = true },
                enabled = !busy,
                filled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showSuicideDialog) {
            SelfDestructDialog(
                onDismiss = { showSuicideDialog = false },
                onConfirm = {
                    showSuicideDialog = false
                    onSelfDestruct()
                },
            )
        }

        Text(
            text = "自动处置只阻断（不卸载、不清数据、不碰系统应用）；卸载与清理仅在覆盖层手动执行。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
    }
}

@Composable
private fun SelfDestructDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var remaining by remember { mutableIntStateOf((Constants.SUICIDE_CONFIRM_WAIT_MS / 1000L).toInt()) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认一键自杀？", color = ShieldWhite) },
        text = {
            Text(
                text = "此操作不可撤销：\n" +
                    "1. 释放锁盾的设备所有者权限（卸载阻止与无障碍白名单随之撤销）\n" +
                    "2. 关闭自保护并停止前台服务\n" +
                    "3. 把锁盾移出无障碍启用列表\n" +
                    "4. 退出进程，应用完全停摆\n\n" +
                    if (remaining > 0) "请在 $remaining 秒后再确认。" else "现在可以确认。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = remaining <= 0) {
                Text(text = if (remaining > 0) "$remaining 秒后可确认" else "确认自杀")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

val ActivationState.label: String
    get() = when (this) {
        ActivationState.NOT_ACTIVATED -> "未激活"
        ActivationState.ACTIVATING -> "激活中"
        ActivationState.PROTECTING -> "防护中"
    }

val OwnerMode.label: String
    get() = when (this) {
        OwnerMode.NONE -> "未设置"
        OwnerMode.DIRECT -> "锁盾自身持有（委派模式已禁用）"
    }

@Composable
fun OwnerModeOption(
    mode: OwnerMode,
    selected: OwnerMode,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val active = mode == selected
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateChip(text = if (active) "已选择" else "未选择", filled = active)
            Spacer(Modifier.width(8.dp))
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = ShieldWhite)
        }
        Text(text = description, style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
        ShieldButton(
            text = if (active) "当前模式" else "选择此模式",
            onClick = onClick,
            filled = !active,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
