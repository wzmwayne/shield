package wzmwayne.shield

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Android 的 regex 实现比桌面 JVM 严格：**未转义的 `}`** 会抛
 * `PatternSyntaxException: Syntax error in regexp pattern`（在真机上表现为类静态初始化崩溃，
 * 桌面 JVM 单测却完全放行——真实踩过一次）。
 *
 * 这个测试扫描源码里所有 `Regex("""…""")` / `Regex("…")` 字面量，要求：
 *  1. 字符类之外的未转义 `{` 与 `}` 必须成对（不成对的一侧必须转义）；
 *  2. 能用桌面 JVM 的 Pattern 编译通过（兜底其它语法错误）。
 */
class RegexLiteralAuditTest {

    @Test
    fun `源码中的正则字面量必须符合 Android regex 规则`() {
        val root = File("src/main/java")
        assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)
        val violations = mutableListOf<String>()
        var checked = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                extractPatterns(line).forEach { pattern ->
                    checked++
                    val problem = inspect(pattern)
                    if (problem != null) {
                        violations += "${file.name}:${index + 1} $problem → $pattern"
                    }
                }
            }
        }
        assertTrue("没有扫描到任何正则字面量，测试本身可能失效了", checked > 0)
        assertTrue(
            "发现不符合 Android regex 规则的正则（未转义花括号在 Android 9 上会崩溃）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `审计器本身能识别出未转义的右花括号`() {
        assertTrue(inspect("""UserInfo\{(\d+):([^}]*)}""") != null)
        assertTrue(inspect("""UserInfo\{(\d+):([^\}]*)\}""") == null)
        // 量词是合法且成对的
        assertTrue(inspect("""a{1,3}b""") == null)
    }

    private fun inspect(pattern: String): String? {
        var inClass = false
        var open = 0
        var close = 0
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                i += 2
                continue
            }
            when (c) {
                '[' -> inClass = true
                ']' -> inClass = false
                '{' -> if (!inClass) open++
                '}' -> if (!inClass) close++
            }
            i++
        }
        if (open != close) {
            return "字符类之外的未转义花括号不成对（{ 出现 $open 次，} 出现 $close 次）"
        }
        return runCatching { Pattern.compile(pattern) }
            .exceptionOrNull()
            ?.let { "编译失败：${it.message}" }
    }

    private fun extractPatterns(line: String): List<String> {
        val patterns = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = line.indexOf("Regex(", cursor)
            if (start < 0) return patterns
            val argStart = start + "Regex(".length
            when {
                line.startsWith("\"\"\"", argStart) -> {
                    val end = line.indexOf("\"\"\"", argStart + 3)
                    if (end < 0) return patterns
                    patterns += line.substring(argStart + 3, end)
                    cursor = end + 3
                }

                line.startsWith("\"", argStart) -> {
                    val end = findClosingQuote(line, argStart + 1)
                    if (end < 0) return patterns
                    patterns += unescape(line.substring(argStart + 1, end))
                    cursor = end + 1
                }

                else -> cursor = argStart
            }
        }
    }

    private fun findClosingQuote(line: String, from: Int): Int {
        var i = from
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    /** 普通字符串字面量里的 `\\` 在最终正则里是单个反斜杠 */
    private fun unescape(raw: String): String = raw.replace("\\\\", "\\")
}
