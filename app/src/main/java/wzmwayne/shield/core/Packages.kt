package wzmwayne.shield.core

import android.content.ComponentName

/** 桌面候选应用的最小信息 */
object Packages {
    data class Entry(
        val packageName: String,
        val label: String,
        val component: ComponentName,
    )
}
