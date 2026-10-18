package wzmwayne.shield.activation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.Trace
import wzmwayne.shield.keepalive.AccessibilitySettings
import wzmwayne.shield.keepalive.EnsureResult
import wzmwayne.shield.keepalive.KeepAliveEngine

/** 阶段五：自主启用无障碍与启动保活。全部依赖 WRITE_SECURE_SETTINGS，不调用任何 Shizuku API。 */
object AccessibilityActivator {

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun enableSelf(context: Context): StepOutcome {
        if (!hasWriteSecureSettings(context)) {
            return StepOutcome.fail(
                "缺少 WRITE_SECURE_SETTINGS",
                "该权限只能由激活阶段通过 Shizuku 授予，请回到上一步重新执行激活",
            )
        }
        Trace.step("用 WRITE_SECURE_SETTINGS 写入 enabled_accessibility_services")
        val result = AccessibilitySettings.ensureSelf(context)
        Trace.step("写入结果：${'$'}result")
        // 写回后立刻回读校验，绝不把失败当成功上报
        val listed = AccessibilitySettings.isSelfListed(context)
        Trace.judge("回读启用列表包含锁盾", listed, AccessibilitySettings.rawValue(context).take(80))
        return when {
            result == EnsureResult.FAILED || !listed -> StepOutcome.fail(
                "写入 enabled_accessibility_services 失败",
                "系统拒绝了写入（可能是厂商限制或权限被撤销），请在系统设置中手动启用锁盾的无障碍服务",
            )

            else -> {
                Prefs.setEnabledAt(System.currentTimeMillis())
                AppLog.event(
                    RiskLevel.L1,
                    "无障碍服务已由锁盾自主启用",
                    if (result == EnsureResult.WRITTEN) "已写入 enabled_accessibility_services" else "已在启用列表中，无需写入",
                )
                StepOutcome.ok(
                    if (result == EnsureResult.WRITTEN) "已写入 enabled_accessibility_services" else "已在启用列表中",
                    "写入后由系统在数秒内绑定服务",
                )
            }
        }
    }

    fun startKeepAlive(context: Context): StepOutcome {
        Trace.step("启动保活服务并注册定时检测（间隔 ${'$'}{Prefs.checkIntervalSeconds.value} 秒）")
        Prefs.setKeepAliveEnabled(true)
        KeepAliveEngine.ensureServiceRunning(context, "激活完成")
        KeepAliveEngine.schedulePeriodicAlarm(context)
        AccessibilitySettings.ensureSelf(context)
        AppLog.event(
            RiskLevel.L1,
            "保活层已启动",
            "监听设置变化、解锁广播与 ${Prefs.checkIntervalSeconds.value} 秒定时检测",
        )
        return StepOutcome.ok("保活层已启动", "定时检测间隔 ${Prefs.checkIntervalSeconds.value} 秒")
    }

    /** 第六步自检的修复入口：重新写回启用列表并立即触发一次保活检测 */
    fun repairAccessibility(context: Context): StepOutcome {
        val enabled = enableSelf(context)
        if (!enabled.ok) return enabled
        KeepAliveEngine.ensureServiceRunning(context, "自检修复")
        KeepAliveEngine.evaluate(context, "自检修复", force = true)
        return StepOutcome.ok("已重新写入无障碍设置并触发检测", enabled.detail)
    }

    fun stopKeepAlive(context: Context): StepOutcome {
        Prefs.setKeepAliveEnabled(false)
        AppLog.event(RiskLevel.L2, "保活层已由用户关闭", "无障碍服务不再自动恢复")
        return StepOutcome.ok("保活层已关闭")
    }

    fun isSelfEnabled(context: Context): Boolean =
        AccessibilitySettings.isSelfEnabledBySystem(context)

    fun isSelfListed(context: Context): Boolean = AccessibilitySettings.isSelfListed(context)
}
