package wzmwayne.shield.debug

import android.content.Context
import wzmwayne.shield.AndroidCompat
import wzmwayne.shield.control.DeviceOwnerControlPlane
import wzmwayne.shield.core.AppFilter
import wzmwayne.shield.core.AppLog
import wzmwayne.shield.core.PackageInventory
import wzmwayne.shield.core.PinHasher
import wzmwayne.shield.core.Policies
import wzmwayne.shield.core.Policy
import wzmwayne.shield.core.Prefs
import wzmwayne.shield.core.RiskLevel
import wzmwayne.shield.keepalive.AccessibilitySettings
import wzmwayne.shield.protection.AccessibilityWhitelist
import wzmwayne.shield.response.PanicResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 调试用 HTTP 接口：仅监听 127.0.0.1，所有请求必须带 `X-Shield-Pin` 头（应用 PIN）。
 * 连续 5 次 PIN 错误 → 锁定 5 分钟。PIN 未启用时接口不可用。
 */
object DebugHttpServer {

    const val DEFAULT_PORT = 8686
    private const val MAX_BODY = 120 * 1024 * 1024 // 允许上传 APK
    private const val MAX_TEXT_BODY = 1024 * 1024
    private const val MAX_FAILURES = 5
    private const val LOCKOUT_MS = 5 * 60_000L

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private var failures = 0
    private var lockedUntil = 0L
    private var appRef: Context? = null

    val isRunning: Boolean get() = running

    /** 可访问地址：局域网 IPv4（ConnectivityManager，比 NetworkInterface 在 Android 上可靠）+ 本机 */
    fun urls(context: Context, port: Int = Prefs.debugHttpPort.value): List<String> {
        val result = mutableListOf("http://127.0.0.1:$port")
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching
            cm.allNetworks.forEach { network ->
                val props = cm.getLinkProperties(network) ?: return@forEach
                props.linkAddresses.forEach { link ->
                    val addr = link.address
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val url = "http://${addr.hostAddress}:$port"
                        if (url !in result) result += url
                    }
                }
            }
        }
        return result
    }

    fun start(context: Context): String {
        if (running) return "调试接口已在运行：http://127.0.0.1:${Prefs.debugHttpPort.value}"
        if (!Prefs.pinEnabled.value) return "请先在设置里启用应用 PIN：调试接口必须用 PIN 验证"
        val app = context.applicationContext
        appRef = app
        val port = Prefs.debugHttpPort.value
        return runCatching {
            // 固定绑定 0.0.0.0：局域网易达（无线调试关闭时也能用）
            val socket = ServerSocket(port, 8, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            running = true
            thread = Thread({ acceptLoop() }, "shield-debug-http").apply {
                isDaemon = true
                start()
            }
            val scope = "局域网可达（0.0.0.0）"
            AppLog.event(RiskLevel.L2, "调试接口", "已启动：$scope，端口 $port")
            "调试接口已启动：$scope，端口 $port；访问地址：${urls(context, port).joinToString("  ")}"
        }.getOrElse {
            running = false
            AppLog.w("调试接口启动失败：${it.javaClass.simpleName}: ${it.message}")
            "调试接口启动失败：${it.javaClass.simpleName}: ${it.message}"
        }
    }

    fun stop(): String {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        thread = null
        AppLog.event(RiskLevel.L2, "调试接口", "已停止")
        return "调试接口已停止"
    }

    private fun acceptLoop() {
        while (running) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: continue
            runCatching { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val headers = mutableMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val rawPath = parts.getOrNull(1).orEmpty()
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")

            // 优先请求头；手机浏览器不方便加头时可用 ?pin=xxxx
            val pin = headers["x-shield-pin"].orEmpty().ifBlank { queryParam(query, "pin").orEmpty() }
            val authError = authenticate(pin)
            if (authError != null) {
                respond(s.getOutputStream(), 401, JSONObject().put("error", authError))
                return
            }

            val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_BODY)
            val body = readFully(input, contentLength)
            val app = appRef

            // 二进制/文本端点
            when (path) {
                "/logs" -> {
                    val text = app?.let { DebugActions.logs(it) } ?: ""
                    respondBytes(s.getOutputStream(), 200, "text/plain; charset=utf-8", text.toByteArray())
                    return
                }

                "/screenshot" -> {
                    val png = wzmwayne.shield.accessibility.ShieldAccessibilityService.screenshotPng()
                    if (png == null) {
                        respond(s.getOutputStream(), 200, JSONObject().put("error", "截屏不可用（无障碍服务未运行或系统拒绝）"))
                    } else {
                        respondBytes(s.getOutputStream(), 200, "image/png", png)
                    }
                    return
                }

                "/install" -> {
                    val name = headers["x-filename"] ?: queryParam(query, "name") ?: "upload.apk"
                    val result = app?.let { DebugActions.installApk(it, body, name) } ?: "服务未就绪"
                    respond(s.getOutputStream(), 200, JSONObject().put("result", result))
                    return
                }

                "/push" -> {
                    val name = headers["x-filename"] ?: queryParam(query, "name") ?: "push.bin"
                    val result = app?.let { DebugActions.pushFile(it, name, body) } ?: "服务未就绪"
                    respond(s.getOutputStream(), 200, JSONObject().put("result", result))
                    return
                }
            }

            val result = if (app == null) {
                JSONObject().put("error", "服务未就绪")
            } else {
                runCatching { route(app, method, path, String(body, Charsets.UTF_8), query) }
                    .getOrElse { JSONObject().put("error", it.message ?: it.javaClass.simpleName) }
            }
            respond(s.getOutputStream(), 200, result)
        }
    }

    private fun readHead(input: java.io.InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream()
        var state = 0
        while (buffer.size() < 64 * 1024) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            buffer.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return buffer.toString("UTF-8").dropLast(4)
                else -> 0
            }
        }
        return buffer.toString("UTF-8")
    }

    private fun readFully(input: java.io.InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) data else data.copyOf(read)
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&').map { it.split('=') }.firstOrNull { it.size == 2 && it[0] == key }?.get(1)

    private fun authenticate(pin: String): String? {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return "PIN 错误次数过多，请 ${(lockedUntil - now) / 1000} 秒后再试"
        if (!Prefs.pinEnabled.value) return "未启用应用 PIN"
        if (pin.isBlank()) return "缺少请求头 X-Shield-Pin"
        val salt = Prefs.pinSalt()
        val hash = Prefs.pinHash()
        if (salt == null || hash == null) return "PIN 数据缺失"
        if (!PinHasher.verify(pin, salt, hash)) {
            failures += 1
            if (failures >= MAX_FAILURES) {
                lockedUntil = now + LOCKOUT_MS
                failures = 0
                AppLog.event(RiskLevel.L3, "调试接口", "PIN 连续错误，已锁定 5 分钟")
            }
            return "PIN 不正确"
        }
        failures = 0
        return null
    }

    private fun route(app: Context, method: String, path: String, body: String, query: String): JSONObject {
        val plane = DeviceOwnerControlPlane(app)
        return when {
            path == "/status" -> JSONObject().apply {
                put("version", AndroidCompat.VERSION_LABEL)
                put("build", AndroidCompat.BUILD_TAG)
                put("activation", Prefs.activationState.value.name)
                put("deviceOwner", plane.isReady())
                put("accessibilityEnabled", AccessibilitySettings.isSelfEnabledBySystem(app))
                put("accessibilityListed", AccessibilitySettings.isSelfListed(app))
                put("whitelist", JSONArray(plane.permittedAccessibilityServices() ?: emptyList<String>()))
                put("trustedApps", JSONArray(Prefs.trustedPackages.value.toList()))
                put("accessWhitelistSelection", JSONArray(Prefs.accessWhitelistSelection.value.toList()))
                put("keepAlive", Prefs.keepAliveEnabled.value)
                put("pinEnabled", Prefs.pinEnabled.value)
                put("debugKill", Prefs.debugKill.value)
                put("killUiShown", Prefs.killUiShown.value)
                put("trustedHomes", JSONArray(Prefs.trustedHomes.value.toList()))
                put("primaryHome", Prefs.primaryHome.value)
                put("panicAt", Prefs.panicAt.value)
                put("sensitiveLockAt", Prefs.sensitiveLockAt.value)
                put("policies", JSONObject().apply {
                    Policy.entries.forEach { put(it.id, Policies.isOn(it)) }
                })
            }

            path == "/apps" -> JSONObject().apply {
                val apps = PackageInventory.listApps(app, Prefs.trustedPackages.value, AppFilter.ALL)
                put("apps", JSONArray().apply {
                    apps.forEach { entry ->
                        put(JSONObject().apply {
                            put("package", entry.packageName)
                            put("label", entry.label)
                            put("system", entry.isSystem)
                            put("trusted", entry.trusted)
                        })
                    }
                })
            }

            path == "/accessibility" && method == "POST" -> JSONObject().apply {
                val json = JSONObject(body.ifEmpty { "{}" })
                when (json.optString("action", "set")) {
                    "only_self" -> {
                        val list = listOf(AccessibilitySettings.componentId(app))
                        put("ok", AccessibilitySettings.writeList(app, list))
                        put("applied", JSONArray(list))
                    }
                    "restore" -> {
                        val snapshot = Prefs.panicSnapshotAccessibility.value
                        put("ok", snapshot.isNotBlank() && AccessibilitySettings.writeList(app, snapshot.split(':')))
                        put("applied", snapshot)
                    }
                    else -> {
                        val array = json.optJSONArray("services") ?: JSONArray()
                        val list = buildList {
                            for (i in 0 until array.length()) add(array.optString(i))
                        }
                        put("ok", AccessibilitySettings.writeList(app, list))
                        put("applied", JSONArray(list))
                    }
                }
                put("enabled", JSONArray(AccessibilitySettings.enabledList(app)))
            }

            path == "/accessibility" -> JSONObject().apply {
                put("raw", AccessibilitySettings.rawValue(app))
                put("enabled", JSONArray(AccessibilitySettings.enabledList(app)))
                put("selfComponent", AccessibilitySettings.componentId(app))
                put("selfListed", AccessibilitySettings.isSelfListed(app))
                put("selfBound", AccessibilitySettings.isSelfEnabledBySystem(app))
                put("masterEnabled", AccessibilitySettings.isAccessibilityEnabled(app))
                put("whitelist", JSONArray(plane.permittedAccessibilityServices() ?: emptyList<String>()))
                put("sensitiveLocked", Prefs.sensitiveLockAt.value > 0)
                put("snapshot", Prefs.panicSnapshotAccessibility.value)
            }

            path == "/whitelist" -> JSONObject().apply {
                put("accessWhitelistDevice", JSONArray(plane.permittedAccessibilityServices() ?: emptyList<String>()))
                put("accessWhitelistSelection", JSONArray(Prefs.accessWhitelistSelection.value.toList()))
                put("appWhitelist", JSONArray(Prefs.trustedPackages.value.toList()))
            }

            path == "/app-whitelist" && method == "POST" -> JSONObject().apply {
                val json = JSONObject(body.ifEmpty { "{}" })
                if (json.optBoolean("clear", false)) {
                    Prefs.setTrustedPackages(emptySet())
                    Prefs.setAccessWhitelistSelection(emptySet())
                    put("ok", true)
                    put("appWhitelist", JSONArray())
                } else {
                    val array = json.optJSONArray("packages") ?: JSONArray()
                    val set = buildSet { for (i in 0 until array.length()) add(array.optString(i)) }.filter { it.isNotBlank() }.toSet()
                    Prefs.setTrustedPackages(set)
                    // 应用白名单默认包含进无障碍白名单
                    Prefs.setAccessWhitelistSelection(Prefs.accessWhitelistSelection.value + set)
                    put("ok", true)
                    put("appWhitelist", JSONArray(set.toList()))
                    put("accessWhitelistSelection", JSONArray(Prefs.accessWhitelistSelection.value.toList()))
                }
            }

            path == "/ui" -> JSONObject().apply {
                val snapshot = wzmwayne.shield.accessibility.ShieldAccessibilityService.uiSnapshot()
                if (snapshot == null) put("error", "无障碍服务未运行或无活动窗口") else put("ui", snapshot)
            }

            path == "/record" && method == "POST" -> JSONObject().apply {
                when (queryParam(query, "action")) {
                    "start" -> PageRecorder.start()
                    "stop" -> PageRecorder.stop()
                    "clear" -> PageRecorder.clear()
                }
                put("recording", PageRecorder.recording)
                put("count", PageRecorder.count())
            }

            path == "/record" -> JSONObject().apply {
                put("recording", PageRecorder.recording)
                put("count", PageRecorder.count())
                put("records", PageRecorder.snapshot())
            }

            path == "/log" -> JSONObject().apply {
                put("panicLog", Prefs.panicLog.value)
                put("events", JSONArray().apply {
                    Prefs.events.value.take(60).forEach { put(it.title + "：" + it.detail) }
                })
            }

            path == "/panic" && method == "POST" -> JSONObject().apply {
                val action = JSONObject(body.ifEmpty { "{}" }).optString("action", "trigger")
                val message = if (action == "restore") PanicResponse.restore(app)
                else PanicResponse.trigger(app, "调试接口")
                put("action", action)
                put("result", message)
            }

            path == "/policy" && method == "POST" -> JSONObject().apply {
                val json = JSONObject(body.ifEmpty { "{}" })
                val id = json.optString("id")
                val enabled = json.optBoolean("enabled", true)
                val policy = Policy.entries.firstOrNull { it.id == id }
                if (policy == null) {
                    put("error", "未知策略：$id")
                } else {
                    Policies.setOn(policy, enabled)
                    put("id", id)
                    put("enabled", Policies.isOn(policy))
                }
            }

            path == "/access-whitelist" && method == "POST" -> JSONObject().apply {
                val json = JSONObject(body.ifEmpty { "{}" })
                if (!json.optBoolean("clear", false) && !Policies.whitelistEnabled()) {
                    put("ok", false)
                    put("error", "「无障碍白名单限制」策略已关闭，拒绝写入")
                } else if (json.optBoolean("clear", false)) {
                    put("ok", plane.clearAccessibilityWhitelist())
                    put("result", "cleared")
                } else {
                    val list = mutableListOf(AccessibilityWhitelist.SELF_PACKAGE)
                    val array = json.optJSONArray("packages") ?: JSONArray()
                    for (i in 0 until array.length()) list += array.optString(i)
                    val distinct = list.filter { it.isNotBlank() }.distinct()
                    put("ok", plane.updateAccessibilityWhitelist(distinct))
                    put("applied", JSONArray(distinct))
                }
            }

            else -> JSONObject().put("error", "未知接口：$method $path")
        }
    }

    private fun respondBytes(out: OutputStream, code: Int, contentType: String, bytes: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 $code OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun respond(out: OutputStream, code: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Unauthorized"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(payload)
            out.flush()
        }
    }
}
