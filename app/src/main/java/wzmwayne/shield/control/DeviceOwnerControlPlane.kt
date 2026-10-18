package wzmwayne.shield.control

import android.app.admin.DevicePolicyManager
import androidx.core.content.ContextCompat
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import wzmwayne.shield.admin.ShieldDeviceAdminReceiver
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.RiskLevel

/**
 * 管控层实现。本期只落地「自我保护」需要的设备所有者能力（卸载阻止、无障碍白名单），
 * 其余处置动作按里程碑推进，未实现前一律返回 false 并留日志，不做假实现。
 */
class DeviceOwnerControlPlane(context: Context) : ControlPlane {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val admin = ComponentName(appContext, ShieldDeviceAdminReceiver::class.java)

    override fun isReady(): Boolean =
        runCatching { dpm?.isDeviceOwnerApp(appContext.packageName) == true }.getOrDefault(false)

    override fun setUninstallBlocked(packageName: String, blocked: Boolean): Boolean {
        val manager = dpm ?: return false
        // setUninstallBlocked 无返回值：成功与否以是否抛出异常为准
        return runCatching { manager.setUninstallBlocked(admin, packageName, blocked) }
            .onFailure { AppLog.w("setUninstallBlocked($packageName) 失败：${it.message}") }
            .isSuccess
    }

    override fun isUninstallBlocked(packageName: String): Boolean {
        val manager = dpm ?: return false
        return runCatching { manager.isUninstallBlocked(admin, packageName) }.getOrDefault(false)
    }

    override fun updateAccessibilityWhitelist(services: List<String>): Boolean {
        val manager = dpm ?: return false
        if (services.isEmpty()) {
            AppLog.w("拒绝提交空的无障碍白名单（会造成自锁）")
            return false
        }
        return runCatching { manager.setPermittedAccessibilityServices(admin, services) }
            .onFailure { AppLog.w("setPermittedAccessibilityServices 失败：${it.message}") }
            .getOrDefault(false)
    }

    /** 某个包是否为当前的设备所有者 */
    fun isDeviceOwnerPackage(packageName: String): Boolean =
        runCatching { dpm?.isDeviceOwnerApp(packageName) == true }.getOrDefault(false)

    /** 锁定默认桌面（addPersistentPreferredActivity，用户与其它应用都改不掉） */
    fun setPersistentHome(component: ComponentName): Boolean = runCatching {
        val manager = dpm ?: return false
        // 注意：IntentFilter.addCategory 返回 Unit（与 Intent 不同），必须单独调用
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_MAIN)
        filter.addCategory(android.content.Intent.CATEGORY_HOME)
        manager.addPersistentPreferredActivity(admin, filter, component)
        true
    }.onFailure { AppLog.w("addPersistentPreferredActivity($component) 失败：${it.message}") }
        .getOrDefault(false)

    /** 解除某应用设置的持久默认（清除） */
    fun clearPersistentHome(packageName: String): Boolean = runCatching {
        dpm?.clearPackagePersistentPreferredActivities(admin, packageName)
        true
    }.onFailure { AppLog.w("clearPackagePersistentPreferredActivities($packageName) 失败：${it.message}") }
        .getOrDefault(false)

    /** 当前所有激活的设备管理器（含设备所有者自身） */
    fun activeAdmins(): List<ComponentName> = runCatching { dpm?.activeAdmins.orEmpty() }.getOrDefault(emptyList())

    /** 撤销某个设备管理器（对设备所有者自身无效） */
    fun removeActiveAdmin(component: ComponentName): Boolean = runCatching {
        dpm?.removeActiveAdmin(component)
        true
    }.onFailure { AppLog.w("removeActiveAdmin(${component.flattenToShortString()}) 失败：${it.message}") }
        .getOrDefault(false)

    override fun clearAccessibilityWhitelist(): Boolean {
        val manager = dpm ?: return false
        return runCatching {
            manager.setPermittedAccessibilityServices(admin, null)
            true
        }.onFailure { AppLog.w("清除无障碍白名单失败：${it.message}") }.getOrDefault(false)
    }

    override fun permittedAccessibilityServices(): List<String>? {
        val manager = dpm ?: return null
        return runCatching { manager.getPermittedAccessibilityServices(admin) }.getOrNull()
    }

    /** 一键自杀前置步骤：显式解除本应用施加的所有自我保护 */
    fun releaseAllProtections(): List<String> {
        val released = mutableListOf<String>()
        val manager = dpm ?: return released

        if (isUninstallBlocked(appContext.packageName)) {
            if (setUninstallBlocked(appContext.packageName, false)) {
                released += "已解除自身卸载阻止"
            }
        }

        if (runCatching { manager.getPermittedAccessibilityServices(admin) }.getOrNull() != null) {
            val cleared = runCatching { manager.setPermittedAccessibilityServices(admin, null) }
                .onFailure { AppLog.w("清空无障碍白名单失败：${it.message}") }
                .isSuccess
            if (cleared) released += "已清空无障碍白名单"
        }

        SELF_RESTRICTIONS.forEach { restriction ->
            val applied = runCatching { manager.getUserRestrictions(admin).containsKey(restriction) }
                .getOrDefault(false)
            if (applied) {
                val ok = runCatching { manager.clearUserRestriction(admin, restriction) }
                    .onFailure { AppLog.w("解除限制 $restriction 失败：${it.message}") }
                    .isSuccess
                if (ok) released += "已解除限制 ${restriction.substringAfterLast('.')}"
            }
        }
        return released
    }

    override fun removeActiveAdmin(component: String): Boolean = notImplemented("removeActiveAdmin")

    override fun setApplicationHidden(packageName: String, hidden: Boolean): Boolean {
        val manager = dpm ?: return false
        return runCatching { manager.setApplicationHidden(admin, packageName, hidden) }
            .onFailure { AppLog.w("setApplicationHidden($packageName=$hidden) 失败：${it.message}") }
            .getOrDefault(false)
    }

    override fun setPackagesSuspended(packageName: String, suspended: Boolean): Boolean {
        val manager = dpm ?: return false
        return runCatching {
            val failed = manager.setPackagesSuspended(admin, arrayOf(packageName), suspended)
            failed.isEmpty()
        }.onFailure { AppLog.w("setPackagesSuspended($packageName=$suspended) 失败：${it.message}") }
            .getOrDefault(false)
    }

    override fun setPermissionGrantState(packageName: String, permission: String, granted: Boolean): Boolean {
        val manager = dpm ?: return false
        val target = if (granted) {
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        } else {
            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
        }
        // 注意：该 API 返回 boolean（是否成功设置），不是返回设置后的状态
        return runCatching { manager.setPermissionGrantState(admin, packageName, permission, target) }
            .onFailure { AppLog.w("setPermissionGrantState($packageName/$permission=$granted) 失败：${it.message}") }
            .getOrDefault(false)
    }

    /** 清理应用数据：阻塞等待系统回调，返回**真实结果** */
    override fun clearApplicationUserData(packageName: String): Boolean {
        val manager = dpm ?: return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val ok = runCatching {
            manager.clearApplicationUserData(
                admin,
                packageName,
                java.util.concurrent.Executor { it.run() },
            ) { _, succeeded ->
                result.set(succeeded)
                latch.countDown()
            }
            true
        }.onFailure { AppLog.w("clearApplicationUserData($packageName) 调用失败：${it.message}") }
            .getOrDefault(false)
        if (!ok) return false
        // 等待系统回调（最多 8 秒），拿不到就按失败处理，绝不谎报「已提交」
        val finished = runCatching { latch.await(8, java.util.concurrent.TimeUnit.SECONDS) }
            .getOrDefault(false)
        val success = finished && result.get()
        AppLog.event(
            if (success) RiskLevel.L3 else RiskLevel.L2,
            "清理应用数据",
            "$packageName：${if (success) "成功" else if (finished) "系统返回失败" else "超时未返回结果"}",
        )
        return success
    }

    /** 卸载结果（含真实原因，供 UI 如实展示） */
    data class UninstallOutcome(val success: Boolean, val message: String)

    /**
     * 卸载：所有通道都**等待系统结果并回读包是否存在**，不返回「已提交」这种假结果。
     */
    override fun tryUninstall(packageName: String): Boolean = uninstall(packageName).success

    fun uninstall(packageName: String): UninstallOutcome {
        if (!isPackageInstalled(packageName)) {
            return UninstallOutcome(true, "已不存在")
        }
        val manager = dpm

        // 路径 1：设备所有者隐藏 API（部分 ROM 提供）
        if (manager != null) {
            val invoked = runCatching {
                val method = manager.javaClass.getMethod("uninstallPackageWithActiveAdmins", String::class.java)
                method.invoke(manager, packageName)
                true
            }.onFailure { AppLog.d("uninstallPackageWithActiveAdmins 不可用：${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (invoked && waitUntilGone(packageName, 6_000L)) {
                return UninstallOutcome(true, "所有者通道卸载成功")
            }
        }

        // 路径 2：PackageInstaller + 阻塞等待结果广播
        val action = "wzmwayne.shield.UNINSTALL_RESULT.${packageName.hashCode()}"
        val latch = java.util.concurrent.CountDownLatch(1)
        val statusCode = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        val statusMessage = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != action) return
                statusCode.set(
                    intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE),
                )
                statusMessage.set(intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE))
                latch.countDown()
            }
        }
        val registered = runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                appContext,
                receiver,
                android.content.IntentFilter(action),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrDefault(false)

        try {
            val pending = android.app.PendingIntent.getBroadcast(
                appContext,
                packageName.hashCode(),
                android.content.Intent(action).setPackage(appContext.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
            )
            val sent = runCatching {
                appContext.packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
                true
            }.onFailure { AppLog.w("uninstall($packageName) 调用失败：${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
            if (!sent) {
                return UninstallOutcome(false, "系统拒绝卸载请求（无 DELETE_PACKAGES 权限）")
            }

            val got = runCatching { latch.await(12, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            val gone = waitUntilGone(packageName, 3_000L)
            return when {
                gone -> UninstallOutcome(true, "PackageInstaller 卸载成功")
                !got -> UninstallOutcome(false, "等待系统卸载结果超时")
                else -> {
                    val code = statusCode.get()
                    val msg = statusMessage.get()
                    val reason = when (code) {
                        android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> "用户/系统中止"
                        android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED -> "被设备策略阻止"
                        android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT -> "版本冲突"
                        android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "不兼容"
                        android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> "APK 无效"
                        android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> "存储不足"
                        else -> "STATUS=$code"
                    }
                    UninstallOutcome(false, "系统返回失败：$reason${if (msg.isNullOrBlank()) "" else "（$msg）"}")
                }
            }
        } finally {
            if (registered) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** 等包真正消失（卸载是异步的） */
    private fun waitUntilGone(packageName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isPackageInstalled(packageName)) return true
            runCatching { Thread.sleep(300) }
        }
        return !isPackageInstalled(packageName)
    }

    /** 回读：应用是否已被隐藏 / 挂起 */
    fun isApplicationHidden(packageName: String): Boolean = runCatching {
        dpm?.isApplicationHidden(admin, packageName) == true
    }.getOrDefault(false)

    fun isPackagesSuspended(packageName: String): Boolean = runCatching {
        dpm?.isPackageSuspended(admin, packageName) == true
    }.getOrDefault(false)

    override fun cutNetwork(): String {
        val manager = dpm ?: return "断网失败：无设备策略服务"
        val marks = mutableListOf<String>()
        val restricted = runCatching {
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            true
        }.getOrDefault(false)
        marks += if (restricted) "已限制移动网络配置" else "限制移动网络失败"

        val wifiOff = runCatching {
            manager.setGlobalSetting(admin, android.provider.Settings.Global.WIFI_ON, "0")
            true
        }.getOrDefault(false)
        marks += if (wifiOff) "已尝试关闭 Wi-Fi" else "Wi-Fi 关闭不被支持（厂商限制）"
        return marks.joinToString("；")
    }

    override fun restoreNetwork(): String {
        val manager = dpm ?: return "恢复网络失败：无设备策略服务"
        runCatching { manager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS) }
        val wifiOn = runCatching {
            manager.setGlobalSetting(admin, android.provider.Settings.Global.WIFI_ON, "1")
            true
        }.getOrDefault(false)
        return if (wifiOn) "已解除移动网络限制并尝试恢复 Wi-Fi" else "已解除移动网络限制（Wi-Fi 需用户手动开启）"
    }

    override fun forceStop(packageName: String): Boolean =
        notImplemented("forceStop（需 Shell，运行时禁止调用）")

    override fun resetPassword(password: String): Boolean = notImplemented("resetPassword")

    private companion object {
        /** 锁盾可能按需启用的用户限制：自杀时必须一并解除 */
        val SELF_RESTRICTIONS = listOf(
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_CREATE_WINDOWS,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_FACTORY_RESET,
        )
    }

    private fun notImplemented(action: String): Boolean {
        AppLog.w("管控动作 $action 尚未实现（当前里程碑只做自我保护）")
        return false
    }
}
