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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import wzmwayne.shield.core.ActivationState
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.OwnerMode
import wzmwayne.shield.shell.ShellState
import wzmwayne.shield.ui.AuxTarget
import wzmwayne.shield.ui.ShieldViewModel
import wzmwayne.shield.ui.components.CheckRow
import wzmwayne.shield.ui.components.MonospaceBlock
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.components.ShieldCard
import wzmwayne.shield.ui.components.StateChip
import wzmwayne.shield.ui.components.TraceBlock
import wzmwayne.shield.ui.theme.ShieldTextSecondary
import wzmwayne.shield.ui.theme.ShieldWhite

/** 顺序按「先拿到 Shizuku，再判断能否进入激活」安排 */
private val STEP_TITLES = listOf(
    "辅助软件",
    "环境检查",
    "所有者模式",
    "执行激活",
    "无障碍与保活",
    "自检",
)

@Composable
fun WizardScreen(
    ui: ShieldViewModel.UiState,
    activationState: ActivationState,
    ownerMode: OwnerMode,
    shizukuState: ShellState,
    isInstalled: (String) -> Boolean,
    onStep: (Int) -> Unit,
    onRefreshEnv: () -> Unit,
    onRequestShizuku: () -> Unit,
    onInstall: (AuxTarget) -> Unit,
    onOpenUnknownSources: () -> Unit,
    onSelectOwnerMode: (OwnerMode) -> Unit,
    onActivate: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onSelfCheck: () -> Unit,
    onRepair: (String) -> Unit,
    onFinish: () -> Unit,
    onVerifyManualActivation: () -> Unit,
    adbCommands: List<String>,
    adbScriptCommand: String,
    adbScriptReady: Boolean,
    trace: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("激活向导", style = MaterialTheme.typography.headlineMedium, color = ShieldWhite)
        Text(
            text = "当前状态：${activationState.label}　·　Shizuku：${shizukuState.label}",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STEP_TITLES.forEachIndexed { index, title ->
                val selected = index == ui.wizardStep
                StateChip(
                    text = "${index + 1}",
                    filled = selected,
                    modifier = Modifier.width(28.dp),
                )
                if (selected) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = ShieldTextSecondary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }

        ShieldCard(title = if (ui.busy) "执行结果（进行中…）" else "执行结果") {
            if (ui.banner.isNotEmpty()) {
                Text(
                    text = ui.banner,
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldWhite,
                )
            }
            TraceBlock(lines = trace)
            if (ui.banner.isEmpty() && trace.isEmpty()) {
                Text(
                    text = "点击上方任意「开始检查 / 执行激活 / 运行自检」后，这里会实时打印每一步的过程与判定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShieldTextSecondary,
                )
            }
        }

        when (ui.wizardStep) {
            0 -> StepAux(isInstalled, onInstall, onOpenUnknownSources, shizukuState)
            1 -> StepEnv(ui, onRefreshEnv, onRequestShizuku, onStep, adbCommands, adbScriptCommand, adbScriptReady, onVerifyManualActivation)
            2 -> StepOwnerMode(ownerMode, onSelectOwnerMode, isInstalled)
            3 -> StepActivate(ui, onActivate, activationState, adbCommands, adbScriptCommand, adbScriptReady, onVerifyManualActivation)
            4 -> StepAccessibility(ui, onEnableAccessibility)
            else -> StepSelfCheck(ui, onSelfCheck, onRepair)
        }

        if (ui.log.isNotEmpty()) {
            ShieldCard(title = "向导执行记录") {
                MonospaceBlock(ui.log.takeLast(8).joinToString("\n"))
            }
        }

        val isLastStep = ui.wizardStep >= STEP_TITLES.size - 1
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton(
                text = "上一步",
                onClick = { onStep(ui.wizardStep - 1) },
                enabled = ui.wizardStep > 0 && !ui.busy,
                filled = false,
                modifier = Modifier.weight(1f),
            )
            ShieldButton(
                text = if (isLastStep) "完成" else "下一步",
                onClick = { if (isLastStep) onFinish() else onStep(ui.wizardStep + 1) },
                // 「完成」在最后一步必须可点：回到仪表盘并做一次完整检测
                enabled = !ui.busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StepAux(
    isInstalled: (String) -> Boolean,
    onInstall: (AuxTarget) -> Unit,
    onOpenUnknownSources: () -> Unit,
    shizukuState: ShellState,
) {
    ShieldCard(title = "第 1 步：辅助软件部署") {
        Text(
            text = "先装好并启动 Shizuku（内置官方版），后面两步依赖它；任一步可跳过。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        AuxRow(
            title = "Shizuku 官方版 13.6.0",
            detail = "提供激活阶段所需的 Shell 权限；当前状态：${shizukuState.label}",
            installed = isInstalled(Constants.PKG_SHIZUKU),
            onInstall = { onInstall(AuxTarget.SHIZUKU) },
        )
        Text(
            text = "启动方式：有 root 时在 Shizuku 内点「通过 Root 启动」；否则用 adb 执行 Shizuku 给出的启动脚本（Android 11+ 也可用无线调试配对）。" +
                "启动后请在 Shizuku 中授权锁盾。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        AuxRow(
            title = "雹 Hail",
            detail = "冻结持有账户或多用户的应用，用于绕过设备所有者前置条件",
            installed = isInstalled(Constants.PKG_HAIL),
            onInstall = { onInstall(AuxTarget.HAIL) },
        )
        ShieldButton("允许锁盾安装应用", onOpenUnknownSources, filled = false, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepEnv(
    ui: ShieldViewModel.UiState,
    onRefreshEnv: () -> Unit,
    onRequestShizuku: () -> Unit,
    onStep: (Int) -> Unit,
    adbCommands: List<String>,
    adbScriptCommand: String,
    adbScriptReady: Boolean,
    onVerifyManualActivation: () -> Unit,
) {
    ShieldCard(title = "第 2 步：环境检查（能否进入激活）") {
        Text(
            text = "用 Shell 身份读取真实状态，判断能否进入激活；激活后的状态属于第 6 步自检。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton("开始检查", onRefreshEnv, enabled = !ui.busy, modifier = Modifier.weight(1f))
            ShieldButton("申请 Shizuku 授权", onRequestShizuku, filled = false, modifier = Modifier.weight(1f))
        }
        val report = ui.env
        if (report == null) {
            Text(
                text = "尚未检查。若上面 Shizuku 显示未运行/未授权，请先回到第 1 步完成安装与启动，再回到这里检查。",
                style = MaterialTheme.typography.bodySmall,
                color = ShieldTextSecondary,
            )
            return@ShieldCard
        }
        report.items.forEach { CheckRow(it) }
        val conclusion = when {
            report.canActivate -> "结论：前提全部满足，可以进入第 4 步「执行激活」。"
            report.ready -> "结论：硬性前提已满足，但 Shizuku 通道尚不可用，请先解决第一项。"
            else -> "结论：还有 ${report.blockers.size} 项阻断，处理后可重新检查。"
        }
        Text(conclusion, style = MaterialTheme.typography.bodySmall, color = ShieldWhite)
        if (ui.env?.shellAvailable == false) {
            AdbFallbackCard(
                scriptCommand = adbScriptCommand,
                scriptReady = adbScriptReady,
                commands = adbCommands,
                enabled = !ui.busy,
                onVerify = onVerifyManualActivation,
            )
        }
        // 跳转本身不是激活动作，允许带着"无法判定"继续；真正的门槛在「执行激活」那一刻
        ShieldButton(
            text = if (report.ready) "进入第 4 步：执行激活" else "仍然进入第 4 步（有项未能判定）",
            onClick = { onStep(3) },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AuxRow(
    title: String,
    detail: String,
    installed: Boolean,
    onInstall: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateChip(text = if (installed) "已安装" else "未安装", filled = !installed)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, color = ShieldWhite)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
        if (onInstall != null) {
            ShieldButton(
                text = if (installed) "重新安装" else "安装 $title",
                onClick = onInstall,
                filled = !installed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepOwnerMode(
    ownerMode: OwnerMode,
    onSelectOwnerMode: (OwnerMode) -> Unit,
    isInstalled: (String) -> Boolean,
) {
    ShieldCard(title = "第 3 步：设备所有者归属（强制）") {
        Text(
            text = "委派（Dhizuku）模式已禁用：锁盾必须自己持有设备所有者身份，才能执行卸载阻止、无障碍白名单等自我保护动作。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        OwnerModeOption(
            mode = OwnerMode.DIRECT,
            selected = if (ownerMode == OwnerMode.NONE) OwnerMode.DIRECT else ownerMode,
            title = "锁盾直接成为设备所有者（唯一可用）",
            description = "执行 dpm set-device-owner 将身份交给锁盾自身；若设备已被其他应用持有，需先移除对方的设备所有者身份。",
            onClick = { onSelectOwnerMode(OwnerMode.DIRECT) },
        )
        Text(
            text = "选择后第 4 步会执行：dpm set-device-owner → pm grant WRITE_SECURE_SETTINGS/DUMP → 预置恢复令牌。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
    }
}

@Composable
private fun StepActivate(
    ui: ShieldViewModel.UiState,
    onActivate: () -> Unit,
    activationState: ActivationState,
    adbCommands: List<String>,
    adbScriptCommand: String,
    adbScriptReady: Boolean,
    onVerifyManualActivation: () -> Unit,
) {
    ShieldCard(title = "第 4 步：执行激活") {
        Text(
            text = "通过 Shizuku 执行：set-device-owner → 授予两项权限 → 预置恢复令牌，并回读确认。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        Text(
            text = "注意：本步骤不会添加 DISALLOW_DEBUGGING_FEATURES，ADB 保持可用；也不会通过 Shell 写 enabled_accessibility_services。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        ShieldButton(
            text = if (activationState == ActivationState.NOT_ACTIVATED) "执行激活" else "重新执行激活",
            onClick = onActivate,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        // 「使用 adb 激活」：直接给出内置激活脚本的路径命令（脚本以 .so 形式随 APK 一起释放）
        AdbFallbackCard(
            scriptCommand = adbScriptCommand,
            scriptReady = adbScriptReady,
            commands = adbCommands,
            enabled = !ui.busy,
            onVerify = onVerifyManualActivation,
        )
    }
}

/**
 * Shizuku 通道不可用时的兜底：把命令交给用户用 adb 执行，执行完点「回读校验」继续。
 */
@Composable
private fun AdbFallbackCard(
    scriptCommand: String,
    scriptReady: Boolean,
    commands: List<String>,
    enabled: Boolean,
    onVerify: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    ShieldCard(title = "使用 adb 激活") {
        Text(
            text = if (scriptReady) {
                "点击「复制命令」后直接在电脑上执行即可（脚本以 .so 形式随 APK 打包，安装时已被系统释放到应用目录，无需手动推送）："
            } else {
                "内置激活脚本未找到（少数机型不释放 jniLibs），可改用下方逐条命令。复制后到电脑上执行："
            },
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        Text(
            text = scriptCommand,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (scriptReady) ShieldWhite else ShieldTextSecondary,
        )
        Text(
            text = "等价的手动命令（脚本执行的就是这三条）：",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        commands.forEachIndexed { index, command ->
            Text(
                text = "${index + 1}. $command",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = ShieldTextSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShieldButton(
                text = "复制命令",
                onClick = { clipboard.setText(AnnotatedString(scriptCommand)) },
                enabled = enabled,
                filled = false,
                modifier = Modifier.weight(1f),
            )
            ShieldButton(
                text = "回读校验",
                onClick = onVerify,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "提示：dpm set-device-owner 不可撤销（除非恢复出厂），且要求设备无账户、无次要用户。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
    }
}

@Composable
private fun StepAccessibility(
    ui: ShieldViewModel.UiState,
    onEnableAccessibility: () -> Unit,
) {
    ShieldCard(title = "第 5 步：启用无障碍与启动保活") {
        Text(
            text = "Shizuku 的使命到此结束。此后锁盾用 WRITE_SECURE_SETTINGS 写入 enabled_accessibility_services，并启动保活层。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        ShieldButton(
            text = "启用无障碍并启动保活",
            onClick = onEnableAccessibility,
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "设备所有者白名单（setPermittedAccessibilityServices）属于自我保护/管控层，激活完成后会自动下发。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
    }
}

@Composable
private fun StepSelfCheck(
    ui: ShieldViewModel.UiState,
    onSelfCheck: () -> Unit,
    onRepair: (String) -> Unit,
) {
    ShieldCard(title = "第 6 步：自检") {
        Text(
            text = "这里检查激活之后才存在的状态；点击任意条目右侧按钮即可就地修复，无需返回前面的步骤。",
            style = MaterialTheme.typography.bodySmall,
            color = ShieldTextSecondary,
        )
        ShieldButton("运行自检", onSelfCheck, enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
        if (ui.selfCheck.isEmpty()) {
            Text("尚未自检。", style = MaterialTheme.typography.bodySmall, color = ShieldTextSecondary)
        } else {
            ui.selfCheck.forEach { item ->
                CheckRow(item = item, onAction = onRepair)
            }
        }
    }
}

val ShellState.label: String
    get() = when (this) {
        ShellState.NO_SHIZUKU -> "未运行"
        ShellState.NO_PERMISSION -> "未授权"
        ShellState.IDLE -> "已授权，待连接"
        ShellState.CONNECTING -> "连接中"
        ShellState.READY -> "已就绪"
        ShellState.DEAD -> "已断开"
    }
