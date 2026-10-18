package wzmwayne.shield.response

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.Packages
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.control.DeviceOwnerControlPlane

/**
 * 默认桌面守护：防「诱导用户把可疑应用设为默认桌面」。
 *
 * 判定：当前 HOME 角色持有者 != 用户选定的可信桌面 → 视为劫持。
 * 处置：用设备所有者 `addPersistentPreferredActivity` **锁回可信桌面**，
 *       并把可疑桌面隐藏 + 挂起（走统一处置链）。
 */
object HomeSentinel {

    /** 列出所有声明为桌面的应用（含系统） */
    fun homeCandidates(context: Context): List<Packages.Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return list.map { info ->
            Packages.Entry(
                packageName = info.activityInfo.packageName,
                label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.activityInfo.packageName),
                component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
            )
        }.distinctBy { it.packageName }
    }

    /** 当前默认桌面包名 */
    fun currentHome(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        return resolved?.activityInfo?.packageName
    }

    /**
     * 检查并自动锁回。返回需要展示的日志行（null = 无需处理）。
     */
    fun check(context: Context): String? {
        val trustedSet = Prefs.trustedHomes.value
        val primary = Prefs.primaryHome.value
        if (primary.isBlank()) return null
        val current = currentHome(context) ?: return null
        // 在可信集合内的桌面都放行
        if (current in trustedSet) return null

        val app = context.applicationContext
        val plane = DeviceOwnerControlPlane(app)
        val target = homeCandidates(app).firstOrNull { it.packageName == primary } ?: return null

        val locked = plane.setPersistentHome(target.component)
        val detail = buildString {
            append("检测到默认桌面被改为 $current（主桌面：$primary）")
            append(if (locked) "；已锁回可信桌面" else "；⚠ 锁回失败（可能已失去设备所有者）")
        }
        AppLog.event(RiskLevel.L3, "默认桌面守护", detail)

        // 可疑桌面：隐藏 + 挂起（走统一处置链，含回读校验）
        if (current != app.packageName) {
            runCatching { PanicResponse.handleAction(app, PanicAction.REDISABLE, listOf(current)) }
        }
        return detail
    }

    /** 手动锁回（设置页按钮） */
    fun lockBack(context: Context): String {
        val primary = Prefs.primaryHome.value
        if (primary.isBlank()) return "尚未选择主桌面"
        val app = context.applicationContext
        val target = homeCandidates(app).firstOrNull { it.packageName == primary }
            ?: return "主桌面 $primary 已不存在，请重新选择"
        val ok = DeviceOwnerControlPlane(app).setPersistentHome(target.component)
        return if (ok) "已锁回桌面：${target.label}" else "锁回失败：请确认锁盾仍是设备所有者"
    }
}
