package wzmwayne.shield.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.RiskLevel

/**
 * 锁盾的设备所有者身份载体。不声明 testOnly，因此 ADB 无法移除其设备所有者身份。
 * 本期只记录启用/停用事件，管控动作留给后续的管控层实现。
 */
class ShieldDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        val who = ComponentName(context.applicationContext, ShieldDeviceAdminReceiver::class.java)
        AppLog.event(RiskLevel.L2, "设备管理器已启用", who.flattenToString())
    }

    override fun onDisabled(context: Context, intent: Intent) {
        AppLog.event(RiskLevel.L3, "设备管理器被移除", "锁盾的设备所有者身份已失效，请重新激活")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "移除锁盾的设备管理员权限后，锁盾将无法继续保护本机。"
}
