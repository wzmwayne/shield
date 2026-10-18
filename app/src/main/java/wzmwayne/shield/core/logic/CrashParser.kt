package wzmwayne.shield.core.logic

/**
 * 解析 `dumpsys accessibility` 中的崩溃行，格式形如：
 *   Crashed services: {pkg/pkg.Svc}
 *   Crashed services: {pkg/pkg.Svc, pkg2/pkg2.Svc2}
 *   Crashed services: (None)
 */
object CrashParser {
    private val CRASH_LINE = Regex("Crashed services:.*")
    private val BRACES = Regex("\\{([^\\{\\}]+)\\}")

    fun parseCrashedServices(dumpsysOutput: String): List<String> {
        val line = CRASH_LINE.find(dumpsysOutput)?.value ?: return emptyList()
        val result = mutableListOf<String>()
        for (group in BRACES.findAll(line)) {
            group.groupValues[1].split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "(None)" }
                .forEach { result.add(it) }
        }
        return result
    }

    fun isCrashed(dumpsysOutput: String, component: String): Boolean {
        val target = AccessibilityList.flatten(component)
        return parseCrashedServices(dumpsysOutput).any { AccessibilityList.flatten(it) == target }
    }
}
