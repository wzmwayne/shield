package wzmwayne.shield.shell

import android.os.SystemClock
import android.util.Log

/**
 * 由 Shizuku 在 shell 进程中实例化的用户服务（uid 2000）。
 * 注意：本类不能依赖任何 UI / Application 上下文能力，只能做纯命令执行。
 */
class ShieldShellService : IShieldShell.Stub() {

    override fun destroy() {
        Log.i(TAG, "destroy, 退出 shell 用户服务进程")
        System.exit(0)
    }

    override fun exec(command: String): String {
        val start = SystemClock.elapsedRealtime()
        val result = ShellRunner.run(command)
        Log.i(TAG, "exec(${SystemClock.elapsedRealtime() - start}ms): $command")
        return result
    }

    companion object {
        private const val TAG = "ShieldShellService"
    }
}
