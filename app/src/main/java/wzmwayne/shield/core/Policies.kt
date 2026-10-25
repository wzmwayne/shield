package wzmwayne.shield.core

/**
 * 保护策略开关（默认全开，可自由关闭）。
 * 分两类：「感知」只看不听令（是否检测/告警），「执行」会真正改变设备状态。
 */
enum class PolicyGroup(val title: String) {
    SENSE("感知"),
    ACT("执行"),
    SELF("自保"),
}

enum class Policy(
    val id: String,
    val title: String,
    val description: String,
    val group: PolicyGroup,
) {
    // ── 感知 ──────────────────────────────────────────────
    SENSE_LAYER("sense_layer", "感知层总开关", "关闭后停止敏感页看护/覆盖层哨兵/默认桌面守护的全部检测与提示", PolicyGroup.SENSE),
    SENSITIVE_GUARD("sensitive_guard", "敏感页看护", "进入系统设置/卸载器/本应用页面时收紧无障碍（离开 8 秒恢复）", PolicyGroup.SENSE),
    OVERLAY_SENTINEL("overlay_sentinel", "覆盖层哨兵", "非白名单覆盖层占用 ≥70% 屏幕时对该应用针对性阻断", PolicyGroup.SENSE),
    HOME_SENTINEL("home_sentinel", "默认桌面守护", "默认桌面被换成非可信桌面时锁回并处置可疑桌面", PolicyGroup.SENSE),

    // ── 执行 ──────────────────────────────────────────────
    CLOSE_ESCALATION("close_escalation", "抗关闭联动", "1 分钟内被关闭 3 次 → 重启无障碍 + 全局阻断；阻断中被关闭 → 中止→禁调试→重跑", PolicyGroup.ACT),
    WHITELIST_LIMIT("whitelist_limit", "无障碍白名单限制", "用 setPermittedAccessibilityServices 限制可启用的无障碍服务；关闭则立即撤销且不再写入", PolicyGroup.ACT),
    LOCK_DEBUG("lock_debug", "锁定调试", "常驻关闭开发者选项与 ADB；关闭该策略即恢复调试", PolicyGroup.ACT),
    PANIC_DISABLE_DEBUG("panic_disable_debug", "阻断时禁止调试", "阻断流程里额外关闭开发者选项与 ADB", PolicyGroup.ACT),

    // ── 自保 ──────────────────────────────────────────────
    KEEPALIVE("keepalive", "无障碍保活", "自身被移出启用列表时用 WRITE_SECURE_SETTINGS 写回", PolicyGroup.SELF),
    FORCE_RESTART("force_restart", "进程级重启", "服务在列表内却持续不健康时强杀自身进程（15 分钟退避，6 小时 3 次上限）", PolicyGroup.SELF),
    UNINSTALL_BLOCK("uninstall_block", "卸载阻止", "用设备所有者 setUninstallBlocked 阻止自身被卸载", PolicyGroup.SELF),
    SELF_LOCK_WHITELIST("self_lock_whitelist", "白名单自锁防护", "下发无障碍白名单时永远包含锁盾自身", PolicyGroup.SELF),
}

object Policies {

    fun isOn(policy: Policy): Boolean = policy.id !in Prefs.disabledPolicies.value

    fun setOn(policy: Policy, enabled: Boolean) {
        val next = Prefs.disabledPolicies.value.toMutableSet()
        if (enabled) next -= policy.id else next += policy.id
        Prefs.setDisabledPolicies(next)
    }

    /** 感知层是否工作（总开关 + 具体策略都要开） */
    fun sensing(policy: Policy): Boolean = isOn(Policy.SENSE_LAYER) && isOn(policy)

    fun isSensingEnabled(): Boolean = isOn(Policy.SENSE_LAYER)

    /** 是否允许写「无障碍白名单」（关闭后任何路径都不再写，只做撤销） */
    fun whitelistEnabled(): Boolean = isOn(Policy.WHITELIST_LIMIT)

    fun all(): List<Pair<Policy, Boolean>> = Policy.entries.map { it to isOn(it) }

    fun grouped(): List<Triple<PolicyGroup, List<Pair<Policy, Boolean>>, Int>> =
        PolicyGroup.entries.map { group ->
            val items = Policy.entries.filter { it.group == group }.map { it to isOn(it) }
            Triple(group, items, items.count { it.second })
        }
}
