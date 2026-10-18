package wzmwayne.shield.core.logic

enum class ServiceHealth {
    /** 设置与系统都认为已连接，且心跳新鲜 */
    OK,

    /** 从未启用（用户手动关闭），保活不强行开启，只提示 */
    NOT_ENABLED,

    /** 系统认为已启用，但设置串里已被抹掉：被 Shell/其他应用改过 */
    REMOVED,

    /** 设置里有、系统未连接，或心跳过期：假死 */
    HUNG,

    /** dumpsys 明确列为崩溃服务 */
    CRASHED,
}

/**
 * 只判断锁盾自身无障碍服务的健康度（与原项目「保活任意白名单应用」的关键差异）。
 */
object HealthJudge {
    fun judge(
        listedInSettings: Boolean,
        listedBySystem: Boolean,
        lastHeartbeat: Long,
        lastEnabledAt: Long,
        now: Long,
        staleMs: Long,
        graceMs: Long,
        crashed: Boolean,
    ): ServiceHealth {
        if (crashed) return ServiceHealth.CRASHED
        if (!listedInSettings && !listedBySystem) return ServiceHealth.NOT_ENABLED
        if (!listedInSettings && listedBySystem) return ServiceHealth.REMOVED
        if (listedInSettings && !listedBySystem) {
            // 刚写入设置时系统还在绑定，宽限期内不判假死
            return if (inGrace(lastEnabledAt, now, graceMs)) ServiceHealth.OK else ServiceHealth.HUNG
        }
        val fresh = lastHeartbeat > 0 && now - lastHeartbeat <= staleMs
        return if (fresh || inGrace(lastEnabledAt, now, graceMs)) ServiceHealth.OK else ServiceHealth.HUNG
    }

    private fun inGrace(lastEnabledAt: Long, now: Long, graceMs: Long): Boolean =
        lastEnabledAt > 0 && now - lastEnabledAt <= graceMs
}
