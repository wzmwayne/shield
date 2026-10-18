package wzmwayne.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wzmwayne.shield.protection.AccessibilityWhitelist

class AccessibilityWhitelistTest {

    private val self = AccessibilityWhitelist.SELF_PACKAGE

    @Test
    fun `自身组件被硬编码且与包名一致`() {
        assertEquals("wzmwayne.shield", AccessibilityWhitelist.SELF_PACKAGE)
        assertEquals(
            "wzmwayne.shield/wzmwayne.shield.accessibility.ShieldAccessibilityService",
            AccessibilityWhitelist.SELF_COMPONENT,
        )
    }

    @Test
    fun `构建白名单时自身包名永远在首位`() {
        val built = AccessibilityWhitelist.build(listOf("com.a", "com.b"))
        assertEquals(self, built.first())
        assertEquals(3, built.size)
    }

    @Test
    fun `构建时过滤非法项并去重`() {
        val built = AccessibilityWhitelist.build(
            listOf(null, "", "   ", "com.a", "com.a", self, "  com.b  "),
        )
        assertEquals(listOf(self, "com.a", "com.b"), built)
    }

    @Test
    fun `未设限制不算自锁`() {
        assertFalse(AccessibilityWhitelist.isSelfLocked(null))
    }

    @Test
    fun `白名单含自身包名时不算自锁`() {
        assertFalse(AccessibilityWhitelist.isSelfLocked(listOf("com.a", self)))
        assertFalse(AccessibilityWhitelist.isSelfLocked(listOf(self)))
    }

    @Test
    fun `白名单缺少自身时判定为自锁`() {
        assertTrue(AccessibilityWhitelist.isSelfLocked(listOf("com.a", "com.b")))
        assertTrue(AccessibilityWhitelist.isSelfLocked(emptyList()))
        // 组件名不是合法参数（AOSP 按包名匹配），因此不能算作包含自身
        assertTrue(AccessibilityWhitelist.isSelfLocked(listOf(AccessibilityWhitelist.SELF_COMPONENT)))
    }

    @Test
    fun `从组件名提取包名`() {
        assertEquals("com.a", AccessibilityWhitelist.packageOf("com.a/com.a.Service"))
        assertEquals("com.a", AccessibilityWhitelist.packageOf("com.a/.Service"))
        assertEquals("com.a", AccessibilityWhitelist.packageOf("com.a"))
        assertEquals("com.a", AccessibilityWhitelist.packageOf("  com.a  "))
    }

    @Test
    fun `描述文本能反映数量与自锁状态`() {
        assertEquals("未设置限制（全部允许）", AccessibilityWhitelist.describe(null))
        assertTrue(AccessibilityWhitelist.describe(listOf(self)).contains("含自身：是"))
        assertTrue(AccessibilityWhitelist.describe(listOf("com.a")).contains("含自身：否"))
    }
}
