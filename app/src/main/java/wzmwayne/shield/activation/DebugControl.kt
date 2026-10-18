package wzmwayne.shield.activation

import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import wzmwayne.shield.admin.ShieldDeviceAdminReceiver
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.RiskLevel

/**
 * 调试通道开关（激活后的第一道收口）。
 *
 * 禁止：设备所有者 `DISALLOW_DEBUGGING_FEATURES` + `ADB_ENABLED=0` + `DEVELOPMENT_SETTINGS_ENABLED=0`
 * 恢复：解除限制并把两个开关打开（测试期需要 adb 时用）。
 */
object DebugControl {

    data class Result(
        val restrictionOk: Boolean,
        val adbValueOk: Boolean,
        val devValueOk: Boolean,
        val message: String,
    )

    fun block(context: Context): Result {
        val restriction = runCatching {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return@runCatching false
            dpm.addUserRestriction(
                ComponentName(context, ShieldDeviceAdminReceiver::class.java),
                UserManager.DISALLOW_DEBUGGING_FEATURES,
            )
            true
        }.getOrDefault(false)

        val adb = runCatching {
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        }.isSuccess
        val dev = runCatching {
            Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        }.isSuccess

        val result = Result(
            restrictionOk = restriction,
            adbValueOk = adb,
            devValueOk = dev,
            message = buildString {
                append("禁止调试：")
                append(if (restriction) "已限制开发者选项" else "⚠ 限制开发者选项失败（需设备所有者）")
                append(if (adb) "；已关闭 ADB" else "；⚠ 关闭 ADB 失败（需 WRITE_SECURE_SETTINGS）")
                append(if (dev) "；已关闭开发者选项开关" else "；⚠ 关闭开发者选项失败")
            },
        )
        AppLog.event(RiskLevel.L3, "禁止调试", result.message)
        return result
    }

    fun allow(context: Context): Result {
        val restriction = runCatching {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return@runCatching false
            dpm.clearUserRestriction(
                ComponentName(context, ShieldDeviceAdminReceiver::class.java),
                UserManager.DISALLOW_DEBUGGING_FEATURES,
            )
            true
        }.getOrDefault(false)

        val adb = runCatching {
            Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        }.isSuccess
        val dev = runCatching {
            Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        }.isSuccess

        val result = Result(
            restrictionOk = restriction,
            adbValueOk = adb,
            devValueOk = dev,
            message = buildString {
                append("恢复调试：")
                append(if (restriction) "已解除开发者选项限制" else "⚠ 解除限制失败")
                append(if (adb) "；已打开 ADB" else "；⚠ 打开 ADB 失败")
                append(if (dev) "；已打开开发者选项开关" else "；⚠ 打开开发者选项失败")
            },
        )
        AppLog.event(RiskLevel.L2, "恢复调试", result.message)
        return result
    }
}
