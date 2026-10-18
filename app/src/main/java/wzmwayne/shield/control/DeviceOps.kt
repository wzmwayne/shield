package wzmwayne.shield.control

import android.content.Context
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.RiskLevel

/**
 * 设备管理能力面板的数据层（能力清单参考开源项目 OwnDroid 的 DeviceOwner 操作集合，
 * 代码为本项目自研实现，不拷贝其源码）。
 *
 * 硬约束：**不提供** wipeData / wipeDevice（恢复出厂）、不提供 reboot；
 * 所有开关都可逆，且执行后立即回读真实状态。
 */
enum class DeviceOp(
    val id: String,
    val title: String,
    val description: String,
    val danger: Boolean = false,
) {
    CAMERA("camera", "禁用相机", "禁止整机使用摄像头（可随时恢复）"),
    SCREENSHOT("screenshot", "禁止截屏", "禁止截屏与录屏（可随时恢复）"),
    STATUS_BAR("status_bar", "禁用状态栏", "隐藏状态栏，防止从状态栏下拉绕过（可随时恢复）"),
    USB_TRANSFER("usb_transfer", "禁止 USB 文件传输", "通过用户限制 DISALLOW_USB_FILE_TRANSFER 阻断 MTP/PTP"),
    SAFE_BOOT("safe_boot", "禁止安全模式", "防止通过安全模式绕过锁盾（DISALLOW_SAFE_BOOT）"),
    ADD_USER("add_user", "禁止添加用户/账户", "DISALLOW_ADD_USER，避免多用户绕过设备所有者约束"),
    RESET_PASSWORD("reset_password", "重置锁屏密码为 123456", "使用预置恢复令牌重置锁屏密码（不可逆，需谨慎）", danger = true),
}

data class DeviceOpState(
    val op: DeviceOp,
    val enabled: Boolean,
    val detail: String = "",
)

object DeviceOpsRunner {

    fun snapshot(context: Context): List<DeviceOpState> {
        val ops = DeviceOwnerOps(context)
        val ready = ops.isReady()
        return DeviceOp.entries.map { op ->
            val enabled = when (op) {
                DeviceOp.CAMERA -> ops.isCameraDisabled()
                DeviceOp.SCREENSHOT -> ops.isScreenCaptureDisabled()
                DeviceOp.STATUS_BAR -> ops.isStatusBarDisabled()
                DeviceOp.USB_TRANSFER -> ops.hasUserRestriction(android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                DeviceOp.SAFE_BOOT -> ops.hasUserRestriction(android.os.UserManager.DISALLOW_SAFE_BOOT)
                DeviceOp.ADD_USER -> ops.hasUserRestriction(android.os.UserManager.DISALLOW_ADD_USER)
                DeviceOp.RESET_PASSWORD -> false
            }
            DeviceOpState(
                op = op,
                enabled = enabled,
                detail = when {
                    !ready && op != DeviceOp.RESET_PASSWORD -> "需要设备所有者身份"
                    enabled -> "已生效"
                    else -> "未启用"
                },
            )
        }
    }

    /** 执行/关闭一项能力，返回可读结果 */
    fun apply(context: Context, op: DeviceOp, enabled: Boolean): String {
        val ops = DeviceOwnerOps(context)
        if (!ops.isReady()) {
            return "需要设备所有者身份：${op.title}未能执行"
        }
        val ok = when (op) {
            DeviceOp.CAMERA -> ops.setCameraDisabled(enabled)
            DeviceOp.SCREENSHOT -> ops.setScreenCaptureDisabled(enabled)
            DeviceOp.STATUS_BAR -> ops.setStatusBarDisabled(enabled)
            DeviceOp.USB_TRANSFER -> ops.setUserRestriction(android.os.UserManager.DISALLOW_USB_FILE_TRANSFER, enabled)
            DeviceOp.SAFE_BOOT -> ops.setUserRestriction(android.os.UserManager.DISALLOW_SAFE_BOOT, enabled)
            DeviceOp.ADD_USER -> ops.setUserRestriction(android.os.UserManager.DISALLOW_ADD_USER, enabled)
            DeviceOp.RESET_PASSWORD -> ops.resetPasswordWithToken("123456")
        }
        val message = "${op.title}：${if (ok) (if (enabled) "已开启" else "已关闭") else "执行失败（系统拒绝或缺少设备所有者）"}"
        AppLog.event(if (ok) RiskLevel.L2 else RiskLevel.L3, "设备管理面板", message)
        return message
    }
}
