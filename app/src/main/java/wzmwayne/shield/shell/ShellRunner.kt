package wzmwayne.shield.shell

import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 真正执行命令的代码：只在 Shizuku 用户服务进程（uid 2000 / shell）中运行，
 * 因此这里可以放心使用 ProcessBuilder 拿到 Shell 身份。
 */
internal object ShellRunner {
    private const val TIMEOUT_MS = 30_000L
    private const val DRAIN_TIMEOUT_SECONDS = 5L

    /** Binder 单次事务约 1MB：这里按字节给 out/err 各留 150KB，合计不超过 300KB */
    private const val MAX_STREAM_BYTES = 150_000

    fun run(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command).start()
            // 必须并发读取两个流，否则管道写满会死锁
            val stdout = readAsync(process.inputStream)
            val stderr = readAsync(process.errorStream)
            val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            JSONObject()
                .put("code", if (finished) process.exitValue() else -1)
                .put("out", trim(stdout.get(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                .put("err", trim(stderr.get(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                .put("timeout", !finished)
                .toString()
        } catch (t: Throwable) {
            JSONObject()
                .put("code", -1)
                .put("out", "")
                .put("err", "执行失败：${t.javaClass.simpleName}: ${t.message}")
                .put("timeout", false)
                .toString()
        }
    }

    /** 按 UTF-8 字节数截断（Binder 限制的是字节，不是字符） */
    private fun trim(text: String): String {
        if (text.toByteArray(Charsets.UTF_8).size <= MAX_STREAM_BYTES) return text
        val builder = StringBuilder()
        var bytes = 0
        for (ch in text) {
            val size = when {
                ch.code < 0x80 -> 1
                ch.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + size > MAX_STREAM_BYTES) break
            builder.append(ch)
            bytes += size
        }
        return builder.append("\n…（输出过长已按 150KB 字节截断）").toString()
    }

    private fun readAsync(stream: InputStream): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        Thread {
            val text = runCatching { stream.bufferedReader().use { it.readText() } }.getOrDefault("")
            future.complete(text)
        }.apply {
            isDaemon = true
            name = "shield-shell-reader"
        }.start()
        return future
    }
}
