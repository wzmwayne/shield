package wzmwayne.shield.threat

import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.ShieldEvent

/** 威胁评估引擎的处置决策。 */
data class ThreatDecision(
    val level: RiskLevel,
    val actions: List<String>,
    val reason: String,
)

/**
 * 威胁评估引擎接口。本期只保留接口与默认实现（仅记录，不下发动作），
 * 规则化的评分与事件组合留给后续迭代。
 */
interface ThreatEngine {
    fun evaluate(event: ShieldEvent): ThreatDecision
}

object RecordingThreatEngine : ThreatEngine {
    override fun evaluate(event: ShieldEvent): ThreatDecision = ThreatDecision(
        level = RiskLevel.L1,
        actions = emptyList(),
        reason = "本期为最小原型：仅记录事件（${event.title}），不下发处置动作",
    )
}
