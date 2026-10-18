package wzmwayne.shield.core.logic

/** 从 shell 命令输出中提取结构化信息（全部为纯函数，便于单元测试）。 */
object ShellParsers {
    data class UserEntry(val id: Int, val name: String, val running: Boolean)

    data class AccountEntry(val name: String, val type: String)

    private val USER_LINE = Regex("""UserInfo\{(\d+):([^\}:]*):([^\}]*)\}""")
    private val ACCOUNT_BLOCK = Regex("""Account\s*\{([^\}]*)\}""")
    private val ACCOUNT_COUNT = Regex("""Accounts:\s*(\d+)""")
    private val DO_ADMIN = Regex("""admin=ComponentInfo\{([^\}]*)\}""")
    private val DO_PACKAGE = Regex("""package=([A-Za-z0-9_.]+)""")

    fun parseUsers(output: String): List<UserEntry> =
        output.lineSequence().mapNotNull { line ->
            val match = USER_LINE.find(line) ?: return@mapNotNull null
            UserEntry(
                id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null,
                name = match.groupValues[2].trim(),
                running = line.contains("running", ignoreCase = true),
            )
        }.toList()

    fun parseAccounts(output: String): List<AccountEntry> =
        ACCOUNT_BLOCK.findAll(output).mapNotNull { block ->
            val body = block.groupValues[1]
            val name = body.substringAfter("name=", "").substringBefore(',').trim()
            val type = body.substringAfter("type=", "").substringBefore(',').trim()
            if (name.isEmpty() && type.isEmpty()) null else AccountEntry(name, type)
        }.toList()

    /** 有些机型只输出计数，取两者较大值更稳妥 */
    fun accountCount(output: String): Int {
        val parsed = ACCOUNT_COUNT.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return maxOf(parsed, parseAccounts(output).size)
    }

    /** 解析 `dumpsys device_policy` 的 Device Owner 段落，找不到则返回 null */
    fun parseDeviceOwner(output: String): String? {
        val index = output.indexOf("Device Owner")
        if (index < 0) return null
        val section = output.substring(index, minOf(output.length, index + 400))
        val head = section.take(120)
        if (Regex("""(?i)\bnull\b|\(none\)""").containsMatchIn(head)) return null
        DO_ADMIN.find(section)?.let { return it.groupValues[1].trim() }
        DO_PACKAGE.find(section)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** 从 Device Owner 段落里取包名，便于比较锁盾自身或 Dhizuku */
    fun deviceOwnerPackage(output: String): String? {
        val owner = parseDeviceOwner(output) ?: return null
        if (owner.contains('/')) return owner.substringBefore('/').trim()
        return owner
    }
}
