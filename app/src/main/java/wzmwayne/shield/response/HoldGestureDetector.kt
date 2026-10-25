package wzmwayne.shield.response

import android.view.KeyEvent

/** 提示音型：READY=阶段提示（两短音），TRIGGERED=触发确认（三长音递升） */
enum class Prompt { READY, TRIGGERED }

/**
 * 全局阻断手势（纯逻辑，可单测）：
 *   **5 秒内「下 上 下 上 下 上 下 上 下 上」交替 5 轮**，全部做完才触发。
 *
 * 设计要点：
 * - 只认按键事件（不做音量变化兜底，避免正常调节音量误触）
 * - 必须以下开头，方向必须严格交替
 * - 从第一步到最后一步总时长 ≤ [windowMs]，否则从头再来
 */
class HoldGestureDetector(
    private val rounds: Int = 5,
    private val windowMs: Long = 5_000L,
) {
    data class State(
        val steps: Int = 0,
        val startedAt: Long = 0L,
        val lastAt: Long = 0L,
    ) {
        companion object {
            val EMPTY = State()
        }
    }

    data class Result(
        val state: State,
        val feedback: String? = null,
        val prompt: Prompt? = null,
        val triggerGlobal: Boolean = false,
    )

    private fun directionOf(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> DOWN
        KeyEvent.KEYCODE_VOLUME_UP -> UP
        else -> 0
    }

    /**
     * 一步：direction 用 -1（音量下）/ +1（音量上）。
     * 期望方向：偶数步（0,2,4…）为下，奇数步为上。
     */
    fun onStep(direction: Int, now: Long, state: State): Result {
        if (direction != DOWN && direction != UP) return Result(state)

        val expired = state.startedAt != 0L && now - state.startedAt > windowMs
        val expected = if (!expired && state.steps % 2 == 0) DOWN else UP

        // 超时 / 方向不对：重新开始；只有"下"能作为新序列的起点
        if (expired || direction != expected) {
            return if (direction == DOWN) {
                Result(State(steps = 1, startedAt = now, lastAt = now))
            } else {
                Result(State.EMPTY)
            }
        }

        val steps = state.steps + 1
        val total = rounds * 2
        if (steps >= total) {
            return Result(
                state = State.EMPTY,
                feedback = "已触发全局阻断（5 秒内上下交替 $rounds 轮）",
                prompt = Prompt.TRIGGERED,
                triggerGlobal = true,
            )
        }
        val next = State(steps = steps, startedAt = if (state.startedAt == 0L) now else state.startedAt, lastAt = now)
        // 完成第一轮（下+上）给一次提示音，避免用户不确定是否在识别
        val feedback = if (steps == 2) "已识别 1 轮，请继续上下交替" else null
        return Result(state = next, feedback = feedback, prompt = if (steps == 2) Prompt.READY else null)
    }

    fun onPress(keyCode: Int, now: Long, state: State): Result =
        onStep(directionOf(keyCode), now, state)

    companion object {
        const val DOWN = -1
        const val UP = 1
    }
}
