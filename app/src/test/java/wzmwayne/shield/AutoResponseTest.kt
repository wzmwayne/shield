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

    private val detector = HoldGestureDetector(pressCount = 3, intervalMs = 700L)

    private fun press(keyCode: Int, now: Long, state: HoldGestureDetector.State) =
        detector.onPress(keyCode, now, state)

    @Test
    fun `连按音量下 3 次触发`() {
        var state = HoldGestureDetector.State.EMPTY
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_000L, state).state
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_300L, state).state
        val result = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_600L, state)
        assertTrue("第三次应立即触发", result.triggerGlobal)
        assertEquals(Prompt.TRIGGERED, result.prompt)
        assertEquals("触发后计数应清零", 0, result.state.count)
    }

    @Test
    fun `连按音量上 3 次同样触发`() {
        var state = HoldGestureDetector.State.EMPTY
        state = press(KeyEvent.KEYCODE_VOLUME_UP, 1_000L, state).state
        state = press(KeyEvent.KEYCODE_VOLUME_UP, 1_200L, state).state
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_UP, 1_400L, state).triggerGlobal)
    }

    @Test
    fun `间隔过长重新计数`() {
        var state = HoldGestureDetector.State.EMPTY
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_000L, state).state
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_300L, state).state
        val late = press(KeyEvent.KEYCODE_VOLUME_DOWN, 3_000L, state)
        assertFalse(late.triggerGlobal)
        assertEquals("超时后从 1 重新计数", 1, late.state.count)
    }

    @Test
    fun `换键重新计数`() {
        var state = HoldGestureDetector.State.EMPTY
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_000L, state).state
        state = press(KeyEvent.KEYCODE_VOLUME_DOWN, 1_200L, state).state
        val switched = press(KeyEvent.KEYCODE_VOLUME_UP, 1_400L, state)
        assertFalse(switched.triggerGlobal)
        assertEquals(1, switched.state.count)
    }

    @Test
    fun `非音量键忽略`() {
        val r = press(KeyEvent.KEYCODE_POWER, 1_000L, HoldGestureDetector.State.EMPTY)
        assertEquals(0, r.state.count)
        assertFalse(r.triggerGlobal)
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
