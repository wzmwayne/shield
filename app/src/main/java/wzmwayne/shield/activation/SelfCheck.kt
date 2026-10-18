package wzmwayne.shield.activation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wzmwayne.shield.core.CheckItem
import wzmwayne.shield.core.CheckState
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RepairActions
import wzmwayne.shield.core.Trace
import wzmwayne.shield.keepalive.AccessibilitySettings
import wzmwayne.shield.keepalive.CrashDetector

/**
 * 阶段七：自检。任何一项不达标都在 UI 中给出修复指引；
 * 可修复项带上 RepairActions 动作 id，用户在向导第 6 步直接点击条目即可执行修复。
 *
 * 注意：本函数会做 dumpsys/设备策略查询，必须在 IO 线程执行（内部已切换）。
 */
object SelfCheck {

    suspend fun run(context: Context): List<CheckItem> = withContext(Dispatchers.IO) {
        Trace.step("开始自检（检查激活后才存在的状态）")
        val items = mutableListOf<CheckItem>()

        val selfOwner = OwnerSetup.isSelfDeviceOwner(context)
        val query = if (selfOwner) DeviceOwnerQuery.Result(true, context.packageName) else DeviceOwnerQuery.query(context)
        items += CheckItem(
            id = "owner",
            title = "设备所有者",
            state = when {
                selfOwner -> CheckState.PASS
                !query.known -> CheckState.UNKNOWN
                else -> CheckState.BLOCK
            },
            detail = when {
                selfOwner -> "锁盾自身持有设备所有者身份（未声明 testOnly，ADB 无法移除）"
                !query.known -> "无法判定（缺少 DUMP 权限或 Shizuku 通道）；请先在上一步完成激活"
                query.packageName == null -> "系统报告当前没有设备所有者：请回到第 4 步重新执行激活"
                else -> "设备所有者由 ${query.packageName} 持有；委派模式已禁用，必须是锁盾自身"
            },
            action = if (selfOwner) null else RepairActions.OPEN_ACTIVATION,
            actionLabel = "重新激活",
        )

        val listed = AccessibilitySettings.isSelfListed(context)
        val connected = AccessibilitySettings.isSelfEnabledBySystem(context)
        items += CheckItem(
            id = "accessibility",
            title = "无障碍服务",
            state = when {
                listed && connected -> CheckState.PASS
                listed -> CheckState.WARN
                else -> CheckState.BLOCK
            },
            detail = when {
                listed && connected -> "已在启用列表且系统已绑定"
                listed -> "已在启用列表但系统尚未绑定：保活层会在下一次检测时处理"
                else -> "不在启用列表中：点击右侧按钮用 WRITE_SECURE_SETTINGS 写回"
            },
            action = if (listed && connected) null else RepairActions.ENABLE_ACCESSIBILITY,
            actionLabel = "写回设置",
        )

        val hasWriteSecure = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        items += CheckItem(
            id = "wss",
            title = "WRITE_SECURE_SETTINGS",
            state = if (hasWriteSecure) CheckState.PASS else CheckState.BLOCK,
            detail = if (hasWriteSecure) "已授予（永久生效，与 Shizuku 是否在线无关）" else "缺失：需重新执行激活阶段",
            action = if (hasWriteSecure) null else RepairActions.OPEN_ACTIVATION,
            actionLabel = "重新激活",
        )

        val hasDump = CrashDetector.hasDumpPermission(context)
        items += CheckItem(
            id = "dump",
            title = "DUMP（崩溃检测）",
            state = if (hasDump) CheckState.PASS else CheckState.WARN,
            detail = if (hasDump) "可用 dumpsys accessibility 检测假死" else "缺失：检测不到假死；可用 Shizuku 重新授予",
            action = if (hasDump) null else RepairActions.GRANT_DUMP,
            actionLabel = "重新授予",
        )

        val token = Prefs.resetToken.value
        items += CheckItem(
            id = "token",
            title = "恢复令牌",
            state = if (token.isNotEmpty()) CheckState.PASS else CheckState.WARN,
            detail = if (token.isNotEmpty()) {
                "已预置（${token.take(6)}…），请在恢复中心导出备份"
            } else {
                "未预置：锁机场景下无法重置密码；点击右侧按钮立即预置"
            },
            action = if (token.isNotEmpty()) null else RepairActions.INSTALL_RESET_TOKEN,
            actionLabel = "预置令牌",
        )

        val adbEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        items += CheckItem(
            id = "adb",
            title = "ADB 调试",
            state = if (adbEnabled) CheckState.PASS else CheckState.WARN,
            detail = if (adbEnabled) "保持可用（设计选择：仅在 L3/L4 阻断时临时收紧）" else "当前已关闭；实时保活不依赖 ADB",
        )

        items += CheckItem(
            id = "keepalive",
            title = "保活层",
            state = when {
                !Prefs.keepAliveEnabled.value -> CheckState.WARN
                Prefs.serviceConnected.value -> CheckState.PASS
                else -> CheckState.WARN
            },
            detail = when {
                !Prefs.keepAliveEnabled.value -> "已关闭：无障碍服务被移除后不会自动恢复；点击右侧按钮开启"
                Prefs.serviceConnected.value -> "保活服务运行中，定时检测间隔 ${Prefs.checkIntervalSeconds.value} 秒"
                else -> "已启用但服务未连接：点击右侧按钮重启并立即检测"
            },
            action = if (Prefs.keepAliveEnabled.value && Prefs.serviceConnected.value) null else RepairActions.RESTART_KEEPALIVE,
            actionLabel = "重启保活",
        )

        val killGap = Prefs.lastKillGapMs.value
        items += CheckItem(
            id = "kill",
            title = "进程中断记录",
            state = CheckState.PASS,
            detail = if (killGap > 0) {
                "上次进程中断约 ${killGap / 1000} 秒，已被重新拉起（累计修复 ${Prefs.fixCount.value} 次）"
            } else {
                "未检测到锁盾进程被强杀的记录"
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            items += CheckItem(
                id = "notification",
                title = "通知权限",
                state = if (notifications) CheckState.PASS else CheckState.WARN,
                detail = if (notifications) "保活前台服务可显示常驻通知" else "未授予：前台服务仍可运行，但用户看不到防护状态",
                action = if (notifications) null else RepairActions.REQUEST_NOTIFICATION,
                actionLabel = "去授权",
            )
        }

        items.forEach { item ->
            Trace.judge(item.title, item.state == CheckState.PASS, item.detail.take(70))
        }
        Trace.result(
            items.none { it.state == CheckState.BLOCK },
            "自检完成：${items.count { it.state != CheckState.PASS }} 项需要关注",
        )
        items
    }
}
