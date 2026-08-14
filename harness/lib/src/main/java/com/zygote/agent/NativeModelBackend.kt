package com.zygote.agent

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect

/**
 * ModelBackend that drives the native on-device engine (llama.cpp .so via JNI).
 *
 * The native [InferenceEngine] is a single-user-prompt engine: load → setSystemPrompt
 * → sendUserPrompt. For a tool loop the model must see the FULL conversation each turn
 * (user + assistant tool-call + tool result + next request), so this backend formats the
 * whole history into one LFM2.5 chat-template prompt and sends it as a single user prompt.
 * The model's raw output (which may contain <|tool_call_start|>…<|tool_call_end|> markup)
 * is returned for the AgentLoop to parse.
 *
 * NOTE on token cost: because the engine accumulates state internally, sending the full
 * formatted history each turn is slightly redundant. A future native upgrade can add a
 * stateless completion entry point to ai_chat.cpp and this backend can call it directly.
 */
class NativeModelBackend(
    private val engine: InferenceEngine,
    private val systemPrompt: String = "",
) : ModelBackend {

    private var systemSet = false

    /** Must be called once the model is loaded. */
    suspend fun ensureSystemPrompt() {
        if (!systemSet) {
            if (systemPrompt.isNotBlank()) {
                engine.setSystemPrompt(systemPrompt)
            }
            systemSet = true
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        onToken: (String) -> Unit,
    ): String {
        // Ensure the system prompt is installed on the engine state machine.
        ensureSystemPrompt()

        // Inject the tool catalog as a system message if tools are present.
        val full = if (tools.isNotEmpty()) {
            val toolListJson = toolsToJson(tools)
            listOf(ChatMessage("system", "List of tools: $toolListJson")) + messages
        } else {
            messages
        }

        // Render the ENTIRE conversation as one LFM2.5 prompt.
        val prompt = Lfm2Format.formatMessages(full, addGenerationPrompt = true)

        val sb = StringBuilder()
        engine.sendUserPrompt(prompt, 1024).collect { token ->
            sb.append(token)
            onToken(token)
        }
        return sb.toString()
    }

    private fun toolsToJson(tools: List<Map<String, Any>>): String {
        // Strip to the function schema shape LFM2.5 expects.
        val slim = tools.map { it["function"] ?: it }
        // Minimal JSON-ish render. A proper serializer (kotlinx-serialization / org.json)
        // can be dropped in; keep it dependency-free here.
        val parts = slim.map { fn ->
            val name = (fn as? Map<*, *>)?.get("name")
            val desc = (fn as? Map<*, *>)?.get("description")
            val params = (fn as? Map<*, *>)?.get("parameters")
            buildString {
                append("{\"name\":\"").append(name).append("\",")
                append("\"description\":\"").append(desc).append("\",")
                append("\"parameters\":").append(renderParams(params))
                append("}")
            }
        }
        return "[" + parts.joinToString(",") + "]"
    }

    private fun renderParams(params: Any?): String {
        if (params is Map<*, *>) {
            return mapToJson(params)
        }
        return "{}"
    }

    private fun mapToJson(m: Map<*, *>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":")
            sb.append(valueToJson(v))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun listToJson(l: List<*>): String =
        "[" + l.joinToString(",") { valueToJson(it) } + "]"

    private fun valueToJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean, is Number -> v.toString()
        is Map<*, *> -> mapToJson(v)
        is List<*> -> listToJson(v)
        else -> "\"$v\""
    }
}
