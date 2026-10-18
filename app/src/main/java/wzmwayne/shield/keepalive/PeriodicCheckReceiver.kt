package wzmwayne.shield.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Prefs

/** 定时检测：AlarmManager 每次触发后重新排下一次（避免厂商清理掉重复闹钟）。 */
class PeriodicCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Prefs.init(app)
        AppLog.i("定时检测唤醒")
        KeepAliveEngine.ensureServiceRunning(app, "定时唤醒")
        KeepAliveEngine.evaluate(app, "定时检测", force = true)
        KeepAliveEngine.schedulePeriodicAlarm(app)
        wzmwayne.shield.protection.SelfProtection.maybeVerify(app, force = true)
    }
}
