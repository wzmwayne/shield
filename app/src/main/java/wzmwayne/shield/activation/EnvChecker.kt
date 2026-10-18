package wzmwayne.shield.activation

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wzmwayne.shield.core.CheckItem
import wzmwayne.shield.core.CheckState
import wzmwayne.shield.core.Trace
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.logic.ShellParsers
import wzmwayne.shield.shell.ShellClient

/**
 * 环境检查：**在拿到 Shizuku 之后**，用 Shell 身份读真实状态，回答一个问题——
 * 「现在能不能进入激活步骤」。
 *
 * 因此这里只有四类硬性前提：Shizuku 通道、多用户、账户、设备所有者占用情况（外加系统版本）。
 * 激活后才存在的状态（WRITE_SECURE_SETTINGS、DUMP、无障碍服务）一律不在这里判断，避免与第 6 步自检重复。
 */
object EnvChecker {

    suspend fun inspect(context: Context): EnvReport = withContext(Dispatchers.IO) {
        Trace.step("开始环境检查：判断现在能否进入激活步骤")
        val shizukuInstalled = ShellClient.isShizukuInstalled(context)
        val shizukuAlive = ShellClient.pingBinder()
        val granted = ShellClient.hasPermission()
        val shellReady = shizukuAlive && granted
        Trace.step(
            "检查 Shizuku：安装=${if (shizukuInstalled) "已安装" else "未安装"}；" +
                "服务=${if (shizukuAlive) "运行中" else "未运行"}；授权=${if (granted) "已授权" else "未授权"}",
        )
        Trace.judge("Shizuku 通道", shellReady, ShellClient.diagnostics())

        val shizukuItem = CheckItem(
            id = "shizuku",
            title = "Shizuku 通道（进入激活的前提）",
            state = when {
                !shizukuInstalled -> CheckState.BLOCK
                !shizukuAlive -> CheckState.BLOCK
                !granted -> CheckState.BLOCK
                else -> CheckState.PASS
            },
            detail = when {
                !shizukuInstalled -> "未安装：请回到上一步用向导安装内置的 Shizuku"
                !shizukuAlive -> "已安装但服务未运行：Android 9-10 需用 adb 执行 Shizuku 的启动脚本；Android 11+ 可用无线调试配对"
                !granted -> "未授权：点击下方「申请 Shizuku 授权」弹出允许窗口并选择允许"
                else -> "Shell 通道可用（${ShellClient.diagnostics()}）"
            },
        )

        var users: List<ShellParsers.UserEntry> = emptyList()
        var accountCount = 0
        var ownerKnown = false
        var deviceOwner: String? = null
        var usersKnown = false
        var accountsKnown = false
        if (shellReady) {
            val usersResult = ShellClient.exec("pm list users")
            usersKnown = usersResult.ok
            if (usersKnown) users = ShellParsers.parseUsers(usersResult.out)

            val accountResult = ShellClient.exec("dumpsys account")
            accountsKnown = accountResult.ok
            if (accountsKnown) accountCount = ShellParsers.accountCount(accountResult.out)

            val query = DeviceOwnerQuery.query(context)
            ownerKnown = query.known
            deviceOwner = query.packageName
        }

        val secondaries = users.filter { it.id != 0 }
        if (shellReady && (usersKnown || accountsKnown || ownerKnown)) {
            Trace.step(
                "解析结果：用户=${if (usersKnown) users.joinToString { "${it.id}:${it.name}" } else "未取到"}；" +
                    "账户数=${if (accountsKnown) accountCount.toString() else "未取到"}；" +
                    "设备所有者=${if (ownerKnown) (deviceOwner ?: "无") else "无法判定"}",
            )
        }
        // 拿不到数据一律不算通过：这是真机上"假通过"的常见来源
        Trace.judge("多用户检查", usersKnown && secondaries.isEmpty(), if (usersKnown) "共 ${users.size} 个用户" else "数据未取到")
        Trace.judge("账户检查", accountsKnown && accountCount == 0, if (accountsKnown) "账户数=$accountCount" else "数据未取到")
        Trace.judge(
            "设备所有者占用（必须为无或锁盾自身）",
            ownerKnown && (deviceOwner == null || deviceOwner == context.packageName),
            if (ownerKnown) "当前=${deviceOwner ?: "无"}" else "无法判定",
        )
        val items = mutableListOf(
            shizukuItem,
            CheckItem(
                id = "users",
                title = "多用户检查（设备所有者硬性前提）",
                state = when {
                    !usersKnown -> CheckState.UNKNOWN
                    secondaries.isNotEmpty() -> CheckState.BLOCK
                    else -> CheckState.PASS
                },
                detail = when {
                    !usersKnown -> "未能读取用户列表（Shizuku 通道不可用或命令失败）"
                    secondaries.isNotEmpty() ->
                        "存在次要用户/工作资料：${secondaries.joinToString { "${it.id}:${it.name}" }}，需先在系统中删除"
                    else -> "仅有当前用户（共 ${users.size} 个），满足前提"
                },
            ),
            CheckItem(
                id = "accounts",
                title = "账户检查（设备所有者硬性前提）",
                state = when {
                    !accountsKnown -> CheckState.UNKNOWN
                    accountCount > 0 -> CheckState.BLOCK
                    else -> CheckState.PASS
                },
                detail = when {
                    !accountsKnown -> "未能读取账户列表（Shizuku 通道不可用或命令失败）"
                    accountCount > 0 -> "检测到 $accountCount 个账户：请先移除，或用内置的雹临时冻结持有账户的应用"
                    else -> "未检测到账户"
                },
            ),
            CheckItem(
                id = "owner",
                title = "设备所有者占用情况",
                state = when {
                    !ownerKnown -> CheckState.UNKNOWN
                    deviceOwner == null -> CheckState.PASS
                    deviceOwner == context.packageName -> CheckState.PASS
                    else -> CheckState.BLOCK
                },
                detail = when {
                    !ownerKnown -> "无法判定当前设备所有者（命令失败或输出格式不符）：请勿贸然执行 dpm"
                    deviceOwner == null -> "当前无设备所有者，可以激活"
                    deviceOwner == context.packageName -> "锁盾已是设备所有者，可直接进入后续步骤"
                    deviceOwner == Constants.PKG_DHIZUKU ->
                        "Dhizuku 正持有设备所有者，但本应用已禁用委派模式：需先在 Dhizuku 中移交/移除设备所有者身份"
                    else -> "已被 $deviceOwner 持有：需先由该应用移除后才能重新激活"
                },
            ),
            CheckItem(
                id = "android",
                title = "系统版本",
                state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) CheckState.PASS else CheckState.BLOCK,
                detail = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），设计要求 Android 9+",
            ),
        )

        val adbEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        items += CheckItem(
            id = "adb",
            title = "ADB 调试（启动 Shizuku 用）",
            state = if (adbEnabled) CheckState.PASS else CheckState.WARN,
            detail = if (adbEnabled) {
                "已开启：Android 9-10 需要用 adb 执行一次 Shizuku 启动脚本；锁盾运行时默认不阻断 ADB"
            } else {
                "当前关闭：若 Shizuku 尚未启动，需要先开启 USB 调试"
            },
        )

        val blockingCount = items.count { it.state == CheckState.BLOCK }
        val unknownCount = items.count { it.state == CheckState.UNKNOWN }
        Trace.result(
            blockingCount == 0 && unknownCount == 0,
            when {
                blockingCount == 0 && unknownCount == 0 -> "结论：前提全部满足，可以进入第 4 步执行激活"
                unknownCount > 0 -> "结论：$unknownCount 项无法判定（数据没取到，可能是 Shizuku 通道不可用），不能进入激活"
                else -> "结论：$blockingCount 项阻断"
            },
        )

        EnvReport(
            items = items,
            shizukuInstalled = shizukuInstalled,
            shizukuAlive = shizukuAlive,
            shizukuGranted = granted,
            userCount = users.size,
            secondaryUser = secondaries.isNotEmpty(),
            accountCount = accountCount,
            ownerKnown = ownerKnown,
            deviceOwner = deviceOwner,
            shellAvailable = shellReady,
        )
    }
}
