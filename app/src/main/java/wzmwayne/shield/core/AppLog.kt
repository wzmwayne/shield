package wzmwayne.shield.core

import android.util.Log

object AppLog {
    private const val TAG = "Shield"

    fun d(message: String) = Log.d(TAG, message)

    fun i(message: String) = Log.i(TAG, message)

    fun w(message: String) = Log.w(TAG, message)

    fun e(message: String, throwable: Throwable? = null) = Log.e(TAG, message, throwable)

    /** 记录一条用户可见事件（同时写日志与事件列表） */
    fun event(level: RiskLevel, title: String, detail: String = "") {
        Log.i(TAG, "[${level.name}] $title ${detail.take(200)}")
        Prefs.recordEvent(level, title, detail)
    }
}
