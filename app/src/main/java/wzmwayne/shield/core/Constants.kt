package wzmwayne.shield.core

object Constants {
    const val PKG_SHIZUKU = "moe.shizuku.privileged.api"
    const val PKG_HAIL = "com.aistra.hail"
    const val PKG_DHIZUKU = "com.rosan.dhizuku"

    /** Dhizuku 的设备所有者 Receiver（由 Dhizuku 自身提供，锁盾不内置该应用） */
    const val DHIZUKU_DA_RECEIVER = "com.rosan.dhizuku/.server.DhizukuDAReceiver"

    const val ASSET_SHIZUKU = "bundled/shizuku.apk"
    const val ASSET_HAIL = "bundled/hail.apk"

    /** Shizuku 用户服务进程名后缀，用于识别 shell 进程 */
    const val USER_SERVICE_SUFFIX = ":shell"

    /** 定时检测间隔：默认 30 秒，步进 10 秒，下限 10 秒 */
    const val DEFAULT_CHECK_INTERVAL_SECONDS = 30
    const val MIN_CHECK_INTERVAL_SECONDS = 10
    const val CHECK_INTERVAL_STEP_SECONDS = 10

    /** 一键自杀的确认等待时间：弹窗出现后需等待这段时间才能点「确认」 */
    const val SUICIDE_CONFIRM_WAIT_MS = 15_000L

    /** 无障碍自保的本地检测间隔（秒）：默认 10 秒 */
    const val LOCAL_CHECK_INTERVAL_SECONDS = 10

    /** 无障碍服务心跳间隔与判定假死的阈值 */
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val HEARTBEAT_STALE_MS = 180_000L

    /** 刚启用后的宽限期，避免误判「假死」 */
    const val ENABLE_GRACE_MS = 60_000L

    /** 延迟 1 秒保活 */
    const val RESTART_DELAY_MS = 1_000L

    /** 崩溃修复的最大重试次数 */
    const val MAX_FIX_RETRY = 2

    /** 崩溃检测的全局冷却时间 */
    const val CRASH_CHECK_COOLDOWN_MS = 5 * 60_000L

    const val CHANNEL_KEEPALIVE = "shield_keepalive"
    const val NOTIFICATION_ID_KEEPALIVE = 1001

    const val ACTION_PERIODIC_CHECK = "wzmwayne.shield.action.PERIODIC_CHECK"
    const val ACTION_TICK = "wzmwayne.shield.action.TICK"
    const val ACTION_UNLOCKED = "wzmwayne.shield.action.UNLOCKED"

    const val EXTRA_SOURCE = "source"

    /** 恢复令牌在设备所有者侧的长度（字节） */
    const val RESET_TOKEN_BYTES = 32
}
