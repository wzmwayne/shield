package wzmwayne.shield.response

/**
 * 紧急处置的逐应用结果（纯数据 + 文本编解码，便于持久化、UI 展示与单测）。
 *
 * 行格式：pkg\u001Flabel\u001Fhidden\u001Fsuspended\u001F权限1,权限2\u001Ferror
 */
data class PanicAppEntry(
    val packageName: String,
    val label: String,
    val hidden: Boolean,
    val suspended: Boolean,
    val revokedPermissions: List<String> = emptyList(),
    val error: String? = null,
) {
    val restoredReady: Boolean get() = hidden || suspended || revokedPermissions.isNotEmpty()

    fun encode(): String = listOf(
        packageName,
        label.replace('\u001F', ' ').replace('\n', ' '),
        if (hidden) "1" else "0",
        if (suspended) "1" else "0",
        revokedPermissions.joinToString(","),
        error.orEmpty().replace('\u001F', ' ').replace('\n', ' '),
    ).joinToString(SEP.toString())

    companion object {
        const val SEP = '\u001F'

        fun decode(line: String): PanicAppEntry? {
            val parts = line.split(SEP.toString())
            if (parts.size < 5) return null
            return PanicAppEntry(
                packageName = parts[0],
                label = parts[1],
                hidden = parts[2] == "1",
                suspended = parts[3] == "1",
                revokedPermissions = parts[4].split(",").map { it.trim() }.filter { it.isNotEmpty() },
                error = parts.getOrNull(5)?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

object PanicReportCodec {

    fun encode(entries: List<PanicAppEntry>): String = entries.joinToString("\n") { it.encode() }

    fun decode(raw: String): List<PanicAppEntry> =
        raw.split('\n').mapNotNull { PanicAppEntry.decode(it) }

    fun summary(entries: List<PanicAppEntry>, skippedSystem: Int): String {
        val hidden = entries.count { it.hidden }
        val suspended = entries.count { it.suspended }
        val revoked = entries.sumOf { it.revokedPermissions.size }
        val failed = entries.count { !it.error.isNullOrEmpty() }
        return "处置 ${entries.size} 个应用：隐藏 $hidden、挂起 $suspended、撤销权限 $revoked 项、失败 $failed 个；跳过系统应用 $skippedSystem 个"
    }
}
