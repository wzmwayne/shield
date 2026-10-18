package wzmwayne.shield.debug

import org.json.JSONArray
import org.json.JSONObject

/** 页面（窗口）记录：开始/停止采集，环形保存最近 500 条 */
object PageRecorder {

    private const val MAX = 500
    private val records = ArrayDeque<JSONObject>()

    @Volatile
    var recording: Boolean = false
        private set

    fun start() {
        recording = true
    }

    fun stop() {
        recording = false
    }

    fun clear() {
        records.clear()
    }

    fun onWindow(packageName: String?, className: String?, at: Long = System.currentTimeMillis()) {
        if (!recording) return
        val pkg = packageName ?: return
        if (records.isNotEmpty()) {
            val last = records.last()
            if (last.optString("package") == pkg && last.optString("class") == className.orEmpty()) return
        }
        records.addLast(
            JSONObject()
                .put("time", at)
                .put("package", pkg)
                .put("class", className.orEmpty()),
        )
        while (records.size > MAX) records.removeFirst()
    }

    fun snapshot(): JSONArray = JSONArray().also { array -> records.forEach { array.put(it) } }

    fun count(): Int = records.size
}
