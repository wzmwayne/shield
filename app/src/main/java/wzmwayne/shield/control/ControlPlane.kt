package wzmwayne.shield.control

import wzmwayne.shield.core.logic.CleanupPolicy

/**
 * 管控层（设备所有者）接口。
 *
 * 硬性约束：锁盾不承担清理职责，任何卸载/清除数据类操作都被接口层直接拒绝。
 */
interface ControlPlane {

    fun isReady(): Boolean

    /** 卸载阻止：设备所有者可对任意应用设置，ADB 卸载同样会被拒绝 */
    fun setUninstallBlocked(packageName: String, blocked: Boolean): Boolean

    fun isUninstallBlocked(packageName: String): Boolean

    /** 无障碍白名单：更新前必须先枚举当前已启用服务并在新列表中包含自身 */
    fun updateAccessibilityWhitelist(services: List<String>): Boolean

    /** 返回当前白名单；null 表示未设限制 */
    /** 清除限制（setPermittedAccessibilityServices(admin, null)） */
    fun clearAccessibilityWhitelist(): Boolean

    fun permittedAccessibilityServices(): List<String>?

    fun removeActiveAdmin(component: String): Boolean

    fun setApplicationHidden(packageName: String, hidden: Boolean): Boolean

    /** 挂起/恢复挂起（系统应用通常不可挂起，会返回 false） */
    fun setPackagesSuspended(packageName: String, suspended: Boolean): Boolean

    /** 授予/撤销运行时权限（仅运行时权限有效） */
    fun setPermissionGrantState(packageName: String, permission: String, granted: Boolean): Boolean

    /** 清理应用数据（设备所有者 API，Android 9+）；不可撤销 */
    fun clearApplicationUserData(packageName: String): Boolean

    /**
     * 尝试卸载。设备所有者没有卸载 API，这里只做「尽力而为」：
     * 成功=已提交卸载；失败由调用方兜底跳系统设置让用户手动卸载。
     */
    fun tryUninstall(packageName: String): Boolean

    /** 临时断网 / 恢复网络 */
    fun cutNetwork(): String

    fun restoreNetwork(): String

    fun forceStop(packageName: String): Boolean

    fun resetPassword(password: String): Boolean

    /** 统一入口的安全闸：卸载、清除数据等清理类动作一律拒绝 */
    fun isOperationAllowed(operation: String): Boolean = !CleanupPolicy.isCleanupOperation(operation)

    /** 锁盾永久不执行的操作，供 UI 与审计展示 */
    val forbiddenOperations: List<String>
        get() = CleanupPolicy.FORBIDDEN_OPERATIONS
}
