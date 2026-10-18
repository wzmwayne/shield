package wzmwayne.shield.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import wzmwayne.shield.BuildConfig
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.Trace

enum class ShellState { NO_SHIZUKU, NO_PERMISSION, IDLE, CONNECTING, READY, DEAD }

/** 请求 Shizuku 授权的结果，便于 UI 给出准确提示 */
enum class ShizukuPermission {
    BINDER_MISSING,
    ALREADY_GRANTED,
    REQUESTED,
    FAILED,
}

/**
 * 仅在激活阶段使用的 Shizuku 通道。
 * 运行时（防护中）不再调用任何 Shizuku API：保活与恢复依赖 WRITE_SECURE_SETTINGS 与设备所有者。
 */
object ShellClient {
    private const val REQUEST_CODE = 4213
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val SERVICE_TAG = "shield-shell"

    /**
     * 通道策略（依据 Shizuku 官方文档 README「Prepare to remove Shizuku#newProcess,
     * developers should have to use UserService instead」）：
     * **只使用 Shizuku 用户服务**（bindUserService），不再使用 newProcess。
     * 注：Shizuku+ 的 Drop-In fork 在 getUserServiceStartCmd 里有 NPE，绑定用户服务会让服务端退出；
     * 因此内置并推荐官方原版 Shizuku 13.6.0。
     */
    private const val USE_USER_SERVICE = true

    private val _state = MutableStateFlow(ShellState.NO_SHIZUKU)
    val state: StateFlow<ShellState> = _state

    private var appContext: Context? = null
    private var iface: IShieldShell? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var pending: CompletableDeferred<IShieldShell>? = null

    /** 是否有一次 bind 正在进行：用它判断"该不该重新 bind"，避免失败后永远不再绑定 */
    private var binding = false
    private var listenersInstalled = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            val stub = service?.let { IShieldShell.asInterface(it) }
            iface = stub
            _state.value = if (stub != null) ShellState.READY else ShellState.DEAD
            pending?.let { waiting ->
                if (stub != null) {
                    waiting.complete(stub)
                } else {
                    waiting.completeExceptionally(IllegalStateException("Shizuku 用户服务返回空 Binder"))
                }
            }
            pending = null
            AppLog.i("Shizuku 用户服务已连接（${diagnostics()}）")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            iface = null
            _state.value = ShellState.DEAD
            pending?.completeExceptionally(IllegalStateException("Shizuku 用户服务已断开"))
            pending = null
            AppLog.w("Shizuku 用户服务已断开")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        AppLog.i("Shizuku Binder 可用（${diagnostics()}）")
        refreshState()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        binding = false
        iface = null
        _state.value = ShellState.NO_SHIZUKU
        AppLog.w("Shizuku Binder 已失效")
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != REQUEST_CODE) return@OnRequestPermissionResultListener
        val granted = result == PackageManager.PERMISSION_GRANTED
        AppLog.i("Shizuku 授权结果：${if (granted) "已授权" else "被拒绝"}")
        _state.value = if (granted) ShellState.IDLE else ShellState.NO_PERMISSION
        if (granted) {
            binding = false
            runCatching { connectService() }
        }
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        if (!listenersInstalled) {
            listenersInstalled = true
            runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceived) }
            runCatching { Shizuku.addBinderDeadListener(binderDead) }
            runCatching { Shizuku.addRequestPermissionResultListener(permissionResult) }
        }
        refreshState()
    }

    fun release() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
        runCatching { Shizuku.removeBinderDeadListener(binderDead) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResult) }
        listenersInstalled = false
    }

    fun refreshState() {
        _state.value = when {
            !pingBinder() -> ShellState.NO_SHIZUKU
            !hasPermission() -> ShellState.NO_PERMISSION
            iface != null -> ShellState.READY
            binding -> ShellState.CONNECTING
            // 可用且已授权，但还没建立用户服务：执行命令时会自动连接
            else -> ShellState.IDLE
        }
    }

    fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(Constants.PKG_SHIZUKU, 0)
        true
    }.getOrDefault(false)

    fun pingBinder(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 直接触发 Shizuku 的「是否允许…」授权弹窗。
     * 返回具体结果，让向导能区分「没启动 Shizuku」「已授权」「弹窗已触发」「调用失败」。
     */
    fun requestPermission(): ShizukuPermission {
        if (hasPermission()) {
            refreshState()
            return ShizukuPermission.ALREADY_GRANTED
        }
        if (!pingBinder()) {
            AppLog.w("请求授权失败：Shizuku 服务未运行")
            return ShizukuPermission.BINDER_MISSING
        }
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            AppLog.i("已向 Shizuku 发起授权请求")
            ShizukuPermission.REQUESTED
        }.getOrElse { t ->
            AppLog.w("请求授权异常：${t.message}")
            ShizukuPermission.FAILED
        }
    }

    /** 用于把状态写进日志与 UI，便于定位"连接超时"到底卡在哪一步 */
    fun diagnostics(): String {
        val client = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        val service = runCatching { Shizuku.getLatestServiceVersion() }.getOrDefault(-1)
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val privilege = when (uid) {
            0 -> "root"
            2000 -> "adb"
            else -> "uid=$uid"
        }
        return "客户端=$client 服务端=$service binder=${pingBinder()} 已授权=${hasPermission()} 权限=$privilege 状态=${_state.value}"
    }

    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        Trace.step("执行 shell：$command")
        val binderBefore = pingBinder()

        // 官方推荐通道：Shizuku 用户服务（uid 2000 或 0 的独立进程里执行命令）
        Trace.step("使用 Shizuku 用户服务通道执行（uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}）")
        val result = if (USE_USER_SERVICE) {
            execViaUserService(command)
        } else {
            ShellResult.failure("未启用任何 Shizuku 通道")
        }

        val out = result.out.trim().replace('\n', ' ').take(160)
        val err = result.err.trim().replace('\n', ' ').take(160)
        Trace.step(
            "返回：退出码=${result.code}${if (result.timeout) "（超时）" else ""}" +
                (if (out.isNotEmpty()) "；输出=$out" else "") +
                (if (err.isNotEmpty()) "；错误=$err" else ""),
        )
        // 关键诊断：激活期间的命令是否把 Shizuku 弄停了
        if (binderBefore && !pingBinder()) {
            Trace.step("⚠ 执行这条命令后 Shizuku 变为不可用（binder 丢失）")
        }
        result
    }

    /** 兜底通道：Shizuku 用户服务（在部分环境会触发服务端 SecureRandom 崩溃，故仅作回退） */
    private suspend fun execViaUserService(command: String): ShellResult {
        val service = try {
            awaitService()
        } catch (t: Throwable) {
            val message = t.message ?: "无法连接 Shizuku 用户服务"
            Trace.step("✘ 连接失败：$message")
            return ShellResult.failure(message)
        }
        val raw = try {
            service.exec(command)
        } catch (t: Throwable) {
            iface = null
            Trace.step("✘ 调用用户服务异常：${t.message}（${diagnostics()}）")
            return ShellResult.failure("调用 shell 用户服务失败：${t.message}（${diagnostics()}）")
        }
        return ShellResult.fromJson(raw)
    }

    fun shutdown() {
        runCatching { iface?.destroy() }
        val context = appContext
        val args = serviceArgs
        if (context != null && args != null) {
            runCatching { Shizuku.unbindUserService(args, connection, false) }
        }
        binding = false
        iface = null
        _state.value = ShellState.IDLE
    }

    private fun connectService() {
        val context = appContext ?: return
        val args = serviceArgs ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShieldShellService::class.java.name),
        )
            .daemon(false)
            .debuggable(false)
            .processNameSuffix("shell")
            .tag(SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
            .also { serviceArgs = it }
        binding = true
        _state.value = ShellState.CONNECTING
        try {
            Shizuku.bindUserService(args, connection)
            AppLog.i("已请求绑定 Shizuku 用户服务（${diagnostics()}）")
        } catch (t: Throwable) {
            binding = false
            AppLog.w("bindUserService 失败：${t.message}（${diagnostics()}）")
        }
    }

    private suspend fun awaitService(): IShieldShell {
        iface?.let { return it }
        val waiting: CompletableDeferred<IShieldShell>
        synchronized(this) {
            iface?.let { return it }
            if (!pingBinder()) {
                throw IllegalStateException("Shizuku 未运行：请先启动 Shizuku（${diagnostics()}）")
            }
            if (!hasPermission()) {
                throw IllegalStateException("尚未在 Shizuku 中授权锁盾（${diagnostics()}）")
            }
            waiting = pending ?: CompletableDeferred<IShieldShell>().also { pending = it }
            // 只要当前没有进行中的绑定就重新绑一次，避免上一次失败后永远不再绑定
            if (!binding) connectService()
        }
        return try {
            withTimeout(CONNECT_TIMEOUT_MS) { waiting.await() }
        } catch (t: Throwable) {
            synchronized(this) {
                if (pending === waiting) pending = null
                binding = false
                serviceArgs?.let { args -> runCatching { Shizuku.unbindUserService(args, connection, false) } }
            }
            throw IllegalStateException("连接 Shizuku 用户服务失败：${t.message}（${diagnostics()}）")
        }
    }
}
