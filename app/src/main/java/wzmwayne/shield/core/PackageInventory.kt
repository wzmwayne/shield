package wzmwayne.shield.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** 应用清单筛选：默认只看用户（第三方）应用 */
enum class AppFilter { USER, SYSTEM, ALL }

/** 应用清单（响应层要用：枚举应用、判定系统应用、取标签） */
object PackageInventory {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val trusted: Boolean,
    )

    fun isSystemApp(info: ApplicationInfo): Boolean =
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    /**
     * 列出可处置的第三方应用（系统应用也在返回列表里，但带 isSystem 标记，响应层一律跳过）。
     * 自身与 Shizuku 会被排除。
     */
    fun listApps(
        context: Context,
        trusted: Set<String>,
        filter: AppFilter = AppFilter.USER,
    ): List<AppEntry> {
        val pm = context.packageManager
        val selfPackage = context.packageName
        return runCatching {
            // 关键：带上 GET_UNINSTALLED_PACKAGES，才能列出「被隐藏/停用」的应用
            // （设备所有者对被隐藏应用仍有可见性；普通查询会把它们过滤掉，处置后就看不到了）
            pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.GET_UNINSTALLED_PACKAGES)
                .asSequence()
                .filter { it.packageName != selfPackage }
                .filter { it.packageName != Constants.PKG_SHIZUKU }
                .filter { it.packageName != Constants.PKG_HAIL }
                .map { info ->
                    AppEntry(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                        isSystem = isSystemApp(info),
                        trusted = info.packageName in trusted,
                    )
                }
                .filter {
                    when (filter) {
                        AppFilter.USER -> !it.isSystem
                        AppFilter.SYSTEM -> it.isSystem
                        AppFilter.ALL -> true
                    }
                }
                .sortedWith(compareBy({ it.isSystem }, { it.label }))
                .toList()
        }.getOrDefault(emptyList())
    }

    /** 单个权限是否已授予（用于撤权/回填后的回读校验） */
    fun isPermissionGranted(context: Context, packageName: String, permission: String): Boolean =
        runCatching {
            context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 已授予的运行时（危险）权限清单：撤销前必须记录，恢复时按它回填 */
    fun grantedRuntimePermissions(context: Context, packageName: String): List<String> {
        val pm = context.packageManager
        val info = runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull() ?: return emptyList()
        val requested = info.requestedPermissions ?: return emptyList()
        return requested.filter { permission ->
            val dangerous = runCatching {
                (pm.getPermissionInfo(permission, 0).protectionLevel and
                    android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) ==
                    android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
            dangerous && pm.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
        }
    }
}
