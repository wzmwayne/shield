package wzmwayne.shield.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 执行过程的流式追踪：检查/激活时把「检查 xxx → 执行 xxx → 判定 xxx → 通过/失败」实时推到界面，
 * 同时写入 logcat（TAG=Shield, 前缀 trace），便于事后从设备日志复盘。
 */
object Trace {
    private const val MAX_LINES = 400

    private const val MAX_FILE_BYTES = 256 * 1024

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private var logFile: File? = null

    /**
     * 把 trace 同时落盘到应用外部目录（/sdcard/Android/data/wzmwayne.shield/files/shield_trace.log），
     * 便于真机失败后直接 adb pull 复盘——VM 的 logcat 缓冲会被系统日志冲掉。
     */
    fun attach(context: Context) {
        // 内部目录一定存在且可写；本机 adb 为 root，可直接 cat 出来
        logFile = runCatching {
            File(context.filesDir, "shield_trace.log").also { file ->
                file.parentFile?.mkdirs()
                if (!file.exists()) file.createNewFile()
            }
        }.getOrNull()
    }

    fun filePath(): String = logFile?.absolutePath ?: "（未初始化）"

    fun clearFile() {
        runCatching { logFile?.writeText("") }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun step(message: String) {
        val stamped = "${TIME_FORMAT.format(Date())}  $message"
        _lines.value = (_lines.value + stamped).takeLast(MAX_LINES)
        AppLog.i("trace | $message")
        appendToFile(stamped)
    }

    private fun appendToFile(line: String) {
        val file = logFile ?: return
        synchronized(this) {
            runCatching {
                if (file.length() > MAX_FILE_BYTES) file.writeText("")
                file.appendText(line + "\n")
            }
        }
    }

    /** 一行结论摘要（同时作为界面 banner） */
    fun result(ok: Boolean, message: String) {
        step("${if (ok) "✔ 通过" else "✘ 失败"}：$message")
    }

    fun judge(what: String, ok: Boolean, detail: String = "") {
        step("判定 $what → ${if (ok) "通过" else "不通过"}${if (detail.isNotEmpty()) "（$detail）" else ""}")
    }

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
}
