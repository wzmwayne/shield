package wzmwayne.shield.ui

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wzmwayne.shield.accessibility.EventRouter
import wzmwayne.shield.activation.AccessibilityActivator
import wzmwayne.shield.activation.AuxAppInstaller
import wzmwayne.shield.activation.DeviceOwnerQuery
import wzmwayne.shield.activation.EnvChecker
import wzmwayne.shield.activation.EnvReport
import wzmwayne.shield.activation.OwnerSetup
import wzmwayne.shield.activation.SelfCheck
import wzmwayne.shield.core.ActivationState
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.CheckItem
import wzmwayne.shield.core.CheckState
import wzmwayne.shield.activation.AdbCommands
import wzmwayne.shield.activation.DebugControl
import wzmwayne.shield.control.DeviceOwnerControlPlane
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.OwnerMode
import wzmwayne.shield.control.DeviceOpState
import wzmwayne.shield.control.DeviceOpsRunner
import wzmwayne.shield.control.DeviceOwnerOps
import wzmwayne.shield.port.owndroid.Restriction
import wzmwayne.shield.port.owndroid.UserRestrictions
import wzmwayne.shield.core.AppFilter
import wzmwayne.shield.core.AppLock
import wzmwayne.shield.core.Packages
import wzmwayne.shield.debug.DebugHttpServer
import wzmwayne.shield.core.Policies
import wzmwayne.shield.core.Policy
import wzmwayne.shield.core.PackageInventory
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RepairActions
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.Trace
import wzmwayne.shield.keepalive.AccessibilitySettings
import wzmwayne.shield.keepalive.CrashDetector
import wzmwayne.shield.keepalive.KeepAliveEngine
import wzmwayne.shield.keepalive.KeepAliveService
import wzmwayne.shield.protection.AccessibilityWhitelist
import wzmwayne.shield.protection.SelfProtection
import wzmwayne.shield.response.PanicAppEntry
import wzmwayne.shield.response.PanicReportCodec
import wzmwayne.shield.response.PanicResponse
import wzmwayne.shield.response.HomeSentinel
import wzmwayne.shield.response.SensitiveGuard
import wzmwayne.shield.shell.ShellClient
import wzmwayne.shield.shell.ShizukuPermission

enum class AuxTarget(val pkg: String, val asset: String, val label: String) {
    SHIZUKU(Constants.PKG_SHIZUKU, Constants.ASSET_SHIZUKU, "Shizuku"),
    HAIL(Constants.PKG_HAIL, Constants.ASSET_HAIL, "雹"),
}

/** 保活页需要的实时快照，全部在 IO 线程刷新，避免组合阶段做 binder 查询 */
data class LiveSnapshot(
    val listed: Boolean = false,
    val systemBound: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val dumpGranted: Boolean = false,
    val updatedAt: Long = 0L,
)

class ShieldViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application.applicationContext

    data class UiState(
        val busy: Boolean = false,
        val banner: String = "",
        val bannerOk: Boolean = true,
        val wizardStep: Int = Prefs.wizardStep.value,
        val env: EnvReport? = null,
        val selfCheck: List<CheckItem> = emptyList(),
        val protection: List<CheckItem> = emptyList(),
        val log: List<String> = emptyList(),
        val live: LiveSnapshot = LiveSnapshot(),
        val apps: List<PackageInventory.AppEntry> = emptyList(),
        val appFilter: AppFilter = AppFilter.USER,
        val appsLoading: Boolean = false,
        val panicActive: Boolean = false,
        val sensitiveLocked: Boolean = false,
        val deviceOps: List<DeviceOpState> = emptyList(),
        val deviceOpsOpen: Boolean = false,
        val whitelistOpen: Boolean = false,
        val whitelistCurrent: String = "未读取",
        val accessWhitelist: Set<String> = emptySet(),
        val accessWhitelistOpen: Boolean = false,
        val lockScreenText: String = Prefs.lockScreenText.value,
        val shortMessage: String = Prefs.shortMessage.value,
        val longMessage: String = Prefs.longMessage.value,
        val organizationName: String = Prefs.organizationName.value,
        val pinEnabled: Boolean = Prefs.pinEnabled.value,
        val debugHttpEnabled: Boolean = Prefs.debugHttpEnabled.value,
        val debugHttpPort: Int = Prefs.debugHttpPort.value,
        val debugHttpLan: Boolean = Prefs.debugHttpLan.value,
        val policies: List<Pair<Policy, Boolean>> = Policies.all(),
        val trustedHomes: Set<String> = Prefs.trustedHomes.value,
        val primaryHome: String = Prefs.primaryHome.value,
        val homeCandidates: List<Packages.Entry> = emptyList(),
        val currentHome: String = "",
        val restrictions: Map<String, Boolean> = emptyMap(),
        val panicEntries: List<PanicAppEntry> = emptyList(),
        val openTab: ShieldTab? = null,
        val notificationRequestTick: Int = 0,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val activationState = Prefs.activationState
    val ownerMode = Prefs.ownerMode
    val keepAliveEnabled = Prefs.keepAliveEnabled
    val checkIntervalSeconds = Prefs.checkIntervalSeconds
    val serviceConnected = Prefs.serviceConnected
    val events = Prefs.events
    val resetToken = Prefs.resetToken
    val lastKillGapMs = Prefs.lastKillGapMs
    val fixCount = Prefs.fixCount
    val selfDestructed = Prefs.selfDestructed
    val trustedPackages = Prefs.trustedPackages
    val shellState = ShellClient.state
    val foregroundApp = EventRouter.foregroundApp
    val trace = Trace.lines

    init {
        viewModelScope.launch {
            runCatching { refreshChecks(repairProtection = false) }
        }
        viewModelScope.launch {
            runCatching { refreshApps() }
        }
        viewModelScope.launch {
            Prefs.panicReport.collect { raw ->
                _ui.update {
                    it.copy(
                        panicEntries = PanicReportCodec.decode(raw),
                        panicActive = Prefs.panicAt.value > 0,
                    )
                }
            }
        }
        viewModelScope.launch {
            Prefs.sensitiveLockAt.collect { at ->
                _ui.update { it.copy(sensitiveLocked = at > 0) }
            }
        }
        viewModelScope.launch {
            while (true) {
                runCatching { refreshLive() }
                delay(5_000)
            }
        }
    }

    // ── 向导 ────────────────────────────────────────────────────────────

    fun setWizardStep(step: Int) {
        val safe = step.coerceIn(0, 5)
        Prefs.setWizardStep(safe)
        _ui.update { it.copy(wizardStep = safe) }
    }

    fun consumeOpenTab() {
        _ui.update { it.copy(openTab = null) }
    }

    fun toast(message: String, ok: Boolean = true) {
        _ui.update { it.copy(banner = message, bannerOk = ok) }
    }

    fun refreshEnv() = launchTask("环境检查") {
        val report = EnvChecker.inspect(app)
        _ui.update { it.copy(env = report) }
        if (report.ready) {
            "环境检查通过，可以继续下一步"
        } else {
            val parts = buildList {
                if (report.blockers.isNotEmpty()) add("${report.blockers.size} 项阻断")
                if (report.unknowns.isNotEmpty()) add("${report.unknowns.size} 项无法判定（数据没取到）")
            }
            "环境检查未通过：${parts.joinToString("、")}"
        }
    }

    fun requestShizukuPermission() {
        ShellClient.refreshState()
        when (ShellClient.requestPermission()) {
            ShizukuPermission.ALREADY_GRANTED -> toast("锁盾已在 Shizuku 授权列表中，可直接执行检查")
            ShizukuPermission.BINDER_MISSING -> toast(
                "Shizuku 服务未运行：请先启动 Shizuku（Android 11+ 无线调试配对；Android 9-10 用 adb 启动脚本）",
                ok = false,
            )
            ShizukuPermission.REQUESTED -> toast("已触发 Shizuku 授权窗口，请点击「允许」")
            ShizukuPermission.FAILED -> toast("请求授权失败：${ShellClient.diagnostics()}", ok = false)
        }
    }

    fun installAux(target: AuxTarget) = launchTask("安装 ${target.label}") {
        if (!AuxAppInstaller.canInstall(app)) {
            AuxAppInstaller.openUnknownSourcesSettings(app)
            appendLog("${target.label}：未允许安装未知应用，已打开系统设置")
            "请先允许锁盾安装应用，然后重新点击安装"
        } else {
            val outcome = AuxAppInstaller.install(app, target.asset, target.label)
            appendLog("${target.label}：${outcome.message}${if (outcome.detail.isNotEmpty()) "（${outcome.detail}）" else ""}")
            outcome.message
        }
    }

    fun selectOwnerMode(mode: OwnerMode) {
        // 委派模式已禁用：无论界面上选什么都强制直连
        Prefs.setOwnerMode(OwnerMode.DIRECT)
        appendLog("设备所有者模式：锁盾自身持有（委派模式已禁用，传入=$mode）")
    }

    fun runActivation() = launchTask("执行激活") {
        // 委派模式已禁用：激活一律走锁盾自身持有
        Prefs.setOwnerMode(OwnerMode.DIRECT)
        Prefs.setSelfDestructed(false)
        val direct = OwnerSetup.setDirectOwner(app)
        appendLog("设置设备所有者：${direct.message} ${direct.detail}")
        // 绝不只看退出码：回读校验失败即判失败，避免"假成功"进入防护状态
        if (!OwnerSetup.isSelfDeviceOwner(app)) {
            Prefs.setOwnerMode(OwnerMode.NONE)
            AppLog.event(RiskLevel.L3, "设备所有者激活失败", direct.detail)
            return@launchTask "激活未生效（锁盾未获得设备所有者身份）：${direct.detail}"
        }

        val grants = OwnerSetup.grantCorePermissions(app)
        appendLog("核心权限：${grants.message} ${grants.detail}")

        val tokenResult = OwnerSetup.installResetToken(app)
        appendLog("恢复令牌：${tokenResult.message}")
        Prefs.setActivationState(ActivationState.ACTIVATING)
        Prefs.setSnapshots(
            owner = "self",
            accessibility = AccessibilitySettings.rawValue(app),
        )
        AppLog.event(RiskLevel.L2, "激活阶段完成", "设备所有者=锁盾自身；${tokenResult.message}")
        "激活已生效，请继续第 5 步启用无障碍服务"
    }

    /** 设备管理面板 */
    fun editLockScreenText(value: String) = _ui.update { it.copy(lockScreenText = value) }

    fun editShortMessage(value: String) = _ui.update { it.copy(shortMessage = value) }

    fun editLongMessage(value: String) = _ui.update { it.copy(longMessage = value) }

    fun editOrganizationName(value: String) = _ui.update { it.copy(organizationName = value) }

    /** 设置/修改应用 PIN（4-8 位数字） */
    fun enablePin(pin: String) = launchTask("设置应用 PIN") {
        if (AppLock.setPin(pin)) {
            _ui.update { it.copy(pinEnabled = true) }
            "PIN 已启用：每次回到锁盾前台都需要输入一次"
        } else {
            "PIN 无效：请使用 4-8 位数字"
        }
    }

    fun setPolicy(policy: Policy, enabled: Boolean) = launchTask("保护策略：${policy.title}") {
        Policies.setOn(policy, enabled)
        val extra = when (policy) {
            // 锁定调试：立即常驻生效 / 立即恢复
            Policy.SENSE_LAYER -> if (!enabled) withContext(Dispatchers.IO) {
                val detail = wzmwayne.shield.response.SensitiveGuard.restore(app)
                _ui.update { it.copy(sensitiveLocked = false) }
                "感知层已关闭：$detail"
            } else null
            Policy.LOCK_DEBUG -> withContext(Dispatchers.IO) {
                val result = if (enabled) DebugControl.block(app) else DebugControl.allow(app)
                result.message
            }
            else -> null
        }
        _ui.update { it.copy(policies = Policies.all()) }
        buildString {
            append("${policy.title}：${if (enabled) "已开启" else "已关闭"}")
            if (extra != null) append("；$extra")
        }
    }

    fun disablePin() = launchTask("关闭应用 PIN") {
        AppLock.clearPin()
        _ui.update { it.copy(pinEnabled = false) }
        "已关闭应用 PIN"
    }

    /** 保存并下发到设备所有者（锁屏文字 / 简短提示 / 详细提示） */
    fun saveTexts() = launchTask("保存提示文案") {
        val state = _ui.value
        Prefs.setTexts(state.lockScreenText, state.shortMessage, state.longMessage, state.organizationName)
        val results = withContext(Dispatchers.IO) {
            val ops = DeviceOwnerOps(app)
            listOf(
                "组织名称" to ops.setOrganizationName(state.organizationName),
                "锁屏提示信息" to ops.setLockScreenInfo(state.lockScreenText),
                "短消息" to ops.setShortSupportMessage(state.shortMessage),
                "长消息" to ops.setLongSupportMessage(state.longMessage),
            )
        }
        "已保存：" + results.joinToString("，") { "${it.first}=${it.second.mark()}" }
    }

    fun resetTexts() = launchTask("恢复默认文案") {
        Prefs.resetTexts()
        _ui.update {
            it.copy(
                lockScreenText = Prefs.DEFAULT_LOCK_SCREEN_TEXT,
                shortMessage = Prefs.DEFAULT_SHORT_MESSAGE,
                longMessage = Prefs.DEFAULT_LONG_MESSAGE,
                organizationName = Prefs.DEFAULT_ORG_NAME,
            )
        }
        val results = withContext(Dispatchers.IO) {
            val ops = DeviceOwnerOps(app)
            listOf(
                "组织名称" to ops.setOrganizationName(Prefs.DEFAULT_ORG_NAME),
                "锁屏提示信息" to ops.setLockScreenInfo(Prefs.DEFAULT_LOCK_SCREEN_TEXT),
                "短消息" to ops.setShortSupportMessage(Prefs.DEFAULT_SHORT_MESSAGE),
                "长消息" to ops.setLongSupportMessage(Prefs.DEFAULT_LONG_MESSAGE),
            )
        }
        "已恢复默认：" + results.joinToString("，") { "${it.first}=${it.second.mark()}" }
    }

    private fun Boolean.mark(): String = if (this) "成功" else "失败"

    /** 读取当前设备所有者白名单（null = 未设限制），并把勾选状态同步到列表 */
    fun refreshWhitelistState() {
        viewModelScope.launch {
            val actual = withContext(Dispatchers.IO) {
                DeviceOwnerControlPlane(app).permittedAccessibilityServices()
            }
            val selection = Prefs.accessWhitelistSelection.value
            // 设备实际生效的集合（含强制加入的自身）
            val effective = actual?.toSet().orEmpty()
            val expected = (selection + AccessibilityWhitelist.SELF_PACKAGE)
            val matched = if (actual == null) selection.isEmpty() else effective.containsAll(expected) && effective.size == expected.size
            _ui.update {
                it.copy(
                    whitelistCurrent = AccessibilityWhitelist.describe(actual) +
                        if (matched) "" else "（⚠ 与本地勾选不一致，可能未下发成功）",
                    accessWhitelist = selection,
                )
            }
        }
    }

    fun openAccessWhitelist() {
        _ui.update { it.copy(accessWhitelistOpen = true) }
        refreshApps()
        refreshWhitelistState()
    }

    fun closeAccessWhitelist() {
        _ui.update { it.copy(accessWhitelistOpen = false) }
    }

    fun toggleAccessWhitelist(packageName: String, checked: Boolean) {
        _ui.update {
            val next = it.accessWhitelist.toMutableSet()
            if (checked) next += packageName else next -= packageName
            Prefs.setAccessWhitelistSelection(next)
            it.copy(accessWhitelist = next)
        }
    }

    /** 一键勾选：当前筛选下的所有应用 */
    fun selectAllAccessWhitelist() {
        _ui.update { state ->
            val next = state.accessWhitelist + state.apps.map { it.packageName }
            Prefs.setAccessWhitelistSelection(next)
            state.copy(accessWhitelist = next)
        }
    }

    /** 下发白名单：自动补上锁盾自身，避免自锁 */
    fun applyWhitelist() = launchTask("下发无障碍白名单") {
        val list = (listOf(AccessibilityWhitelist.SELF_PACKAGE) + _ui.value.accessWhitelist).distinct()
        val ok = withContext(Dispatchers.IO) { DeviceOwnerControlPlane(app).updateAccessibilityWhitelist(list) }
        refreshWhitelistState()
        if (ok) "白名单已下发（${list.size} 个包名，含锁盾自身）" else "白名单下发失败：请确认锁盾仍是设备所有者"
    }

    /** 清除白名单限制 */
    fun clearWhitelist() = launchTask("清除无障碍白名单") {
        val ok = withContext(Dispatchers.IO) { DeviceOwnerControlPlane(app).clearAccessibilityWhitelist() }
        refreshWhitelistState()
        if (ok) "已清除白名单限制（所有无障碍服务恢复允许）" else "清除失败：请确认锁盾仍是设备所有者"
    }

    /** 读取桌面候选 + 当前默认桌面 */
    fun refreshHomes() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                Triple(
                    HomeSentinel.homeCandidates(app),
                    Prefs.trustedHomes.value to Prefs.primaryHome.value,
                    HomeSentinel.currentHome(app).orEmpty(),
                )
            }
            _ui.update {
                it.copy(
                    homeCandidates = data.first,
                    trustedHomes = data.second.first,
                    primaryHome = data.second.second,
                    currentHome = data.third,
                )
            }
        }
    }

    fun toggleTrustedHome(packageName: String, trusted: Boolean) = launchTask("可信桌面") {
        val next = _ui.value.trustedHomes.toMutableSet()
        if (trusted) next += packageName else next -= packageName
        // 主桌面必须在可信集合内：取消勾选主桌面时自动改选集合内第一个
        var primary = _ui.value.primaryHome
        if (primary !in next) primary = next.firstOrNull().orEmpty()
        Prefs.setTrustedHomes(next, primary)
        refreshHomes()
        "可信桌面：${if (trusted) "已加入" else "已移除"} $packageName（共 ${next.size} 个，主桌面 ${primary.ifEmpty { "未设置" }}）"
    }

    fun setPrimaryHome(packageName: String) = launchTask("设置主桌面") {
        val next = _ui.value.trustedHomes + packageName
        Prefs.setTrustedHomes(next, packageName)
        val result = withContext(Dispatchers.IO) { HomeSentinel.lockBack(app) }
        refreshHomes()
        "主桌面已设为 $packageName；$result"
    }

    fun lockHomeNow() = launchTask("锁回可信桌面") {
        val message = withContext(Dispatchers.IO) { HomeSentinel.lockBack(app) }
        refreshHomes()
        message
    }

    /** 调试接口开关：开启即启动（固定 0.0.0.0），关闭即停止 */
    fun setDebugHttpEnabled(enabled: Boolean, port: Int) = launchTask("调试接口") {
        Prefs.setDebugHttp(enabled, port, true)
        val message = if (enabled) DebugHttpServer.start(app) else DebugHttpServer.stop()
        _ui.update {
            it.copy(
                debugHttpEnabled = Prefs.debugHttpEnabled.value,
                debugHttpPort = port,
                debugHttpLan = true,
            )
        }
        message
    }

    fun openWhitelist() {
        _ui.update { it.copy(whitelistOpen = true) }
        refreshApps()
    }

    fun closeWhitelist() {
        _ui.update { it.copy(whitelistOpen = false) }
    }

    fun openSettings() {
        refreshWhitelistState()
    }

    fun openDeviceOps() {
        _ui.update { it.copy(deviceOpsOpen = true) }
        refreshDeviceOps()
        refreshRestrictions()
    }

    fun closeDeviceOps() {
        _ui.update { it.copy(deviceOpsOpen = false) }
    }

    fun refreshDeviceOps() {
        viewModelScope.launch {
            val ops = withContext(Dispatchers.IO) { DeviceOpsRunner.snapshot(app) }
            _ui.update { it.copy(deviceOps = ops) }
        }
    }

    /** 用户限制（移植自 OwnDroid 的目录） */
    fun refreshRestrictions() {
        viewModelScope.launch {
            val states = withContext(Dispatchers.IO) {
                val ops = DeviceOwnerOps(app)
                UserRestrictions.all()
                    .filter { android.os.Build.VERSION.SDK_INT >= it.requiresApi }
                    .associate { it.key to ops.isRestrictionSet(it.key) }
            }
            _ui.update { it.copy(restrictions = states) }
        }
    }

    fun setRestriction(restriction: Restriction, enabled: Boolean) = launchTask(restriction.title) {
        val ok = withContext(Dispatchers.IO) {
            DeviceOwnerOps(app).setRestrictionByConstant(restriction.key, enabled)
        }
        refreshRestrictions()
        "${restriction.title}：${if (ok) (if (enabled) "已开启" else "已关闭") else "执行失败（需设备所有者或系统不支持）"}"
    }

    fun applyDeviceOp(state: DeviceOpState, enabled: Boolean) = launchTask(state.op.title) {
        val message = withContext(Dispatchers.IO) { DeviceOpsRunner.apply(app, state.op, enabled) }
        refreshDeviceOps()
        message
    }

    /** 激活后的第一步：收掉调试通道（禁用开发者选项与 ADB） */
    fun blockDebugging() = launchTask("禁止调试") {
        val result = DebugControl.block(app)
        Trace.step(result.message)
        refreshChecks(repairProtection = false)
        result.message
    }

    /** 测试期需要 adb 时临时恢复调试 */
    fun allowDebugging() = launchTask("恢复调试") {
        val result = DebugControl.allow(app)
        Trace.step(result.message)
        refreshChecks(repairProtection = false)
        result.message
    }

    fun enableAccessibility() = launchTask("启用无障碍与保活") {
        val enabled = AccessibilityActivator.enableSelf(app)
        appendLog("启用无障碍：${enabled.message} ${enabled.detail}")
        if (!enabled.ok) return@launchTask "启用失败：${enabled.detail}"

        val keepAlive = AccessibilityActivator.startKeepAlive(app)
        appendLog("保活层：${keepAlive.message} ${keepAlive.detail}")

        // 必须由锁盾自身持有设备所有者，才允许进入防护状态
        if (!OwnerSetup.isSelfDeviceOwner(app)) {
            Prefs.setActivationState(ActivationState.ACTIVATING)
            return@launchTask "无障碍已启用，但锁盾不是设备所有者：请回到第 4 步重新执行激活"
        }

        Prefs.setActivationState(ActivationState.PROTECTING)
        val protection = SelfProtection.applyAll(app, "激活完成")
        _ui.update { it.copy(protection = protection.items) }
        appendLog("自我保护：${if (protection.repairs.isEmpty()) "已就绪" else protection.repairs.joinToString("；")}")
        refreshChecks(repairProtection = false)
        "锁盾已进入防护状态，自我保护措施已应用"
    }

    fun runSelfCheck() = launchTask("自检") {
        val items = SelfCheck.run(app)
        _ui.update { it.copy(selfCheck = items) }
        val bad = items.count { it.state != CheckState.PASS }
        if (bad == 0) "自检通过：全部项目正常" else "自检完成：$bad 项需要关注，点击对应条目即可修复"
    }

    // ── 自我保护 ────────────────────────────────────────────────────────

    fun applyProtection() = launchTask("应用自我保护") {
        val report = SelfProtection.applyAll(app, "用户手动")
        _ui.update { it.copy(protection = report.items) }
        if (report.repairs.isEmpty()) "保护措施已就绪，无需变更" else report.repairs.joinToString("；")
    }

    fun verifyProtection() = launchTask("校验自我保护") {
        val report = SelfProtection.verify(app, repair = true)
        _ui.update { it.copy(protection = report.items) }
        val blocking = report.items.count { it.state == CheckState.BLOCK }
        when {
            blocking > 0 -> "$blocking 项处于阻断状态，请按说明处理"
            report.repairs.isNotEmpty() -> "已自动修复：${report.repairs.joinToString("；")}"
            else -> "全部自我保护措施正常"
        }
    }

    /** 统一修复入口：向导第 6 步与自保页的条目点击都走这里 */
    fun repair(action: String?) = launchTask("修复") {
        when (action) {
            RepairActions.ENABLE_ACCESSIBILITY -> {
                val outcome = AccessibilityActivator.repairAccessibility(app)
                refreshChecks(repairProtection = false)
                if (outcome.ok) "${outcome.message}；已触发保活检测" else "${outcome.message}：${outcome.detail}"
            }

            RepairActions.RESTART_KEEPALIVE -> {
                if (!Prefs.keepAliveEnabled.value) Prefs.setKeepAliveEnabled(true)
                KeepAliveEngine.ensureServiceRunning(app, "自检修复")
                KeepAliveEngine.schedulePeriodicAlarm(app)
                KeepAliveEngine.evaluate(app, "自检修复", force = true)
                refreshChecks(repairProtection = false)
                "已重启保活服务并触发一次检测"
            }

            RepairActions.INSTALL_RESET_TOKEN -> {
                val outcome = OwnerSetup.installResetToken(app)
                refreshChecks(repairProtection = false)
                if (outcome.ok) outcome.message else "${outcome.message}：${outcome.detail}"
            }

            RepairActions.GRANT_DUMP -> {
                val result = ShellClient.exec("pm grant ${app.packageName} android.permission.DUMP")
                refreshChecks(repairProtection = false)
                if (result.ok) "DUMP 授权命令已执行；若仍显示未授予请重新执行激活" else "授予失败：${result.summary}"
            }

            RepairActions.APPLY_SELF_PROTECTION -> {
                val report = SelfProtection.applyAll(app, "自检修复")
                _ui.update { it.copy(protection = report.items) }
                if (report.repairs.isEmpty()) "保护措施已就绪，无需变更" else report.repairs.joinToString("；")
            }

            RepairActions.OPEN_ACTIVATION -> {
                setWizardStep(3)
                _ui.update { it.copy(openTab = ShieldTab.WIZARD) }
                "已切换到第 4 步「执行激活」，请重新执行设备所有者激活"
            }

            RepairActions.REQUEST_NOTIFICATION -> {
                _ui.update { it.copy(notificationRequestTick = it.notificationRequestTick + 1) }
                "请在系统弹窗中允许通知"
            }

            else -> "该项暂无自动修复动作"
        }
    }

    // ── 保活 ────────────────────────────────────────────────────────────

    fun checkNow() {
        toast("已触发保活检测，结果会写入事件日志")
        KeepAliveEngine.evaluate(app, "手动检测", force = true)
    }

    fun restartKeepAliveService() {
        KeepAliveEngine.ensureServiceRunning(app, "手动重启")
        KeepAliveEngine.evaluate(app, "手动重启", force = true)
        toast("保活服务已触发重启与检测")
    }

    fun setCheckInterval(seconds: Int) {
        Prefs.setCheckIntervalSeconds(seconds)
        KeepAliveEngine.schedulePeriodicAlarm(app)
        toast("定时检测间隔已设为 ${Prefs.checkIntervalSeconds.value} 秒")
    }

    /**
     * 仪表盘「刷新」：立即重新完整检测一遍——
     * 环境检查（能否进入激活）+ 自我保护（含自动修复）+ 自检 + 实时快照 + 保活检测。
     */
    fun refreshAll() = launchTask("完整检测") {
        val env = EnvChecker.inspect(app)
        val protection = SelfProtection.verify(app, repair = true)
        val selfCheck = SelfCheck.run(app)
        refreshLive()
        KeepAliveEngine.ensureServiceRunning(app, "手动刷新")
        KeepAliveEngine.evaluate(app, "手动刷新", force = true)
        _ui.update { it.copy(env = env, protection = protection.items, selfCheck = selfCheck) }
        val checkIssues = selfCheck.count { it.state != CheckState.PASS }
        buildString {
            append("完整检测完成：环境")
            append(if (env.ready) "通过" else "${env.blockers.size} 阻断/${env.unknowns.size} 无法判定")
            append("；自保")
            append(if (protection.repairs.isEmpty()) "正常" else "已修复 ${protection.repairs.size} 项")
            append("；自检 ")
            append(if (checkIssues == 0) "全部通过" else "$checkIssues 项待处理")
        }
    }

    fun toggleKeepAlive(enabled: Boolean) {
        if (enabled) {
            Prefs.setKeepAliveEnabled(true)
            KeepAliveEngine.ensureServiceRunning(app, "用户开启保活")
            KeepAliveEngine.schedulePeriodicAlarm(app)
            AppLog.event(RiskLevel.L1, "用户开启保活层")
            toast("保活层已开启")
        } else {
            Prefs.setKeepAliveEnabled(false)
            AppLog.event(RiskLevel.L2, "用户关闭保活层", "无障碍服务不再自动恢复")
            toast("保活层已关闭")
        }
    }

    /**
     * 一键自杀（不可撤销）：释放设备所有者 → 关闭自保 → 移出无障碍启用列表 → 停前台服务 → 退出进程。
     * 自杀后 `Prefs.selfDestructed=true`，所有保活/复活路径（无障碍节拍、前台服务、开机广播）一律不再启动。
     */
    fun selfDestruct() = launchTask("一键自杀") {
        Prefs.setSelfDestructed(true)
        Prefs.setKeepAliveEnabled(false)
        // 先解除本应用施加的保护，再释放设备所有者（顺序不能反：解除保护需要 DO 身份）
        val plane = DeviceOwnerControlPlane(app)
        val releasedProtections = plane.releaseAllProtections()
        appendLog("解除自我保护：${if (releasedProtections.isEmpty()) "无" else releasedProtections.joinToString("；")}")
        val released = OwnerSetup.clearDeviceOwner(app)
        appendLog("释放设备所有者：${released.message} ${released.detail}")
        AccessibilitySettings.removeSelf(app)
        app.stopService(Intent(app, KeepAliveService::class.java))
        Prefs.setActivationState(ActivationState.NOT_ACTIVATED)
        AppLog.event(
            RiskLevel.L3,
            "用户执行一键自杀",
            "已解除自我保护（${releasedProtections.joinToString("；")}）、释放设备所有者、关闭自保、移出无障碍启用列表；应用完全停摆",
        )
        delay(1_500)
        Process.killProcess(Process.myPid())
        "已执行：设备所有者已释放、应用完全停摆"
    }

    /** 自杀后重新启用：清除停摆标记并重新走一遍「启用无障碍 + 启动保活 + 应用自我保护」 */
    fun resumeProtection() = launchTask("重新启用防护") {
        if (!OwnerSetup.isSelfDeviceOwner(app)) {
            return@launchTask "锁盾当前不是设备所有者：请先完成激活（向导第 4 步，或用 adb 执行 dpm set-device-owner）"
        }
        Prefs.setSelfDestructed(false)
        val enabled = AccessibilityActivator.enableSelf(app)
        appendLog("启用无障碍：${enabled.message}")
        val keepAlive = AccessibilityActivator.startKeepAlive(app)
        appendLog("保活层：${keepAlive.message}")
        val protection = SelfProtection.applyAll(app, "自杀后重新启用")
        _ui.update { it.copy(protection = protection.items) }
        Prefs.setActivationState(ActivationState.PROTECTING)
        refreshChecks(repairProtection = false)
        "已重新启用：无障碍 ${enabled.message}；保活 ${keepAlive.message}；保护 ${protection.repairs.size} 项"
    }

    /** Shizuku 不可用时：用户在 adb 里执行完命令后回读校验，通过即可继续后续步骤 */
    fun verifyManualActivation() = launchTask("回读校验（adb 手动激活）") {
        val hasWriteSecure = app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasDump = wzmwayne.shield.keepalive.CrashDetector.hasDumpPermission(app)
        val selfOwner = OwnerSetup.isSelfDeviceOwner(app)
        Trace.step("按 adb 手动激活路径回读校验：")
        Trace.judge("WRITE_SECURE_SETTINGS", hasWriteSecure)
        Trace.judge("DUMP", hasDump)
        Trace.judge("设备所有者=锁盾自身", selfOwner, if (selfOwner) "已生效" else "未生效，请在 adb 中执行 dpm 命令")

        if (!selfOwner) {
            return@launchTask "校验未通过：锁盾还不是设备所有者，请在 adb 中执行第 3 条命令后重试"
        }
        Prefs.setSelfDestructed(false)
        Prefs.setActivationState(ActivationState.ACTIVATING)
        Prefs.setOwnerMode(OwnerMode.DIRECT)
        Prefs.setSnapshots(owner = "self", accessibility = AccessibilitySettings.rawValue(app))
        if (!hasWriteSecure) {
            return@launchTask "设备所有者已生效，但仍缺少 WRITE_SECURE_SETTINGS，请补执行第 1 条命令"
        }
        AppLog.event(RiskLevel.L2, "adb 手动激活校验通过", AdbCommands.summary(app))
        refreshChecks(repairProtection = false)
        "校验通过：设备所有者与核心权限均已生效，可以进入第 5 步启用无障碍与保活"
    }

    /** 保护对象白名单：勾选/取消勾选 */
    fun toggleTrusted(packageName: String, trusted: Boolean) = launchTask("更新保护对象") {
        Prefs.toggleTrusted(packageName, trusted)
        // 应用白名单默认包含在无障碍白名单里（用户仍可在白名单页手动取消）
        val selection = Prefs.accessWhitelistSelection.value.toMutableSet()
        if (trusted) selection += packageName else selection -= packageName
        Prefs.setAccessWhitelistSelection(selection)
        _ui.update { it.copy(accessWhitelist = selection) }
        refreshApps()
        val count = Prefs.trustedPackages.value.size
        if (trusted) "已把 $packageName 加入保护对象（当前 $count 个）" else "已从保护对象移除 $packageName（当前 $count 个）"
    }

    /**
     * 异步刷新应用列表（枚举 + 取标签开销大，绝不放主线程）。
     * 默认只看用户（非系统）应用，可切换 用户/系统/所有。
     */
    fun refreshApps() {
        val filter = _ui.value.appFilter
        viewModelScope.launch {
            _ui.update { it.copy(appsLoading = true) }
            val apps = withContext(Dispatchers.IO) {
                PackageInventory.listApps(app, Prefs.trustedPackages.value + app.packageName, filter)
            }
            _ui.update { it.copy(apps = apps, appsLoading = false) }
        }
    }

    fun setAppFilter(filter: AppFilter) {
        _ui.update { it.copy(appFilter = filter) }
        refreshApps()
    }

    /** 重新打开紧急处置覆盖层（覆盖层可以关闭，但处置仍然生效） */
    fun reopenPanicOverlay() = launchTask("重新打开覆盖层") {
        if (wzmwayne.shield.accessibility.ShieldAccessibilityService.reopenOverlay()) {
            "已重新打开紧急处置覆盖层"
        } else {
            "无法打开：无障碍服务未运行（请先在「保护 → 保活」里恢复服务）"
        }
    }

    /** 撤销敏感页看护（把无障碍启用列表与白名单恢复回去） */
    fun restoreSensitive() = launchTask("撤销敏感页看护") {
        val summary = SensitiveGuard.restore(app)
        _ui.update { it.copy(sensitiveLocked = false) }
        refreshChecks(repairProtection = false)
        summary
    }

    /** 撤销紧急处置（按快照回滚） */
    fun restorePanic() = launchTask("撤销紧急处置") {
        val summary = PanicResponse.restore(app)
        _ui.update { it.copy(panicActive = false, panicEntries = emptyList()) }
        refreshChecks(repairProtection = false)
        summary
    }

    /** 手动触发紧急处置（等价于音量手势，便于在应用内测试） */
    fun triggerPanic() = launchTask("紧急处置（手动触发）") {
        val summary = PanicResponse.trigger(app, "应用内手动触发")
        _ui.update {
            it.copy(
                panicActive = true,
                panicEntries = PanicReportCodec.decode(Prefs.panicReport.value),
            )
        }
        // 覆盖层由无障碍服务承载：手动触发后必须主动拉起，否则看不到处置界面
        val overlayShown = wzmwayne.shield.accessibility.ShieldAccessibilityService.reopenOverlay()
        if (overlayShown) {
            "$summary（处置界面已显示）"
        } else {
            "$summary ｜ ⚠ 未显示处置界面：无障碍服务未运行，请到「保护 → 保活」恢复服务后重试"
        }
    }

    fun clearEvents() {
        Prefs.clearEvents()
        toast("事件日志已清空")
    }

    fun stopServiceQuietly() {
        KeepAliveService.markRestartRequested(app)
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private suspend fun refreshChecks(repairProtection: Boolean) {
        val protection = withContext(Dispatchers.IO) { SelfProtection.verify(app, repair = repairProtection) }
        val selfCheck = if (Prefs.activationState.value == ActivationState.PROTECTING) {
            SelfCheck.run(app)
        } else {
            _ui.value.selfCheck
        }
        _ui.update { it.copy(protection = protection.items, selfCheck = selfCheck) }
    }

    private suspend fun refreshLive() {
        val live = withContext(Dispatchers.IO) {
            LiveSnapshot(
                listed = AccessibilitySettings.isSelfListed(app),
                systemBound = AccessibilitySettings.isSelfEnabledBySystem(app),
                accessibilityEnabled = AccessibilitySettings.isAccessibilityEnabled(app),
                dumpGranted = CrashDetector.hasDumpPermission(app),
                updatedAt = System.currentTimeMillis(),
            )
        }
        _ui.update { it.copy(live = live) }
    }

    private fun appendLog(line: String) {
        _ui.update { state -> state.copy(log = (state.log + line).takeLast(40)) }
    }

    private fun launchTask(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            Trace.clear()
            Trace.step("=== $label 开始 ===")
            _ui.update { it.copy(busy = true, banner = "$label 进行中…", bannerOk = true) }
            val message = try {
                // 所有涉及 dumpsys / 设备策略 / Secure Settings 的动作统一在 IO 线程执行
                withContext(Dispatchers.IO) { block() }
            } catch (t: Throwable) {
                AppLog.e("$label 异常", t)
                Trace.result(false, "$label 异常：${t.message}")
                "$label 失败：${t.message}"
            }
            Trace.step("=== $label 结束：$message ===")
            _ui.update { it.copy(busy = false, banner = message) }
        }
    }
}
