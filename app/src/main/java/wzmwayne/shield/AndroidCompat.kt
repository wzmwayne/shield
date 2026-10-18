package wzmwayne.shield

/** 少量跨模块常量（避免 UI/调试接口直接依赖 BuildConfig） */
object AndroidCompat {
    const val VERSION_LABEL = "0.1.0"

    /** 每次交付手动更新，便于通过 /status 确认装机版本 */
    const val BUILD_TAG = "2026-10-18-manual-stop-revoke-all-details"
}
