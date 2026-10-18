package wzmwayne.shield.activation

import android.content.Context
import android.os.Build
import wzmwayne.shield.admin.ShieldDeviceAdminReceiver
import java.io.File

/**
 * Shizuku 通道不可用时的兜底路径：把激活需要的命令直接交给用户，用 adb 手动执行。
 *
 * 为什么需要它：部分环境（如 VMOS 虚拟机）的 Shizuku 代理不支持用户服务与 newProcess 事务，
 * 应用自己无法完成激活；此时只要用户能连 adb，整条激活链路依然可以走通。
 */
object AdbCommands {

    fun permissionCommands(context: Context): List<String> = listOf(
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
        "adb shell pm grant ${context.packageName} android.permission.DUMP",
    )

    fun ownerCommand(context: Context): String =
        "adb shell dpm set-device-owner ${context.packageName}/${ShieldDeviceAdminReceiver::class.java.name}"

    fun all(context: Context): List<String> = permissionCommands(context) + ownerCommand(context)

    /** 打包在 APK（jniLibs）里、安装时被系统释放到 nativeLibraryDir 的激活脚本名 */
    const val SCRIPT_NAME = "libshield_activate.so"

    /**
     * 激活脚本在设备上的绝对路径。
     * 正常情况下就是 nativeLibraryDir 下的那个"假 .so"；少数机型该目录为空时按 APK 目录反推。
     */
    fun scriptPath(context: Context): String {
        val fromNativeDir = context.applicationInfo.nativeLibraryDir
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(it, SCRIPT_NAME) }
        if (fromNativeDir != null && fromNativeDir.exists()) return fromNativeDir.absolutePath

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val apkDir = File(context.applicationInfo.sourceDir).parentFile
        return File(apkDir, "lib/$abi/$SCRIPT_NAME").absolutePath
    }

    fun scriptExists(context: Context): Boolean = runCatching { File(scriptPath(context)).exists() }.getOrDefault(false)

    /** 用户要执行的完整 adb 命令 */
    fun scriptCommand(context: Context): String = "adb shell sh ${scriptPath(context)}"

    /** 一行摘要，便于放进 trace / 事件日志 */
    fun summary(context: Context): String = all(context).joinToString(" && ")
}
