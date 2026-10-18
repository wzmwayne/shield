package wzmwayne.shield.response

import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import wzmwayne.shield.admin.ShieldDeviceAdminReceiver
import wzmwayne.shield.control.DeviceOwnerControlPlane
import wzmwayne.shield.core.AppFilter
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.activation.DebugControl
import wzmwayne.shield.core.DisposalState
import wzmwayne.shield.core.Policies
import wzmwayne.shield.core.Policy
import wzmwayne.shield.core.PackageInventory
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.core.Trace
import wzmwayne.shield.keepalive.AccessibilitySettings
import wzmwayne.shield.protection.AccessibilityWhitelist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 响应对抗层：紧急手势触发后的自动处置 + 覆盖层里的手动处置。
 *
 * 口径（用户已确认）：
 *  · 白名单 = 锁盾自身 + 用户勾选的保护对象；其余第三方应用一律处置；
 *  · 系统应用在自动处置阶段一律不碰；手动列表可按筛选查看；
 *  · 自动处置力度：隐藏 + 挂起 + 撤销运行时权限 + 临时断网；
 *  · 手动处置额外允许「清理应用数据」与「卸载」；
 *  · 列表默认只显示非系统应用，可单选切换 用户/系统/所有，且始终异步加载（避免卡死）。
 */
object PanicResponse {

    private val modelExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shield-panic-model").apply { isDaemon = true }
    }

    private fun stream(line: String) {
        Trace.step(line)
        PanicOverlay.appendLine(line)
        Prefs.appendPanicLog(line)
    }

    fun trigger(context: Context, source: String): String {
        val app = context.applicationContext
        val plane = DeviceOwnerControlPlane(app)
        val marks = mutableListOf<String>()
        Prefs.clearPanicLog()
        stream("触发方式：$source")
        stream("触发时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        DisposalState.begin()
        try {

        Prefs.setPanicSnapshot(
            accessibility = AccessibilitySettings.rawValue(app),
            adbEnabled = isAdbEnabled(app),
        )

        stream("① 收紧无障碍白名单为「仅锁盾自身」")
        if (!Policies.isOn(Policy.SELF_LOCK_WHITELIST)) {
            marks += "白名单自锁防护已关闭，跳过白名单收紧"
        } else if (plane.isReady()) {
            marks += if (plane.updateAccessibilityWhitelist(listOf(AccessibilityWhitelist.SELF_PACKAGE))) {
                "无障碍白名单已收紧为仅锁盾"
            } else {
                "无障碍白名单收紧失败（设备所有者状态异常）"
            }
        } else {
            marks += "未持有设备所有者，跳过白名单收紧"
        }

        val selfComponent = AccessibilitySettings.componentId(app)
        val before = AccessibilitySettings.rawValue(app)
        val others = AccessibilitySettings.enabledList(app).filterNot { it == selfComponent }
        stream("② 关闭其它无障碍服务：${others.size} 个")
        if (others.isEmpty()) {
            marks += "本就没有其它无障碍服务在运行"
        } else {
            val written = runCatching {
                Settings.Secure.putString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    selfComponent,
                )
            }.getOrDefault(false)
            marks += if (written) {
                "已关闭 ${others.size} 个其它无障碍服务"
            } else {
                "关闭其它无障碍服务失败（WRITE_SECURE_SETTINGS 失效）"
            }
            AppLog.event(RiskLevel.L3, "紧急处置：关闭其它无障碍服务", others.joinToString(":"))
            Trace.step("原启用列表已备份：${before.take(120)}")
        }
        aborted()?.let { return it }

        // ③ 撤销所有「非设备所有者」的设备管理器：攻击者常靠设备管理器拿到卸载阻止/清数据等能力
        if (Policies.isOn(Policy.PANIC_DISABLE_DEBUG)) {
            stream("④ 自动禁止调试（开发者选项 + ADB）")
            val debugResult = DebugControl.block(app)
            stream(debugResult.message)
            marks += debugResult.message
        } else {
            stream("④ 禁止调试已关闭，跳过")
        }
        aborted()?.let { return it }

        val trusted = Prefs.trustedPackages.value + app.packageName
        val allApps = PackageInventory.listApps(app, trusted, AppFilter.ALL)
        val systemSkipped = allApps.count { it.isSystem }
        val targets = allApps.filter { !it.isSystem && !it.trusted }
        stream("⑤ 处置非白名单第三方应用：${targets.size} 个（跳过系统应用 $systemSkipped 个）")
        val entries = targets.mapIndexed { index, entryApp ->
            val entry = disposeApp(app, plane, entryApp)
            val tail = entry.error ?: "隐藏/挂起/撤权 ${entry.revokedPermissions.size} 项"
            stream("[${index + 1}/${targets.size}] ${entryApp.label} → $tail")
            entry
        }
        Prefs.setPanicReport(PanicReportCodec.encode(entries))
        aborted()?.let { return it }
        marks += if (entries.isEmpty()) {
            "无非白名单第三方应用需要处置（系统应用 $systemSkipped 个已跳过）"
        } else {
            PanicReportCodec.summary(entries, systemSkipped)
        }

        Prefs.setPanicAt(System.currentTimeMillis())
            val summary = "紧急处置已生效：" + marks.joinToString("；")
            AppLog.event(RiskLevel.L3, "紧急处置已执行（$source）", summary)
            Trace.result(true, summary)
            return summary
        } finally {
            DisposalState.end()
        }
    }

    

    

    /** 处置过程中无障碍被关闭 → 由 KeepAliveEngine 置位，这里在步骤间隙中止 */
    private fun aborted(): String? {
        if (!DisposalState.shouldAbort()) return null
        val message = "已中止当前阻断：处置过程中无障碍被关闭，接下来会后台禁用调试并重新执行全局阻断"
        AppLog.event(RiskLevel.L3, "阻断已中止", message)
        Trace.step(message)
        return message
    }

    /** 异步刷新覆盖层列表（默认只看非系统应用） */
    fun refreshOverlayModel(context: Context, filter: AppFilter) {
        val app = context.applicationContext
        PanicOverlay.setBusy(true)
        modelExecutor.execute {
            runCatching { buildOverlayModel(app, filter) }
                .onSuccess { PanicOverlay.setApps(it) }
                .onFailure { AppLog.w("刷新应用列表失败：${it.message}") }
            PanicOverlay.setBusy(false)
        }
    }

    fun buildOverlayModel(context: Context, filter: AppFilter): List<OverlayApp> {
        val trustedSet = Prefs.trustedPackages.value
        val trusted = trustedSet + context.packageName
        val report = PanicReportCodec.decode(Prefs.panicReport.value).associateBy { it.packageName }
        val privileged = privilegedPackages(context)
        return PackageInventory.listApps(context, trusted, filter)
            .map { entry ->
                val record = report[entry.packageName]
                OverlayApp(
                    packageName = entry.packageName,
                    label = entry.label,
                    checked = false,
                    failed = !record?.error.isNullOrEmpty(),
                    stillActive = isStillActive(context, entry.packageName, record?.hidden == true),
                    highPrivilege = entry.packageName in privileged,
                    trusted = entry.packageName in trustedSet,
                )
            }
    }

    /** 拥有高权限的应用集合：设备管理器 / 无障碍服务 / 悬浮窗 / 安装未知应用 */
    private fun privilegedPackages(context: Context): Set<String> {
        val result = mutableSetOf<String>()
        val plane = DeviceOwnerControlPlane(context)
        plane.activeAdmins().forEach { result += it.packageName }

        runCatching {
            val services = context.packageManager.queryIntentServices(
                android.content.Intent("android.accessibilityservice.AccessibilityService"),
                0,
            )
            services.forEach { result += it.serviceInfo.packageName }
        }

        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        runCatching {
            PackageInventory.listApps(context, emptySet(), AppFilter.ALL).forEach { app ->
                val uid = runCatching { context.packageManager.getApplicationInfo(app.packageName, 0).uid }
                    .getOrDefault(-1)
                if (uid < 0) return@forEach
                // REQUEST_INSTALL_PACKAGES 没有公开常量，直接用字符串
                listOf(
                    android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    "android:request_install_packages",
                ).forEach { op ->
                    if (appOps?.checkOpNoThrow(op, uid, app.packageName) == android.app.AppOpsManager.MODE_ALLOWED) {
                        result += app.packageName
                    }
                }
            }
        }
        return result
    }

    fun handleAction(context: Context, action: PanicAction, packages: List<String>): String {
        val app = context.applicationContext
        val plane = DeviceOwnerControlPlane(app)
        if (action != PanicAction.CANCEL_ALL && packages.isEmpty()) return "未勾选任何应用"

        return when (action) {
            PanicAction.CLEAR_DATA -> {
                var ok = 0
                val failures = mutableListOf<String>()
                packages.forEach { pkg ->
                    val done = plane.clearApplicationUserData(pkg)
                    if (done) ok += 1 else failures += pkg
                    stream("清理数据：$pkg → ${if (done) "已完成" else "失败"}")
                }
                val message = buildString {
                    append("清理数据结果：成功 $ok/${packages.size} 个（不可撤销）")
                    if (failures.isNotEmpty()) append("；失败：${failures.joinToString()}")
                }
                AppLog.event(RiskLevel.L3, "手动处置：清理应用数据", "$message｜${packages.joinToString()}")
                message
            }

            PanicAction.REDISABLE -> {
                val entries = packages.map { pkg ->
                    val entryApp = PackageInventory.listApps(app, emptySet(), AppFilter.ALL)
                        .firstOrNull { it.packageName == pkg } ?: PackageInventory.AppEntry(pkg, pkg, false, false)
                    disposeApp(app, plane, entryApp).also { stream("再次禁用：${it.label} → ${it.error ?: "完成"}") }
                }
                persistReportEntries(entries)
                // 手动处置同样要收紧无障碍白名单：本应用 + 应用白名单
                val trustedNow = (listOf(AccessibilityWhitelist.SELF_PACKAGE) + Prefs.trustedPackages.value).distinct()
                val whitelistOk = plane.updateAccessibilityWhitelist(trustedNow)
                stream("手动处置：已下发无障碍白名单 = 本应用 + 应用白名单（${trustedNow.size} 个）")
                val message = buildString {
                    append("已再次禁用 ${entries.size} 个应用（隐藏+挂起+撤权）")
                    append(if (whitelistOk) "；白名单已收紧为 ${trustedNow.size} 个包" else "；⚠ 白名单下发失败")
                }
                AppLog.event(RiskLevel.L3, "手动处置：再次禁用", "$message｜${packages.joinToString()}")
                message
            }

            PanicAction.RESTORE -> {
                persistReportEntries(packages.map { pkg -> restoreApp(app, plane, pkg) })
                val message = "已恢复 ${packages.size} 个应用（取消隐藏/挂起并回填权限）"
                AppLog.event(RiskLevel.L2, "手动处置：恢复应用", "$message｜${packages.joinToString()}")
                message
            }

            PanicAction.UNINSTALL -> {
                val failed = mutableListOf<String>()
                val okDetails = mutableListOf<String>()
                packages.forEach { pkg ->
                    val outcome = plane.uninstall(pkg)
                    if (outcome.success) {
                        okDetails += "$pkg（${outcome.message}）"
                        stream("卸载：$pkg → 成功（${outcome.message}）")
                    } else {
                        failed += pkg
                        stream("卸载：$pkg → 失败（${outcome.message}）")
                    }
                }
                val ok = okDetails.size
                if (failed.isNotEmpty()) {
                    // 回退系统：优先直接拉起系统卸载界面，拉不起再退到应用详情页
                    val opened = runCatching {
                        app.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_UNINSTALL_PACKAGE,
                                android.net.Uri.parse("package:${failed.first()}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        runCatching {
                            app.startActivity(
                                android.content.Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${failed.first()}"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    val message = "卸载结果：成功 $ok 个${if (okDetails.isEmpty()) "" else "（${okDetails.joinToString()}）"}；" +
                        "${failed.size} 个失败，已回退系统卸载界面"
                    AppLog.event(RiskLevel.L3, "手动处置：卸载", message)
                    message
                } else {
                    val message = "卸载结果：全部成功 $ok 个（${okDetails.joinToString()}）"
                    AppLog.event(RiskLevel.L3, "手动处置：卸载", message)
                    message
                }
            }

            PanicAction.STOP_APP -> {
                var ok = 0
                val failed = mutableListOf<String>()
                packages.forEach { pkg ->
                    val suspended = plane.setPackagesSuspended(pkg, true) && plane.isPackagesSuspended(pkg)
                    if (suspended) ok++ else failed += pkg
                    stream("停止应用：$pkg → ${if (suspended) "已暂停（无法运行、自启动、悬浮）" else "失败"}")
                }
                val message = buildString {
                    append("停止应用结果：成功 $ok/${packages.size} 个（暂停后无法运行/自启动/悬浮，可恢复）")
                    if (failed.isNotEmpty()) append("；失败：${failed.joinToString()}")
                }
                AppLog.event(RiskLevel.L3, "手动处置：停止应用", message)
                message
            }

            PanicAction.REVOKE_ALL -> {
                var revokedTotal = 0
                val entries = packages.map { pkg ->
                    val label = PackageInventory.listApps(app, emptySet(), AppFilter.ALL)
                        .firstOrNull { it.packageName == pkg }?.label ?: pkg
                    val granted = PackageInventory.grantedRuntimePermissions(app, pkg)
                    val revoked = mutableListOf<String>()
                    granted.forEach { permission ->
                        plane.setPermissionGrantState(pkg, permission, false)
                        if (!PackageInventory.isPermissionGranted(app, pkg, permission)) revoked += permission
                    }
                    revokedTotal += revoked.size
                    stream("撤销权限：$label → ${revoked.size}/${granted.size} 项（含自启动/悬浮等后台能力随暂停一并失效）")
                    PanicAppEntry(pkg, label, hidden = false, suspended = false, revokedPermissions = revoked)
                }
                persistReportEntries(entries)
                val message = "撤销权限结果：共撤销 $revokedTotal 项运行时权限（无障碍白名单未改动）"
                AppLog.event(RiskLevel.L3, "手动处置：撤销所有权限", message)
                message
            }

            PanicAction.OPEN_DETAILS -> {
                val targets = packages.take(1)
                if (targets.isEmpty()) {
                    "未勾选任何应用"
                } else {
                    val pkg = targets.first()
                    val opened = runCatching {
                        app.startActivity(
                            android.content.Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:$pkg"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    }.getOrDefault(false)
                    val message = if (opened) "已打开 $pkg 的应用详情页" else "打开应用详情失败（系统拒绝）"
                    AppLog.event(RiskLevel.L1, "手动处置：打开应用详情", message)
                    message
                }
            }

            PanicAction.CANCEL_ALL -> restore(app)
        }
    }

    fun statusText(context: Context): String {
        val entries = PanicReportCodec.decode(Prefs.panicReport.value)
        val failed = entries.count { !it.error.isNullOrEmpty() }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(Prefs.panicAt.value.coerceAtLeast(System.currentTimeMillis())))
        return buildString {
            append("处置 ${entries.size} 个非白名单应用（系统应用已跳过）\n")
            append("隐藏 ${entries.count { it.hidden }}｜挂起 ${entries.count { it.suspended }}｜撤权 ${entries.sumOf { it.revokedPermissions.size }} 项\n")
            if (failed > 0) append("失败 $failed 个，见红色顶置项\n")
            append("白名单已收紧为仅锁盾；其它无障碍已关闭；开发者选项与 ADB 已禁用；网络已临时阻断\n")
            append("触发时间 $time")
        }
    }

    fun restore(context: Context): String {
        PanicOverlay.hide()
        val app = context.applicationContext
        val plane = DeviceOwnerControlPlane(app)
        val marks = mutableListOf<String>()

        val snapshot = Prefs.panicSnapshotAccessibility.value
        if (snapshot.isNotBlank()) {
            val restored = runCatching {
                Settings.Secure.putString(
                    app.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    snapshot,
                )
            }.getOrDefault(false)
            marks += if (restored) "已恢复原无障碍启用列表" else "恢复无障碍列表失败"
        }

        if (plane.isReady()) {
            val cleared = runCatching {
                val dpm = app.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                val admin = ComponentName(app, ShieldDeviceAdminReceiver::class.java)
                dpm?.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                true
            }.getOrDefault(false)
            marks += if (cleared) "已解除调试限制" else "解除调试限制失败"
            // 恢复阻断时先「撤销无障碍白名单限制」
            val whitelistCleared = plane.clearAccessibilityWhitelist()
            marks += if (whitelistCleared) "已撤销无障碍白名单（所有服务恢复允许）" else "撤销无障碍白名单失败"
            // 用户手动配置过白名单 → 恢复后重新落地，否则保持"不限制"
            val selection = Prefs.accessWhitelistSelection.value
            if (selection.isNotEmpty()) {
                val list = (listOf(AccessibilityWhitelist.SELF_PACKAGE) + selection).distinct()
                marks += if (plane.updateAccessibilityWhitelist(list)) {
                    "已按你的白名单重新下发（${list.size} 个包）"
                } else {
                    "⚠ 你的白名单重新下发失败（可到设置页手动下发）"
                }
            }
        }

        val adbRestored = runCatching {
            Settings.Global.putInt(
                app.contentResolver,
                Settings.Global.ADB_ENABLED,
                if (Prefs.panicSnapshotAdbEnabled.value) 1 else 0,
            )
            Settings.Global.putInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            true
        }.getOrDefault(false)
        marks += if (adbRestored) {
            "已按快照恢复 ADB 状态（原状态=${if (Prefs.panicSnapshotAdbEnabled.value) "开启" else "关闭"}）"
        } else {
            "恢复 ADB 状态失败"
        }

        val entries = PanicReportCodec.decode(Prefs.panicReport.value)
        var appsRestored = 0
        var alreadyUninstalled = 0
        val leftovers = mutableListOf<PanicAppEntry>()
        entries.forEach { entry ->
            // 恢复不包含「重新安装/恢复卸载应用」：已卸载的直接跳过
            val installed = runCatching { app.packageManager.getPackageInfo(entry.packageName, 0); true }
                .getOrDefault(false)
            if (!installed) {
                alreadyUninstalled += 1
                return@forEach
            }
            val unsuspendOk = if (entry.suspended) plane.setPackagesSuspended(entry.packageName, false) else true
            val unhideOk = if (entry.hidden) plane.setApplicationHidden(entry.packageName, false) else true

            // 逐项回填并回读校验：只有真正授予成功才算恢复
            val stillRevoked = entry.revokedPermissions.filter { permission ->
                plane.setPermissionGrantState(entry.packageName, permission, true)
                !PackageInventory.isPermissionGranted(context = app, packageName = entry.packageName, permission = permission)
            }
            if (!unsuspendOk || !unhideOk || stillRevoked.isNotEmpty()) {
                leftovers += entry.copy(
                    hidden = !unhideOk,
                    suspended = !unsuspendOk,
                    revokedPermissions = stillRevoked,
                    error = buildString {
                        if (!unhideOk) append("取消隐藏失败；")
                        if (!unsuspendOk) append("取消挂起失败；")
                        if (stillRevoked.isNotEmpty()) append("${stillRevoked.size} 项权限回填失败")
                    }.trimEnd('；').ifEmpty { null },
                )
            } else if (entry.restoredReady) {
                appsRestored += 1
            }
        }
        // 历史版本可能在阻断时限制过网络：这里静默解除，不再主动断网
        plane.restoreNetwork()
        if (alreadyUninstalled > 0) marks += "$alreadyUninstalled 个应用已卸载，不参与恢复（卸载不可回滚）"
        if (entries.isNotEmpty()) {
            marks += "已恢复 $appsRestored 个应用（取消隐藏/挂起/回填权限）"
            if (leftovers.isNotEmpty()) marks += "⚠ ${leftovers.size} 个应用未完全恢复，已保留在处置清单里可重试"
        }

        // 只有全部恢复成功才清空清单；否则保留失败项供重试
        if (leftovers.isEmpty()) {
            Prefs.setPanicReport("")
            Prefs.setPanicAt(0L)
        } else {
            Prefs.setPanicReport(PanicReportCodec.encode(leftovers))
            marks += "处置状态保留（覆盖层仍可继续处理）"
        }

        val summary = "已恢复：" + marks.joinToString("；")
        AppLog.event(RiskLevel.L2, "紧急处置已撤销", summary)
        Trace.result(true, summary)
        return summary
    }

    private fun persistReportEntries(entries: List<PanicAppEntry>) {
        val current = PanicReportCodec.decode(Prefs.panicReport.value).associateBy { it.packageName }.toMutableMap()
        entries.forEach { current[it.packageName] = it }
        Prefs.setPanicReport(PanicReportCodec.encode(current.values.toList()))
    }

    private fun restoreApp(
        context: Context,
        plane: DeviceOwnerControlPlane,
        packageName: String,
    ): PanicAppEntry {
        val entry = PanicReportCodec.decode(Prefs.panicReport.value).firstOrNull { it.packageName == packageName }
        val unsuspendOk = plane.setPackagesSuspended(packageName, false)
        val unhideOk = plane.setApplicationHidden(packageName, false)

        val stillRevoked = entry?.revokedPermissions.orEmpty().filter { permission ->
            plane.setPermissionGrantState(packageName, permission, true)
            !PackageInventory.isPermissionGranted(context, packageName, permission)
        }
        return PanicAppEntry(
            packageName = packageName,
            label = entry?.label ?: packageName,
            hidden = !unhideOk,
            suspended = !unsuspendOk,
            revokedPermissions = stillRevoked,
            error = when {
                !unhideOk || !unsuspendOk -> "取消隐藏/挂起失败"
                stillRevoked.isNotEmpty() -> "${stillRevoked.size} 项权限回填失败"
                else -> null
            },
        )
    }

    private fun disposeApp(
        context: Context,
        plane: DeviceOwnerControlPlane,
        app: PackageInventory.AppEntry,
    ): PanicAppEntry {
        val errors = mutableListOf<String>()
        val grantedBefore = PackageInventory.grantedRuntimePermissions(context, app.packageName)

        // ⚠ 顺序很重要：必须先撤权，再隐藏/挂起。
        // 被挂起或隐藏的应用 setPermissionGrantState 会失败，导致「撤了撤不掉」或「撤了没记录 → 恢复不回去」。
        val revoked = mutableListOf<String>()
        grantedBefore.forEach { permission ->
            val applied = plane.setPermissionGrantState(app.packageName, permission, false)
            val stillGranted = PackageInventory.isPermissionGranted(context, app.packageName, permission)
            if (applied || !stillGranted) {
                // 无论 API 返回值如何，只要回读确认已不授予，就记为「需要恢复」
                revoked += permission
            } else {
                errors += "撤权失败:${permission.substringAfterLast('.')}"
            }
        }

        // 隐藏/挂起：调用后回读真实状态，不拿 API 返回值当真
        plane.setApplicationHidden(app.packageName, true)
        val hidden = plane.isApplicationHidden(app.packageName)
        if (!hidden) errors += "隐藏失败"

        plane.setPackagesSuspended(app.packageName, true)
        val suspended = plane.isPackagesSuspended(app.packageName)
        if (!suspended) errors += "挂起失败"

        AppLog.event(
            RiskLevel.L3,
            "处置应用：${app.label}",
            "隐藏=$hidden 挂起=$suspended 撤销权限=${revoked.size} 项 ${errors.joinToString("；")}",
        )
        return PanicAppEntry(
            packageName = app.packageName,
            label = app.label,
            hidden = hidden,
            suspended = suspended,
            revokedPermissions = revoked,
            error = errors.joinToString("；").ifEmpty { null },
        )
    }

    private fun isStillActive(context: Context, packageName: String, hidden: Boolean): Boolean {
        if (hidden) return false
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return false
        val stopped = (info.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        val suspended = (info.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        return info.enabled && !stopped && !suspended
    }

    private fun isAdbEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)
}
