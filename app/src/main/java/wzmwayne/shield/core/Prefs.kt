package wzmwayne.shield.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全应用唯一的持久化与状态中心。内存 StateFlow 为唯一事实来源，
 * 写入时同步落盘，重启后由 init() 恢复。
 */
object Prefs {
    private const val FILE = "shield_prefs"

    const val DEFAULT_LOCK_SCREEN_TEXT = "此设备受锁盾保护。如非本人操作，请立即锁屏并联系机主。"
    const val DEFAULT_SHORT_MESSAGE = "此设备已由锁盾保护并阻止操作"
    const val DEFAULT_ORG_NAME = "锁盾"
    const val DEFAULT_LONG_MESSAGE = "该设备已由锁盾（wzmwayne.shield）作为设备所有者管理：卸载保护、" +
        "无障碍保活、敏感页看护与紧急阻断均已启用。"
    private const val MAX_EVENTS = 200

    private const val K_STATE = "activation_state"
    private const val K_OWNER_MODE = "owner_mode"
    private const val K_KEEPALIVE = "keepalive_enabled"
    private const val K_INTERVAL = "check_interval_seconds"
    private const val K_TOKEN = "reset_token"
    private const val K_HEARTBEAT = "last_heartbeat"
    private const val K_ENABLED_AT = "last_enabled_at"
    private const val K_CONNECTED = "service_connected"
    private const val K_KILL_GAP = "last_kill_gap_ms"
    private const val K_FIX_COUNT = "fix_count"
    private const val K_WIZARD_STEP = "wizard_step"
    private const val K_EVENTS = "events"
    private const val K_SELF_DESTRUCTED = "self_destructed"
    private const val K_PANIC_AT = "panic_at"
    private const val K_PANIC_ACCESSIBILITY = "panic_snapshot_accessibility"
    private const val K_PANIC_ADB = "panic_snapshot_adb"
    private const val K_TRUSTED = "trusted_packages"
    private const val K_SENSITIVE_AT = "sensitive_lock_at"
    private const val K_ACCESS_STRIKES = "accessibility_strikes"
    private const val K_ESCALATIONS = "escalation_strikes"
    private const val K_SENSITIVE_SNAPSHOT = "sensitive_snapshot_accessibility"
    private const val K_PANIC_REPORT = "panic_report"
    private const val K_TEXT_LOCK_SCREEN = "text_lock_screen"
    private const val K_TEXT_SHORT = "text_short"
    private const val K_TEXT_LONG = "text_long"
    private const val K_TEXT_ORG = "text_org_name"
    private const val K_PIN_ENABLED = "pin_enabled"
    private const val K_PIN_SALT = "pin_salt"
    private const val K_PIN_HASH = "pin_hash"
    private const val K_DISABLED_POLICIES = "disabled_policies"
    private const val K_PANIC_LOG = "panic_log"
    private const val K_TRUSTED_HOME = "trusted_home"
    private const val K_TRUSTED_HOMES = "trusted_homes"
    private const val K_PRIMARY_HOME = "primary_home"
    private const val K_ACCESS_SELECTION = "access_whitelist_selection"
    private const val K_DEBUG_HTTP = "debug_http_enabled"
    private const val K_DEBUG_HTTP_PORT = "debug_http_port"
    private const val K_DEBUG_HTTP_LAN = "debug_http_lan"
    private const val K_DEBUG_KILL = "debug_kill"
    private const val K_KILL_UI_SHOWN = "kill_ui_shown"
    private const val K_OWNER_SNAPSHOT = "owner_snapshot"
    private const val K_ACCESSIBILITY_SNAPSHOT = "accessibility_snapshot"

    private var sp: SharedPreferences? = null

    val activationState = MutableStateFlow(ActivationState.NOT_ACTIVATED)
    val ownerMode = MutableStateFlow(OwnerMode.NONE)
    val keepAliveEnabled = MutableStateFlow(false)
    val checkIntervalSeconds = MutableStateFlow(Constants.DEFAULT_CHECK_INTERVAL_SECONDS)
    val resetToken = MutableStateFlow("")
    val lastHeartbeat = MutableStateFlow(0L)
    val lastEnabledAt = MutableStateFlow(0L)
    val serviceConnected = MutableStateFlow(false)
    val lastKillGapMs = MutableStateFlow(0L)
    val fixCount = MutableStateFlow(0)
    val wizardStep = MutableStateFlow(0)
    val ownerSnapshot = MutableStateFlow("")
    val accessibilitySnapshot = MutableStateFlow("")

    /** 一键自杀后为 true：应用完全停摆，任何保活/复活路径都不再启动 */
    val selfDestructed = MutableStateFlow(false)

    /** 紧急处置（音量手势）状态与回滚快照 */
    val panicAt = MutableStateFlow(0L)
    val panicSnapshotAccessibility = MutableStateFlow("")
    val panicSnapshotAdbEnabled = MutableStateFlow(false)

    /** 用户勾选的保护对象（白名单 = 锁盾自身 + 这些包） */
    val trustedPackages = MutableStateFlow<Set<String>>(emptySet())

    /** 最近一次紧急处置的逐应用结果 */
    val panicReport = MutableStateFlow("")

    /** 锁屏文字 / 简短提示 / 详细提示（带默认值，可在设置页编辑） */
    val lockScreenText = MutableStateFlow(DEFAULT_LOCK_SCREEN_TEXT)
    val shortMessage = MutableStateFlow(DEFAULT_SHORT_MESSAGE)
    val longMessage = MutableStateFlow(DEFAULT_LONG_MESSAGE)
    val organizationName = MutableStateFlow(DEFAULT_ORG_NAME)

    /** 应用内 PIN：只存盐与哈希 */
    val pinEnabled = MutableStateFlow(false)

    /** 已关闭的保护策略 id 集合（默认全开） */
    val disabledPolicies = MutableStateFlow<Set<String>>(emptySet())

    /** 处置期间的完整日志（覆盖层「查看处置日志」用，最多 400 行） */
    val panicLog = MutableStateFlow("")

    /** 可信桌面集合（复选框） */
    val trustedHomes = MutableStateFlow<Set<String>>(emptySet())

    /** 主桌面（单选）：触发时锁回到它 */
    val primaryHome = MutableStateFlow("")

    /** 无障碍白名单勾选集合（应用白名单里的应用默认包含，可手动取消） */
    val accessWhitelistSelection = MutableStateFlow<Set<String>>(emptySet())

    /** 调试 HTTP 接口（仅本机，需 PIN） */
    val debugHttpEnabled = MutableStateFlow(false)
    val debugHttpPort = MutableStateFlow(8686)

    /** 调试接口是否绑 0.0.0.0（局域网可达）；false = 仅 127.0.0.1 */
    val debugHttpLan = MutableStateFlow(false)

    /** 隐藏解锁：debug_kill（仪表盘标题连点 10 次）与 kill_ui_shown（设置标题连点） */
    val debugKill = MutableStateFlow(false)
    val killUiShown = MutableStateFlow(false)

    /** 敏感页触发的"无障碍收紧"状态与回滚快照 */
    val sensitiveLockAt = MutableStateFlow(0L)
    val sensitiveSnapshotAccessibility = MutableStateFlow("")

    private val _events = MutableStateFlow<List<ShieldEvent>>(emptyList())
    val events: StateFlow<List<ShieldEvent>> = _events

    fun init(context: Context) {
        if (sp != null) return
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = prefs
        activationState.value = enumOrDefault(prefs.getString(K_STATE, null), ActivationState.NOT_ACTIVATED)
        ownerMode.value = enumOrDefault(prefs.getString(K_OWNER_MODE, null), OwnerMode.NONE)
        keepAliveEnabled.value = prefs.getBoolean(K_KEEPALIVE, false)
        checkIntervalSeconds.value = prefs.getInt(K_INTERVAL, Constants.DEFAULT_CHECK_INTERVAL_SECONDS)
        resetToken.value = prefs.getString(K_TOKEN, "").orEmpty()
        lastHeartbeat.value = prefs.getLong(K_HEARTBEAT, 0L)
        lastEnabledAt.value = prefs.getLong(K_ENABLED_AT, 0L)
        serviceConnected.value = prefs.getBoolean(K_CONNECTED, false)
        lastKillGapMs.value = prefs.getLong(K_KILL_GAP, 0L)
        fixCount.value = prefs.getInt(K_FIX_COUNT, 0)
        wizardStep.value = prefs.getInt(K_WIZARD_STEP, 0)
        selfDestructed.value = prefs.getBoolean(K_SELF_DESTRUCTED, false)
        panicAt.value = prefs.getLong(K_PANIC_AT, 0L)
        panicSnapshotAccessibility.value = prefs.getString(K_PANIC_ACCESSIBILITY, "").orEmpty()
        panicSnapshotAdbEnabled.value = prefs.getBoolean(K_PANIC_ADB, false)
        trustedPackages.value = prefs.getString(K_TRUSTED, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        panicReport.value = prefs.getString(K_PANIC_REPORT, "").orEmpty()
        lockScreenText.value = prefs.getString(K_TEXT_LOCK_SCREEN, DEFAULT_LOCK_SCREEN_TEXT).orEmpty()
        shortMessage.value = prefs.getString(K_TEXT_SHORT, DEFAULT_SHORT_MESSAGE).orEmpty()
        longMessage.value = prefs.getString(K_TEXT_LONG, DEFAULT_LONG_MESSAGE).orEmpty()
        organizationName.value = prefs.getString(K_TEXT_ORG, DEFAULT_ORG_NAME).orEmpty()
        pinEnabled.value = prefs.getBoolean(K_PIN_ENABLED, false)
        panicLog.value = prefs.getString(K_PANIC_LOG, "").orEmpty()
        val legacyHome = prefs.getString(K_TRUSTED_HOME, "").orEmpty()
        val stored = prefs.getString(K_TRUSTED_HOMES, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { if (legacyHome.isNotEmpty()) setOf(legacyHome) else emptySet() }
        trustedHomes.value = stored
        primaryHome.value = prefs.getString(K_PRIMARY_HOME, "").orEmpty().ifEmpty { legacyHome }
        debugHttpEnabled.value = prefs.getBoolean(K_DEBUG_HTTP, false)
        debugHttpPort.value = prefs.getInt(K_DEBUG_HTTP_PORT, 8686)
        debugHttpLan.value = prefs.getBoolean(K_DEBUG_HTTP_LAN, false)
        debugKill.value = prefs.getBoolean(K_DEBUG_KILL, false)
        killUiShown.value = prefs.getBoolean(K_KILL_UI_SHOWN, false)
        accessWhitelistSelection.value = prefs.getString(K_ACCESS_SELECTION, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .ifEmpty { trustedPackages.value }
        disabledPolicies.value = prefs.getString(K_DISABLED_POLICIES, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        sensitiveLockAt.value = prefs.getLong(K_SENSITIVE_AT, 0L)
        sensitiveSnapshotAccessibility.value = prefs.getString(K_SENSITIVE_SNAPSHOT, "").orEmpty()
        ownerSnapshot.value = prefs.getString(K_OWNER_SNAPSHOT, "").orEmpty()
        accessibilitySnapshot.value = prefs.getString(K_ACCESSIBILITY_SNAPSHOT, "").orEmpty()
        _events.value = prefs.getString(K_EVENTS, "").orEmpty()
            .split('\n')
            .mapNotNull { ShieldEvent.decode(it) }
            .sortedByDescending { it.timestamp }
    }

    fun setActivationState(state: ActivationState) {
        activationState.value = state
        edit { it.putString(K_STATE, state.name) }
    }

    fun setOwnerMode(mode: OwnerMode) {
        ownerMode.value = mode
        edit { it.putString(K_OWNER_MODE, mode.name) }
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        keepAliveEnabled.value = enabled
        edit { it.putBoolean(K_KEEPALIVE, enabled) }
    }

    fun setCheckIntervalSeconds(seconds: Int) {
        val value = maxOf(seconds, Constants.MIN_CHECK_INTERVAL_SECONDS)
        checkIntervalSeconds.value = value
        edit { it.putInt(K_INTERVAL, value) }
    }

    fun setResetToken(token: String) {
        resetToken.value = token
        edit { it.putString(K_TOKEN, token) }
    }

    fun setHeartbeat(now: Long) {
        lastHeartbeat.value = now
        edit { it.putLong(K_HEARTBEAT, now) }
    }

    fun setEnabledAt(now: Long) {
        lastEnabledAt.value = now
        edit { it.putLong(K_ENABLED_AT, now) }
    }

    fun setServiceConnected(connected: Boolean) {
        serviceConnected.value = connected
        edit { it.putBoolean(K_CONNECTED, connected) }
    }

    fun setLastKillGap(gapMs: Long) {
        lastKillGapMs.value = gapMs
        edit { it.putLong(K_KILL_GAP, gapMs) }
    }

    fun bumpFixCount() {
        val next = fixCount.value + 1
        fixCount.value = next
        edit { it.putInt(K_FIX_COUNT, next) }
    }

    fun setWizardStep(step: Int) {
        wizardStep.value = step
        edit { it.putInt(K_WIZARD_STEP, step) }
    }

    fun setSnapshots(owner: String, accessibility: String) {
        ownerSnapshot.value = owner
        accessibilitySnapshot.value = accessibility
        edit {
            it.putString(K_OWNER_SNAPSHOT, owner)
            it.putString(K_ACCESSIBILITY_SNAPSHOT, accessibility)
        }
    }

    fun setPanicAt(timestamp: Long) {
        panicAt.value = timestamp
        edit { it.putLong(K_PANIC_AT, timestamp) }
    }

    fun setTrustedPackages(packages: Set<String>) {
        trustedPackages.value = packages
        edit { it.putString(K_TRUSTED, packages.joinToString(",")) }
    }

    fun toggleTrusted(packageName: String, trusted: Boolean) {
        val next = trustedPackages.value.toMutableSet()
        if (trusted) next += packageName else next -= packageName
        setTrustedPackages(next)
    }

    /**
     * 记录一次「无障碍服务被关闭」，返回**最近 60 秒内**的累计次数。
     */
    fun recordAccessibilityStrike(): Int {
        val now = System.currentTimeMillis()
        val window = 60_000L
        val kept = accessibilityStrikes().filter { now - it <= window } + now
        edit { it.putString(K_ACCESS_STRIKES, kept.joinToString(",")) }
        return kept.size
    }

    private fun accessibilityStrikes(): List<Long> =
        getStringList(K_ACCESS_STRIKES).mapNotNull { it.toLongOrNull() }

    /** 10 分钟内允许的「全局阻断重启升级」次数上限 */
    fun recordEscalation(): Int {
        val now = System.currentTimeMillis()
        val window = 10 * 60_000L
        val kept = getStringList(K_ESCALATIONS).mapNotNull { it.toLongOrNull() }
            .filter { now - it <= window } + now
        edit { it.putString(K_ESCALATIONS, kept.joinToString(",")) }
        return kept.size
    }

    private fun getStringList(key: String): List<String> =
        sp?.getString(key, "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun setPinEnabled(enabled: Boolean) {
        pinEnabled.value = enabled
        edit { it.putBoolean(K_PIN_ENABLED, enabled) }
    }

    fun setDebugKill(enabled: Boolean) {
        debugKill.value = enabled
        edit { it.putBoolean(K_DEBUG_KILL, enabled) }
    }

    fun setKillUiShown(shown: Boolean) {
        killUiShown.value = shown
        edit { it.putBoolean(K_KILL_UI_SHOWN, shown) }
    }

    fun setDebugHttp(enabled: Boolean, port: Int, lan: Boolean) {
        debugHttpEnabled.value = enabled
        debugHttpPort.value = port
        debugHttpLan.value = lan
        edit {
            it.putBoolean(K_DEBUG_HTTP, enabled)
            it.putInt(K_DEBUG_HTTP_PORT, port)
            it.putBoolean(K_DEBUG_HTTP_LAN, lan)
        }
    }

    fun setAccessWhitelistSelection(packages: Set<String>) {
        accessWhitelistSelection.value = packages
        edit { it.putString(K_ACCESS_SELECTION, packages.joinToString(",")) }
    }

    fun setTrustedHomes(packages: Set<String>, primary: String) {
        trustedHomes.value = packages
        primaryHome.value = primary
        edit {
            it.putString(K_TRUSTED_HOMES, packages.joinToString(","))
            it.putString(K_PRIMARY_HOME, primary)
        }
    }

    /**
     * 历史阻断日志里的旧手势文案加注记：
     * 旧版本用过「连按 3 次 / 音量连续变化 3 次」，现在只认「音量键上下交替 5 轮」。
     */
    fun migrateLegacyPanicLog() {
        val current = panicLog.value
        if (current.isBlank()) return
        var updated = current
        listOf(
            "手势：音量连续变化 3 次（按键兜底通道）",
            "手势：连按音量键 3 次",
        ).forEach { legacy ->
            updated = updated.replace(legacy, "$legacy（旧版本记录，当前手势为音量键上下交替 5 轮）")
        }
        if (updated != current) {
            panicLog.value = updated
            edit { it.putString(K_PANIC_LOG, updated) }
        }
    }

    fun clearPanicLog() {
        panicLog.value = ""
        edit { it.putString(K_PANIC_LOG, "") }
    }

    fun appendPanicLog(line: String) {
        val lines = (panicLog.value.split('\n').filter { it.isNotBlank() } + line).takeLast(400)
        val text = lines.joinToString("\n")
        panicLog.value = text
        edit { it.putString(K_PANIC_LOG, text) }
    }

    fun setDisabledPolicies(ids: Set<String>) {
        disabledPolicies.value = ids
        edit { it.putString(K_DISABLED_POLICIES, ids.joinToString(",")) }
    }

    fun setPin(salt: String, hash: String) {
        edit {
            it.putString(K_PIN_SALT, salt)
            it.putString(K_PIN_HASH, hash)
        }
    }

    fun clearPin() {
        edit {
            it.remove(K_PIN_SALT)
            it.remove(K_PIN_HASH)
        }
    }

    fun pinSalt(): ByteArray? = sp?.getString(K_PIN_SALT, null)
        ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }

    fun pinHash(): ByteArray? = sp?.getString(K_PIN_HASH, null)
        ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }

    fun setTexts(lockScreen: String, short: String, long: String, organization: String) {
        lockScreenText.value = lockScreen
        shortMessage.value = short
        longMessage.value = long
        organizationName.value = organization
        edit {
            it.putString(K_TEXT_LOCK_SCREEN, lockScreen)
            it.putString(K_TEXT_SHORT, short)
            it.putString(K_TEXT_LONG, long)
            it.putString(K_TEXT_ORG, organization)
        }
    }

    fun resetTexts() =
        setTexts(DEFAULT_LOCK_SCREEN_TEXT, DEFAULT_SHORT_MESSAGE, DEFAULT_LONG_MESSAGE, DEFAULT_ORG_NAME)

    fun setSensitiveLock(timestamp: Long, accessibilitySnapshot: String) {
        sensitiveLockAt.value = timestamp
        sensitiveSnapshotAccessibility.value = accessibilitySnapshot
        edit {
            it.putLong(K_SENSITIVE_AT, timestamp)
            it.putString(K_SENSITIVE_SNAPSHOT, accessibilitySnapshot)
        }
    }

    fun clearSensitiveLock() {
        sensitiveLockAt.value = 0L
        sensitiveSnapshotAccessibility.value = ""
        edit {
            it.putLong(K_SENSITIVE_AT, 0L)
            it.putString(K_SENSITIVE_SNAPSHOT, "")
        }
    }

    fun setPanicReport(raw: String) {
        panicReport.value = raw
        edit { it.putString(K_PANIC_REPORT, raw) }
    }

    fun setPanicSnapshot(accessibility: String, adbEnabled: Boolean) {
        panicSnapshotAccessibility.value = accessibility
        panicSnapshotAdbEnabled.value = adbEnabled
        edit {
            it.putString(K_PANIC_ACCESSIBILITY, accessibility)
            it.putBoolean(K_PANIC_ADB, adbEnabled)
        }
    }

    fun setSelfDestructed(value: Boolean) {
        selfDestructed.value = value
        edit { it.putBoolean(K_SELF_DESTRUCTED, value) }
    }

    fun recordEvent(level: RiskLevel, title: String, detail: String = "") {
        val updated = (listOf(ShieldEvent(System.currentTimeMillis(), level, title, detail)) + _events.value)
            .take(MAX_EVENTS)
        _events.value = updated
        val encoded = updated.joinToString("\n") { it.encode() }
        edit { it.putString(K_EVENTS, encoded) }
    }

    fun clearEvents() {
        _events.value = emptyList()
        edit { it.putString(K_EVENTS, "") }
    }

    private inline fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val prefs = sp ?: return
        val editor = prefs.edit()
        block(editor)
        editor.apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        if (raw == null) fallback else runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
}
