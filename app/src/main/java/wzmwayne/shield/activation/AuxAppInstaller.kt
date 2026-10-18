package wzmwayne.shield.activation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Trace
import java.io.File
import kotlin.coroutines.resume

/**
 * 阶段一：辅助软件部署。
 * 只内置 Shizuku 与雹；Dhizuku 由用户自行安装（本项目不内置）。
 * 任一步骤都可跳过：未装齐不影响后续步骤继续推进。
 */
object AuxAppInstaller {

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** 启动已安装应用的入口 Activity */
    fun launch(context: Context, pkg: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    suspend fun install(
        context: Context,
        assetPath: String,
        label: String,
        timeoutMs: Long = 180_000L,
    ): StepOutcome {
        val app = context.applicationContext
        if (!canInstall(app)) {
            return StepOutcome.fail(
                "缺少「安装未知应用」权限",
                "请先在系统设置中允许锁盾安装应用，然后再试；也可以直接跳过该步骤自行安装",
            )
        }
        Trace.step("准备安装 $label（内置 assets：$assetPath）")
        return withTimeoutOrNull(timeoutMs) { awaitInstall(app, assetPath, label) }
            ?: StepOutcome.fail("安装 $label 超时", "可跳过该步骤，稍后自行安装").also {
                Trace.result(false, "$label 安装超时（可跳过）")
            }
    }

    private suspend fun awaitInstall(app: Context, assetPath: String, label: String): StepOutcome =
        suspendCancellableCoroutine { continuation ->
            val installer = app.packageManager.packageInstaller
            val action = "${app.packageName}.SHIELD_INSTALL.${System.currentTimeMillis()}"
            var receiver: BroadcastReceiver? = null

            fun cleanup() {
                receiver?.let { runCatching { app.unregisterReceiver(it) } }
                receiver = null
            }

            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    when (status) {
                        PackageInstaller.STATUS_SUCCESS -> {
                            cleanup()
                            AppLog.i("$label 安装完成")
                            Trace.result(true, "$label 安装完成")
                            if (continuation.isActive) continuation.resume(StepOutcome.ok("$label 已安装"))
                        }

                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            Trace.step("等待系统安装确认弹窗：$label")
                            // 系统弹出安装确认；保持监听，用户确认后系统会再次回调最终状态
                            val confirm = extractConfirmIntent(intent)
                            if (confirm != null) {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { app.startActivity(confirm) }
                            }
                        }

                        else -> {
                            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                            cleanup()
                            AppLog.w("$label 安装失败（$status）：$message")
                            Trace.result(false, "$label 安装失败：${'$'}message")
                            if (continuation.isActive) {
                                continuation.resume(
                                    StepOutcome.fail("$label 安装失败", message.ifEmpty { "状态码 $status" }),
                                )
                            }
                        }
                    }
                }
            }
            receiver = listener
            continuation.invokeOnCancellation { cleanup() }

            try {
                ContextCompat.registerReceiver(
                    app,
                    listener,
                    IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    app.assets.open(assetPath).use { input ->
                        session.openWrite(assetFileName(label), 0, -1).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val pendingIntent = android.app.PendingIntent.getBroadcast(
                        app,
                        sessionId,
                        Intent(action).setPackage(app.packageName),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pendingIntent.intentSender)
                }
            } catch (t: Throwable) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resume(StepOutcome.fail("$label 安装异常", t.message.orEmpty()))
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun extractConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun assetFileName(label: String): String =
        "${File(label).name.ifEmpty { "bundled" }}.apk"
}
