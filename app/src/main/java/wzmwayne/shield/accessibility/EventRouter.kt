package wzmwayne.shield.accessibility

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import wzmwayne.shield.BuildConfig
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.logic.EventRules
import java.util.ArrayDeque

/**
 * 感知层的事件分流：前台应用追踪 + 频率类事件记录。
 * 具体的分级决策留给后续威胁评估引擎，这里只产出事实。
 */
object EventRouter {
    private const val MAX_HISTORY = 10
    private const val MIN_LOG_GAP_MS = 30_000L
    private const val DIALOG_DEDUPE_MS = 60_000L

    private val clickTimestamps = ArrayDeque<Long>()
    private val switchTimestamps = ArrayDeque<Long>()

    private val _foregroundApp = MutableStateFlow("")
    val foregroundApp: StateFlow<String> = _foregroundApp

    private val _recentApps = MutableStateFlow<List<String>>(emptyList())
    val recentApps: StateFlow<List<String>> = _recentApps

    private var lastClickLogAt = 0L
    private var lastSwitchLogAt = 0L
    private var lastDialogLogAt = 0L

    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(event)
        }
    }

    private fun onWindowChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == BuildConfig.APPLICATION_ID) return
        if (pkg == _foregroundApp.value) return
        _foregroundApp.value = pkg
        _recentApps.value = (listOf(pkg) + _recentApps.value).take(MAX_HISTORY)

        val now = System.currentTimeMillis()
        switchTimestamps.addLast(now)
        while (switchTimestamps.isNotEmpty() && now - switchTimestamps.first() > EventRules.SWITCH_WINDOW_MS) {
            switchTimestamps.removeFirst()
        }
        if (EventRules.isRapidAppSwitch(switchTimestamps.toList(), now) && now - lastSwitchLogAt > MIN_LOG_GAP_MS) {
            lastSwitchLogAt = now
            AppLog.event(
                RiskLevel.L2,
                "跨应用高频切换",
                "30 秒内切换超过 ${EventRules.SWITCH_THRESHOLD} 个应用，当前前台：$pkg",
            )
        }
    }

    private fun onClick(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        clickTimestamps.addLast(now)
        while (clickTimestamps.isNotEmpty() && now - clickTimestamps.first() > EventRules.CLICK_WINDOW_MS) {
            clickTimestamps.removeFirst()
        }
        if (EventRules.isClickBurst(clickTimestamps.toList(), now) && now - lastClickLogAt > MIN_LOG_GAP_MS) {
            lastClickLogAt = now
            AppLog.event(
                RiskLevel.L2,
                "高频点击",
                "5 秒内超过 ${EventRules.CLICK_THRESHOLD} 次，来源=${event.packageName}",
            )
        }
    }

    private fun onContentChanged(event: AccessibilityEvent) {
        val className = event.className?.toString().orEmpty()
        val pkg = event.packageName?.toString().orEmpty()
        val sensitive = SENSITIVE_MARKERS.any {
            className.contains(it, ignoreCase = true) || pkg.contains(it, ignoreCase = true)
        }
        if (!sensitive) return
        val now = System.currentTimeMillis()
        if (now - lastDialogLogAt < DIALOG_DEDUPE_MS) return
        lastDialogLogAt = now
        AppLog.event(RiskLevel.L1, "出现敏感系统界面", "$pkg / $className")
    }

    private val SENSITIVE_MARKERS = listOf(
        "DeviceAdmin",
        "Accessibility",
        "PackageInstaller",
        "PermissionController",
        "GrantPermissions",
        "ConfirmDeviceCredential",
    )
}
