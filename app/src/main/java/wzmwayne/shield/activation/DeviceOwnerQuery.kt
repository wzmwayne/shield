package wzmwayne.shield.activation

import android.content.Context
import wzmwayne.shield.core.logic.ShellParsers
import wzmwayne.shield.keepalive.CrashDetector
import wzmwayne.shield.shell.ShellClient
import java.util.concurrent.TimeUnit

/**
 * 查询当前设备所有者。
 * 优先用本机 DUMP 权限直接读，其次退回激活阶段的 Shizuku 通道。
 * 关键：必须区分"确认没有设备所有者"与"无法判定"，后者不能当作可激活。
 */
object DeviceOwnerQuery {

    data class Result(val known: Boolean, val packageName: String?)

    suspend fun query(context: Context): Result {
        if (CrashDetector.hasDumpPermission(context)) {
            val output = runDumpsysDevicePolicy()
            if (output != null && output.contains(DEVICE_OWNER_MARKER)) {
                return Result(known = true, packageName = ShellParsers.deviceOwnerPackage(output))
            }
        }
        if (ShellClient.pingBinder() && ShellClient.hasPermission()) {
            val result = ShellClient.exec("dumpsys device_policy")
            if (result.ok && result.out.contains(DEVICE_OWNER_MARKER)) {
                return Result(known = true, packageName = ShellParsers.deviceOwnerPackage(result.out))
            }
        }
        return Result(known = false, packageName = null)
    }

    fun queryLocal(context: Context): Result {
        if (!CrashDetector.hasDumpPermission(context)) return Result(known = false, packageName = null)
        val output = runDumpsysDevicePolicy() ?: return Result(known = false, packageName = null)
        if (!output.contains(DEVICE_OWNER_MARKER)) return Result(known = false, packageName = null)
        return Result(known = true, packageName = ShellParsers.deviceOwnerPackage(output))
    }

    private const val DEVICE_OWNER_MARKER = "Device Owner"

    /** 注意：本函数会阻塞，调用方必须已处于 IO 线程（query 的调用点都跑在 Dispatchers.IO） */
    private fun runDumpsysDevicePolicy(): String? = try {
        val process = ProcessBuilder("dumpsys", "device_policy").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor(10, TimeUnit.SECONDS)) output else {
            process.destroyForcibly()
            null
        }
    } catch (t: Throwable) {
        null
    }
}
