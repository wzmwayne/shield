package wzmwayne.shield.protection

import wzmwayne.shield.core.logic.AccessibilityList

/**
 * 无障碍白名单构建（保护措施清单第 12、45 条）。
 *
 * 自身包名**硬编码**在此，不依赖运行时读取，避免把自己排除在白名单外造成自锁。
 *
 * 重要：`setPermittedAccessibilityServices` 的参数是**包名**列表（AOSP 侧按 packageName 匹配），
 * 传组件名会导致写入失败，或写入成功后按包名匹配不到而把包括自身在内的所有非系统服务全部禁掉。
 */
object AccessibilityWhitelist {

    const val SELF_PACKAGE = "wzmwayne.shield"

    const val SELF_SERVICE_CLASS = "wzmwayne.shield.accessibility.ShieldAccessibilityService"

    /** 自身无障碍服务的组件名（仅用于展示与日志，不作为白名单参数） */
    const val SELF_COMPONENT = "$SELF_PACKAGE/$SELF_SERVICE_CLASS"

    fun parse(raw: List<String>?): List<String>? = raw?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** 白名单为 null 表示"未设限制"（所有服务都被允许），不构成自锁 */
    fun isSelfLocked(whitelist: List<String>?): Boolean {
        val list = parse(whitelist) ?: return false
        return list.none { entry -> entry == SELF_PACKAGE }
    }

    /**
     * 构建提交给 setPermittedAccessibilityServices 的完整列表（全部为包名）：
     * 自身 + 当前已启用的服务所属包名 + 额外信任包名，去重且自身永远在首位。
     */
    fun build(
        enabledPackages: List<String?>,
        extraTrusted: Collection<String> = emptyList(),
    ): List<String> {
        val result = LinkedHashSet<String>()
        result.add(SELF_PACKAGE)
        enabledPackages.forEach { entry ->
            val value = entry?.trim().orEmpty()
            if (value.isNotEmpty() && value != SELF_PACKAGE) result.add(value)
        }
        extraTrusted.forEach { entry ->
            val value = entry.trim()
            if (value.isNotEmpty()) result.add(value)
        }
        return result.toList()
    }

    /** 从组件名或包名里取出包名，便于调用方容错 */
    fun packageOf(entry: String): String =
        if (entry.contains('/')) AccessibilityList.flatten(entry).substringBefore('/') else entry.trim()

    /** 面向 UI 的简述 */
    fun describe(whitelist: List<String>?): String {
        val list = parse(whitelist) ?: return "未设置限制（全部允许）"
        return "${list.size} 项，含自身：${if (isSelfLocked(list)) "否" else "是"}"
    }
}
