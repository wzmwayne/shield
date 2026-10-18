package wzmwayne.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wzmwayne.shield.core.logic.CleanupPolicy
import wzmwayne.shield.core.logic.EventRules
import wzmwayne.shield.core.logic.ShellParsers

class LogicParsersTest {

    @Test
    fun `解析 pm list users`() {
        val output = """
            Users:
            	UserInfo{0:机主:13} running
            	UserInfo{10:工作资料:10}
        """.trimIndent()
        val users = ShellParsers.parseUsers(output)
        assertEquals(2, users.size)
        assertEquals(0, users[0].id)
        assertTrue(users[0].running)
        assertEquals("工作资料", users[1].name)
    }

    @Test
    fun `解析账户与计数`() {
        val output = """
            Accounts: 1
              Account {name=someone@gmail.com, type=com.google}
        """.trimIndent()
        assertEquals(1, ShellParsers.accountCount(output))
        assertEquals("com.google", ShellParsers.parseAccounts(output).first().type)

        assertEquals(0, ShellParsers.accountCount("Accounts: 0"))
    }

    @Test
    fun `解析设备所有者`() {
        val withOwner = """
            Current Device Policy Manager state:
              Enabled Device Admins (User 0, provisioningState: 0):
              Device Owner:
                admin=ComponentInfo{wzmwayne.shield/wzmwayne.shield.admin.ShieldDeviceAdminReceiver}
                name=
                package=wzmwayne.shield
                User ID: 0
        """.trimIndent()
        assertEquals("wzmwayne.shield", ShellParsers.deviceOwnerPackage(withOwner))

        val noOwner = """
            Current Device Policy Manager state:
              Device Owner: null
              Enabled Device Admins (User 0, provisioningState: 0):
        """.trimIndent()
        assertNull(ShellParsers.parseDeviceOwner(noOwner))
        assertNull(ShellParsers.parseDeviceOwner("no owner here"))
    }

    @Test
    fun `频率规则`() {
        val now = 100_000L
        val burst = (1..11).map { now - it * 100L }
        assertTrue(EventRules.isClickBurst(burst, now))
        assertTrue(!EventRules.isClickBurst(burst.take(5), now))
        // 窗口外的事件不计入
        assertTrue(!EventRules.isClickBurst(burst.map { it - 10_000L }, now))
    }

    @Test
    fun `清理类操作被硬性拒绝`() {
        assertTrue(CleanupPolicy.isCleanupOperation("pm uninstall --user 0 com.evil.app"))
        assertTrue(CleanupPolicy.isCleanupOperation("pm clear com.evil.app"))
        assertTrue(CleanupPolicy.isCleanupOperation("cmd package uninstall com.evil.app"))
        assertTrue(!CleanupPolicy.isCleanupOperation("pm disable-user com.evil.app"))
        assertTrue(!CleanupPolicy.isCleanupOperation("am force-stop com.evil.app"))
        assertTrue(!CleanupPolicy.isCleanupOperation("settings put secure enabled_accessibility_services x/y"))
    }
}
