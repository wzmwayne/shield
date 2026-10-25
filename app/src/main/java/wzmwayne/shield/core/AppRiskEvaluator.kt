package wzmwayne.shield.core

/**
 * 应用风险评分（纯逻辑，可单测）。
 * 分数越高越可疑；排序时分数降序。
 */
object AppRiskEvaluator {

    data class Flags(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val trusted: Boolean,
        val deviceAdmin: Boolean,
        val accessibilityServiceDeclared: Boolean,
        val accessibilityEnabled: Boolean,
        val overlayGranted: Boolean,
        val installPackagesGranted: Boolean,
        val hidden: Boolean,
        val suspended: Boolean,
    )

    data class Score(
        val value: Int,
        val tags: List<String>,
    )

    fun evaluate(flags: Flags): Score {
        var score = 0
        val tags = mutableListOf<String>()

        if (!flags.trusted) {
            score += 30
            tags += "非白名单"
        } else {
            score -= 20
            tags += "白名单"
        }

        if (flags.deviceAdmin) {
            score += 25
            tags += "设备管理器"
        }
        if (flags.accessibilityEnabled) {
            score += 25
            tags += "无障碍(启用)"
        } else if (flags.accessibilityServiceDeclared) {
            score += 15
            tags += "无障碍(声明)"
        }
        if (flags.overlayGranted) {
            score += 15
            tags += "悬浮窗"
        }
        if (flags.installPackagesGranted) {
            score += 10
            tags += "装应用"
        }
        if (flags.hidden) {
            score += 5
            tags += "已隐藏"
        }
        if (flags.suspended) {
            score += 5
            tags += "已暂停"
        }
        if (flags.isSystem) {
            score -= 30
            tags += "系统"
        }

        return Score(score.coerceIn(-100, 200), tags)
    }
}
