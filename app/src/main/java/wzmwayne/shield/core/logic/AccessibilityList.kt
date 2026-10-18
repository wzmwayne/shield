package wzmwayne.shield.core.logic

/**
 * enabled_accessibility_services 的纯字符串处理。
 * 系统写入格式为 "pkg/cls:pkg/cls"，其中 cls 可能是 ".Service" 简写。
 */
object AccessibilityList {
    private const val SEPARATOR = ':'

    fun parse(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** 把 pkg/.Svc 与 pkg/Svc 统一成 pkg/pkg.Svc，便于比较 */
    fun flatten(entry: String): String {
        val index = entry.indexOf('/')
        if (index <= 0) return entry.trim()
        val pkg = entry.substring(0, index).trim()
        val cls = entry.substring(index + 1).trim()
        return when {
            cls.isEmpty() -> entry.trim()
            cls.startsWith(".") -> "$pkg/$pkg$cls"
            !cls.contains('.') -> "$pkg/$pkg.$cls"
            else -> entry.trim()
        }
    }

    fun contains(raw: String?, component: String): Boolean {
        val target = flatten(component)
        return parse(raw).any { flatten(it) == target }
    }

    /** 返回补齐自身后的设置值；无需变更时返回 null（调用方据此避免写入，防止触发观察者回环） */
    fun ensure(raw: String?, component: String): String? {
        if (contains(raw, component)) return null
        return (parse(raw) + component).joinToString(SEPARATOR.toString())
    }

    fun remove(raw: String?, component: String): String {
        val target = flatten(component)
        return parse(raw).filterNot { flatten(it) == target }.joinToString(SEPARATOR.toString())
    }
}
