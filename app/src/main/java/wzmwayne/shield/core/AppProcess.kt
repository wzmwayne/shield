package wzmwayne.shield.core

import android.os.Process

/**
 * Shizuku 用户服务以 shell（uid 2000）身份运行。
 *
 * 注意：Shizuku 用 LoadedApk.makeApplication(true, null) 创建 Application，
 * 而 AOSP 只在 instrumentation != null 时才回调 onCreate，因此 shell 进程实际上不会执行
 * ShieldApplication.onCreate —— 这个判断是"双保险"，不能作为唯一防线。
 * 硬约束依旧成立：shell 侧只允许加载 ShieldShellService/ShellRunner，禁止引用 Prefs、前台服务、UI。
 */
object AppProcess {
    private const val SHELL_UID = 2000

    fun isShellProcess(): Boolean = runCatching { Process.myUid() == SHELL_UID }.getOrDefault(false)
}
