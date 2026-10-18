package wzmwayne.shield

import android.app.Application
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.AppProcess
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.Trace
import wzmwayne.shield.keepalive.KeepAliveEngine
import wzmwayne.shield.protection.SelfProtection

class ShieldApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Shizuku 用户服务会把本 Application 也实例化到 shell 进程，那里不做任何应用初始化
        if (AppProcess.isShellProcess()) {
            AppLog.i("shell 用户服务进程启动，跳过应用初始化")
            return
        }
        Prefs.init(this)
        Trace.attach(this)
        Trace.step("进程启动（pid=${android.os.Process.myPid()}）trace 文件：${Trace.filePath()}")
        KeepAliveEngine.recordProcessStart(this)
        // 进程重启后重申自我保护措施（第 41 条：被强杀恢复）
        SelfProtection.onProcessStart(this)
    }
}
