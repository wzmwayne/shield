package wzmwayne.shield.response

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import wzmwayne.shield.core.Prefs

/**
 * 覆盖层哨兵：非白名单（或无障碍创建的）覆盖层只要占到屏幕约 70% 就直接判锁机嫌疑，
 * 命中即对来源包做**针对性阻断**（隐藏+挂起+撤权），不做全局处置。
 */
object OverlaySentinel {

    /** 判定阈值：覆盖率 ≥ 70% 即命中 */
    const val COVERAGE_THRESHOLD = 0.70f

    /**
     * 纯计算：窗口覆盖率（可单测）。
     * 入参用 int 而不是 Rect —— android.graphics.Rect 在 JVM 单测里是空实现，拿不到真实坐标。
     */
    fun coverageRatio(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Float {
        if (screenWidth <= 0 || screenHeight <= 0) return 0f
        val visibleLeft = left.coerceAtLeast(0)
        val visibleTop = top.coerceAtLeast(0)
        val visibleRight = right.coerceAtMost(screenWidth)
        val visibleBottom = bottom.coerceAtMost(screenHeight)
        val visibleW = (visibleRight - visibleLeft).coerceAtLeast(0)
        val visibleH = (visibleBottom - visibleTop).coerceAtLeast(0)
        val area = visibleW.toLong() * visibleH.toLong()
        val screen = screenWidth.toLong() * screenHeight.toLong()
        return if (screen == 0L) 0f else area.toFloat() / screen.toFloat()
    }

    fun coverageRatio(windowBounds: Rect, screenWidth: Int, screenHeight: Int): Float =
        coverageRatio(windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom, screenWidth, screenHeight)

    /**
     * 扫描当前窗口：返回命中"锁机嫌疑"的包名。
     * 只把「覆盖层类窗口」（TYPE_SYSTEM / TYPE_ACCESSIBILITY_OVERLAY）视为覆盖层；
     * 普通应用窗口（TYPE_APPLICATION）与输入法不计。
     */
    fun scan(service: AccessibilityService): String? {
        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val trusted = Prefs.trustedPackages.value + service.packageName
        val bounds = Rect()

        val windows = runCatching { service.windows }.getOrNull() ?: return null
        for (window in windows) {
            val type = window.type
            val isOverlay = type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            if (!isOverlay) continue

            val root = runCatching { window.root }.getOrNull() ?: continue
            val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: continue
            if (pkg == service.packageName) continue
            if (pkg == "com.android.systemui") continue
            if (trusted.contains(pkg)) continue

            window.getBoundsInScreen(bounds)
            if (coverageRatio(bounds, screenW, screenH) >= COVERAGE_THRESHOLD) {
                return pkg
            }
        }
        return null
    }
}
