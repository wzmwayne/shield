package wzmwayne.shield.core

/** 激活状态机的三个稳定态；告警/阻断/恢复由后续威胁引擎驱动，本期不实现。 */
enum class ActivationState {
    NOT_ACTIVATED,
    ACTIVATING,
    PROTECTING,
}

/**
 * 设备所有者身份归属方式。
 * 委派（Dhizuku）模式已被明确禁用：锁盾必须自己持有设备所有者身份，否则自我保护能力不完整。
 */
enum class OwnerMode {
    NONE,

    /** 锁盾自身持有设备所有者身份（唯一允许的模式） */
    DIRECT,
}

/** 向导/自检条目的结论。 */
enum class CheckState {
    PASS,
    WARN,
    BLOCK,
    UNKNOWN,
}

/** 风险等级，供事件日志与后续威胁引擎共用。 */
enum class RiskLevel(val label: String) {
    L1("观察"),
    L2("告警"),
    L3("切断"),
    L4("恢复"),
}

data class CheckItem(
    val id: String,
    val title: String,
    val state: CheckState,
    val detail: String,
    /** 非空表示该条目可点击执行修复，值为 RepairActions 中的动作 id */
    val action: String? = null,
    val actionLabel: String = "修复",
)

/** 一条事件日志记录。纯字符串编解码，便于单元测试。 */
data class ShieldEvent(
    val timestamp: Long,
    val level: RiskLevel,
    val title: String,
    val detail: String,
) {
    fun encode(): String = listOf(
        timestamp.toString(),
        level.name,
        title.replace('\n', ' ').replace(UNIT_SEPARATOR, ' '),
        detail.replace('\n', ' ').replace(UNIT_SEPARATOR, ' '),
    ).joinToString(UNIT_SEPARATOR.toString())

    companion object {
        const val UNIT_SEPARATOR = '\u001F'
        private const val MAX_DETAIL = 400

        fun decode(line: String): ShieldEvent? {
            val parts = line.split(UNIT_SEPARATOR)
            if (parts.size < 4) return null
            val ts = parts[0].toLongOrNull() ?: return null
            val level = RiskLevel.entries.firstOrNull { it.name == parts[1] } ?: return null
            return ShieldEvent(ts, level, parts[2], parts[3].take(MAX_DETAIL))
        }
    }
}
