package wzmwayne.shield.core.logic

/** 事件组合规则中与「频率」相关的纯计算，供感知层调用。 */
object EventRules {
    const val CLICK_WINDOW_MS = 5_000L
    const val CLICK_THRESHOLD = 10
    const val SWITCH_WINDOW_MS = 30_000L
    const val SWITCH_THRESHOLD = 5

    fun countWithin(timestamps: List<Long>, now: Long, windowMs: Long): Int =
        timestamps.count { it in (now - windowMs)..now }

    /** 高频模拟点击：5 秒内超过 10 次 */
    fun isClickBurst(timestamps: List<Long>, now: Long): Boolean =
        countWithin(timestamps, now, CLICK_WINDOW_MS) > CLICK_THRESHOLD

    /** 跨应用连续操作：30 秒内切换超过 5 个应用 */
    fun isRapidAppSwitch(switchTimestamps: List<Long>, now: Long): Boolean =
        countWithin(switchTimestamps, now, SWITCH_WINDOW_MS) > SWITCH_THRESHOLD
}
