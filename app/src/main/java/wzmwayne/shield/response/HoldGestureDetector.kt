package wzmwayne.shield.response

import android.view.KeyEvent

/** 提示音型：READY=两短音（提示/注意），TRIGGERED=三长音递升（触发确认） */
enum class Prompt { READY, TRIGGERED }

/**
 * 全局阻断手势识别（纯逻辑，可单测）：
 *   **连续快速按音量键（上或下）3 次**即触发全局阻断（用户确认：长按在该机型上不生效，改为连点）。
 *
 * 判定：同一音量键 3 次 ACTION_DOWN，相邻两次间隔 ≤ [intervalMs]，整体 ≤ 3 秒。
 */
class HoldGestureDetector(
    private val pressCount: Int = 3,
    private val intervalMs: Long = 700L,
) {
    data class State(
        val count: Int = 0,
        val lastKey: Int = 0,
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

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    /** 每次按下为一次计数（无障碍 onKeyEvent 的 ACTION_DOWN，含系统按键重复要去重） */
    fun onPress(keyCode: Int, now: Long, state: State): Result {
        if (!isVolumeKey(keyCode)) return Result(state)

        val continued = state.lastKey == keyCode && now - state.lastAt <= intervalMs
        val count = if (continued) state.count + 1 else 1
        val next = State(count = count, lastKey = keyCode, lastAt = now)

        if (count >= pressCount) {
            return Result(
                state = State.EMPTY,
                feedback = "已触发全局阻断（连按音量键 ${pressCount} 次）",
                prompt = Prompt.TRIGGERED,
                triggerGlobal = true,
            )
        }
        return Result(state = next)
    }

    /** 兼容旧调用：把 ACTION_UP 当作无操作 */
    fun onRelease(keyCode: Int, state: State): Result = Result(state)
}
