package wzmwayne.shield.debug

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RiskLevel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 调试接口的执行类能力：安装 APK、推送文件（仅应用沙箱）、读取日志 */
object DebugActions {

    /** 安装 APK（PackageInstaller 会话；设备所有者场景通常无需再点确认） */
    fun installApk(context: Context, bytes: ByteArray, name: String): String {
        if (bytes.isEmpty()) return "APK 内容为空"
        val installer = context.packageManager.packageInstaller
        val action = "wzmwayne.shield.DEBUG_INSTALL_RESULT"
        val latch = CountDownLatch(1)
        val status = AtomicInteger(Int.MIN_VALUE)
        val message = AtomicReference<String?>(null)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != action) return
                status.set(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE))
                message.set(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                latch.countDown()
            }
        }
        val registered = runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                receiver,
                android.content.IntentFilter(action),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrDefault(false)
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_AUTO)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, bytes.size.toLong()).use { out ->
                    out.write(bytes)
                    session.fsync(out)
                }
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(action).setPackage(context.packageName),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            val done = latch.await(60, TimeUnit.SECONDS)
            val code = status.get()
            val text = if (code == PackageInstaller.STATUS_SUCCESS) {
                "安装成功：$name"
            } else {
                "安装失败：STATUS=$code ${message.get().orEmpty()}"
            }
            AppLog.event(if (code == PackageInstaller.STATUS_SUCCESS) RiskLevel.L2 else RiskLevel.L3, "调试接口：安装 APK", text)
            return if (done) text else "安装结果未知（等待超时），请到系统查看"
        } catch (t: Throwable) {
            AppLog.w("调试安装失败：${t.message}")
            return "安装失败：${t.javaClass.simpleName}: ${t.message}"
        } finally {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** 推送文件到应用沙箱（files/debug_push/），返回绝对路径 */
    fun pushFile(context: Context, name: String, bytes: ByteArray): String {
        val safe = name.replace("/", "_").replace("\\", "_").ifBlank { "push.bin" }
        val dir = File(context.filesDir, "debug_push").apply { mkdirs() }
        val file = File(dir, safe)
        return runCatching {
            file.writeBytes(bytes)
            AppLog.event(RiskLevel.L2, "调试接口：推送文件", "${file.absolutePath}（${bytes.size} 字节）")
            file.absolutePath
        }.getOrElse { "写入失败：${it.message}（$name）" }
    }

    /** 本应用日志：trace + 事件 */
    fun logs(context: Context): String {
        val trace = runCatching { File(context.filesDir, "shield_trace.log").readText() }.getOrDefault("")
        val events = Prefs.events.value.take(100).joinToString("\n") { "[" + it.level.name + "] " + it.title + "：" + it.detail }
        return buildString {
            append("# trace\n")
            append(trace.takeLast(20_000))
            append("\n\n# events\n")
            append(events)
        }
    }
}
