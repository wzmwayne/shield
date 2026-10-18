package wzmwayne.shield.keepalive

import android.content.Context
import android.provider.Settings
import wzmwayne.shield.accessibility.ShieldAccessibilityService
import wzmwayne.shield.core.logic.AccessibilityList

/** ensureSelf 的结果：必须区分「本来就在」和「写不进去」，否则会把失败当成功上报 */
enum class EnsureResult { ALREADY_LISTED, WRITTEN, FAILED }

/** 对 enabled_accessibility_services 的读写封装。 */
object AccessibilitySettings {

    fun componentId(context: Context): String =
        "${context.packageName}/${ShieldAccessibilityService::class.java.name}"

    fun rawValue(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

    fun enabledList(context: Context): List<String> = AccessibilityList.parse(rawValue(context))

    /** 直接写入启用列表（调用方需持有 WRITE_SECURE_SETTINGS） */
    fun writeList(context: Context, services: List<String>): Boolean = runCatching {
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.filter { it.isNotBlank() }.joinToString(":"),
        )
    }.getOrDefault(false)

    fun isSelfListed(context: Context): Boolean =
        AccessibilityList.contains(rawValue(context), componentId(context))

    fun isAccessibilityEnabled(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    }.getOrDefault(false)

    /** 系统实际绑定的无障碍服务里是否包含锁盾 */
    fun isSelfEnabledBySystem(context: Context): Boolean {
        if (wzmwayne.shield.core.Prefs.serviceConnected.value) return true
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            ?: return false
        val mask = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        return runCatching {
            manager.getEnabledAccessibilityServiceList(mask).any {
                it.resolveInfo?.serviceInfo?.packageName == context.packageName
            }
        }.getOrDefault(false)
    }

    /** 写回自身服务；三态结果，写失败会被上层如实上报 */
    fun ensureSelf(context: Context): EnsureResult {
        val raw = rawValue(context)
        val updated = AccessibilityList.ensure(raw, componentId(context))
            ?: return EnsureResult.ALREADY_LISTED
        val written = runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated,
            )
        }.getOrDefault(false)
        if (!written) return EnsureResult.FAILED
        runCatching {
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        return EnsureResult.WRITTEN
    }

    fun removeSelf(context: Context): Boolean {
        val raw = rawValue(context)
        val updated = AccessibilityList.remove(raw, componentId(context))
        if (updated == raw) return false
        return runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated,
            )
        }.getOrDefault(false)
    }
}
