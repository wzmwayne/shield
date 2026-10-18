package wzmwayne.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wzmwayne.shield.core.logic.AccessibilityList

class AccessibilityListTest {

    private val self = "wzmwayne.shield/wzmwayne.shield.accessibility.ShieldAccessibilityService"

    @Test
    fun `解析冒号分隔的设置串`() {
        assertEquals(
            listOf("a/a.S", "b/b.S"),
            AccessibilityList.parse("a/a.S:b/b.S"),
        )
        assertTrue(AccessibilityList.parse(null).isEmpty())
        assertTrue(AccessibilityList.parse("").isEmpty())
        assertTrue(AccessibilityList.parse(":").isEmpty())
    }

    @Test
    fun `展开简写形式的组件名`() {
        assertEquals("pkg/pkg.Svc", AccessibilityList.flatten("pkg/.Svc"))
        assertEquals("pkg/pkg.Svc", AccessibilityList.flatten("pkg/pkg.Svc"))
        assertEquals("pkg/pkg.Svc", AccessibilityList.flatten("pkg/Svc"))
    }

    @Test
    fun `自身被移除时补回并保留其他服务`() {
        val current = "other/other.S"
        val updated = AccessibilityList.ensure(current, self)
        assertEquals("other/other.S:$self", updated)
    }

    @Test
    fun `自身已存在时不产生变更`() {
        assertNull(AccessibilityList.ensure(self, self))
        assertNull(AccessibilityList.ensure("a/a.S:$self", self))
        // 简写形式同样视为已存在
        assertNull(AccessibilityList.ensure("wzmwayne.shield/.accessibility.ShieldAccessibilityService", self))
    }

    @Test
    fun `移除自身不影响其他服务`() {
        val raw = "a/a.S:$self:b/b.S"
        assertEquals("a/a.S:b/b.S", AccessibilityList.remove(raw, self))
        assertEquals("", AccessibilityList.remove(self, self))
    }

    @Test
    fun `包含判断对简写与全写都成立`() {
        assertTrue(AccessibilityList.contains("wzmwayne.shield/.accessibility.ShieldAccessibilityService", self))
        assertFalse(AccessibilityList.contains("a/a.S", self))
    }
}
