package com.example.llama

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.AiChat
import com.zygote.agent.AgentEvent
import com.zygote.agent.AgentLoop
import com.zygote.agent.NativeModelBackend
import com.zygote.agent.PermissionKind
import com.zygote.agent.PhoneTools
import com.zygote.agent.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ON-DEVICE spine proof: loads the real native LFM2.5-230M model via the
 * llama.cpp .so, drives AgentLoop through a full tool-calling turn, and logs
 * the entire trajectory to logcat. This exercises: native inference, the LFM2.5
 * chat template, tool-call parsing, registry dispatch, and the shell tool.
 *
 * Run:  ./gradlew :app:connectedDebugAndroidTest  (or adb shell am instrument)
 * Watch: adb logcat -s ZygoteSpine
 */
@RunWith(AndroidJUnit4::class)
class ZygoteSpineTest {

    private val TAG = "ZygoteSpine"

    @Test
    fun agentRunsToolCallTurnOnDevice() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Locate a model GGUF (use the fast 230M first; fall back to 2.6B).
        val modelsDir = File(ctx.filesDir, "models")
        val gguf = modelsDir.listFiles()
            ?.filter { it.name.endsWith(".gguf") }
            ?.sortedBy { it.length() }
            ?.firstOrNull()
        assertTrue("No .gguf in ${modelsDir.path}", gguf != null)
        Log.i(TAG, "using model: ${gguf!!.absolutePath} (${gguf.length() / 1_000_000} MB)")

        // 2. Build the native engine + backend.
        val engine = AiChat.getInferenceEngine(ctx)
        engine.loadModel(gguf.absolutePath)
        val backend = NativeModelBackend(engine, systemPrompt = "You are Zygote, an on-device agent. Use tools when asked.")
        backend.ensureSystemPrompt()
        Log.i(TAG, "model loaded + system prompt set")

        // 3. Register the phone tools (shell works without accessibility).
        val registry = ToolRegistry()
        PhoneTools(ctx).registerAll(registry)
        val shellTool = registry.get("shell")
        assertTrue("shell tool registered", shellTool != null)

        // 4. Run a tool-calling turn.
        val loop = AgentLoop(model = backend, registry = registry)
        val events = mutableListOf<AgentEvent>()
        val userMsg = "Run the shell command `echo on-device-agent-alive` and report its output."
        var finalText = ""

        loop.run(
            userMessage = userMsg,
            systemPrompt = backendSystemPrompt(),
            sessionId = "spine-proof",
            askPermission = { _, _ -> true },  // auto-approve for the demo
            emit = { ev ->
                events.add(ev)
                when (ev) {
                    is AgentEvent.Token -> Log.i(TAG, "[token] ${ev.text}")
                    is AgentEvent.ToolStart -> Log.i(TAG, "[tool-start] ${ev.name} ${ev.args}")
                    is AgentEvent.ToolResultEvent -> Log.i(
                        TAG, "[tool-result] ${ev.name} -> ${ev.result}"
                    )
                    is AgentEvent.TurnComplete -> { finalText = ev.finalText; Log.i(TAG, "[complete] $finalText") }
                    is AgentEvent.Think -> Log.i(TAG, "[think] ${ev.text}")
                }
            },
        )

        // 5. Assert the spine worked.
        Log.i(TAG, "=== SPINE SUMMARY ===")
        Log.i(TAG, "events: ${events.size} | tool-calls: ${events.count { it is AgentEvent.ToolStart }} | final: $finalText")
        assertTrue("no events emitted", events.isNotEmpty())
        assertTrue(
            "expected a tool call; got ${events.map { it.javaClass.simpleName }}",
            events.any { it is AgentEvent.ToolStart }
        )
        engine.cleanUp()
    }

    private fun backendSystemPrompt(): String =
        "You are Zygote, a fully on-device agent. You control this phone. " +
            "When asked to run a command, use the shell tool and report the output."

    /**
     * Proves the accessibility bridge: read_screen must return a real view
     * hierarchy (not "not connected"), and a tap gesture must dispatch.
     * Requires PhoneControlService to be enabled in Settings → Accessibility.
     */
    @Test
    fun phoneToolsReadScreenAndTapOnDevice() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = ToolRegistry()
        PhoneTools(ctx).registerAll(registry)

        // Bring our own activity to the foreground so there is a window to read.
        val intent = android.content.Intent(ctx, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        Thread.sleep(3000)

        val read = registry.get("read_screen")
        assertTrue("read_screen registered", read != null)
        val res = read!!.handler(
            com.zygote.agent.ToolContext("a11y-proof") { _, _ -> true },
            mapOf("max_elements" to 40),
        )
        Log.i(TAG, "[read_screen] -> $res")
        val ok = res as? com.zygote.agent.ToolResult.Ok
        assertTrue("read_screen should succeed, got: $res", ok != null)
        assertTrue(
            "read_screen should return real content, got: ${ok!!.output.take(120)}",
            ok.output.isNotBlank() && !ok.output.startsWith("ERROR") &&
                !ok.output.contains("not connected")
        )
        Log.i(TAG, "[read_screen sample]\n${ok.output.take(600)}")

        // Tap somewhere harmless (center of screen = our launcher/chat UI).
        val tap = registry.get("tap")
        assertTrue("tap registered", tap != null)
        val tapRes = tap!!.handler(
            com.zygote.agent.ToolContext("a11y-proof") { _, _ -> true },
            mapOf("x" to 540, "y" to 900),
        )
        Log.i(TAG, "[tap] -> $tapRes")
        assertTrue("tap should dispatch, got: $tapRes", tapRes is com.zygote.agent.ToolResult.Ok)
    }
}
