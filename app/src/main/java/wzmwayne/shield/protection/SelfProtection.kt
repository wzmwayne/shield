package wzmwayne.shield.protection

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import wzmwayne.shield.activation.OwnerSetup
import wzmwayne.shield.control.DeviceOwnerControlPlane
import wzmwayne.shield.core.ActivationState
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.CheckItem
import wzmwayne.shield.core.CheckState
import wzmwayne.shield.core.OwnerMode
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RepairActions
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.Trace
import wzmwayne.shield.core.logic.AccessibilityList
import wzmwayne.shield.keepalive.AccessibilitySettings
import wzmwayne.shield.keepalive.EnsureResult
import wzmwayne.shield.keepalive.KeepAliveEngine
import java.util.concurrent.Executors

/**
 * 自我保护层（保护措施清单第 40-45 条）。只在「激活后」生效。
 *
 * 40 设备所有者身份保护：不声明 testOnly（Manifest 已保证）+ 运行时校验身份是否仍有效
 * 41 进程存活保护：前台服务 + 开机/解锁/定时广播 + 心跳中断记录（保活层提供），此处负责重启后重申保护措施
 * 42 无障碍服务保活：与保活层联动校验服务是否在启用列表且健康
 * 43 自身卸载保护：setUninstallBlocked(自身, true)，并定期校验、缺失即补
 * 44 自身权限保护：校验 WRITE_SECURE_SETTINGS 是否仍然有效（只读校验，写入只发生在明确修复时）
 * 45 白名单自锁防护：白名单构建硬编码自身包名，校验白名单是否含自身，缺失立即重建
 *
 * 线程约定：verify/apply 全部在私有 executor 上执行，调用方（Application/Service/UI）不会被阻塞。
 */
object SelfProtection {

    private const val VERIFY_THROTTLE_MS = 10 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shield-selfprotection").apply { isDaemon = true }
    }

    private var lastVerifyAt = 0L

    data class Report(val items: List<CheckItem>, val repairs: List<String>)

    /** 激活完成时调用：一次性应用全部自我保护措施（同步返回结果，调用方需在 IO 线程） */
    fun applyAll(context: Context, reason: String): Report {
        val report = verify(context, repair = true)
        val repairedText = if (report.repairs.isEmpty()) "保护措施已就绪，无需变更" else report.repairs.joinToString("；")
        AppLog.event(RiskLevel.L1, "自我保护已应用", "$reason：$repairedText")
        return report
    }

    /** 节流后的后台校验，任何线程调用都安全 */
    fun maybeVerify(context: Context, force: Boolean = false) {
        if (Prefs.activationState.value != ActivationState.PROTECTING) return
        val now = System.currentTimeMillis()
        if (!force && now - lastVerifyAt < VERIFY_THROTTLE_MS) return
        lastVerifyAt = now
        val appContext = context.applicationContext
        executor.execute {
            runCatching { verify(appContext, repair = true) }
                .onFailure { AppLog.e("自我保护校验异常：${it.message}", it) }
        }
    }

    /** 进程被强杀后重启：设备策略本身持久，这里重申并校验一遍 */
    fun onProcessStart(context: Context) {
        if (Prefs.activationState.value != ActivationState.PROTECTING) return
        lastVerifyAt = 0
        maybeVerify(context, force = true)
    }

    fun verify(context: Context, repair: Boolean): Report {
        val plane = DeviceOwnerControlPlane(context)
        val packageName = context.packageName
        val items = mutableListOf<CheckItem>()
        val repairs = mutableListOf<String>()
        val owner = plane.isReady()
        Trace.step("自我保护校验：设备所有者=${if (owner) "锁盾自身" else "非锁盾（或委托模式）"}")

        // 第 40 条：设备所有者身份（必须由锁盾自身持有，委派模式已禁用）
        items += CheckItem(
            id = "owner",
            title = "设备所有者身份（第 40 条）",
            state = if (owner) CheckState.PASS else CheckState.BLOCK,
            detail = if (owner) {
                "锁盾自身持有设备所有者身份；Manifest 未声明 testOnly，ADB 无法通过 dpm remove-active-admin 移除"
            } else {
                "锁盾不是设备所有者（委派模式已禁用）：请重新执行激活流程"
            },
            action = if (owner) null else RepairActions.OPEN_ACTIVATION,
            actionLabel = "重新激活",
        )

        // 第 43 条：自身卸载保护
        if (!wzmwayne.shield.core.Policies.isOn(wzmwayne.shield.core.Policy.UNINSTALL_BLOCK)) {
            Trace.step("卸载阻止策略已关闭，跳过")
        }
        var uninstallBlocked = plane.isUninstallBlocked(packageName)
        if (owner && !uninstallBlocked && repair && wzmwayne.shield.core.Policies.isOn(wzmwayne.shield.core.Policy.UNINSTALL_BLOCK)) {
            if (plane.setUninstallBlocked(packageName, true)) {
                uninstallBlocked = true
                repairs += "已启用自身卸载保护（setUninstallBlocked）"
            }
        }
        Trace.judge("自身卸载保护（setUninstallBlocked）", uninstallBlocked, if (owner) "" else "无设备所有者身份")
        items += CheckItem(
            id = "uninstall",
            title = "自身卸载保护（第 43 条）",
            state = when {
                uninstallBlocked -> CheckState.PASS
                owner -> CheckState.WARN
                else -> CheckState.UNKNOWN
            },
            detail = when {
                uninstallBlocked -> "已阻止卸载：系统与 ADB 的 uninstall 请求都会被拒绝"
                owner -> "尚未阻止卸载，点击右侧按钮立即生效"
                else -> "无设备所有者身份，无法设置卸载保护"
            },
            action = if (!uninstallBlocked && owner) RepairActions.APPLY_SELF_PROTECTION else null,
            actionLabel = "应用保护",
        )

        // 第 45 条：白名单自锁防护
        var whitelist = plane.permittedAccessibilityServices()
        var selfLocked = AccessibilityWhitelist.isSelfLocked(whitelist)
        Trace.step("无障碍白名单：${AccessibilityWhitelist.describe(whitelist)}")
        // 约定：只有「自动/手动阻断」与「敏感页看护」才写无障碍白名单；自保只做判定，绝不重复下发。
        val lockDownActive = wzmwayne.shield.core.Prefs.sensitiveLockAt.value > 0L
        if (lockDownActive) {
            Trace.step("敏感页看护生效中：白名单由看护统一管理，自保不介入")
        } else if (owner && repair && selfLocked && whitelist != null) {
            repairs += "白名单不含锁盾自身（异常残留）：建议到「设置 → 无障碍白名单」清除限制"
        }
        Trace.judge("白名单含自身（自锁防护）", !selfLocked, AccessibilityWhitelist.describe(whitelist))
        items += CheckItem(
            id = "whitelist",
            title = "无障碍白名单自锁防护（第 45 条）",
            state = when {
                selfLocked -> CheckState.WARN
                else -> CheckState.PASS
            },
            detail = when {
                lockDownActive -> "敏感页看护生效中：白名单由看护统一管理（离开后自动撤销）"
                selfLocked -> "白名单不含锁盾自身（异常残留）：建议清除限制或重新下发"
                whitelist == null -> "未设置限制（默认状态：阻断与敏感页时才会临时下发）"
                else -> "白名单含锁盾自身（${AccessibilityWhitelist.describe(whitelist)}）"
            },
            action = null,
            actionLabel = "下发白名单",
        )

        // 第 42 条：无障碍服务在启用列表中
        val listed = AccessibilitySettings.isSelfListed(context)
        val connected = AccessibilitySettings.isSelfEnabledBySystem(context)
        items += CheckItem(
            id = "accessibility",
            title = "无障碍服务保活（第 42 条）",
            state = when {
                listed && connected -> CheckState.PASS
                listed -> CheckState.WARN
                else -> CheckState.BLOCK
            },
            detail = when {
                listed && connected -> "已在启用列表且系统已绑定；保活层持续守护"
                listed -> "已在启用列表但系统尚未绑定，保活层会在下一次检测时处理"
                else -> "不在启用列表中，保活层将用 WRITE_SECURE_SETTINGS 写回"
            },
            action = if (listed && connected) null else RepairActions.ENABLE_ACCESSIBILITY,
            actionLabel = "写回设置",
        )

        // 第 44 条：WRITE_SECURE_SETTINGS 有效性（只读校验 + 明确修复时才写）
        val hasWriteSecure = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        val listedAfter = AccessibilitySettings.isSelfListed(context)
        items += CheckItem(
            id = "permission",
            title = "自身权限保护（第 44 条）",
            state = if (hasWriteSecure) CheckState.PASS else CheckState.BLOCK,
            detail = if (hasWriteSecure) {
                "WRITE_SECURE_SETTINGS 已授予；当前启用列表${if (listedAfter) "包含" else "不包含"}锁盾（列表校验即为其有效性证据）"
            } else {
                "WRITE_SECURE_SETTINGS 已失效：请重新执行激活阶段授予"
            },
            action = if (hasWriteSecure) null else RepairActions.OPEN_ACTIVATION,
            actionLabel = "重新激活",
        )

        Trace.judge("WRITE_SECURE_SETTINGS 有效性", hasWriteSecure, if (listedAfter) "启用列表包含锁盾" else "启用列表不含锁盾")
        val hasDump = context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        items += CheckItem(
            id = "dump",
            title = "崩溃检测权限（第 42 条配套）",
            state = if (hasDump) CheckState.PASS else CheckState.WARN,
            detail = if (hasDump) {
                "DUMP 有效：可用 dumpsys accessibility 检测自身假死"
            } else {
                "DUMP 缺失：只能依赖定时重置与设置回写两条保活路径；可用 Shizuku 重新授予"
            },
            action = if (hasDump) null else RepairActions.GRANT_DUMP,
            actionLabel = "重新授予",
        )

        // 第 41 条：进程存活
        val killGap = Prefs.lastKillGapMs.value
        items += CheckItem(
            id = "process",
            title = "进程存活保护（第 41 条）",
            state = if (Prefs.serviceConnected.value) CheckState.PASS else CheckState.WARN,
            detail = buildString {
                append("设备所有者功能由系统服务托管，不依赖应用进程；")
                append(if (killGap > 0) "上次进程中断约 ${killGap / 1000} 秒，已自动拉起" else "暂无强杀记录")
                append("（累计修复 ${Prefs.fixCount.value} 次）")
            },
            action = if (Prefs.serviceConnected.value) null else RepairActions.RESTART_KEEPALIVE,
            actionLabel = "重启保活",
        )

        val blocking = items.filter { it.state == CheckState.BLOCK }
        if (blocking.isNotEmpty() && repair) {
            AppLog.event(
                RiskLevel.L2,
                "自我保护校验发现异常",
                blocking.joinToString("；") { it.title + "：" + it.detail },
            )
        }
        if (repairs.isNotEmpty()) {
            AppLog.event(RiskLevel.L2, "自我保护已自动修复", repairs.joinToString("；"))
            toast(context, "自我保护已生效：" + repairs.joinToString("；"))
        } else if (blocking.isNotEmpty()) {
            toast(context, "自我保护发现 ${blocking.size} 项异常：" + blocking.joinToString("；") { it.title })
        }
        Trace.result(blocking.isEmpty(), "自我保护校验：${if (blocking.isEmpty()) "全部正常" else "${blocking.size} 项阻断"}")
        return Report(items, repairs)
    }

    /** 构建白名单：当前已启用服务所属包名 + 硬编码自身包名 */
    /**
     * 自保重建白名单：必须包含「本应用 + 应用白名单 + 已启用的无障碍服务」，
     * 否则会把用户配置的应用白名单一起冲掉（此前的错误行为）。
     */
    fun buildWhitelist(context: Context): List<String> {
        KeepAliveEngine.markSelfWrite()
        return AccessibilityWhitelist.build(
            enabledServicePackages(context),
            wzmwayne.shield.core.Prefs.trustedPackages.value,
        )
    }

    /** 枚举当前已启用无障碍服务所属的包名（白名单参数必须是包名） */
    fun enabledServicePackages(context: Context): List<String?> {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return emptyList()
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .map { info ->
                    info.resolveInfo?.serviceInfo?.packageName
                        ?: info.id?.let { AccessibilityWhitelist.packageOf(it) }
                }
        }.getOrDefault(emptyList())
    }

    /** 自保生效时在界面弹提示（Toast 必须在主线程） */
    private fun toast(context: Context, message: String) {
        if (!Prefs.keepAliveEnabled.value && Prefs.activationState.value != ActivationState.PROTECTING) return
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 明确修复动作：写回自身无障碍设置（对应用户点击「写回设置」） */
    fun repairAccessibility(context: Context): String {
        val result = AccessibilitySettings.ensureSelf(context)
        return when (result) {
            EnsureResult.WRITTEN -> "已写入 enabled_accessibility_services"
            EnsureResult.ALREADY_LISTED -> "启用列表已包含锁盾，无需写入"
            EnsureResult.FAILED -> "写入失败：系统拒绝了 WRITE_SECURE_SETTINGS 写入"
        }
    }
}
