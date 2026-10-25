package wzmwayne.shield

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import wzmwayne.shield.core.ActivationState
import wzmwayne.shield.core.AppLock
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.keepalive.KeepAliveEngine
import wzmwayne.shield.protection.SelfProtection
import wzmwayne.shield.shell.ShellClient
import wzmwayne.shield.ui.ShieldApp
import wzmwayne.shield.ui.ShieldViewModel
import wzmwayne.shield.ui.theme.ShieldTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ShieldViewModel by viewModels()
    private var shellInstalled = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 无论是否授予，保活服务都能运行，只是用户看不到常驻通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        Prefs.migrateLegacyPanicLog()
        AppLock.lockIfEnabled()
        // 调试接口：按上次开关状态自动恢复
        if (Prefs.debugHttpEnabled.value) {
            runCatching { wzmwayne.shield.debug.DebugHttpServer.start(applicationContext) }
        }

        // 严格遵循设计约束：只在「尚未进入防护状态」的激活阶段装配 Shizuku 通道
        if (Prefs.activationState.value != ActivationState.PROTECTING) {
            ShellClient.install(this)
            shellInstalled = true
        }

        requestNotificationPermissionIfNeeded()

        setContent {
            ShieldTheme {
                ShieldApp(viewModel, onRequestNotification = { requestNotificationPermissionIfNeeded() })
            }
        }
    }

    override fun onStop() {
        // 每次离开前台即上锁（可选 PIN）；锁层在 Activity 内部，不是独立页面
        AppLock.onBackground()
        // 与 PIN 同时机：重新隐藏隐藏入口解锁出来的「危险操作」区域
        Prefs.setKillUiShown(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.activationState.value == ActivationState.PROTECTING) {
            // 进程中断检测只在 Application.onCreate 做一次，这里不重复调用，避免重复上报
            KeepAliveEngine.ensureServiceRunning(this, "应用前台")
            KeepAliveEngine.evaluate(this, "应用前台")
            SelfProtection.maybeVerify(applicationContext)
            // 防护中：不再需要 Shizuku 通道
            if (shellInstalled) {
                ShellClient.shutdown()
                ShellClient.release()
                shellInstalled = false
            }
        }
    }

    override fun onDestroy() {
        if (shellInstalled) {
            ShellClient.release()
            shellInstalled = false
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
