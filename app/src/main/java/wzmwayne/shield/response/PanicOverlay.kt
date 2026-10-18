package wzmwayne.shield.response

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import wzmwayne.shield.core.AppFilter
import wzmwayne.shield.core.AppLog
import java.text.Collator

/** 覆盖层里可执行的处置动作 */
enum class PanicAction {
    CLEAR_DATA,
    REDISABLE,
    RESTORE,
    UNINSTALL,
    /** 停止应用（暂停 = 无法运行/自启动/悬浮，可恢复） */
    STOP_APP,
    /** 撤销所有运行时权限（不动无障碍白名单） */
    REVOKE_ALL,
    /** 打开系统「应用详情」页（受系统限制，某些场景打不开） */
    OPEN_DETAILS,
    CANCEL_ALL,
}

/** 覆盖层列表项：应用名 + 包名 + 勾选态 + 是否"自动处置失败且仍在运行" */
data class OverlayApp(
    val packageName: String,
    val label: String,
    val checked: Boolean = false,
    val failed: Boolean = false,
    val stillActive: Boolean = false,
    /** 是否拥有高权限（无障碍 / 设备管理器 / 悬浮窗 / 安装未知应用） */
    val highPrivilege: Boolean = false,
    /** 是否在保护白名单里 */
    val trusted: Boolean = false,
) {
    val pinned: Boolean get() = failed && stillActive

    /**
     * 排序分级（用户指定）：
     * 0 阻断失败 → 1 有高权限 → 2 其他非白名单 → 3 白名单应用
     */
    val tier: Int
        get() = when {
            pinned -> 0
            highPrivilege -> 1
            trusted -> 3
            else -> 2
        }
}

/**
 * 紧急处置覆盖层（TYPE_ACCESSIBILITY_OVERLAY）。
 *
 *  · 处置过程**流式显示**；
 *  · 列表默认只看非系统应用，可用单选切换 用户/系统/所有；带**刷新**按钮重新获取（异步）；
 *  · 自动处置失败且仍在运行的应用**顶置红标**；
 *  · 列表下方按钮组：清理数据 / 再次禁用 / 恢复 / 卸载选中 / 取消处置并恢复全部；
 *  · 融合组件：全部自绘 + 原生 onTouchEvent，不注册可点击无障碍节点（其它无障碍无法模拟点击）；
 *  · 每 3 秒重新注册一次视图，尽量保持最上层。
 */
object PanicOverlay {

    private const val RE_REGISTER_MS = 3_000L
    private const val MAX_STREAM_LINES = 6

    private val handler = Handler(Looper.getMainLooper())
    private var view: PanicOverlayView? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    var filter: AppFilter = AppFilter.USER
        private set

    private val reRegister = object : Runnable {
        override fun run() {
            reassertTop()
            handler.postDelayed(this, RE_REGISTER_MS)
        }
    }

    fun show(
        service: AccessibilityService,
        onAction: (PanicAction, List<String>) -> Unit,
        onFilterChange: (AppFilter) -> Unit,
        onRefresh: () -> Unit,
        onClose: () -> Unit,
    ) {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        if (view == null) {
            val newView = PanicOverlayView(service)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            view = newView
            layoutParams = params
            windowManager = wm
            runCatching { wm.addView(newView, params) }.onFailure { AppLog.w("覆盖层添加失败：${it.message}") }
            handler.removeCallbacks(reRegister)
            handler.postDelayed(reRegister, RE_REGISTER_MS)
        }
        view?.apply {
            this.onAction = onAction
            this.filter = PanicOverlay.filter
            this.onFilterChange = { picked ->
                PanicOverlay.filter = picked
                onFilterChange(picked)
            }
            this.onRefresh = onRefresh
            this.onClose = onClose
            invalidate()
        }
    }

    fun setTitle(title: String) = onMain {
        view?.overlayTitle = title
        view?.invalidate()
    }

    fun setPhase(phase: String) = onMain {
        view?.phase = phase
        view?.invalidate()
    }

    fun setLines(lines: List<String>) = onMain {
        view?.lines = lines.takeLast(MAX_STREAM_LINES)
        view?.invalidate()
    }

    fun appendLine(line: String) = onMain {
        val current = view ?: return@onMain
        current.lines = (current.lines + line).takeLast(MAX_STREAM_LINES)
        current.invalidate()
    }

    fun setApps(apps: List<OverlayApp>) = onMain {
        val sorted = apps.sortedWith(
            compareBy<OverlayApp> { it.tier }
                .thenBy(Collator.getInstance()) { it.label },
        )
        view?.apps = sorted
        view?.invalidate()
    }

    /** 处置日志（点击「处置日志」按钮时全量展示，处置结束后仍可查看） */
    fun setLog(lines: List<String>) = onMain {
        view?.logLines = lines
        view?.invalidate()
    }

    fun setBusy(busy: Boolean) = onMain {
        view?.busy = busy
        view?.invalidate()
    }

    fun hide() {
        handler.removeCallbacks(reRegister)
        val v = view ?: return
        val wm = windowManager
        view = null
        windowManager = null
        layoutParams = null
        wm?.let { runCatching { it.removeViewImmediate(v) } }
    }

    fun isShowing(): Boolean = view != null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun reassertTop() {
        val v = view ?: return
        val wm = windowManager ?: return
        val params = layoutParams ?: return
        runCatching {
            wm.removeViewImmediate(v)
            wm.addView(v, params)
        }.onFailure { AppLog.d("覆盖层重新注册失败（忽略）：${it.message}") }
    }
}

/** 融合式覆盖层视图：全部自绘，触摸自己处理 */
class PanicOverlayView(context: Context) : View(context) {

    var overlayTitle: String = "锁盾 · 紧急处置"
    var phase: String = "处置中…"
    var lines: List<String> = emptyList()
    var apps: List<OverlayApp> = emptyList()
    var filter: AppFilter = AppFilter.USER
    var busy: Boolean = false
    var onAction: ((PanicAction, List<String>) -> Unit)? = null
    var onFilterChange: ((AppFilter) -> Unit)? = null
    var onRefresh: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var logLines: List<String> = emptyList()
    private var logMode = false

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        isFakeBoldText = true
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        textSize = 30f
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
    }
    private val pinPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5555.toInt()
        textSize = 30f
        isFakeBoldText = true
    }
    private val optionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    private val optionActivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF2A2A2A.toInt()
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF9A9A9A.toInt()
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val optionActivePaintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val optionIdlePaintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() }
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt() }
    private val buttonText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    private val dangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val dangerText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val buttonRects = LinkedHashMap<PanicAction, RectF>()
    private val filterRects = LinkedHashMap<AppFilter, RectF>()
    private val refreshRect = RectF()
    private val closeRect = RectF()
    private val logButtonRect = RectF()
    private val backRect = RectF()
    private var pressedLog = false
    private var pressedBack = false
    private var pressedClose = false
    private val checkboxRects = LinkedHashMap<String, RectF>()
    private var pressedButton: PanicAction? = null
    private var pressedFilter: AppFilter? = null
    private var pressedRefresh = false

    private var streamTop = 0f
    private var filterTop = 0f
    private var listTop = 0f
    private var listBottom = 0f
    private var scrollY = 0f
    private var contentHeight = 0f
    private var touchStartY = 0f
    private var dragging = false

    private val rowHeight = 104f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        filterTouchesWhenObscured = true
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
    }

    private fun streamHeight(): Float = lines.size.coerceAtLeast(1) * 38f + 24f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val header = 104f + streamHeight() + 92f
        val list = apps.size.coerceAtMost(6) * rowHeight + 16f
        setMeasuredDimension(width, (header + list + 250f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        streamTop = 96f
        filterTop = streamTop + streamHeight()
        listTop = filterTop + 92f
        val buttonsHeight = 350f
        listBottom = h - buttonsHeight
        contentHeight = apps.size * rowHeight

        // 筛选单选 + 刷新
        filterRects.clear()
        val margin = 24f
        val optionWidth = (w - margin * 2 - 24f - 150f) / 3f
        val order = listOf(AppFilter.USER, AppFilter.SYSTEM, AppFilter.ALL)
        order.forEachIndexed { index, f ->
            val left = margin + index * (optionWidth + 12f)
            filterRects[f] = RectF(left, filterTop + 12f, left + optionWidth, filterTop + 72f)
        }
        refreshRect.set(w - margin - 138f, filterTop + 12f, w - margin, filterTop + 72f)
        closeRect.set(w - margin - 108f, 20f, w - margin, 74f)
        logButtonRect.set(w - margin - 236f, 20f, w - margin - 120f, 74f)
        backRect.set(margin, listBottom + 20f, margin + (w - margin * 2 - 16f) / 3f, listBottom + 108f)

        // 处置按钮
        buttonRects.clear()
        val gap = 16f
        val btnHeight = 88f
        val totalWidth = w - margin * 2
        val btnWidth = (totalWidth - gap * 2) / 3f
        listOf(PanicAction.CLEAR_DATA, PanicAction.REDISABLE, PanicAction.RESTORE).forEachIndexed { index, action ->
            val left = margin + index * (btnWidth + gap)
            buttonRects[action] = RectF(left, listBottom + 20f, left + btnWidth, listBottom + 20f + btnHeight)
        }
        val secondRowTop = listBottom + 20f + btnHeight + 12f
        buttonRects[PanicAction.UNINSTALL] = RectF(margin, secondRowTop, margin + btnWidth, secondRowTop + btnHeight)
        buttonRects[PanicAction.CANCEL_ALL] =
            RectF(margin + btnWidth + gap, secondRowTop, w - margin, secondRowTop + btnHeight)
        val thirdRowTop = secondRowTop + btnHeight + 12f
        buttonRects[PanicAction.STOP_APP] =
            RectF(margin, thirdRowTop, margin + btnWidth, thirdRowTop + btnHeight)
        buttonRects[PanicAction.REVOKE_ALL] =
            RectF(margin + btnWidth + gap, thirdRowTop, margin + btnWidth * 2 + gap, thirdRowTop + btnHeight)
        buttonRects[PanicAction.OPEN_DETAILS] =
            RectF(margin + (btnWidth + gap) * 2, thirdRowTop, w - margin, thirdRowTop + btnHeight)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, borderPaint)

        canvas.drawText(overlayTitle, 24f, 60f, titlePaint)
        // 右上角「关闭」：仅收起覆盖层，不改变已执行的处置
        canvas.drawRoundRect(closeRect, 8f, 8f, if (pressedClose) buttonPressedPaint else buttonPaint)
        val closeTextY = closeRect.centerY() - (optionPaint.descent() + optionPaint.ascent()) / 2f
        canvas.drawText("关闭", closeRect.centerX(), closeTextY, optionPaint)

        if (logMode) {
            // 日志模式：全量展示处置期间做了什么（可滚动）
            val logPaint = TextPaint(bodyPaint).apply { textSize = 26f }
            canvas.save()
            canvas.clipRect(0f, listTop, width.toFloat(), listBottom)
            logLines.forEachIndexed { index, line ->
                val rowTop = listTop - scrollY + index * 34f
                if (rowTop + 34f < listTop || rowTop > listBottom) return@forEachIndexed
                canvas.drawText(line.take(64), 24f, rowTop + 26f, logPaint)
            }
            canvas.restore()
            if (logLines.isEmpty()) {
                canvas.drawText("暂无处置日志", 24f, listTop + 30f, bodyPaint)
            }
            val backPaint = if (pressedBack) buttonPressedPaint else buttonPaint
            canvas.drawRoundRect(backRect, 8f, 8f, backPaint)
            val backTextY = backRect.centerY() - (buttonText.descent() + buttonText.ascent()) / 2f
            canvas.drawText("返回列表", backRect.centerX(), backTextY, buttonText)
            return
        }

        // 右上角偏左：「处置日志」入口（处置完成后仍可查看）
        canvas.drawRoundRect(logButtonRect, 8f, 8f, if (pressedLog) buttonPressedPaint else buttonPaint)
        canvas.drawText("处置日志", logButtonRect.centerX(), closeTextY, optionPaint)
        canvas.drawText(phase, 24f + titlePaint.measureText(overlayTitle) + 20f, 60f, bodyPaint)

        var y = streamTop + 30f
        lines.forEach { line ->
            canvas.drawText(line.take(58), 24f, y, bodyPaint)
            y += 38f
        }

        // 筛选单选 + 刷新
        filterRects.forEach { (f, rect) ->
            val active = f == filter
            canvas.drawRoundRect(rect, 8f, 8f, if (active) optionActivePaintFill else optionIdlePaintFill)
            val label = when (f) {
                AppFilter.USER -> "用户"
                AppFilter.SYSTEM -> "系统"
                AppFilter.ALL -> "所有"
            }
            val textY = rect.centerY() - (optionPaint.descent() + optionPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), textY, if (active) optionActivePaint else optionPaint)
        }
        canvas.drawRoundRect(refreshRect, 8f, 8f, if (pressedRefresh) buttonPressedPaint else optionIdlePaintFill)
        val refreshLabel = if (busy) "刷新中…" else "刷新"
        val refreshTextY = refreshRect.centerY() - (optionPaint.descent() + optionPaint.ascent()) / 2f
        canvas.drawText(refreshLabel, refreshRect.centerX(), refreshTextY, optionPaint)

        canvas.drawLine(24f, filterTop + 84f, width - 24f, filterTop + 84f, borderPaint)
        canvas.drawText("共 ${apps.size} 项（默认只看非系统应用）", 24f, filterTop + 112f - 92f + 84f, bodyPaint)

        canvas.save()
        canvas.clipRect(0f, listTop, width.toFloat(), listBottom)
        checkboxRects.clear()
        apps.forEachIndexed { index, app ->
            val rowTop = listTop - scrollY + index * rowHeight
            if (rowTop + rowHeight < listTop || rowTop > listBottom) return@forEachIndexed

            val box = RectF(28f, rowTop + 22f, 76f, rowTop + 70f)
            checkboxRects[app.packageName] = box
            if (app.checked) {
                canvas.drawRect(box, boxFill)
                canvas.drawRect(box, boxPaint)
            } else {
                canvas.drawRect(box, boxPaint)
            }

            canvas.drawText(app.label.take(28), 96f, rowTop + 52f, if (app.pinned) pinPaint else labelPaint)

            // 多标签：·阻断失败 ·高权限 ·白名单 …
            val tags = buildList {
                if (app.pinned) add("阻断失败" to 0xFFFF5555.toInt())
                if (app.highPrivilege) add("高权限" to 0xFFFFC107.toInt())
                if (app.trusted) add("白名单" to 0xFF66BB6A.toInt())
            }
            var tagRight = width - 24f
            tags.asReversed().forEach { (text, color) ->
                val tagPaint = TextPaint(optionPaint).apply {
                    textSize = 24f
                    textAlign = Paint.Align.RIGHT
                    this.color = color
                }
                canvas.drawText("· $text", tagRight, rowTop + 52f, tagPaint)
                tagRight -= tagPaint.measureText("· $text") + 12f
            }
            canvas.drawText(app.packageName.take(52), 96f, rowTop + 88f, bodyPaint)
            if (app.pinned) {
                canvas.drawText("自动处置失败 · 仍在运行", 96f, rowTop + 118f, pinPaint)
            }
            canvas.drawLine(24f, rowTop + rowHeight - 2f, width - 24f, rowTop + rowHeight - 2f, borderPaint)
        }
        canvas.restore()

        buttonRects.forEach { (action, rect) ->
            val paint = when {
                action == PanicAction.UNINSTALL -> dangerPaint
                pressedButton == action -> buttonPressedPaint
                else -> buttonPaint
            }
            canvas.drawRoundRect(rect, 8f, 8f, paint)
            val textPaint = if (action == PanicAction.UNINSTALL) dangerText else buttonText
            val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(actionLabel(action), rect.centerX(), textY, textPaint)
        }
    }

    private fun actionLabel(action: PanicAction): String = when (action) {
        PanicAction.CLEAR_DATA -> "清理数据"
        PanicAction.REDISABLE -> "再次禁用"
        PanicAction.RESTORE -> "恢复"
        PanicAction.UNINSTALL -> "卸载选中"
        PanicAction.STOP_APP -> "停止应用"
        PanicAction.REVOKE_ALL -> "撤销全部权限"
        PanicAction.OPEN_DETAILS -> "打开应用详情"
        PanicAction.CANCEL_ALL -> "取消处置并恢复全部"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                dragging = false
                if (event.y > listTop && event.y < listBottom) return true
                pressedFilter = filterRects.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
                pressedRefresh = refreshRect.contains(event.x, event.y)
                pressedClose = closeRect.contains(event.x, event.y)
                pressedLog = logButtonRect.contains(event.x, event.y)
                pressedBack = logMode && backRect.contains(event.x, event.y)
                if (logMode) return true
                pressedButton = buttonRects.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.y > listTop && event.y < listBottom) {
                    if (kotlin.math.abs(event.y - touchStartY) > 12f) dragging = true
                    if (dragging) {
                        val total = if (logMode) logLines.size * 34f else contentHeight
                        val maxScroll = (total - (listBottom - listTop)).coerceAtLeast(0f)
                        scrollY = (scrollY - (event.y - touchStartY)).coerceIn(0f, maxScroll)
                        touchStartY = event.y
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (logMode) {
                    if (pressedBack && backRect.contains(event.x, event.y)) {
                        logMode = false
                        scrollY = 0f
                    }
                    if (pressedClose && closeRect.contains(event.x, event.y)) onClose?.invoke()
                    pressedBack = false
                    pressedClose = false
                    dragging = false
                    invalidate()
                    return true
                }
                if (pressedLog && logButtonRect.contains(event.x, event.y)) {
                    logMode = true
                    scrollY = 0f
                    pressedLog = false
                    invalidate()
                    return true
                }
                val inList = event.y > listTop && event.y < listBottom
                if (inList && !dragging) {
                    checkboxRects.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key?.let { toggle(it) }
                }
                pressedFilter?.let { f ->
                    if (filterRects[f]?.contains(event.x, event.y) == true && f != filter) {
                        filter = f
                        scrollY = 0f
                        onFilterChange?.invoke(f)
                    }
                }
                if (pressedRefresh && refreshRect.contains(event.x, event.y)) onRefresh?.invoke()
                if (pressedClose && closeRect.contains(event.x, event.y)) onClose?.invoke()
                pressedButton?.let { action ->
                    if (buttonRects[action]?.contains(event.x, event.y) == true) {
                        onAction?.invoke(action, apps.filter { it.checked }.map { it.packageName })
                    }
                }
                pressedButton = null
                pressedFilter = null
                pressedRefresh = false
                pressedClose = false
                dragging = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedButton = null
                pressedFilter = null
                pressedRefresh = false
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun toggle(packageName: String) {
        apps = apps.map { if (it.packageName == packageName) it.copy(checked = !it.checked) else it }
        invalidate()
    }
}
