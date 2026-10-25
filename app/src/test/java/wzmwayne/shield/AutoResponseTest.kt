package wzmwayne.shield

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wzmwayne.shield.response.HoldGestureDetector
import wzmwayne.shield.response.Prompt
import wzmwayne.shield.response.OverlaySentinel
import wzmwayne.shield.response.SensitiveGuard

class HoldGestureDetectorTest {

    private val detector = HoldGestureDetector(rounds = 5, windowMs = 5_000L)

    private fun press(keyCode: Int, now: Long, state: HoldGestureDetector.State) =
        detector.onPress(keyCode, now, state)

    /** 依次喂入 下 上 下 上 … */
    private fun feed(count: Int, startAt: Long = 1_000L, gap: Long = 300L): Pair<Boolean, HoldGestureDetector.State> {
        var state = HoldGestureDetector.State.EMPTY
        var triggered = false
        repeat(count) { i ->
            val key = if (i % 2 == 0) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
            val r = press(key, startAt + i * gap, state)
            state = r.state
            if (r.triggerGlobal) triggered = true
        }
        return triggered to state
    }

    @Test
    fun `5 秒内上下交替 5 轮触发`() {
        val (triggered, state) = feed(count = 10)
        assertTrue("下上下上×5 应触发", triggered)
        assertEquals("触发后清零", 0, state.steps)
    }

    @Test
    fun `只做 4 轮不触发`() {
        val (triggered, state) = feed(count = 8)
        assertFalse(triggered)
        assertEquals(8, state.steps)
    }

    @Test
    fun `正常调节音量不误触`() {
        var state = HoldGestureDetector.State.EMPTY
        var triggered = false
        // 连续按 3 次音量下
        (0 until 3).forEach { i ->
            val r = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_000L + i * 200L, state)
            state = r.state; triggered = triggered || r.triggerGlobal
        }
        // 再连按 5 次音量上
        (0 until 5).forEach { i ->
            val r = press(KeyEvent.KEYCODE_VOLUME_UP, 2_000L + i * 200L, state)
            state = r.state; triggered = triggered || r.triggerGlobal
        }
        assertFalse("普通调音量不应触发", triggered)
    }

    @Test
    fun `超过 5 秒窗口重新开始`() {
        var state = HoldGestureDetector.State.EMPTY
        // 前 6 步按时序完成
        (0 until 6).forEach { i ->
            val key = if (i % 2 == 0) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
            state = press(key, 1_000L + i * 300L, state).state
        }
        assertEquals(6, state.steps)
        // 第 7 步拖到窗口外（1_000 + 6_000）
        val late = press(KeyEvent.KEYCODE_VOLUME_DOWN, 8_500L, state)
        assertFalse(late.triggerGlobal)
        assertEquals("超时后从第 1 步重来", 1, late.state.steps)
    }

    @Test
    fun `方向不对不计数`() {
        // 先按"上"不能作为起点
        val first = press(KeyEvent.KEYCODE_VOLUME_UP, 1_000L, HoldGestureDetector.State.EMPTY)
        assertEquals(0, first.state.steps)
        // 正确起步后再按"下"（应为"上"）→ 重置为 1
        var state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_200L, HoldGestureDetector.State.EMPTY).state
        assertEquals(1, state.steps)
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_400L, state).state
        assertEquals("方向不对要从头开始", 1, state.steps)
    }

    @Test
    fun `非音量键忽略`() {
        val r = press(KeyEvent.KEYCODE_POWER, 1_000L, HoldGestureDetector.State.EMPTY)
        assertEquals(0, r.state.steps)
        assertFalse(r.triggerGlobal)
    }

    @Test
    fun `完成第一轮给提示音`() {
        var state = HoldGestureDetector.State.EMPTY
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_000L, state).state
        val r = press(KeyEvent.KEYCODE_VOLUME_UP, 1_300L, state)
        assertEquals(Prompt.READY, r.prompt)
    }
}

class OverlaySentinelTest {

    @Test
    fun `覆盖率按可见区域计算`() {
        // 全屏
        assertEquals(1f, OverlaySentinel.coverageRatio(0, 0, 1000, 2000, 1000, 2000), 0.001f)
        // 恰好 70%
        assertEquals(0.7f, OverlaySentinel.coverageRatio(0, 0, 1000, 1400, 1000, 2000), 0.001f)
        // 超出屏幕部分被裁剪
        assertEquals(1f, OverlaySentinel.coverageRatio(-500, -500, 5000, 5000, 1000, 2000), 0.001f)
        // 零尺寸防御
        assertEquals(0f, OverlaySentinel.coverageRatio(100, 100, 100, 100, 1000, 2000), 0.001f)
        // 屏幕尺寸非法
        assertEquals(0f, OverlaySentinel.coverageRatio(0, 0, 100, 100, 0, 0), 0.001f)
    }

    @Test
    fun `阈值是 70 百分比`() {
        assertEquals(0.70f, OverlaySentinel.COVERAGE_THRESHOLD, 0.0001f)
    }
}

class SensitiveGuardTest {

    @Test
    fun `本应用与卸载页都算敏感页`() {
        assertTrue(SensitiveGuard.isSensitive("wzmwayne.shield", "wzmwayne.shield.MainActivity"))
        assertTrue(SensitiveGuard.isSensitive("wzmwayne.shield", null))
        assertTrue(SensitiveGuard.isSensitive("com.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity"))
        assertTrue(SensitiveGuard.isSensitive("com.android.settings", "com.android.settings.applications.InstalledAppDetails"))
    }

    @Test
    fun `系统设置的任意页面都算敏感页`() {
        assertTrue(SensitiveGuard.isSensitive("com.android.settings", "com.android.settings.Settings"))
        assertTrue(SensitiveGuard.isSensitive("com.android.settings", "com.android.settings.wifi.WifiSettings"))
        assertTrue(SensitiveGuard.isSensitive("com.android.settings", null))
    }

    @Test
    fun `本应用所有页面都算敏感页`() {
        assertTrue(SensitiveGuard.isSensitive("wzmwayne.shield", "wzmwayne.shield.MainActivity"))
        assertTrue(SensitiveGuard.isSensitive("wzmwayne.shield", null))
    }

    @Test
    fun `普通第三方页面不误判`() {
        assertFalse(SensitiveGuard.isSensitive("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        assertFalse(SensitiveGuard.isSensitive("com.android.launcher3", "com.android.launcher3.Launcher"))
        assertFalse(SensitiveGuard.isSensitive(null, null))
    }
}

class PinHasherTest {

    @Test
    fun `相同 PIN 与盐得到相同哈希`() {
        val salt = ByteArray(16) { 7 }
        val a = wzmwayne.shield.core.PinHasher.hash("1234", salt)
        val b = wzmwayne.shield.core.PinHasher.hash("1234", salt)
        assertArrayEquals(a, b)
    }

    @Test
    fun `不同 PIN 哈希不同`() {
        val salt = ByteArray(16) { 3 }
        val a = wzmwayne.shield.core.PinHasher.hash("1234", salt)
        val b = wzmwayne.shield.core.PinHasher.hash("1235", salt)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `校验正确与错误 PIN`() {
        val salt = wzmwayne.shield.core.PinHasher.randomSalt()
        val hash = wzmwayne.shield.core.PinHasher.hash("8642", salt)
        assertTrue(wzmwayne.shield.core.PinHasher.verify("8642", salt, hash))
        assertFalse(wzmwayne.shield.core.PinHasher.verify("8643", salt, hash))
    }

    @Test
    fun `盐长度不同导致校验失败`() {
        val salt = wzmwayne.shield.core.PinHasher.randomSalt()
        val hash = wzmwayne.shield.core.PinHasher.hash("1111", salt)
        assertFalse(wzmwayne.shield.core.PinHasher.verify("1111", salt, hash.copyOf(hash.size - 1)))
    }
}

class AppRiskEvaluatorTest {

    private fun flags(
        trusted: Boolean = false,
        admin: Boolean = false,
        accDeclared: Boolean = false,
        accEnabled: Boolean = false,
        overlay: Boolean = false,
        system: Boolean = false,
        hidden: Boolean = false,
    ) = wzmwayne.shield.core.AppRiskEvaluator.Flags(
        packageName = "com.demo",
        label = "Demo",
        isSystem = system,
        trusted = trusted,
        deviceAdmin = admin,
        accessibilityServiceDeclared = accDeclared,
        accessibilityEnabled = accEnabled,
        overlayGranted = overlay,
        installPackagesGranted = false,
        hidden = hidden,
        suspended = false,
    )

    @Test
    fun `非白名单高权限得分更高`() {
        val plain = wzmwayne.shield.core.AppRiskEvaluator.evaluate(flags())
        val admin = wzmwayne.shield.core.AppRiskEvaluator.evaluate(flags(admin = true))
        assertTrue(admin.value > plain.value)
        assertTrue(admin.tags.contains("设备管理器"))
    }

    @Test
    fun `白名单与系统应用降分`() {
        val trusted = wzmwayne.shield.core.AppRiskEvaluator.evaluate(flags(trusted = true))
        val plain = wzmwayne.shield.core.AppRiskEvaluator.evaluate(flags())
        val system = wzmwayne.shield.core.AppRiskEvaluator.evaluate(flags(system = true))
        assertTrue(trusted.value < plain.value)
        assertTrue(system.value < plain.value)
        assertTrue(trusted.tags.contains("白名单"))
    }

    @Test
    fun `启用无障碍比仅声明更高`() {
        val declared = wzmwayne.shield.core.AppRiskEvaluator.evaluate(flags(accDeclared = true))
        val enabled = wzmwayne.shield.core.AppRiskEvaluator.evaluate(flags(accDeclared = true, accEnabled = true))
        assertTrue(enabled.value > declared.value)
        assertTrue(enabled.tags.contains("无障碍(启用)"))
    }
}
