package wzmwayne.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wzmwayne.shield.core.logic.CrashParser

class CrashParserTest {

    private val self = "wzmwayne.shield/wzmwayne.shield.accessibility.ShieldAccessibilityService"

    @Test
    fun `未找到崩溃行时返回空`() {
        assertTrue(CrashParser.parseCrashedServices("Bound services: {}").isEmpty())
        assertFalse(CrashParser.isCrashed("Crashed services: (None)", self))
    }

    @Test
    fun `单个崩溃服务`() {
        val output = "  mIsEnabled=true\n  Crashed services: {$self}\n  mIsTouchExplorationEnabled=false"
        assertEquals(listOf(self), CrashParser.parseCrashedServices(output))
        assertTrue(CrashParser.isCrashed(output, self))
    }

    @Test
    fun `多个崩溃服务与简写形式`() {
        val output = "Crashed services: {other/other.Other, wzmwayne.shield/.accessibility.ShieldAccessibilityService}"
        val parsed = CrashParser.parseCrashedServices(output)
        assertEquals(2, parsed.size)
        assertTrue(CrashParser.isCrashed(output, self))
        assertTrue(CrashParser.isCrashed(output, "other/other.Other"))
        assertFalse(CrashParser.isCrashed(output, "z/z.Z"))
    }
}
