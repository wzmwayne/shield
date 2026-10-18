package wzmwayne.shield.core.logic

/**
 * 清理类操作的硬性禁令（纯逻辑，可单测）。
 * 锁盾的定位是阻断威胁，卸载与数据清除一律由用户自行决定。
 */
object CleanupPolicy {

    val FORBIDDEN_OPERATIONS: List<String> = listOf(
        "pm uninstall",
        "pm clear",
        "pm uninstall-system-updates",
        "packageinstaller uninstall",
        "wipeData",
        "clearApplicationUserData",
        "dpm remove-active-admin（针对锁盾自身）",
    )

    private val FORBIDDEN_PATTERNS = listOf(
        Regex("""\bpm\s+uninstall\b"""),
        Regex("""\bpm\s+clear\b"""),
        Regex("""\bcmd\s+package\s+uninstall\b"""),
        Regex("""\bcmd\s+package\s+clear\b"""),
        Regex("""\bpackage\s+uninstall\b"""),
        Regex("""\bwipeData\b"""),
        Regex("""\bclearApplicationUserData\b"""),
        Regex("""\buninstall-system-updates\b"""),
    )

    fun isCleanupOperation(operation: String): Boolean =
        FORBIDDEN_PATTERNS.any { it.containsMatchIn(operation) }
}
