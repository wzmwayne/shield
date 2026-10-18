package wzmwayne.shield.control

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import wzmwayne.shield.admin.ShieldDeviceAdminReceiver
import wzmwayne.shield.core.AppLog

/**
 * DeviceOwnerControlPlane 的扩展能力（能力面板用）。
 * 与主管控层共用同一个设备策略实例，失败只记录不抛出。
 */
class DeviceOwnerOps(private val context: Context) {

    private val dpm: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin: ComponentName = ComponentName(context, ShieldDeviceAdminReceiver::class.java)

    fun isReady(): Boolean = runCatching { dpm?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)

    fun setCameraDisabled(disabled: Boolean): Boolean = runCatching {
        dpm?.setCameraDisabled(admin, disabled) ?: return false
        true
    }.onFailure { AppLog.w("setCameraDisabled 失败：${it.message}") }.getOrDefault(false)

    fun isCameraDisabled(): Boolean = runCatching { dpm?.getCameraDisabled(admin) == true }.getOrDefault(false)

    fun setScreenCaptureDisabled(disabled: Boolean): Boolean = runCatching {
        dpm?.setScreenCaptureDisabled(admin, disabled) ?: return false
        true
    }.onFailure { AppLog.w("setScreenCaptureDisabled 失败：${it.message}") }.getOrDefault(false)

    fun isScreenCaptureDisabled(): Boolean = runCatching { dpm?.getScreenCaptureDisabled(admin) == true }.getOrDefault(false)

    fun setStatusBarDisabled(disabled: Boolean): Boolean = runCatching {
        dpm?.setStatusBarDisabled(admin, disabled) ?: return false
        true
    }.onFailure { AppLog.w("setStatusBarDisabled 失败：${it.message}") }.getOrDefault(false)

    fun isStatusBarDisabled(): Boolean = runCatching { dpm?.isStatusBarDisabled() == true }.getOrDefault(false)

    fun setUserRestriction(key: String, enabled: Boolean): Boolean = runCatching {
        val manager = dpm ?: return false
        if (enabled) manager.addUserRestriction(admin, key) else manager.clearUserRestriction(admin, key)
        true
    }.onFailure { AppLog.w("userRestriction($key=$enabled) 失败：${it.message}") }.getOrDefault(false)

    fun hasUserRestriction(key: String): Boolean = runCatching {
        dpm?.getUserRestrictions(admin)?.getBoolean(key) == true
    }.getOrDefault(false)

    /** 把 UserManager.DISALLOW_* 常量名解析成真实的 restriction 字符串 */
    fun restrictionValue(constantName: String): String? = runCatching {
        android.os.UserManager::class.java.getField(constantName).get(null) as? String
    }.getOrNull()

    fun setRestrictionByConstant(constantName: String, enabled: Boolean): Boolean {
        val key = restrictionValue(constantName) ?: return false
        return setUserRestriction(key, enabled)
    }

    fun isRestrictionSet(constantName: String): Boolean {
        val key = restrictionValue(constantName) ?: return false
        return hasUserRestriction(key)
    }

    /** 组织名称（设备所有者） */
    fun setOrganizationName(text: CharSequence?): Boolean = runCatching {
        dpm?.setOrganizationName(admin, text) ?: return false
        true
    }.onFailure { AppLog.w("setOrganizationName 失败：${it.message}") }.getOrDefault(false)

    fun organizationName(): String = runCatching { dpm?.getOrganizationName(admin)?.toString().orEmpty() }
        .getOrDefault("")

    /** 锁屏界面文字（设备所有者锁屏通知） */
    fun setLockScreenInfo(text: CharSequence?): Boolean = runCatching {
        dpm?.setDeviceOwnerLockScreenInfo(admin, text) ?: return false
        true
    }.onFailure { AppLog.w("setDeviceOwnerLockScreenInfo 失败：${it.message}") }.getOrDefault(false)

    fun lockScreenInfo(): String = runCatching {
        dpm?.deviceOwnerLockScreenInfo?.toString().orEmpty()
    }.getOrDefault("")

    /** 简短提示（锁屏/LockTask 界面显示） */
    fun setShortSupportMessage(text: CharSequence?): Boolean = runCatching {
        dpm?.setShortSupportMessage(admin, text) ?: return false
        true
    }.onFailure { AppLog.w("setShortSupportMessage 失败：${it.message}") }.getOrDefault(false)

    fun shortSupportMessage(): String = runCatching { dpm?.getShortSupportMessage(admin)?.toString().orEmpty() }
        .getOrDefault("")

    /** 详细提示（设置 → 设备管理 详情页显示） */
    fun setLongSupportMessage(text: CharSequence?): Boolean = runCatching {
        dpm?.setLongSupportMessage(admin, text) ?: return false
        true
    }.onFailure { AppLog.w("setLongSupportMessage 失败：${it.message}") }.getOrDefault(false)

    fun longSupportMessage(): String = runCatching { dpm?.getLongSupportMessage(admin)?.toString().orEmpty() }
        .getOrDefault("")

    fun resetPasswordWithToken(password: String): Boolean = runCatching {
        val manager = dpm ?: return false
        val token = ByteArray(32) { (it * 7).toByte() }
        manager.setResetPasswordToken(admin, token)
        manager.resetPasswordWithToken(admin, password, token, 0)
        true
    }.onFailure { AppLog.w("resetPasswordWithToken 失败：${it.message}") }.getOrDefault(false)
}
