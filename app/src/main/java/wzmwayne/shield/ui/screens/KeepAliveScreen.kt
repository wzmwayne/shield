package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import wzmwayne.shield.core.Constants
import wzmwayne.shield.ui.LiveSnapshot
import wzmwayne.shield.ui.components.KeyValueRow
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldBorder
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun KeepAliveScreen(
    keepAliveEnabled: Boolean,
    intervalSeconds: Int,
    serviceConnected: Boolean,
    lastHeartbeat: Long,
    fixCount: Int,
    killGapMs: Long,
    live: LiveSnapshot,
    onToggle: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onCheckNow: () -> Unit,
    onRestartService: () -> Unit,
    onRepairAccessibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("无障碍保活", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)

        ShieldCard(title = "保活开关") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = keepAliveEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ShieldBlack,
                        checkedTrackColor = ShieldWhite,
                        checkedBorderColor = ShieldWhite,
                        uncheckedThumbColor = ShieldTextDim,
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = ShieldBorder,
                    ),
                )
                Text(
                    text = if (keepAliveEnabled) "保活运行中：设置变化 / 解锁 / 定时都会复查" else "保活已关闭：无障碍服务被移除后不会自动恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldTextSecondary,
                )
            }
        }

        ShieldCard(title = "实时状态") {
            KeyValueRow("系统设置内", if (live.listed) "是" else "否")
            KeyValueRow("系统已绑定", if (live.systemBound) "是" else "否")
            KeyValueRow("无障碍总开关", if (live.accessibilityEnabled) "已开启" else "已关闭")
            KeyValueRow("保活服务", if (serviceConnected) "运行中" else "未连接")
            KeyValueRow(
                "最近心跳",
                if (lastHeartbeat > 0) TIME_FORMAT.format(Date(lastHeartbeat)) else "尚未收到",
            )
            KeyValueRow("DUMP 权限", if (live.dumpGranted) "已授予（可检测假死）" else "未授予（只能定时重置）")
            KeyValueRow("累计修复次数", fixCount.toString())
            KeyValueRow(
                "上次进程中断",
                if (killGapMs > 0) "约 ${killGapMs / 1000} 秒（已自动拉起）" else "无记录",
            )
            KeyValueRow(
                "快照时间",
                if (live.updatedAt > 0) TIME_FORMAT.format(Date(live.updatedAt)) else "刷新中",
            )
        }

        ShieldCard(title = "定时检测间隔") {
            Text(
                text = "当前 $intervalSeconds 秒（下限 ${Constants.MIN_CHECK_INTERVAL_SECONDS} 秒，步进 ${Constants.CHECK_INTERVAL_STEP_SECONDS} 秒）。" +
                    "注意：息屏深度休眠时系统会推迟 Alarm，此时由无障碍服务内的 10 秒节拍兜底。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShieldButton(
                    "-${Constants.CHECK_INTERVAL_STEP_SECONDS} 秒",
                    { onInterval(intervalSeconds - Constants.CHECK_INTERVAL_STEP_SECONDS) },
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
                ShieldButton(
                    "+${Constants.CHECK_INTERVAL_STEP_SECONDS} 秒",
                    { onInterval(intervalSeconds + Constants.CHECK_INTERVAL_STEP_SECONDS) },
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ShieldButton("写回无障碍设置", onRepairAccessibility, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton("立即检测并修复", onCheckNow, modifier = Modifier.weight(1f))
            ShieldButton("重启保活服务", onRestartService, filled = false, modifier = Modifier.weight(1f))
        }

        ShieldCard(title = "保活策略（只保活锁盾自身）") {
            Text(
                text = "0. 无障碍服务内每 ${Constants.LOCAL_CHECK_INTERVAL_SECONDS} 秒自检一次；应用侧每 ${Constants.DEFAULT_CHECK_INTERVAL_SECONDS} 秒定时检测（可调，下限 ${Constants.MIN_CHECK_INTERVAL_SECONDS} 秒）。\n" +
                    "1. 监听 enabled_accessibility_services：一旦自身被移出，立即用 WRITE_SECURE_SETTINGS 写回。\n" +
                    "2. 崩溃检测：dumpsys accessibility 报告自身崩溃时，执行「关闭 → 延迟 1 秒 → 重开」。\n" +
                    "3. 复查：5 秒后复查，仍失败则重试最多 ${Constants.MAX_FIX_RETRY} 次。\n" +
                    "4. 重启强杀：服务仍在启用列表中却持续不健康时才强杀自身进程；带 15 分钟退避，且 6 小时内最多 3 次，避免杀-拉起死循环。\n" +
                    "4b. 被反复关闭：1 分钟内被关闭 3 次 → 重启无障碍服务（写回启用列表并拉起前台服务，**不重启设备**）并执行全局阻断；" +
                    "阻断过程中被关闭 → 先中止当前阻断，后台禁用调试后再重跑全局阻断（10 分钟内最多升级 3 次）。\n" +
                    "5. 语义：只要保活开关（或自保护）开着，锁盾被移出启用列表就一律立即写回，包括你在系统设置里手动关闭。" +
                    "要真正停掉，请先关掉这个保活开关。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
