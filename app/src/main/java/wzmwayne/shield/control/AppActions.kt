package wzmwayne.shield.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.PackageInventory
import wzmwayne.shield.core.RiskLevel

/** 应用管理页的单应用操作（全部使用应用自身权限：设备所有者 / AppOps 只读） */
object AppActions {

    enum class Action(val label: String) {
        HIDE("隐藏应用"),
        UNHIDE("取消隐藏"),
        SUSPEND("禁用（停止）"),
        UNSUSPEND("恢复运行"),
        BLOCK_UNINSTALL("禁止卸载"),
        ALLOW_UNINSTALL("允许卸载"),
        REVOKE_ALL("撤销所有权限"),
        RESTORE_PERMISSIONS("恢复权限"),
        UNINSTALL("卸载应用"),
        DETAILS("打开应用详情"),
    }

    fun run(context: Context, packageName: String, action: Action): String {
        val app = context.applicationContext
        val plane = DeviceOwnerControlPlane(app)
        val message = when (action) {
            Action.HIDE -> {
                plane.setApplicationHidden(packageName, true)
                if (plane.isApplicationHidden(packageName)) "已隐藏 $packageName" else "隐藏失败（需设备所有者）"
            }
            Action.UNHIDE -> {
                plane.setApplicationHidden(packageName, false)
                if (!plane.isApplicationHidden(packageName)) "已取消隐藏 $packageName" else "取消隐藏失败"
            }
            Action.SUSPEND -> {
                plane.setPackagesSuspended(packageName, true)
                if (plane.isPackagesSuspended(packageName)) "已禁用（停止）$packageName" else "禁用失败（系统不允许挂起该应用）"
            }
            Action.UNSUSPEND -> {
                plane.setPackagesSuspended(packageName, false)
                if (!plane.isPackagesSuspended(packageName)) "已恢复运行 $packageName" else "恢复失败"
            }
            Action.BLOCK_UNINSTALL -> {
                plane.setUninstallBlocked(packageName, true)
                if (plane.isUninstallBlocked(packageName)) "已禁止卸载 $packageName" else "禁止卸载失败"
            }
            Action.ALLOW_UNINSTALL -> {
                plane.setUninstallBlocked(packageName, false)
                if (!plane.isUninstallBlocked(packageName)) "已允许卸载 $packageName" else "操作失败"
            }
            Action.REVOKE_ALL -> {
                val granted = PackageInventory.grantedRuntimePermissions(app, packageName)
                var revoked = 0
                granted.forEach { permission ->
                    plane.setPermissionGrantState(packageName, permission, false)
                    if (!PackageInventory.isPermissionGranted(app, packageName, permission)) revoked += 1
                }
                "已撤销 $revoked/${granted.size} 项运行时权限（无障碍白名单未改动）"
            }
            Action.RESTORE_PERMISSIONS -> {
                // 把所有运行时权限交还系统默认策略，用户可在系统设置中手动管理
                val declared = runCatching {
                    app.packageManager.getPackageInfo(
                        packageName,
                        android.content.pm.PackageManager.GET_PERMISSIONS,
                    ).requestedPermissions?.toList().orEmpty()
                }.getOrDefault(emptyList())
                val dangerous = declared.filter { permission ->
                    runCatching {
                        app.packageManager.getPermissionInfo(permission, 0).protection ==
                            android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
                    }.getOrDefault(false)
                }
                var restored = 0
                dangerous.forEach { permission ->
                    if (plane.restorePermissionPolicy(packageName, permission)) restored += 1
                }
                "已恢复 $restored/${dangerous.size} 项权限为系统默认：可在系统设置中手动管理"
            }
            Action.UNINSTALL -> plane.uninstall(packageName).let { it.message }
            Action.DETAILS -> {
                val ok = runCatching {
                    app.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
                if (ok) "已打开 $packageName 的应用详情" else "打开应用详情失败"
            }
        }
        AppLog.event(RiskLevel.L2, "应用管理：${action.label}", "$packageName → $message")
        return message
    }
}
