package wzmwayne.shield.core

import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用内 PIN 锁（**不是独立 Activity**，避免被 `am start` 直接绕过）。
 *
 * 语义（按用户要求）：
 *  · 可选开启；
 *  · 每次应用离开前台（onStop）即上锁，回到前台必须重新输入一次 PIN；
 *  · 锁屏渲染在唯一的 MainActivity 内部（Compose 顶层覆盖），不产生可被外部拉起的「PIN 页面」；
 *  · 只存 PBKDF2 盐+哈希，不存明文；连续输错有递增冷却。
 */
object AppLock {

    private const val MAX_ATTEMPTS_BEFORE_DELAY = 3
    private var failedAttempts = 0
    private var blockedUntil = 0L

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    /** 应用启动/回前台时调用：启用 PIN 则上锁 */
    fun lockIfEnabled() {
        _locked.value = Prefs.pinEnabled.value
    }

    fun onBackground() {
        if (Prefs.pinEnabled.value) _locked.value = true
    }

    val cooldownRemainingMs: Long
        get() = (blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 返回 true = 解锁成功 */
    fun unlock(pin: String): Boolean {
        if (!Prefs.pinEnabled.value) {
            _locked.value = false
            return true
        }
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return false

        val salt = Prefs.pinSalt()
        val hash = Prefs.pinHash()
        val ok = salt != null && hash != null && PinHasher.verify(pin, salt, hash)
        if (ok) {
            failedAttempts = 0
            blockedUntil = 0L
            _locked.value = false
            AppLog.event(RiskLevel.L1, "应用 PIN", "已解锁")
        } else {
            failedAttempts += 1
            if (failedAttempts >= MAX_ATTEMPTS_BEFORE_DELAY) {
                val delayMs = 5_000L * (failedAttempts - MAX_ATTEMPTS_BEFORE_DELAY + 1)
                blockedUntil = now + delayMs.coerceAtMost(60_000L)
            }
            AppLog.event(RiskLevel.L2, "应用 PIN", "输入错误（第 $failedAttempts 次）")
        }
        return ok
    }

    fun setPin(pin: String): Boolean {
        if (pin.length !in 4..8 || pin.any { !it.isDigit() }) return false
        val salt = PinHasher.randomSalt()
        val hash = PinHasher.hash(pin, salt)
        Prefs.setPin(
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            hash = Base64.encodeToString(hash, Base64.NO_WRAP),
        )
        Prefs.setPinEnabled(true)
        failedAttempts = 0
        blockedUntil = 0L
        _locked.value = false
        AppLog.event(RiskLevel.L2, "应用 PIN", "已启用")
        return true
    }

    fun clearPin() {
        Prefs.clearPin()
        Prefs.setPinEnabled(false)
        _locked.value = false
        failedAttempts = 0
        blockedUntil = 0L
        AppLog.event(RiskLevel.L2, "应用 PIN", "已关闭")
    }
}
