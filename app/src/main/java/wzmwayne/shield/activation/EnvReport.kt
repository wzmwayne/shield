package wzmwayne.shield.activation

import wzmwayne.shield.core.CheckItem
import wzmwayne.shield.core.CheckState

/** 激活向导每一步的执行结果。 */
data class StepOutcome(
    val ok: Boolean,
    val message: String,
    val detail: String = "",
) {
    companion object {
        fun ok(message: String, detail: String = "") = StepOutcome(true, message, detail)

        fun fail(message: String, detail: String = "") = StepOutcome(false, message, detail)
    }
}

/**
 * 环境检查报告：**只回答「能否进入激活步骤」**。
 * 因此这里只包含设备所有者激活的硬性前提 + Shizuku 通道可用性，
 * 不做 WRITE_SECURE_SETTINGS / 无障碍服务等激活后才有意义的检查（那些属于第 6 步自检）。
 */
data class EnvReport(
    val items: List<CheckItem>,
    val shizukuInstalled: Boolean = false,
    val shizukuAlive: Boolean = false,
    val shizukuGranted: Boolean = false,
    val userCount: Int = 0,
    val secondaryUser: Boolean = false,
    val accountCount: Int = 0,
    val ownerKnown: Boolean = false,
    val deviceOwner: String? = null,
    val shellAvailable: Boolean = false,
) {
    val blockers: List<CheckItem> get() = items.filter { it.state == CheckState.BLOCK }

    /** 数据没取到的项（例如 Shizuku 通道不可用），同样不能算通过 */
    val unknowns: List<CheckItem> get() = items.filter { it.state == CheckState.UNKNOWN }

    /** 是否可以进入激活步骤：所有前置项都必须明确通过 */
    val ready: Boolean get() = blockers.isEmpty() && unknowns.isEmpty()

    val canActivate: Boolean get() = ready && shellAvailable
}
