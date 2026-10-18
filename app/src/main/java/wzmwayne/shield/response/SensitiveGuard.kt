package wzmwayne.shield.response

import android.content.Context
import android.provider.Settings
import wzmwayne.shield.BuildConfig
import wzmwayne.shield.control.DeviceOwnerControlPlane
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.Trace
import wzmwayne.shield.keepalive.AccessibilitySettings
import wzmwayne.shield.protection.AccessibilityWhitelist

/**
 * 敏感页看护（自动路径，只做无障碍收紧，不做全局处置）。
 *
 * 判定：进入敏感系统页面（卸载确认页 / 应用信息页 / 停用应用 / 设备管理器激活页 /
 * 无障碍设置 / 开发者选项 / 特殊权限页）时立即执行：
 *   ① 禁用**非白名单应用**的无障碍服务（从 enabled_accessibility_services 中移除）；
 *   ② 把无障碍白名单收紧到「白名单应用 + 本应用」。
 *
 * 处置前保存快照，可在自保页一键回滚；有 30 秒冷却，避免反复写设置。
 */
object SensitiveGuard {

    private const val COOLDOWN_MS = 30_000L

    /**
     * 敏感页来源包（这些包的**所有页面**都算敏感页）：
     * 系统设置全部页面 / 应用安装-卸载器 / 权限授予页 / **锁盾自身**。
     */
    private val SENSITIVE_PACKAGES = listOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.huawei.android.settings",
        "com.miui.securitycenter",
        BuildConfig.APPLICATION_ID,
    )

    /** 类名关键字（大小写不敏感） */
    private val CLASS_HINTS = listOf(
        "uninstaller",
        "installedappdetails",
        "applicationdetails",
        "appinfo",
        "deviceadminadd",
        "accessibilitysettings",
        "developmentsettings",
        "specialappaccess",
        "manageapplications",
        "apps",
    )

    private var lastRunAt = 0L

    /** 是否为敏感页（纯逻辑，可单测）：上述包的**任意页面**都算敏感页 */
    fun isSensitive(packageName: String?, className: String?): Boolean {
        val pkg = packageName.orEmpty().lowercase()
        if (pkg.isEmpty()) return false
        return SENSITIVE_PACKAGES.any { pkg == it.lowercase() }
    }

    /** 敏感页出现时调用；返回处置摘要（null = 冷却中或不需要处置） */
    fun onSensitivePage(context: Context, packageName: String?, className: String?): String? {
        if (!wzmwayne.shield.core.Policies.sensing(wzmwayne.shield.core.Policy.SENSITIVE_GUARD)) return null
        if (!isSensitive(packageName, className)) return null
        val now = System.currentTimeMillis()
        if (now - lastRunAt < COOLDOWN_MS) return null
        lastRunAt = now
        return lockDown(context, "敏感页：$packageName/${className?.substringAfterLast('.')}")
    }

    /** 收紧无障碍启用列表与白名单 */
    fun lockDown(context: Context, source: String): String {
        val app = context.applicationContext
        val trusted = (Prefs.trustedPackages.value + app.packageName).toList()
        val current = AccessibilitySettings.enabledList(app)
        val keep = current.filter { entry -> trusted.any { entry.substringBefore('/') == it } }
        val removed = current.size - keep.size

        if (Prefs.sensitiveLockAt.value == 0L) {
            Prefs.setSensitiveLock(System.currentTimeMillis(), AccessibilitySettings.rawValue(app))
        }

        var written = false
        if (removed > 0) {
            written = runCatching {
                Settings.Secure.putString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    keep.joinToString(":"),
                )
            }.getOrDefault(false)
        }

        val whitelistOk = DeviceOwnerControlPlane(app).updateAccessibilityWhitelist(trusted)

        val summary = buildString {
            append("敏感页看护已生效（$source）：")
            append(if (removed > 0) "已禁用 $removed 个非白名单无障碍服务" else "没有非白名单无障碍服务在运行")
            append("；已下发无障碍白名单 = 本应用 + 应用白名单（${trusted.size} 个）：${trusted.joinToString()}")
            if (removed > 0 && !written) append("；⚠ 写入启用列表失败")
            if (!whitelistOk) append("；⚠ 白名单下发失败（需设备所有者）")
        }
        AppLog.event(RiskLevel.L3, "敏感页看护", summary)
        Trace.step(summary)
        PanicOverlay.appendLine(summary)
        return summary
    }

    fun isLockedDown(): Boolean = Prefs.sensitiveLockAt.value > 0

    /** 回滚到收紧之前 */
    fun restore(context: Context): String {
        val app = context.applicationContext
        val snapshot = Prefs.sensitiveSnapshotAccessibility.value
        val marks = mutableListOf<String>()
        if (snapshot.isNotBlank()) {
            val ok = runCatching {
                Settings.Secure.putString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    snapshot,
                )
            }.getOrDefault(false)
            marks += if (ok) "已恢复原无障碍启用列表" else "恢复启用列表失败"
        }
        // 离开敏感页：先撤销限制；若用户手动配置过白名单，再按配置重新下发
        val plane = DeviceOwnerControlPlane(app)
        if (plane.isReady()) {
            marks += if (plane.clearAccessibilityWhitelist()) {
                "已撤销无障碍白名单（恢复为不限制）"
            } else {
                "撤销白名单失败"
            }
            val selection = Prefs.accessWhitelistSelection.value
            if (selection.isNotEmpty()) {
                val list = (listOf(AccessibilityWhitelist.SELF_PACKAGE) + selection).distinct()
                marks += if (plane.updateAccessibilityWhitelist(list)) {
                    "已按你的白名单重新下发（${list.size} 个包）"
                } else {
                    "⚠ 白名单重新下发失败"
                }
            }
        }
        Prefs.clearSensitiveLock()
        val summary = "敏感页看护已撤销：" + marks.joinToString("；")
        AppLog.event(RiskLevel.L2, "敏感页看护已撤销", summary)
        Trace.step(summary)
        return summary
    }
}
