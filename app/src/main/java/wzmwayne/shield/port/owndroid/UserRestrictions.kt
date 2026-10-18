/*
 * 用户限制（UserRestrictions）目录
 *
 * 移植自 OwnDroid（https://github.com/BinTianqi/OwnDroid）
 * Copyright (C) BinTianqi，GPL-3.0
 *
 * 本文件是锁盾的一部分，同样以 GPL-3.0 发布；能力清单与分组沿用上游，文案已本地化。
 */
package wzmwayne.shield.port.owndroid

/** 一项用户限制：key 为 UserManager.DISALLOW_* 常量名（运行时按同名常量取值） */
data class Restriction(
    val key: String,
    val title: String,
    val requiresApi: Int = 1,
)

enum class RestrictionCategory(val title: String) {
    NETWORK("网络"),
    CONNECTIVITY("连接与共享"),
    APPLICATIONS("应用"),
    MEDIA("媒体与显示"),
    USERS("用户与账户"),
    OTHER("其它"),
}

@Suppress("InlinedApi")
object UserRestrictions {

    val network = listOf(
        Restriction("DISALLOW_CONFIG_MOBILE_NETWORKS", "配置移动数据", 1),
        Restriction("DISALLOW_CONFIG_WIFI", "配置Wi-Fi", 1),
        Restriction("DISALLOW_DATA_ROAMING", "数据漫游", 24),
        Restriction("DISALLOW_CELLULAR_2G", "使用2G(GSM)", 34),
        Restriction("DISALLOW_ULTRA_WIDEBAND_RADIO", "超宽频段无线电", 34),
        Restriction("DISALLOW_ADD_WIFI_CONFIG", "添加Wi-Fi配置", 33),
        Restriction("DISALLOW_CHANGE_WIFI_STATE", "修改Wi-Fi状态", 33),
        Restriction("DISALLOW_WIFI_DIRECT", "Wi-Fi直连", 1),
        Restriction("DISALLOW_WIFI_TETHERING", "Wi-Fi共享", 33),
        Restriction("DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI", "分享设备管理器配置的Wi-Fi", 33),
        Restriction("DISALLOW_NETWORK_RESET", "重置网络", 1),
        Restriction("DISALLOW_CONFIG_TETHERING", "配置网络共享", 1),
        Restriction("DISALLOW_CONFIG_VPN", "配置VPN", 1),
        Restriction("DISALLOW_CONFIG_PRIVATE_DNS", "配置私人DNS", 29),
        Restriction("DISALLOW_AIRPLANE_MODE", "飞行模式", 28),
        Restriction("DISALLOW_CONFIG_CELL_BROADCASTS", "配置小区广播", 1),
        Restriction("DISALLOW_SMS", "短信", 1),
        Restriction("DISALLOW_OUTGOING_CALLS", "拨出电话", 1),
        Restriction("DISALLOW_SIM_GLOBALLY", "下载eSIM", 1),
        Restriction("DISALLOW_THREAD_NETWORK", "Thread网络", 36),
    )

    val connectivity = listOf(
        Restriction("DISALLOW_BLUETOOTH", "蓝牙", 26),
        Restriction("DISALLOW_BLUETOOTH_SHARING", "蓝牙分享", 26),
        Restriction("DISALLOW_SHARE_LOCATION", "分享位置", 1),
        Restriction("DISALLOW_CONFIG_LOCATION", "配置位置信息", 28),
        Restriction("DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO", "nfc", 35),
        Restriction("DISALLOW_OUTGOING_BEAM", "Beam发送", 1),
        Restriction("DISALLOW_USB_FILE_TRANSFER", "USB文件传输", 1),
        Restriction("DISALLOW_MOUNT_PHYSICAL_MEDIA", "挂载物理媒体", 1),
        Restriction("DISALLOW_PRINTING", "打印", 28),
    )

    val applications = listOf(
        Restriction("DISALLOW_INSTALL_APPS", "安装应用", 1),
        Restriction("DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY", "安装未知来源应用（全局）", 29),
        Restriction("DISALLOW_INSTALL_UNKNOWN_SOURCES", "安装未知来源应用", 1),
        Restriction("DISALLOW_UNINSTALL_APPS", "卸载应用", 1),
        Restriction("DISALLOW_APPS_CONTROL", "控制应用", 1),
        Restriction("DISALLOW_CONFIG_DEFAULT_APPS", "修改默认App", 34),
        Restriction("ENSURE_VERIFY_APPS", "禁用应用验证", 1),
    )

    val media = listOf(
        Restriction("DISALLOW_CONFIG_BRIGHTNESS", "调整亮度", 28),
        Restriction("DISALLOW_CONFIG_SCREEN_TIMEOUT", "修改屏幕超时", 28),
        Restriction("DISALLOW_AMBIENT_DISPLAY", "息屏显示", 28),
        Restriction("DISALLOW_ADJUST_VOLUME", "调整音量", 1),
        Restriction("DISALLOW_UNMUTE_MICROPHONE", "取消麦克风静音", 1),
        Restriction("DISALLOW_CAMERA_TOGGLE", "切换摄像头使用权限", 31),
        Restriction("DISALLOW_MICROPHONE_TOGGLE", "切换麦克风使用权限", 31),
    )

    val users = listOf(
        Restriction("DISALLOW_ADD_USER", "添加用户", 1),
        Restriction("DISALLOW_REMOVE_USER", "移除用户", 1),
        Restriction("DISALLOW_USER_SWITCH", "切换用户", 28),
        Restriction("DISALLOW_ADD_MANAGED_PROFILE", "创建工作资料", 26),
        Restriction("DISALLOW_REMOVE_MANAGED_PROFILE", "删除工作资料", 26),
        Restriction("DISALLOW_ADD_PRIVATE_PROFILE", "创建私密空间", 35),
        Restriction("DISALLOW_SET_USER_ICON", "修改用户头像", 24),
        Restriction("DISALLOW_CROSS_PROFILE_COPY_PASTE", "跨用户复制", 1),
        Restriction("DISALLOW_SHARE_INTO_MANAGED_PROFILE", "分享至工作应用", 28),
        Restriction("DISALLOW_UNIFIED_PASSWORD", "个人和工作密码相同", 28),
    )

    val other = listOf(
        Restriction("DISALLOW_AUTOFILL", "自动填充服务", 26),
        Restriction("DISALLOW_CONFIG_CREDENTIALS", "配置凭据", 1),
        Restriction("DISALLOW_CONTENT_CAPTURE", "内容捕获", 29),
        Restriction("DISALLOW_CONTENT_SUGGESTIONS", "内容建议", 29),
        Restriction("DISALLOW_ASSIST_CONTENT", "辅助内容", 35),
        Restriction("DISALLOW_CREATE_WINDOWS", "创建窗口", 1),
        Restriction("DISALLOW_SET_WALLPAPER", "更换壁纸", 24),
        Restriction("DISALLOW_GRANT_ADMIN", "启用设备管理器", 34),
        Restriction("DISALLOW_FUN", "娱乐", 1),
        Restriction("DISALLOW_MODIFY_ACCOUNTS", "修改账号设置", 1),
        Restriction("DISALLOW_CONFIG_LOCALE", "修改语言", 28),
        Restriction("DISALLOW_CONFIG_DATE_TIME", "修改日期、时间", 28),
        Restriction("DISALLOW_SYSTEM_ERROR_DIALOGS", "系统报错对话框", 28),
        Restriction("DISALLOW_FACTORY_RESET", "恢复出厂设置", 1),
        Restriction("DISALLOW_SAFE_BOOT", "安全模式", 1),
        Restriction("DISALLOW_DEBUGGING_FEATURES", "调试功能", 1),
    )

    fun categories(): List<Pair<RestrictionCategory, List<Restriction>>> = listOf(
        RestrictionCategory.NETWORK to network,
        RestrictionCategory.CONNECTIVITY to connectivity,
        RestrictionCategory.APPLICATIONS to applications,
        RestrictionCategory.MEDIA to media,
        RestrictionCategory.USERS to users,
        RestrictionCategory.OTHER to other,
    )

    /** 只在当前系统版本上可用的条目 */
    fun available(sdkInt: Int): List<Pair<RestrictionCategory, List<Restriction>>> =
        categories().map { (category, list) ->
            category to list.filter { sdkInt >= it.requiresApi }
        }

    fun all(): List<Restriction> = categories().flatMap { it.second }
}
