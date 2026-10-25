package wzmwayne.shield.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Constants
import wzmwayne.shield.core.Policies
import wzmwayne.shield.core.Policy
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.keepalive.KeepAliveEngine
import wzmwayne.shield.response.HoldGestureDetector
import wzmwayne.shield.response.HomeSentinel
import wzmwayne.shield.response.OverlaySentinel
import wzmwayne.shield.response.PanicAction
import wzmwayne.shield.response.PanicOverlay
import wzmwayne.shield.response.PanicResponse
import wzmwayne.shield.response.SensitiveGuard
import wzmwayne.shield.response.Prompt
import wzmwayne.shield.response.PromptPlayer

/**
 * 感知层 + 保活主逻辑宿主 + 自动响应执行者。
 *
 * 分工：
 *  · 保活主逻辑节拍（10 秒）：自检 + 拉起前台服务；
 *  · 感知：前台应用、点击/窗口事件、敏感页看护、覆盖层哨兵；
 *  · 自动响应（针对性，不做全局）：① 敏感页 → 收紧无障碍（退出敏感页后恢复）；② 覆盖层 ≥70% → 针对性阻断；
 *  · 全局响应（唯一入口）：连按音量键（上或下）3 次 → 三长音递升 + 覆盖层弹出 → 触发全局阻断。
 */
class ShieldAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private val heartbeat = object : Runnable {
        override fun run() {
            Prefs.setHeartbeat(System.currentTimeMillis())
            handler.postDelayed(this, Constants.HEARTBEAT_INTERVAL_MS)
        }
    }

    /** 保活主逻辑节拍（10 秒）：自检 + 拉起组件 + 覆盖层巡检 */
    private val logicTick = object : Runnable {
        override fun run() {
            Prefs.setHeartbeat(System.currentTimeMillis())
            KeepAliveEngine.evaluate(this@ShieldAccessibilityService, "无障碍保活自检")
            KeepAliveEngine.ensureServiceRunning(this@ShieldAccessibilityService, "无障碍保活自检")
            checkOverlayCoverage()
            // 阻断生效期间：持续撤销新出现的设备管理器（应用可自行申请，必须反复清）
            if (Policies.sensing(Policy.HOME_SENTINEL)) {
                HomeSentinel.check(this@ShieldAccessibilityService)?.let { detail ->
                    Toast.makeText(this@ShieldAccessibilityService, "默认桌面守护：$detail", Toast.LENGTH_LONG).show()
                }
            }
            handler.postDelayed(this, Constants.LOCAL_CHECK_INTERVAL_SECONDS * 1_000L)
        }
    }

    private val holdDetector = HoldGestureDetector()
    private var holdState = HoldGestureDetector.State.EMPTY

    private var lastOverlayScanAt = 0L
    private var sensitiveRestoreScheduled = false
    private val sensitiveRestore = Runnable {
        sensitiveRestoreScheduled = false
        val summary = SensitiveGuard.restore(this)
        // 恢复后必须给用户明确反馈（Toast + 提示音）
        Toast.makeText(this, "敏感页看护已恢复：$summary", Toast.LENGTH_SHORT).show()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        Prefs.setServiceConnected(true)
        Prefs.setHeartbeat(System.currentTimeMillis())
        PromptPlayer.init(this)
        instance = this

        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, Constants.HEARTBEAT_INTERVAL_MS)
        handler.removeCallbacks(logicTick)
        handler.post(logicTick)

        AppLog.event(RiskLevel.L1, "无障碍服务已连接", "感知层、保活主逻辑与自动响应已就绪")
        if (Prefs.keepAliveEnabled.value) {
            KeepAliveEngine.ensureServiceRunning(this, "无障碍服务就绪")
        }
        Prefs.migrateLegacyPanicLog()
        // 调试接口：开关开着就随服务自动启动（固定 0.0.0.0）
        if (Prefs.debugHttpEnabled.value) {
            kotlin.concurrent.thread(isDaemon = true) {
                runCatching { wzmwayne.shield.debug.DebugHttpServer.start(this) }
            }
        }
        // 「锁定调试」策略：服务启动时重新锁定（常驻生效）
        if (Policies.isOn(Policy.LOCK_DEBUG)) {
            kotlin.concurrent.thread(isDaemon = true) {
                runCatching { wzmwayne.shield.activation.DebugControl.block(this) }
            }
        }
        // 处置仍生效（例如服务被系统重启）时重新挂上覆盖层
        if (Prefs.panicAt.value > 0) showPanicOverlay()
    }

    /**
     * 全局阻断手势：连按音量键（上或下）3 次 → 三长音递升 + 覆盖层 → 执行全局处置。
     * 返回 false：不拦截系统原本的音量行为。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        // 用户确认：长按在该机型不生效 → 改为「连按音量键 3 次」，用无障碍 onKeyEvent 计数
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val result = holdDetector.onPress(event.keyCode, now, holdState)
        holdState = result.state
        result.prompt?.let { prompt ->
            when (prompt) {
                Prompt.READY -> PromptPlayer.ready()
                Prompt.TRIGGERED -> PromptPlayer.triggered()
            }
        }
        result.feedback?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }

        if (result.triggerGlobal) {
            triggerGlobalBlock("手势：连按音量键 3 次")
        }
        return false
    }

    /** 全局阻断的统一入口（按键通道与音量变化兜底通道共用） */
    private fun triggerGlobalBlock(reason: String) {
        PanicOverlay.setTitle("锁盾 · 紧急处置（用户触发）")
        PanicOverlay.setLines(emptyList())
        PanicOverlay.setPhase("全局阻断中…")
        showPanicOverlay()
        val summary = PanicResponse.trigger(this, reason)
        PanicOverlay.setPhase("全局阻断完成（可勾选应用继续手动处置）")
        reloadOverlayApps()
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        Prefs.setHeartbeat(System.currentTimeMillis())
        // 感知层总开关关闭：不采集、不判定、不提示（保活节拍不受影响）
        if (!Policies.isSensingEnabled()) {
            if (SensitiveGuard.isLockedDown()) {
                cancelSensitiveRestore()
                SensitiveGuard.restore(this)
            }
            return
        }
        EventRouter.onEvent(safeEvent)

        if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            wzmwayne.shield.debug.PageRecorder.onWindow(
                safeEvent.packageName?.toString(),
                safeEvent.className?.toString(),
            )
        }
        if (safeEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = safeEvent.packageName?.toString()
        val className = safeEvent.className?.toString()

        // 敏感页看护：进入敏感页收紧无障碍；离开敏感页后恢复
        if (SensitiveGuard.isSensitive(packageName, className)) {
            cancelSensitiveRestore()
            SensitiveGuard.onSensitivePage(this, packageName, className)?.let {
                // 敏感页看护只提示文字，不发声
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        } else if (SensitiveGuard.isLockedDown()) {
            scheduleSensitiveRestore()
        }

        checkOverlayCoverage()
    }

    private fun scheduleSensitiveRestore() {
        if (sensitiveRestoreScheduled) return
        sensitiveRestoreScheduled = true
        handler.postDelayed(sensitiveRestore, SENSITIVE_RESTORE_DELAY_MS)
    }

    private fun cancelSensitiveRestore() {
        sensitiveRestoreScheduled = false
        handler.removeCallbacks(sensitiveRestore)
    }

    /** 覆盖层哨兵：非白名单/无障碍创建的覆盖层 ≥70% → 针对性阻断来源应用 */
    private fun checkOverlayCoverage() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverlayScanAt < OVERLAY_SCAN_THROTTLE_MS) return
        lastOverlayScanAt = now
        if (!Policies.sensing(Policy.OVERLAY_SENTINEL)) return
        val suspect = OverlaySentinel.scan(this) ?: return
        // 自动路径：先挂覆盖层（标题标明是自动针对性阻断），再做处置
        PanicOverlay.setTitle("锁盾 · 自动处置（针对性）")
        PanicOverlay.setPhase("检测到覆盖层 ≥70%，正在针对性阻断…")
        if (!PanicOverlay.isShowing()) showPanicOverlay()
        // 自动路径：userInitiated=false —— 即便动作是 REDISABLE 也明确不是用户操作
        val summary = PanicResponse.handleAction(this, PanicAction.REDISABLE, listOf(suspect), userInitiated = false)
        PanicOverlay.setPhase("针对性阻断完成")
        reloadOverlayApps()
        AppLog.event(RiskLevel.L3, "覆盖层锁机嫌疑（≥70%）", "$suspect → $summary")
        PanicOverlay.appendLine("覆盖层锁机嫌疑：$suspect（≥70%）→ 已针对性阻断")
        PromptPlayer.alert()
        Toast.makeText(this, "检测到 $suspect 的覆盖层覆盖 ≥70%，已针对性阻断：$summary", Toast.LENGTH_LONG).show()
    }

    private fun showPanicOverlay() {
        PanicOverlay.show(
            service = this,
            onAction = { action, packages ->
                val message = PanicResponse.handleAction(this, action, packages, userInitiated = true)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (action == PanicAction.CANCEL_ALL) PanicOverlay.hide() else reloadOverlayApps()
            },
            onFilterChange = { reloadOverlayApps() },
            onRefresh = {
                Toast.makeText(this, "正在刷新应用列表…", Toast.LENGTH_SHORT).show()
                reloadOverlayApps()
            },
            // 「关闭」只收起覆盖层，已执行的处置保持不变；可从「保护」页的响应层状态重新打开
            onClose = {
                PanicOverlay.hide()
                Toast.makeText(this, "已关闭覆盖层（处置仍然生效）", Toast.LENGTH_SHORT).show()
            },
        )
        PanicOverlay.setLines(listOf(PanicResponse.statusText(this).split('\n').first()))
        refreshOverlayLog()
        reloadOverlayApps()
    }

    private fun reloadOverlayApps() {
        PanicResponse.refreshOverlayModel(this, PanicOverlay.filter)
        refreshOverlayLog()
    }

    /** 把持久化的处置日志喂给覆盖层（处置结束后仍可查看） */
    private fun refreshOverlayLog() {
        PanicOverlay.setLog(Prefs.panicLog.value.split('\n').filter { it.isNotBlank() })
    }

    override fun onInterrupt() {
        AppLog.w("无障碍服务被中断")
        Prefs.setServiceConnected(false)
        KeepAliveEngine.onServiceUnbound(this)
    }

    override fun onUnbind(intent: Intent): Boolean {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(logicTick)
        handler.removeCallbacks(sensitiveRestore)
        Prefs.setServiceConnected(false)
        AppLog.event(RiskLevel.L2, "无障碍服务被断开", "保活层将在 1 秒后尝试恢复")
        KeepAliveEngine.onServiceUnbound(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(logicTick)
        handler.removeCallbacks(sensitiveRestore)
        PromptPlayer.release()
        Prefs.setServiceConnected(false)
        super.onDestroy()
    }

    companion object {
        private const val OVERLAY_SCAN_THROTTLE_MS = 2_000L
        private const val SENSITIVE_RESTORE_DELAY_MS = 8_000L

        @Volatile
        private var instance: ShieldAccessibilityService? = null


        /** 当前界面快照（调试接口用）：包名/类名 + 浅层节点树 */
        fun uiSnapshot(): org.json.JSONObject? {
            val service = instance ?: return null
            val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
            val json = org.json.JSONObject()
            json.put("package", runCatching { root.packageName?.toString() }.getOrNull().orEmpty())
            json.put("class", runCatching { root.className?.toString() }.getOrNull().orEmpty())
            json.put("windowCount", runCatching { service.windows.size }.getOrDefault(0))
            val nodes = org.json.JSONArray()
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 3 || nodes.length() > 200) return
                nodes.put(
                    org.json.JSONObject()
                        .put("depth", depth)
                        .put("class", runCatching { node.className?.toString() }.getOrNull().orEmpty())
                        .put("text", runCatching { node.text?.toString() }.getOrNull().orEmpty())
                        .put("id", runCatching { node.viewIdResourceName }.getOrNull().orEmpty()),
                )
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(root, 0)
            json.put("nodes", nodes)
            return json
        }

        /** 截屏（Android 11+ 无障碍截图），返回 PNG 字节 */
        fun screenshotPng(): ByteArray? {
            val service = instance ?: return null
            if (android.os.Build.VERSION.SDK_INT < 30) return null
            val latch = java.util.concurrent.CountDownLatch(1)
            var bitmap: android.graphics.Bitmap? = null
            runCatching {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            val hardware = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            bitmap = hardware?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            runCatching { result.hardwareBuffer.close() }
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            latch.countDown()
                        }
                    },
                )
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            val bmp = bitmap ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }

        /** 供应用内重新打开紧急处置覆盖层（关闭后仍可再打开） */
        fun reopenOverlay(): Boolean {
            val service = instance ?: return false
            service.handler.post { service.showPanicOverlay() }
            return true
        }
    }
}
