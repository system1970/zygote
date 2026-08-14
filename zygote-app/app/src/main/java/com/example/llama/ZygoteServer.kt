package com.example.llama

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.arm.aichat.AiChat
import com.zygote.agent.AgentEvent
import com.zygote.agent.AgentLoop
import com.zygote.agent.ChatMessage
import com.zygote.agent.NativeModelBackend
import com.zygote.agent.PermissionKind
import com.zygote.agent.PhoneTools
import com.zygote.agent.SessionStore
import com.zygote.agent.TodoStore
import com.zygote.agent.ToolRegistry
import com.zygote.agent.ToolResult
import com.zygote.agent.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygote local server: serves the PWA (from assets) and the /v1 API that
 * drives the on-device harness. Bound to 127.0.0.1 only — nothing leaves
 * the device. This is the seam the PWA talks to (zygote-pwa/src/lib/api.ts).
 *
 * Endpoints:
 *   GET  /v1/telemetry              -> { model, tok_per_sec, ttft_ms, ram_mb, battery_pct, ... }
 *   POST /v1/agent/run              -> SSE stream of AgentEvents (text, think, tool_start, tool_result)
 *   GET  /v1/skills                 -> ["shell", "read_screen", ...]
 *   GET  /v1/session/{id}           -> trajectory (placeholder JSON)
 *   everything else                 -> static file from assets/zygote-pwa/
 */
class ZygoteServer(private val ctx: Context, private val port: Int = 8787) : Runnable {

    companion object {
        private const val TAG = "ZygoteServer"
        private const val ASSET_ROOT = "zygote-pwa"
        private const val SYSTEM_PROMPT =
            "You are Zygote, a fully on-device agent running on this phone. " +
                "You can see the screen, run commands, and control the device. " +
                "Use the provided tools when they help. Be concise."
        @Volatile private var instance: ZygoteServer? = null
        private val started = AtomicBoolean(false)

        fun start(context: Context): ZygoteServer {
            val app = context.applicationContext
            if (instance == null) {
                instance = ZygoteServer(app)
            }
            val s = instance!!
            if (started.compareAndSet(false, true)) {
                Thread(s, "zygote-server").start()
            }
            return s
        }
    }

    private val server: ServerSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private var engine = AiChat.getInferenceEngine(ctx)
    private val registry = ToolRegistry().also {
        PhoneTools(ctx).registerAll(it)
        registerTodoTool(it)
    }
    private val modelsDir = File(ctx.filesDir, "models")
    private val sessions = SessionStore(File(ctx.filesDir, "sessions"))
    private val todos = TodoStore(File(ctx.filesDir, "todos"))
    @Volatile private var modelLoaded = false

    /** opencode-style to-do tool: the agent can plan and track progress. */
    private fun registerTodoTool(reg: ToolRegistry) {
        reg.register(
            ToolSpec(
                name = "todo_read",
                description = "List the current to-do items for this session. Call this BEFORE " +
                    "updating a todo so you know the exact items and positions.",
                parameters = emptyMap(),
                permission = PermissionKind.SAFE,
                handler = { ctx, _ ->
                    val items = todos.read(ctx.sessionId)
                    if (items.isEmpty()) ToolResult.Ok("No todos yet for this session.")
                    else ToolResult.Ok(items.joinToString("; ") {
                        "[${it.position}] ${it.status} ${it.content}"
                    })
                }
            )
        )
        reg.register(
            ToolSpec(
                name = "todo_write",
                description = "Create or update a to-do item for the current session. " +
                    "Position is the slot index (0-based); empty content deletes the slot. " +
                    "Status: todo | in-progress | done | canceled. Use todo_read first to see items.",
                parameters = mapOf(
                    "content" to mapOf("type" to "string", "description" to "Task text"),
                    "status" to mapOf("type" to "string", "description" to "todo|in-progress|done|canceled"),
                    "priority" to mapOf("type" to "string", "description" to "low|normal|high"),
                    "position" to mapOf("type" to "integer", "description" to "Slot index (0-based)"),
                ),
                permission = PermissionKind.SAFE,
                handler = { ctx, args ->
                    val content = args["content"] as? String ?: ""
                    val status = args["status"] as? String ?: "todo"
                    val priority = args["priority"] as? String ?: "normal"
                    val position = (args["position"] as? Number)?.toInt() ?: 0
                    val updated = todos.write(ctx.sessionId, content, status, priority, position)
                    val done = updated.count { it.status == "done" }
                    ToolResult.Ok("${done}/${updated.size} todos done: " +
                        updated.joinToString("; ") { "[${it.position}] ${it.status} ${it.content}" })
                }
            )
        )
    }

    override fun run() {
        Log.i(TAG, "listening on http://127.0.0.1:$port")
        // Load the fast model up-front so the first agent run doesn't stall.
        Thread { loadBestModel() }.start()
        while (!server.isClosed) {
            try {
                val client = server.accept()
                Thread { handle(client) }.start()
            } catch (e: Exception) {
                if (!server.isClosed) Log.e(TAG, "accept failed", e)
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 120_000
            val req = parseRequest(socket.getInputStream())
            if (req == null) {
                socket.close()
                return
            }
            val (method, rawPath) = req
            val path = URLDecoder.decode(rawPath.split("?").first(), "UTF-8")
            Log.i(TAG, "$method $path")

            when {
                method == "GET" && path.startsWith("/v1/telemetry") -> {
                    // Optional ?session=<id> scopes counters to that session.
                    val sid = Regex("[?&]session=([^&]*)").find(rawPath)?.groupValues?.get(1)
                    respondJson(socket, telemetryJson(sid))
                }
                method == "POST" && path == "/v1/agent/run" -> handleAgentRun(socket, readBody(socket))
                method == "POST" && path == "/v1/model" -> handleModelSwitch(socket, readBody(socket))
                method == "GET" && path == "/v1/skills" -> respondJson(socket, skillsJson())
                method == "GET" && path == "/v1/sessions" -> respondJson(socket, sessions.listWithMeta())
                method == "POST" && path == "/v1/sessions" -> respondJson(
                    socket, """{"session_id":"${sessions.create()}"}"""
                )
                method == "GET" && path.startsWith("/v1/session/") && path.endsWith("/todos") ->
                    respondJson(socket, todos.toJson(path.removePrefix("/v1/session/").removeSuffix("/todos")))
                method == "GET" && path.startsWith("/v1/session/") -> respondJson(socket, sessionJson(path))
                else -> serveStatic(socket, path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handle failed: ${e.message}")
            try { respond(socket, 500, "text/plain", "error: ${e.message}") } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Writes SSE headers; returns false if the socket is already gone. */
    private fun startStream(socket: Socket): Boolean {
        return try {
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: text/event-stream\r\n".toByteArray())
            out.write("Cache-Control: no-cache\r\n".toByteArray())
            out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- API ----------
    // Live telemetry state (measured from real agent runs, not hardcoded).
    // Per-session stats so the UI can show THIS session's numbers, not a
    // server-lifetime aggregate.
    data class SessionTelemetry(
        var turns: Int = 0,
        var steps: Int = 0,
        var llmMs: Long = 0L,
        var toolMs: Long = 0L,
        var inputChars: Long = 0L,
        var lastTokPerSec: Double = 0.0,
        var lastTtftMs: Long = -1L,
    )

    private val sessionTelemetry = java.util.concurrent.ConcurrentHashMap<String, SessionTelemetry>()
    @Volatile private var lastModel = ""

    private fun telemetryJson(sessionId: String?): String {
        val memInfo = File("/proc/meminfo").readText()
        val memAvailableMb = (Regex("MemAvailable:\\s+(\\d+)").find(memInfo)?.groupValues?.get(1)?.toLongOrNull()
            ?: 0L) / 1024L
        val battery = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val modelName = loadedModelName()
        val s = sessionId?.let { sessionTelemetry[it] }
        val turns = s?.turns ?: 0
        val steps = s?.steps ?: 0
        val llmMs = s?.llmMs ?: 0L
        val toolMs = s?.toolMs ?: 0L
        // Prompt-token estimate: ~4 chars/token for LFM2.5 (byte-pair-ish).
        val inputTokens = ((s?.inputChars ?: 0L) / 4L).toInt()
        val tokPerSec = s?.lastTokPerSec ?: 0.0
        val ttftMs = s?.lastTtftMs ?: -1L
        return """{
          "model": "$modelName",
          "tok_per_sec": $tokPerSec,
          "ttft_ms": $ttftMs,
          "ram_mb": $memAvailableMb,
          "battery_pct": $batteryPct,
          "turns": $turns,
          "steps": $steps,
          "llm_time_ms": $llmMs,
          "tool_time_ms": $toolMs,
          "input_tokens": $inputTokens
        }"""
    }

    private fun skillsJson(): String {
        val names = registry.getDefinitions().mapNotNull { def ->
            (def["function"] as? Map<*, *>)?.get("name") as? String
        }
        return """["${names.joinToString("\",\"")}"]"""
    }

    private fun sessionJson(path: String): String {
        val id = path.removePrefix("/v1/session/")
        return sessions.trajectoryJson(id)
    }

    private fun handleAgentRun(socket: Socket, body: String) {
        val message = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        Log.i(TAG, "agent/run: message=$message body=${body.take(120)}")
        if (message == null) return
        val sessionId = Regex("\"session_id\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "anon"

        respondStreaming(socket) { out ->
            runBlocking {
                Log.i(TAG, "agent/run: waiting for model (loaded=$modelLoaded)")
                // Wait for the startup model load (bounded), so the stream doesn't
                // die before the first event — the "harness offline" bug. Also
                // waits out any in-flight model switch (race fix: a run must
                // never call sendUserPrompt mid-teardown, or it gets 0 tokens).
                val deadline = System.currentTimeMillis() + 60_000
                while ((!modelLoaded || modelSwitching) && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(100)
                }
                if (!modelLoaded) {
                    Log.i(TAG, "agent/run: model NOT loaded, sending error event")
                    out(sseJson(AgentEvent.Think("Model still loading…")))
                    out(sseJson(AgentEvent.TurnComplete("Zygote model is not loaded yet. Try again in a few seconds.")))
                    return@runBlocking
                }

                Log.i(TAG, "agent/run: model loaded, admitting message")
                // Admission (opencode pattern): the user message is durably logged
                // before the model ever runs, so nothing is lost on crash.
                sessions.create(sessionId)
                sessions.append(sessionId, "user", message)
                // Relevance filter (Liquid docs: "only include tools relevant to
                // the current request") — a 2000-token tool list at 2.6B prefill
                // speeds is seconds of dead time per turn.
                val tools = relevantTools(message)
                // Title: opencode pattern — generated once, from the first user
                // message, fire-and-forget (here: instant heuristic).
                if (sessions.title(sessionId).isBlank()) {
                    sessions.setTitle(sessionId, sessions.deriveTitle(message))
                }
                Log.i(TAG, "agent/run: building loop")

                val backend = NativeModelBackend(
                    engine, systemPrompt = SYSTEM_PROMPT, systemAlreadySet = true
                )
                val loop = AgentLoop(model = backend, registry = registry)
                backend.ensureSystemPrompt()
                // Resume: reconstruct prior context (Claude Code --continue).
                // The just-admitted current message is the last "user" entry —
                // drop only that one; AgentLoop re-adds it below.
                val logged = sessions.messages(sessionId)
                val resumed = if (logged.lastOrNull()?.role == "user") logged.dropLast(1) else logged

                // Live telemetry for THIS run — segment-accurate: generation
                // time excludes tool-gap time (each contiguous token burst is
                // its own segment, closed on ToolStart or at run end).
                val runStart = System.currentTimeMillis()
                var firstTokenAt = -1L
                var segStart = -1L   // start of the current token burst
                var segEnd = -1L     // end of the current token burst
                var llmMsThisRun = 0L
                var tokenCount = 0
                var toolStartAt = -1L
                var toolMsThisRun = 0L

                fun closeSegment() {
                    if (segStart > 0 && segEnd >= segStart) {
                        llmMsThisRun += segEnd - segStart
                    }
                    segStart = -1
                    segEnd = -1
                }

                val st = sessionTelemetry.getOrPut(sessionId) { SessionTelemetry() }
                st.inputChars += message.length
                st.turns++
                runInFlight = true
                try {
                loop.run(
                    userMessage = message,
                    systemPrompt = SYSTEM_PROMPT,
                    sessionId = sessionId,
                    askPermission = { _, _ -> true },
                    emit = { ev ->
                        out(sseJson(ev))
                        // Visibility ⟺ logged: everything streamed is appended.
                        when (ev) {
                            is AgentEvent.Token -> {
                                val now = System.currentTimeMillis()
                                if (firstTokenAt < 0) firstTokenAt = now
                                if (segStart < 0) segStart = now
                                segEnd = now
                                tokenCount++
                            }
                            is AgentEvent.Think -> sessions.append(sessionId, "think", ev.text)
                            is AgentEvent.ThinkDelta -> { /* streamed live only; final Think is logged */ }
                            is AgentEvent.ToolStart -> {
                                closeSegment()
                                toolStartAt = System.currentTimeMillis()
                                st.steps++
                                sessions.append(
                                    sessionId, "tool_start", "${ev.name}(${ev.args})"
                                )
                            }
                            is AgentEvent.ToolResultEvent -> {
                                if (toolStartAt > 0) {
                                    toolMsThisRun += System.currentTimeMillis() - toolStartAt
                                    toolStartAt = -1
                                }
                                sessions.append(
                                    sessionId, "tool_result", "${ev.name}: ${ev.result}"
                                )
                            }
                            is AgentEvent.TurnComplete -> sessions.append(sessionId, "assistant", ev.finalText)
                        }
                    },
                    initialHistory = resumed,
                    tools = tools,
                )
                closeSegment()

                // Fold this run's measurements into the per-session telemetry.
                st.llmMs += llmMsThisRun
                st.toolMs += toolMsThisRun
                st.lastTtftMs = if (firstTokenAt > 0) firstTokenAt - runStart else -1L
                st.lastTokPerSec = if (llmMsThisRun > 0) (tokenCount * 1000.0 / llmMsThisRun) else 0.0
                lastModel = loadedModelName()
                out(sseJson(AgentEvent.TurnComplete("")))
                } finally {
                    runInFlight = false
                }
            }
        }
    }

    /** PWA-contract SSE: one `data: {json}` frame per event (see api.ts AgentEvent). */
    private fun sseJson(ev: AgentEvent): String = when (ev) {
        is AgentEvent.Token -> "data: {\"type\":\"text_delta\",\"delta\":${jsonEscape(ev.text)}}\n\n"
        is AgentEvent.Think -> "data: {\"type\":\"think\",\"text\":${jsonEscape(ev.text)}}\n\n"
        is AgentEvent.ThinkDelta ->
            "data: {\"type\":\"think_delta\",\"delta\":${jsonEscape(ev.text)}}\n\n"
        is AgentEvent.ToolStart -> "data: {\"type\":\"tool_start\",\"name\":\"${ev.name}\",\"args\":${jsonEscape(ev.args.toString())}}\n\n"
        is AgentEvent.ToolResultEvent ->
            "data: {\"type\":\"tool_result\",\"name\":\"${ev.name}\",\"output\":${jsonEscape(ev.result.toString())}}\n\n"
        is AgentEvent.TurnComplete ->
            if (ev.finalText.isEmpty())
                "data: {\"type\":\"done\",\"model\":\"${loadedModelName()}\"}\n\n"
            else
                "data: {\"type\":\"done\",\"final_text\":${jsonEscape(ev.finalText)},\"model\":\"${loadedModelName()}\"}\n\n"
    }

    private fun jsonEscape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** Loads the best model present: 230M for fast startup/TTFT, else first GGUF. */
    private fun loadBestModel() {
        try {
            val candidates = modelsDir.listFiles()?.filter { it.name.endsWith(".gguf") }?.sortedBy { it.length() }
                ?: emptyList()
            if (candidates.isEmpty()) {
                Log.e(TAG, "no models in ${modelsDir.path}")
                return
            }
            // Prefer the fast chat model (1.2B-Instruct, docs-recommended), then
            // the tiny router (230M); both are KleidiAI Q4_0 fast paths.
            val pick = candidates.firstOrNull { it.name.contains("1.2B") }
                ?: candidates.firstOrNull { it.name.contains("230M") }
                ?: candidates.first()
            loadedModelFile = pick.name
            Log.i(TAG, "loading model: ${pick.name} (${pick.length() / 1048576} MB)")
            val started = System.currentTimeMillis()
            kotlinx.coroutines.runBlocking {
                engine.loadModel(pick.absolutePath)
                engine.setSystemPrompt(SYSTEM_PROMPT)
            }
            modelLoaded = true
            Log.i(TAG, "model ready in ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            Log.e(TAG, "model load failed: ${e.message}")
        }
    }

    /** POST /v1/model — single-model build: only the 1.2B exists; no-op unless
     *  a different GGUF was manually pushed (e.g. a new quant for testing). */
    private fun handleModelSwitch(socket: Socket, body: String) {
        val requested = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: ""
        // Only honor requests for models that are actually present on device.
        val target = modelsDir.listFiles()?.firstOrNull {
            it.name.endsWith(".gguf") && (requested.contains("1.2B") || requested.contains(it.name))
        }
        if (target == null || target.name == loadedModelFile) {
            respondJson(socket, """{"ok":true,"model":"${loadedModelName()}"}""")
            return
        }
        // Set the switching flag SYNCHRONOUSLY here, before the worker thread
        // even spawns — otherwise a run can slip in between "request accepted"
        // and "thread starts", see modelLoaded=true, and get torn down
        // mid-generation by cleanUp() → zero tokens.
        modelSwitching = true
        modelLoaded = false
        Thread {
            try {
                // CRITICAL: never tear down the engine while a run is using it.
                // A run holds runInFlight from loop start to finish; the switch
                // waits (bounded) for it instead of corrupting native state.
                var waited = 0
                while (runInFlight && waited < 60000) {
                    Thread.sleep(100)
                    waited += 100
                }
                kotlinx.coroutines.runBlocking {
                    engine.cleanUp()
                    loadedModelFile = target.name
                    engine.loadModel(target.absolutePath)
                    engine.setSystemPrompt(SYSTEM_PROMPT)
                    modelLoaded = true
                }
                Log.i(TAG, "model switched to ${target.name}")
            } catch (e: Exception) {
                Log.e(TAG, "model switch failed: ${e.message}")
                modelLoaded = false
            } finally {
                modelSwitching = false
            }
        }.start()
        respondJson(socket, """{"ok":true,"model":"${target.name}","loading":true}""")
    }

    /**
     * Relevance filter (Liquid docs: "only include tools relevant to the current
     * request"). Always: todo_read/todo_write/shell. Add phone tools by intent.
     * Cuts prefill tokens by 5-10x on simple turns.
     */
    private fun relevantTools(message: String): List<Map<String, Any>> {
        val m = message.lowercase()
        val all = registry.getDefinitions()
        fun has(vararg words: String) = words.any { m.contains(it) }
        return all.filter { def ->
            val name = (def["function"] as? Map<*, *>)?.get("name") as? String ?: return@filter false
            when (name) {
                "todo_read", "todo_write", "shell" -> true
                "read_screen" -> has("screen", "read", "see", "show", "display", "look", "ui", "app")
                "tap", "long_press" -> has("tap", "click", "press", "touch", "open", "launch")
                "type_text" -> has("type", "write", "enter", "input", "text")
                "swipe" -> has("swipe", "scroll", "slide")
                "open_app" -> has("open", "launch", "app", "start")
                "call" -> has("call", "dial", "phone")
                "send_sms" -> has("sms", "text ", "message", "whatsapp", "send ")
                else -> false
            }
        }
    }

    /** GET /v1/sessions -> session list for the picker (newest first). */
    private fun loadedModelName(): String =
        modelsDir.listFiles()?.firstOrNull { it.name == loadedModelFile }?.name
            ?: modelsDir.listFiles()?.firstOrNull { it.name.endsWith(".gguf") }?.name
            ?: "none"

    @Volatile private var loadedModelFile: String? = null
    @Volatile private var modelSwitching = false
    @Volatile private var runInFlight = false

    // ---------- static files ----------

    private fun serveStatic(socket: Socket, path: String) {
        val relative = if (path == "/" || path.isEmpty()) "index.html" else path.removePrefix("/")
        val assetPath = "$ASSET_ROOT/$relative"
        val mime = when {
            relative.endsWith(".html") -> "text/html; charset=utf-8"
            relative.endsWith(".js") -> "application/javascript"
            relative.endsWith(".css") -> "text/css"
            relative.endsWith(".svg") -> "image/svg+xml"
            relative.endsWith(".png") -> "image/png"
            relative.endsWith(".webmanifest") -> "application/manifest+json"
            else -> "application/octet-stream"
        }
        val bytes = try {
            ctx.assets.open(assetPath).use { it.readBytes() }
        } catch (e: Exception) {
            respond(socket, 404, "text/plain", "not found: $relative")
            return
        }
        respondBytes(socket, 200, mime, bytes)
    }

    // ---------- HTTP plumbing ----------

    private fun parseRequest(input: java.io.InputStream): Pair<String, String>? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            // '\r' was never appended, so nothing to drop — return the line as-is.
            if (prev == '\r'.code && b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            prev = b
        }
    }

    private fun readBody(socket: Socket): String {
        val header = StringBuilder()
        while (true) {
            val l = readLine(socket.getInputStream()) ?: break
            if (l.isEmpty()) break
            header.append(l).append('\n')
        }
        val len = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(header.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = socket.getInputStream().read(buf, read, len - read)
            if (n == -1) break
            read += n
        }
        return String(buf, 0, read, StandardCharsets.UTF_8)
    }

    private fun respondJson(socket: Socket, json: String) =
        respond(socket, 200, "application/json", json)

    private fun respond(socket: Socket, code: Int, mime: String, body: String) =
        respondBytes(socket, code, mime, body.toByteArray(StandardCharsets.UTF_8))

    private fun respondBytes(socket: Socket, code: Int, mime: String, body: ByteArray) {
        val out = socket.getOutputStream()
        val reason = if (code == 200) "OK" else "ERROR"
        out.write("HTTP/1.1 $code $reason\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n".toByteArray())
        out.write("Content-Length: ${body.size}\r\n".toByteArray())
        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    /** Streaming SSE response; the writer is called per event and flushed. */
    private fun respondStreaming(socket: Socket, emit: (suspend (String) -> Unit) -> Unit) {
        if (!startStream(socket)) return
        emit { sseChunk ->
            try { socket.getOutputStream().write(sseChunk.toByteArray()); socket.getOutputStream().flush() }
            catch (_: Exception) {}
        }
    }
}
