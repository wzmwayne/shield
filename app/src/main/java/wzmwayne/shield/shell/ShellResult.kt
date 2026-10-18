package wzmwayne.shield.shell

import org.json.JSONObject

/** shell 用户服务的执行结果。 */
data class ShellResult(
    val code: Int,
    val out: String,
    val err: String,
    val timeout: Boolean,
) {
    val ok: Boolean get() = code == 0 && !timeout

    /** 供 UI 展示的一行摘要 */
    val summary: String
        get() = when {
            timeout -> "命令执行超时"
            ok -> out.trim().ifEmpty { "命令执行成功（无输出）" }
            else -> (err.ifBlank { out }).trim().ifEmpty { "命令执行失败（退出码 $code）" }
        }

    companion object {
        fun fromJson(raw: String?): ShellResult = try {
            val json = JSONObject(raw.orEmpty())
            ShellResult(
                code = json.optInt("code", -1),
                out = json.optString("out"),
                err = json.optString("err"),
                timeout = json.optBoolean("timeout", false),
            )
        } catch (t: Throwable) {
            ShellResult(-1, "", "无法解析 shell 返回：${t.message}", false)
        }

        fun failure(message: String) = ShellResult(-1, "", message, false)
    }
}
