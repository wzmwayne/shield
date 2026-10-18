package wzmwayne.shield.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Constants
import wzmwayne.shield.activation.DebugControl
import wzmwayne.shield.core.DisposalState
import wzmwayne.shield.core.Policies
import wzmwayne.shield.core.Policy
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.response.PanicResponse
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.logic.HealthJudge
import wzmwayne.shield.core.logic.ServiceHealth
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 保活层大脑：只保活锁盾自身（与参考实现「保活任意白名单应用」的最大差异）。
 *
 * 线程约定：所有 dumpsys、设置读写、Thread.sleep 一律在 executor 单线程执行；
 * Handler 只用于"延迟后再丢回 executor"，绝不把耗时任务跑在主线程。
 *
 * 处置顺序：直接重写设置 → 关闭-延迟 1 秒-重开 → 5 秒后复查 → 仍失败重试（最多 2 次）
 * → 仍失败且服务仍被期望启用时，才升级为进程级重启（带持久化退避与次数上限）。
 */
object KeepAliveEngine {
    const val SOURCE_SETTINGS_CHANGED = "无障碍设置变化"

    private const val RECHECK_DELAY_MS = 5_000L
    private const val SELF_WRITE_SUPPRESS_MS = 4_000L
    private const val KILL_BACKOFF_MS = 15 * 60_000L
    private const val KILL_WINDOW_MS = 6 * 60 * 60_000L
    private const val MAX_KILLS_PER_WINDOW = 3

    private const val RUNTIME_PREFS = "shield_runtime"
    private const val KEY_LAST_KILL_AT = "last_kill_at"
    private const val KEY_KILL_COUNT = "kill_count"
    private const val KEY_RESTART_REQUESTED_AT = "restart_requested_at"

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shield-keepalive").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)

    /** 1 分钟内被关闭达到该次数即升级：重启无障碍服务 + 全局阻断 */
    private const val STRIKES_FOR_ESCALATION = 3

    /** 10 分钟内最多升级几次，避免反复升级死循环 */
    private const val MAX_ESCALATIONS_PER_WINDOW = 3

    private var lastCrashCheckAt = 0L
    private var lastFixAt = 0L
    private var fixAttempt = 0
    private var processStartReported = false

    private fun runtime(context: Context) =
        context.applicationContext.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)

    /**
     * 进程启动时调用（只在 ShieldApplication 调一次）。
     * 关键点：进程刚起来时心跳必然是旧的，必须把宽限期刷新到当前时刻，
     * 否则会被误判为假死并触发破坏性修复。
     */
    fun recordProcessStart(context: Context) {
        if (processStartReported) return
        processStartReported = true
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        Prefs.setServiceConnected(false)
        if (!Prefs.keepAliveEnabled.value) return

        if (AccessibilitySettings.isSelfListed(appContext)) {
            Prefs.setEnabledAt(now)
        }

        val requestedAt = runtime(appContext).getLong(KEY_RESTART_REQUESTED_AT, 0L)
        val selfRequestedRestart = requestedAt > 0 && now - requestedAt < KILL_BACKOFF_MS
        val lastHeartbeat = Prefs.lastHeartbeat.value
        if (selfRequestedRestart || lastHeartbeat <= 0) return

        val gap = now - lastHeartbeat
        if (gap > Constants.HEARTBEAT_STALE_MS) {
            Prefs.setLastKillGap(gap)
            AppLog.event(
                RiskLevel.L3,
                "检测到锁盾进程中断",
                "中断约 ${gap / 1000} 秒；无障碍服务由系统重新绑定，保活层已接管恢复",
            )
        }
    }

    /** 其他模块（如自我保护）写设置前调用，抑制由此触发的观察者回调 */
    fun markSelfWrite() {
        lastFixAt = System.currentTimeMillis()
    }

    fun ensureServiceRunning(context: Context, reason: String) {
        Prefs.init(context.applicationContext)
        if (!Prefs.keepAliveEnabled.value || Prefs.selfDestructed.value) return
        if (!Policies.isOn(Policy.KEEPALIVE)) return
        val intent = Intent(context, KeepAliveService::class.java).putExtra(Constants.EXTRA_SOURCE, reason)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            AppLog.w("启动保活服务失败（$reason）：${t.message}")
            schedulePeriodicAlarm(context)
        }
    }

    fun evaluate(context: Context, source: String, force: Boolean = false) {
        if (!Prefs.keepAliveEnabled.value || Prefs.selfDestructed.value) return
        // 修复过程中我们自己会写设置，观察者回调需要抑制，避免自我触发的连环修复
        if (source == SOURCE_SETTINGS_CHANGED && System.currentTimeMillis() - lastFixAt < SELF_WRITE_SUPPRESS_MS) {
            AppLog.d("忽略自身写入设置引起的回调")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            AppLog.d("保活检查进行中，跳过本次触发（$source）")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                doEvaluate(appContext, source, force)
            } catch (t: Throwable) {
                AppLog.e("保活检查异常：${t.message}", t)
            } finally {
                busy.set(false)
            }
        }
    }

    fun onServiceUnbound(context: Context) {
        val appContext = context.applicationContext
        handler.postDelayed({ evaluate(appContext, "无障碍服务断开后复查") }, Constants.RESTART_DELAY_MS)
    }

    fun schedulePeriodicAlarm(context: Context) {
        if (!Prefs.keepAliveEnabled.value) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + Prefs.checkIntervalSeconds.value * 1_000L
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, periodicIntent(context))
        }.onFailure { AppLog.w("定时唤醒设置失败：${it.message}") }
    }

    private fun periodicIntent(context: Context): PendingIntent {
        val intent = Intent(context, PeriodicCheckReceiver::class.java)
            .setAction(Constants.ACTION_PERIODIC_CHECK)
        return PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun doEvaluate(context: Context, source: String, force: Boolean) {
        val component = AccessibilitySettings.componentId(context)
        val inSettings = AccessibilitySettings.isSelfListed(context)
        val bySystem = AccessibilitySettings.isSelfEnabledBySystem(context)
        val now = System.currentTimeMillis()
        val crashed = if (shouldCheckCrash(context, now, force)) CrashDetector.selfCrashed(context, component) else null
        val health = HealthJudge.judge(
            listedInSettings = inSettings,
            listedBySystem = bySystem,
            lastHeartbeat = Prefs.lastHeartbeat.value,
            lastEnabledAt = Prefs.lastEnabledAt.value,
            now = now,
            staleMs = Constants.HEARTBEAT_STALE_MS,
            graceMs = Constants.ENABLE_GRACE_MS,
            crashed = crashed == true,
        )
        AppLog.d("保活检查[$source]：设置内=$inSettings 系统内=$bySystem 崩溃=$crashed 结论=$health")
        when (health) {
            ServiceHealth.OK -> fixAttempt = 0

            // 只要自保开着，无障碍服务不在启用列表里就一律按「被移出」处理并恢复。
            // 用户想真正停掉，必须先在应用内关闭自保，或走仪表盘的「一键自杀」（带冷却）。
            ServiceHealth.NOT_ENABLED -> {
                AppLog.event(
                    RiskLevel.L3,
                    "无障碍服务不在启用列表中",
                    "来源=$source，自保开启中 → 按被移出处理并恢复",
                )
                onAccessibilityDisabled(context, component, source)
                fix(context, component, source)
            }

            ServiceHealth.REMOVED -> {
                AppLog.event(RiskLevel.L3, "无障碍服务被移出系统设置", "来源=$source，已用 WRITE_SECURE_SETTINGS 恢复")
                onAccessibilityDisabled(context, component, source)
                fix(context, component, source)
            }

            ServiceHealth.HUNG -> {
                AppLog.event(RiskLevel.L2, "无障碍服务假死", "来源=$source，执行重启流程")
                fix(context, component, source)
            }

            ServiceHealth.CRASHED -> {
                AppLog.event(RiskLevel.L3, "系统报告锁盾无障碍服务崩溃", "来源=$source，执行重启流程")
                fix(context, component, source)
            }
        }
    }

    private fun shouldCheckCrash(context: Context, now: Long, force: Boolean): Boolean {
        if (!CrashDetector.hasDumpPermission(context)) return false
        if (force) return true
        if (now - lastCrashCheckAt < Constants.CRASH_CHECK_COOLDOWN_MS) return false
        lastCrashCheckAt = now
        return true
    }

    /**
     * 无障碍服务被关闭时的联动（运行在 executor 线程）：
     *  ① 若此刻正在处置 → 中止当前阻断，后台禁止调试，随后重新执行全局阻断；
     *  ② 若 1 分钟内被关闭 3 次 → 重启无障碍服务（fix 会写回启用列表并拉起前台服务）并触发全局阻断。
     * 注意：这里的「重启」只重启**无障碍服务**，绝不重启设备。
     */
    private fun onAccessibilityDisabled(context: Context, component: String, source: String) {
        if (!Policies.isOn(Policy.CLOSE_ESCALATION)) return
        val strikes = Prefs.recordAccessibilityStrike()

        if (DisposalState.running) {
            DisposalState.requestAbort()
            AppLog.event(
                RiskLevel.L3,
                "阻断过程中无障碍被关闭",
                "来源=$source → 中止当前阻断，后台禁止调试后重新执行全局阻断",
            )
            if (Prefs.recordEscalation() <= MAX_ESCALATIONS_PER_WINDOW) {
                executor.execute {
                    sleepQuietly(600)
                    DebugControl.block(context)
                    sleepQuietly(300)
                    PanicResponse.trigger(context, "阻断过程中无障碍被关闭，已重新启动全局阻断")
                }
            }
            return
        }

        if (strikes >= STRIKES_FOR_ESCALATION) {
            AppLog.event(
                RiskLevel.L3,
                "无障碍服务 1 分钟内被关闭 ${strikes} 次",
                "重启无障碍服务并触发全局阻断（不重启设备）",
            )
            if (Prefs.recordEscalation() <= MAX_ESCALATIONS_PER_WINDOW) {
                executor.execute {
                    sleepQuietly(600)
                    PanicResponse.trigger(context, "无障碍 1 分钟内被关闭 ${strikes} 次")
                }
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    /** 运行在 executor 线程 */
    private fun fix(context: Context, component: String, source: String) {
        Prefs.bumpFixCount()
        lastFixAt = System.currentTimeMillis()
        // 第一步：直接重写设置（对「被移出设置」即时生效）
        val rewritten = AccessibilitySettings.ensureSelf(context)
        // 第二步：关闭服务，延迟 1 秒后重开（对应设计文档的「延迟 1 秒保活」）
        AccessibilitySettings.removeSelf(context)
        sleep(Constants.RESTART_DELAY_MS)
        val reEnabled = AccessibilitySettings.ensureSelf(context)
        Prefs.setEnabledAt(System.currentTimeMillis())
        AppLog.event(
            RiskLevel.L2,
            "保活修复已执行",
            "来源=$source，重写=$rewritten，重开=$reEnabled",
        )
        scheduleRecheck(context, component, source)
    }

    private fun scheduleRecheck(context: Context, component: String, source: String) {
        handler.postDelayed({
            executor.execute { recheck(context, component, source) }
        }, RECHECK_DELAY_MS)
    }

    /** 运行在 executor 线程 */
    private fun recheck(context: Context, component: String, source: String) {
        val listedInSettings = AccessibilitySettings.isSelfListed(context)
        val bySystem = AccessibilitySettings.isSelfEnabledBySystem(context)
        val crashed = CrashDetector.selfCrashed(context, component)
        val healthy = listedInSettings && crashed != true && (bySystem || System.currentTimeMillis() - Prefs.lastEnabledAt.value < Constants.ENABLE_GRACE_MS)
        if (healthy) {
            fixAttempt = 0
            Prefs.setServiceConnected(bySystem)
            AppLog.event(RiskLevel.L1, "保活复查通过", "无障碍服务已回到正常状态（来源=$source）")
            return
        }
        if (!listedInSettings) {
            // 服务不在启用列表里，说明用户/系统明确关闭了它，不再继续破坏性修复
            fixAttempt = 0
            AppLog.w("复查发现服务不在启用列表，停止修复链（来源=$source）")
            return
        }
        if (fixAttempt < Constants.MAX_FIX_RETRY) {
            fixAttempt += 1
            AppLog.event(RiskLevel.L2, "保活复查未通过，进行第 $fixAttempt 次重试", "设置内=$listedInSettings 系统内=$bySystem 崩溃=$crashed")
            fix(context, component, "修复后复查")
            return
        }
        fixAttempt = 0
        forceRestart(context, source)
    }

    /**
     * 重启强杀：直接重启服务失败后强杀自身进程，交由系统重新绑定并靠前台服务/开机解锁广播兜底。
     * 带持久化退避与窗口内次数上限，避免「杀-拉起-再杀」的死循环。
     */
    private fun forceRestart(context: Context, source: String) {
        if (!Policies.isOn(Policy.FORCE_RESTART)) return
        if (!AccessibilitySettings.isSelfListed(context)) {
            AppLog.w("服务不在启用列表中，取消进程级重启")
            return
        }
        val prefs = runtime(context)
        val now = System.currentTimeMillis()
        val lastKillAt = prefs.getLong(KEY_LAST_KILL_AT, 0L)
        var count = prefs.getInt(KEY_KILL_COUNT, 0)
        if (now - lastKillAt > KILL_WINDOW_MS) count = 0

        if (now - lastKillAt < KILL_BACKOFF_MS) {
            AppLog.event(
                RiskLevel.L2,
                "进程级重启被退避抑制",
                "距上次重启不足 ${KILL_BACKOFF_MS / 60_000} 分钟，等待系统或前台服务自行恢复",
            )
            return
        }
        if (count >= MAX_KILLS_PER_WINDOW) {
            AppLog.event(
                RiskLevel.L3,
                "已达进程重启上限，停止自杀重启",
                "近 ${KILL_WINDOW_MS / 3_600_000} 小时内已重启 $count 次，请手动检查系统无障碍设置",
            )
            return
        }

        prefs.edit()
            .putLong(KEY_LAST_KILL_AT, now)
            .putInt(KEY_KILL_COUNT, count + 1)
            .putLong(KEY_RESTART_REQUESTED_AT, now)
            .apply()
        AppLog.event(
            RiskLevel.L3,
            "保活升级为进程级重启",
            "来源=$source，第 ${count + 1} 次；强杀锁盾进程以触发系统重新绑定",
        )
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
