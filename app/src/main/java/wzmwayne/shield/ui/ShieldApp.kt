package wzmwayne.shield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wzmwayne.shield.activation.AuxAppInstaller
import wzmwayne.shield.core.CheckState
import wzmwayne.shield.core.Policies
import wzmwayne.shield.core.RepairActions
import wzmwayne.shield.ui.components.ShieldButton
import wzmwayne.shield.ui.screens.DashboardScreen
import wzmwayne.shield.ui.screens.EventsScreen
import wzmwayne.shield.ui.screens.AccessibilityWhitelistScreen
import wzmwayne.shield.ui.screens.AppsScreen
import wzmwayne.shield.ui.screens.DeviceOpsScreen
import wzmwayne.shield.ui.screens.KeepAliveScreen
import wzmwayne.shield.ui.screens.PinLockScreen
import wzmwayne.shield.ui.screens.PoliciesScreen
import wzmwayne.shield.ui.screens.SelfProtectionScreen
import wzmwayne.shield.ui.screens.SettingsScreen
import wzmwayne.shield.ui.screens.WhitelistScreen
import wzmwayne.shield.ui.screens.WizardScreen
import wzmwayne.shield.ui.theme.ShieldBlack
import wzmwayne.shield.ui.theme.ShieldBorder
import wzmwayne.shield.ui.theme.ShieldTextDim
import wzmwayne.shield.ui.theme.ShieldWhite

enum class ShieldTab(val label: String) {
    DASHBOARD("仪表盘"),
    WIZARD("向导"),
    PROTECT("保护"),
    APPS("应用"),
    POLICIES("策略"),
    EVENTS("日志"),
    SETTINGS("设置"),
}

@Composable
fun ShieldApp(viewModel: ShieldViewModel, onRequestNotification: () -> Unit) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activationState by viewModel.activationState.collectAsStateWithLifecycle()
    val ownerMode by viewModel.ownerMode.collectAsStateWithLifecycle()
    val keepAliveEnabled by viewModel.keepAliveEnabled.collectAsStateWithLifecycle()
    val interval by viewModel.checkIntervalSeconds.collectAsStateWithLifecycle()
    val serviceConnected by viewModel.serviceConnected.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val foregroundApp by viewModel.foregroundApp.collectAsStateWithLifecycle()
    val lastKillGap by viewModel.lastKillGapMs.collectAsStateWithLifecycle()
    val fixCount by viewModel.fixCount.collectAsStateWithLifecycle()
    val shellState by viewModel.shellState.collectAsStateWithLifecycle()
    val lastHeartbeat by wzmwayne.shield.core.Prefs.lastHeartbeat.collectAsStateWithLifecycle()
    val trace by viewModel.trace.collectAsStateWithLifecycle()
    val selfDestructed by viewModel.selfDestructed.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(ShieldTab.DASHBOARD) }
    val locked by wzmwayne.shield.core.AppLock.locked.collectAsStateWithLifecycle()

    // 自检条目里的「重新激活」等动作需要把用户带回向导页
    LaunchedEffect(ui.openTab) {
        ui.openTab?.let { target ->
            tab = target
            viewModel.consumeOpenTab()
        }
    }
    LaunchedEffect(ui.notificationRequestTick) {
        if (ui.notificationRequestTick > 0) onRequestNotification()
    }

    // 进入自保页刷新应用清单；进入设置页刷新无障碍白名单当前值
    LaunchedEffect(tab) {
        when (tab) {
            ShieldTab.PROTECT -> viewModel.refreshApps()
            ShieldTab.APPS -> viewModel.refreshAppRisks()
            ShieldTab.SETTINGS -> {
                viewModel.openSettings()
                viewModel.refreshHomes()
            }
            else -> Unit
        }
    }

    if (locked) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ShieldBlack),
        ) {
            PinLockScreen()
        }
        return
    }

    Scaffold(
        containerColor = ShieldBlack,
        bottomBar = { ShieldBottomBar(tab) { tab = it } },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            when (tab) {
                ShieldTab.DASHBOARD -> DashboardScreen(
                    activationState = activationState,
                    ownerMode = ownerMode,
                    keepAliveEnabled = keepAliveEnabled,
                    serviceConnected = serviceConnected,
                    foregroundApp = foregroundApp,
                    lastKillGapMs = lastKillGap,
                    fixCount = fixCount,
                    protectionIssues = ui.protection.count { it.state != CheckState.PASS },
                    events = events,
                    busy = ui.busy,
                    selfDestructed = selfDestructed,
                    onOpenWizard = { tab = ShieldTab.WIZARD },
                    onCheckNow = viewModel::checkNow,
                    onRefresh = viewModel::refreshAll,
                    onSelfDestruct = viewModel::selfDestruct,
                    onResumeProtection = viewModel::resumeProtection,
                    onBlockDebug = viewModel::blockDebugging,
                    killUiShown = ui.killUiShown,
                    onTitleTaps = viewModel::onDashboardTitleTaps,
                    onAllowDebug = viewModel::allowDebugging,
                    sensitiveLocked = ui.sensitiveLocked,
                    panicActive = ui.panicActive,
                )

                ShieldTab.WIZARD -> WizardScreen(
                    ui = ui,
                    activationState = activationState,
                    ownerMode = ownerMode,
                    shizukuState = shellState,
                    isInstalled = { pkg -> AuxAppInstaller.isInstalled(context, pkg) },
                    onStep = viewModel::setWizardStep,
                    onRefreshEnv = viewModel::refreshEnv,
                    onRequestShizuku = viewModel::requestShizukuPermission,
                    onInstall = viewModel::installAux,
                    onOpenUnknownSources = { AuxAppInstaller.openUnknownSourcesSettings(context) },
                    onSelectOwnerMode = viewModel::selectOwnerMode,
                    onActivate = viewModel::runActivation,
                    onEnableAccessibility = viewModel::enableAccessibility,
                    onSelfCheck = viewModel::runSelfCheck,
                    onRepair = viewModel::repair,
                    onVerifyManualActivation = viewModel::verifyManualActivation,
                    adbCommands = wzmwayne.shield.activation.AdbCommands.all(context),
                    adbScriptCommand = wzmwayne.shield.activation.AdbCommands.scriptCommand(context),
                    adbScriptReady = wzmwayne.shield.activation.AdbCommands.scriptExists(context),
                    onFinish = {
                        tab = ShieldTab.DASHBOARD
                        viewModel.refreshAll()
                    },
                    trace = trace,
                )

                ShieldTab.PROTECT -> {
                    var protectPage by remember { mutableStateOf(0) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ShieldButton(
                                text = "自保",
                                onClick = { protectPage = 0 },
                                filled = protectPage != 0,
                                modifier = Modifier.weight(1f),
                            )
                            ShieldButton(
                                text = "保活",
                                onClick = { protectPage = 1 },
                                filled = protectPage != 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        if (protectPage == 0) {
                            SelfProtectionScreen(
                                items = ui.protection,
                                busy = ui.busy,
                                apps = ui.apps,
                                appFilter = ui.appFilter,
                                appsLoading = ui.appsLoading,
                                onFilterChange = viewModel::setAppFilter,
                                onRefreshApps = viewModel::refreshApps,
                                panicActive = ui.panicActive,
                                panicEntries = ui.panicEntries,
                                sensitiveLocked = ui.sensitiveLocked,
                                onRestoreSensitive = viewModel::restoreSensitive,
                                onApply = viewModel::applyProtection,
                                onVerify = viewModel::verifyProtection,
                                onRepair = viewModel::repair,
                                onToggleTrusted = viewModel::toggleTrusted,
                                onTriggerPanic = viewModel::triggerPanic,
                                onRestorePanic = viewModel::restorePanic,
                                onReopenOverlay = viewModel::reopenPanicOverlay,
                            )
                        } else {
                            KeepAliveScreen(
                                keepAliveEnabled = keepAliveEnabled,
                                intervalSeconds = interval,
                                serviceConnected = serviceConnected,
                                lastHeartbeat = lastHeartbeat,
                                fixCount = fixCount,
                                killGapMs = lastKillGap,
                                live = ui.live,
                                onToggle = viewModel::toggleKeepAlive,
                                onInterval = viewModel::setCheckInterval,
                                onCheckNow = viewModel::checkNow,
                                onRestartService = viewModel::restartKeepAliveService,
                                onRepairAccessibility = { viewModel.repair(RepairActions.ENABLE_ACCESSIBILITY) },
                            )
                        }
                    }
                }

                ShieldTab.APPS -> AppsScreen(
                    apps = ui.appRisks,
                    loading = ui.appRiskLoading,
                    appFilter = ui.appFilter,
                    actionTarget = ui.appActionTarget,
                    onFilterChange = { filter ->
                        viewModel.setAppFilter(filter)
                        viewModel.refreshAppRisks()
                    },
                    onRefresh = viewModel::refreshAppRisks,
                    onOpenActions = viewModel::openAppActions,
                    onCloseActions = viewModel::closeAppActions,
                    onRunAction = viewModel::runAppAction,
                )

                ShieldTab.POLICIES -> PoliciesScreen(
                    groups = Policies.grouped(),
                    busy = ui.busy,
                    onToggle = viewModel::setPolicy,
                    onBack = { tab = ShieldTab.SETTINGS },
                )

                ShieldTab.SETTINGS -> if (ui.accessWhitelistOpen) {
                    AccessibilityWhitelistScreen(
                        apps = ui.apps,
                        selected = ui.accessWhitelist,
                        appFilter = ui.appFilter,
                        appsLoading = ui.appsLoading,
                        busy = ui.busy,
                        current = ui.whitelistCurrent,
                        onFilterChange = viewModel::setAppFilter,
                        onRefreshApps = {
                            viewModel.refreshApps()
                            viewModel.refreshWhitelistState()
                        },
                        onToggle = viewModel::toggleAccessWhitelist,
                        onSelectAll = viewModel::selectAllAccessWhitelist,
                        onApply = viewModel::applyWhitelist,
                        onClear = viewModel::clearWhitelist,
                        onBack = viewModel::closeAccessWhitelist,
                    )
                } else if (ui.whitelistOpen) {
                    WhitelistScreen(
                        apps = ui.apps,
                        appFilter = ui.appFilter,
                        appsLoading = ui.appsLoading,
                        busy = ui.busy,
                        onFilterChange = viewModel::setAppFilter,
                        onRefreshApps = viewModel::refreshApps,
                        onToggleTrusted = viewModel::toggleTrusted,
                        onBack = viewModel::closeWhitelist,
                    )
                } else if (ui.deviceOpsOpen) {
                    DeviceOpsScreen(
                        ops = ui.deviceOps,
                        restrictions = ui.restrictions,
                        busy = ui.busy,
                        onRefresh = {
                            viewModel.refreshDeviceOps()
                            viewModel.refreshRestrictions()
                        },
                        onToggle = viewModel::applyDeviceOp,
                        onSetRestriction = viewModel::setRestriction,
                        onBack = viewModel::closeDeviceOps,
                    )
                } else {
                    SettingsScreen(
                        busy = ui.busy,
                        onBlockDebug = viewModel::blockDebugging,
                        onAllowDebug = viewModel::allowDebugging,
                        onOpenDeviceOps = viewModel::openDeviceOps,
                        onOpenWhitelist = viewModel::openWhitelist,
                        onTitleTaps = viewModel::onSettingsTitleTaps,
                        organizationName = ui.organizationName,
                        onEditOrganization = viewModel::editOrganizationName,
                        lockScreenText = ui.lockScreenText,
                        shortMessage = ui.shortMessage,
                        longMessage = ui.longMessage,
                        onEditLockScreen = viewModel::editLockScreenText,
                        onEditShort = viewModel::editShortMessage,
                        onEditLong = viewModel::editLongMessage,
                        onSaveTexts = viewModel::saveTexts,
                        onResetTexts = viewModel::resetTexts,
                        trustedHomes = ui.trustedHomes,
                        primaryHome = ui.primaryHome,
                        currentHome = ui.currentHome,
                        homeCandidates = ui.homeCandidates,
                        onToggleTrustedHome = viewModel::toggleTrustedHome,
                        onSetPrimaryHome = viewModel::setPrimaryHome,
                        onLockHome = viewModel::lockHomeNow,
                        debugHttpEnabled = ui.debugHttpEnabled,
                        debugHttpPort = ui.debugHttpPort,
                        onToggleDebugHttp = viewModel::setDebugHttpEnabled,
                        pinEnabled = ui.pinEnabled,
                        onEnablePin = viewModel::enablePin,
                        onDisablePin = viewModel::disablePin,
                        whitelistCurrent = ui.whitelistCurrent,
                        onOpenAccessWhitelist = viewModel::openAccessWhitelist,
                        onClearWhitelist = viewModel::clearWhitelist,
                    )
                }

                ShieldTab.EVENTS -> EventsScreen(
                    events = events,
                    onClear = viewModel::clearEvents,
                )
            }
        }
    }
}

@Composable
private fun ShieldBottomBar(current: ShieldTab, onSelect: (ShieldTab) -> Unit) {
    Column {
        HorizontalDivider(color = ShieldBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ShieldBlack)
                .padding(vertical = 6.dp),
        ) {
            ShieldTab.entries.forEach { tab ->
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tab == current) ShieldWhite else ShieldTextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
