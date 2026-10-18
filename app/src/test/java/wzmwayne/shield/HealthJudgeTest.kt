package wzmwayne.shield

import org.junit.Assert.assertEquals
import org.junit.Test
import wzmwayne.shield.core.logic.HealthJudge
import wzmwayne.shield.core.logic.ServiceHealth

class HealthJudgeTest {

    private val now = 1_000_000L
    private val stale = 180_000L
    private val grace = 60_000L

    private fun judge(
        listed: Boolean,
        bySystem: Boolean,
        heartbeat: Long = now,
        enabledAt: Long = now - 10 * grace,
        crashed: Boolean = false,
    ) = HealthJudge.judge(
        listedInSettings = listed,
        listedBySystem = bySystem,
        lastHeartbeat = heartbeat,
        lastEnabledAt = enabledAt,
        now = now,
        staleMs = stale,
        graceMs = grace,
        crashed = crashed,
    )

    @Test
    fun `设置与系统一致且心跳新鲜时正常`() {
        assertEquals(ServiceHealth.OK, judge(listed = true, bySystem = true))
    }

    @Test
    fun `心跳过期判为假死`() {
        assertEquals(ServiceHealth.HUNG, judge(listed = true, bySystem = true, heartbeat = now - stale - 1))
    }

    @Test
    fun `刚启用时处于宽限期不判假死`() {
        assertEquals(
            ServiceHealth.OK,
            judge(listed = true, bySystem = false, heartbeat = 0, enabledAt = now - grace / 2),
        )
    }

    @Test
    fun `被移出设置串而系统仍认为启用时判为被移除`() {
        assertEquals(ServiceHealth.REMOVED, judge(listed = false, bySystem = true))
    }

    @Test
    fun `用户手动关闭时不强行开启`() {
        assertEquals(ServiceHealth.NOT_ENABLED, judge(listed = false, bySystem = false))
    }

    @Test
    fun `dumpsys 报告崩溃时优先级最高`() {
        assertEquals(ServiceHealth.CRASHED, judge(listed = true, bySystem = true, crashed = true))
        assertEquals(ServiceHealth.CRASHED, judge(listed = false, bySystem = false, crashed = true))
    }
}
