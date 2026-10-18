package wzmwayne.shield.keepalive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.logic.CrashParser
import java.util.concurrent.TimeUnit

/**
 * 崩溃（假死）检测：读取 `dumpsys accessibility` 的 Crashed services 行。
 * 需要激活阶段由 Shizuku 授予的 android.permission.DUMP。
 */
object CrashDetector {
    private const val TIMEOUT_MS = 10_000L

    fun hasDumpPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** null 表示无法判定（未授予 DUMP 或执行失败） */
    fun selfCrashed(context: Context, component: String): Boolean? {
        if (!hasDumpPermission(context)) return null
        val output = runDumpsys() ?: return null
        return CrashParser.isCrashed(output, component)
    }

    private fun runDumpsys(): String? = try {
        val process = ProcessBuilder("dumpsys", "accessibility").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) output else {
            process.destroyForcibly()
            AppLog.w("dumpsys accessibility 超时")
            null
        }
    } catch (t: Throwable) {
        AppLog.w("dumpsys accessibility 失败：${t.message}")
        null
    }
}
