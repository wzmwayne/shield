package wzmwayne.shield.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Prefs

/** 开机与自身更新后的兜底拉起。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs.init(context.applicationContext)
        AppLog.i("收到广播 $action，尝试拉起保活服务")
        KeepAliveEngine.ensureServiceRunning(context.applicationContext, "广播 $action")
        KeepAliveEngine.evaluate(context.applicationContext, "广播 $action")
        // 开机/更新后重申自我保护措施
        wzmwayne.shield.protection.SelfProtection.maybeVerify(context.applicationContext, force = true)
    }
}
