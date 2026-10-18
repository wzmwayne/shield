package wzmwayne.shield.keepalive

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.Prefs

/**
 * 保活层的前台常驻服务：
 *  · 监听 enabled_accessibility_services 变化（Shell 或恶意应用一旦改设置，立即恢复）
 *  · 监听解锁广播（部分机型解锁后会挂起无障碍服务）
 *  · 60 秒本地心跳 + AlarmManager 定时检测（≥5 分钟）
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var unlockReceiver: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            KeepAliveEngine.evaluate(this@KeepAliveService, "前台服务心跳")
            // 自我保护校验（内部有 10 分钟节流）
            wzmwayne.shield.protection.SelfProtection.maybeVerify(this@KeepAliveService)
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifications.ensureChannel(this)
        startForegroundCompat()
        registerSettingsObserver()
        registerUnlockReceiver()
        handler.postDelayed(tick, TICK_INTERVAL_MS)
        KeepAliveEngine.schedulePeriodicAlarm(this)
        AppLog.i("保活层本地检测间隔：${TICK_INTERVAL_MS / 1000} 秒")
        AppLog.i("保活服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(Constants.EXTRA_SOURCE) ?: "服务启动"
        KeepAliveEngine.evaluate(this, source)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        AppLog.w("保活服务被销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = Notifications.build(this, getString(wzmwayne.shield.R.string.keepalive_notification_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_ID_KEEPALIVE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID_KEEPALIVE, notification)
        }
    }

    private fun registerSettingsObserver() {
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                KeepAliveEngine.evaluate(this@KeepAliveService, KeepAliveEngine.SOURCE_SETTINGS_CHANGED)
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                contentObserver,
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                contentObserver,
            )
        }.onFailure { AppLog.w("注册设置观察者失败：${it.message}") }
        observer = contentObserver
    }

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> KeepAliveEngine.evaluate(context, "解锁检测")
                    Intent.ACTION_SCREEN_ON -> KeepAliveEngine.evaluate(context, "亮屏检测")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { AppLog.w("注册解锁广播失败：${it.message}") }
        unlockReceiver = receiver
    }

    companion object {
        /** 无障碍自保的本地检测间隔：默认 10 秒（只读设置+心跳，开销极小） */
        const val TICK_INTERVAL_MS = Constants.LOCAL_CHECK_INTERVAL_SECONDS * 1_000L
        private const val PREF_RESTART_REQUESTED = "restart_requested_at"

        fun markRestartRequested(context: Context) {
            context.getSharedPreferences("shield_runtime", Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_RESTART_REQUESTED, System.currentTimeMillis())
                .apply()
        }
    }
}
