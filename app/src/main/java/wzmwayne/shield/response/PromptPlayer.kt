package wzmwayne.shield.response

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.media.ToneGenerator
import android.media.AudioManager
import wzmwayne.shield.R
import wzmwayne.shield.core.AppLog

/**
 * 固定提示音型播放器（内置方波音频，零第三方依赖）。
 *
 *  就绪   = 两短音（880Hz ×2）            → 敏感页看护生效（无障碍已收紧）
 *  触发   = 三长音递升（660/784/988Hz）    → 全局阻断被触发（长按音量键 3 秒）
 *  告警   = 一短一长（1046Hz）             → 覆盖层哨兵命中 / 看护恢复
 *
 * 走**闹钟音频属性**：媒体音量被静音时依然能出声；SoundPool 不可用时回退 ToneGenerator 蜂鸣。
 */
object PromptPlayer {

    private var soundPool: SoundPool? = null
    private var readyId = 0
    private var triggerId = 0
    private var alertId = 0
    private var tone: ToneGenerator? = null

    fun init(context: Context) {
        if (soundPool != null) return
        runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(attributes)
                .build()
            readyId = pool.load(context.applicationContext, R.raw.shield_ready, 1)
            triggerId = pool.load(context.applicationContext, R.raw.shield_trigger, 1)
            alertId = pool.load(context.applicationContext, R.raw.shield_alert, 1)
            soundPool = pool
        }.onFailure { AppLog.w("SoundPool 初始化失败，改用蜂鸣：${it.message}") }
    }

    /** 敏感页看护生效：两短音 */
    fun ready() = play(readyId)

    /** 触发全局阻断（长按音量键 3 秒）：三长音递升 */
    fun triggered() = play(triggerId)

    /** 覆盖层哨兵命中 / 看护恢复：一短一长 */
    fun alert() = play(alertId)

    private fun play(soundId: Int) {
        val pool = soundPool
        if (pool == null || soundId == 0) {
            beep()
            return
        }
        val streamId = runCatching { pool.play(soundId, 1f, 1f, 1, 0, 1f) }.getOrDefault(0)
        if (streamId == 0) beep()
    }

    fun beep() {
        runCatching {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, 95).also { tone = it }
            generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
        }
    }

    fun release() {
        runCatching { soundPool?.release() }
        soundPool = null
        readyId = 0
        triggerId = 0
        alertId = 0
        runCatching { tone?.release() }
        tone = null
    }
}
