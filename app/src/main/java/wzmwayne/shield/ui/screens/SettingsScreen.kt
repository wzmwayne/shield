package wzmwayne.shield.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.Packages
import wzmwayne.shield.debug.DebugHttpServer
import wzmwayne.shield.port.owndroid.UserRestrictions
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldWhite

@Composable
fun SettingsScreen(
    busy: Boolean,
    onBlockDebug: () -> Unit,
    onAllowDebug: () -> Unit,
    onOpenDeviceOps: () -> Unit,
    onOpenWhitelist: () -> Unit,
    organizationName: String,
    onEditOrganization: (String) -> Unit,
    lockScreenText: String,
    shortMessage: String,
    longMessage: String,
    onEditLockScreen: (String) -> Unit,
    onEditShort: (String) -> Unit,
    onEditLong: (String) -> Unit,
    onSaveTexts: () -> Unit,
    onResetTexts: () -> Unit,
    trustedHomes: Set<String>,
    primaryHome: String,
    currentHome: String,
    homeCandidates: List<Packages.Entry>,
    onToggleTrustedHome: (String, Boolean) -> Unit,
    onSetPrimaryHome: (String) -> Unit,
    onLockHome: () -> Unit,
    debugHttpEnabled: Boolean,
    debugHttpPort: Int,
    onToggleDebugHttp: (Boolean, Int) -> Unit,
    pinEnabled: Boolean,
    onEnablePin: (String) -> Unit,
    onDisablePin: () -> Unit,
    whitelistCurrent: String,
    onOpenAccessWhitelist: () -> Unit,
    onClearWhitelist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)

        ShieldCard(title = "调试通道") {
            Text(
                text = "禁止调试 = 限制开发者选项 + 关闭 ADB；全局阻断会自动执行。「恢复调试」用于测试期开回 adb。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShieldButton(
                    text = "禁止调试（开发者选项 + ADB）",
                    onClick = onBlockDebug,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                ShieldButton(
                    text = "恢复调试",
                    onClick = onAllowDebug,
                    enabled = !busy,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ShieldCard(title = "可信桌面（防默认桌面劫持）") {
            Text(
                text = "当前默认桌面：${currentHome.ifEmpty { "未知" }}｜可信：${trustedHomes.size} 个｜主桌面：${primaryHome.ifEmpty { "未设置" }}",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Text(
                text = "勾选=可信（在可信集合内都放行）；单选=主桌面（一旦默认桌面被换成不可信应用，自动锁回主桌面并处置该应用）。",
                style = MaterialTheme.typography.labelSmall,
                color = ShieldTextDim,
            )
            if (homeCandidates.isEmpty()) {
                Text("未找到桌面应用（点刷新）。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
            }
            homeCandidates.forEach { app ->
                val trusted = app.packageName in trustedHomes
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = trusted,
                        onCheckedChange = { value -> onToggleTrustedHome(app.packageName, value) },
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
                    if (app.packageName == currentHome) StateChip(text = "当前默认", filled = true)
                    androidx.compose.material3.RadioButton(
                        selected = app.packageName == primaryHome,
                        onClick = { onSetPrimaryHome(app.packageName) },
                        enabled = trusted,
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(
                            selectedColor = ShieldWhite,
                            unselectedColor = ShieldTextDim,
                        ),
                    )
                }
            }
            ShieldButton("立即锁回可信桌面", onLockHome, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }

        ShieldCard(title = "调试接口（HTTP，需 PIN）") {
            var portInput by remember { mutableStateOf(debugHttpPort.toString()) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (debugHttpEnabled) "已开启" else "已关闭",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShieldWhite,
                    )
                    Text(
                        text = if (pinEnabled) {
                            "固定绑定 0.0.0.0；开启后随应用自动启动"
                        } else {
                            "需先启用应用 PIN"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = ShieldTextDim,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = debugHttpEnabled,
                    onCheckedChange = { value ->
                        onToggleDebugHttp(value, portInput.toIntOrNull() ?: 8686)
                    },
                    enabled = !busy && pinEnabled,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = ShieldBlack,
                        checkedTrackColor = ShieldWhite,
                        checkedBorderColor = ShieldWhite,
                        uncheckedThumbColor = ShieldTextDim,
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = ShieldTextSecondary,
                    ),
                )
            }
            if (debugHttpEnabled) {
                val urls = DebugHttpServer.urls(context = LocalContext.current, port = debugHttpPort)
                Text(
                    text = urls.joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = ShieldTextSecondary,
                )
                if (urls.size == 1) {
                    Text(
                        text = "⚠ 未检测到局域网 IP（Wi-Fi 未连接）：目前只有手机本机可访问",
                        style = MaterialTheme.typography.labelSmall,
                        color = ShieldTextDim,
                    )
                }
            }
            TextField(
                value = portInput,
                onValueChange = { value -> portInput = value.filter { it.isDigit() }.take(5) },
                label = "端口（默认 8686，改完重新开关一次生效）",
                minLines = 1,
            )
            Text(
                text = "接口：GET /status /apps /logs /ui /screenshot /accessibility /whitelist /record；" +
                    "POST /install /push /panic /policy /access-whitelist /app-whitelist /accessibility /record。请求头 X-Shield-Pin 必填。",
                style = MaterialTheme.typography.labelSmall,
                color = ShieldTextDim,
            )
            Text(
                text = "⚠ 绑定 0.0.0.0：同一 Wi-Fi 下知道 PIN 即可控制设备（安装 APK/阻断/改白名单）。明文 HTTP，用完请关闭。",
                style = MaterialTheme.typography.labelSmall,
                color = ShieldTextDim,
            )
        }

        ShieldCard(title = "应用 PIN 码（可选）") {
            Text(
                text = if (pinEnabled) {
                    "已启用：每次回到前台都要输入一次"
                } else {
                    "未启用：可设置 4-8 位数字，启用后回到前台需输入"
                },
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            var pinInput by remember { mutableStateOf("") }
            TextField(
                value = pinInput,
                onValueChange = { value -> pinInput = value.filter { it.isDigit() }.take(8) },
                label = if (pinEnabled) "修改 PIN（4-8 位数字）" else "设置 PIN（4-8 位数字）",
                minLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShieldButton(
                    text = if (pinEnabled) "修改" else "启用",
                    onClick = {
                        onEnablePin(pinInput)
                        pinInput = ""
                    },
                    enabled = !busy && pinInput.length >= 4,
                    modifier = Modifier.weight(1f),
                )
                ShieldButton(
                    text = "关闭 PIN",
                    onClick = onDisablePin,
                    enabled = !busy && pinEnabled,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "锁层渲染在本应用内部（非独立页面），无法用外部命令直接跳过。",
                style = MaterialTheme.typography.labelSmall,
                color = ShieldTextDim,
            )
        }

        ShieldCard(title = "组织名称与提示信息（设备所有者）") {
            TextField(
                value = organizationName,
                onValueChange = onEditOrganization,
                label = "组织名称",
                minLines = 1,
            )
            TextField(
                value = lockScreenText,
                onValueChange = onEditLockScreen,
                label = "锁屏提示信息（覆盖并锁定系统锁屏信息）",
                minLines = 2,
            )
            TextField(
                value = shortMessage,
                onValueChange = onEditShort,
                label = "提供支持的短消息（≤200 字，禁用功能被触发时显示）",
                minLines = 1,
            )
            TextField(
                value = longMessage,
                onValueChange = onEditLong,
                label = "提供支持的长消息（≤20000 字，设备管理员详情里显示）",
                minLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShieldButton(
                    text = "保存并下发",
                    onClick = onSaveTexts,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                ShieldButton(
                    text = "恢复默认",
                    onClick = onResetTexts,
                    enabled = !busy,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ShieldCard(title = "无障碍白名单（设备所有者）") {
            Text(
                text = "当前：$whitelistCurrent",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShieldButton(
                    text = "打开白名单设置",
                    onClick = onOpenAccessWhitelist,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                ShieldButton(
                    text = "清除限制",
                    onClick = onClearWhitelist,
                    enabled = !busy,
                    filled = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ShieldCard(title = "白名单配置") {
            Text(
                text = "白名单 = 锁盾自身 + 勾选的应用；未勾选的第三方应用会被处置。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            ShieldButton(
                text = "打开白名单配置",
                onClick = onOpenWhitelist,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ShieldCard(title = "设备管理面板") {
            Text(
                text = "设备开关 + ${UserRestrictions.all().size} 项用户限制（移植自 OwnDroid，GPL-3.0）。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            ShieldButton(
                text = "打开设备管理面板",
                onClick = onOpenDeviceOps,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ShieldCard(title = "关于") {
            Text(
                text = "锁盾 ${BuildConfigShield.VERSION_LABEL}",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldWhite,
            )
            Text(
                text = "包名 ${BuildConfigShield.PACKAGE}　·　运行下限 Android 9（API 28）",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Text(
                text = "不做：恢复出厂、重启设备。自动处置不卸载、不清数据。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            Text(
                text = "保活节拍 ${Constants.LOCAL_CHECK_INTERVAL_SECONDS} 秒　·　定时检测默认 ${Constants.DEFAULT_CHECK_INTERVAL_SECONDS} 秒" +
                    "（下限 ${Constants.MIN_CHECK_INTERVAL_SECONDS} 秒）",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
        }
    }
}

/** 避免在 UI 里直接引用 BuildConfig 造成的测试耦合 */
object BuildConfigShield {
    const val PACKAGE = "wzmwayne.shield"
    const val VERSION_LABEL = "0.1.0"
}

/** 黑白风格多行输入框 */
@Composable
private fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ShieldWhite,
            unfocusedTextColor = ShieldWhite,
            focusedBorderColor = ShieldWhite,
            unfocusedBorderColor = ShieldTextSecondary,
            focusedLabelColor = ShieldWhite,
            unfocusedLabelColor = ShieldTextSecondary,
            cursorColor = ShieldWhite,
        ),
    )
}
