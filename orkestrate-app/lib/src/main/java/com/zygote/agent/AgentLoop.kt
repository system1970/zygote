package com.zygote.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The agent loop: goal → model picks tool → harness executes → observe → repeat.
 *
 * This is the "skill picker" pattern made explicit: the model never does the
 * dangerous/reliable work itself — it only selects a tool and fills args. The
 * harness executes, feeds the result back, and loops until the model stops
 * requesting tools.
 *
 * Model-agnostic: it consumes a [ModelBackend] abstraction so the exact
 * inference engine (local llama.cpp .so, or an OpenAI-compatible endpoint)
 * is swappable — the DeepSeek seam idea, native.
 */
interface ModelBackend {
    /** Stream a single assistant turn. Returns a raw string that may contain
     *  tool-call markup (e.g. LFM2.5 `<|tool_call_start|>…<|tool_call_end|>`). */
    suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        onToken: suspend (String) -> Unit,
    ): String
}

data class ChatMessage(val role: String, val content: String)

/** A structured tool invocation the loop parses out of model output. */
data class ParsedToolCall(val name: String, val args: Map<String, Any?>)

/**
 * Parses model output into a sequence of tool calls.
 * Default implementation handles LFM2.5's native token format:
 * `<|tool_call_start|>[name(args)]<|tool_call_end|>`.
 * Override for other formats (Qwen3 JSON, etc.).
 */
fun interface ToolCallParser {
    fun parse(output: String): List<ParsedToolCall>

    companion object {
        /**
         * LFM2.5 exact format (per docs.liquid.ai/lfm/key-concepts/tool-use):
         *   <|tool_call_start|>[func_name(arg=val, arg2="x")]<|tool_call_end|>
         * Models may emit SEVERAL calls in one block:
         *   <|tool_call_start|>[f1(), f2(x=1)]<|tool_call_end|>
         * So: find every block, then parse every call inside it.
         */
        val LFM2_5 = ToolCallParser { out ->
            val blockRe = Regex("<\\|tool_call_start\\|>([\\s\\S]*?)<\\|tool_call_end\\|>")
            val callRe = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)")
            blockRe.findAll(out).flatMap { block ->
                callRe.findAll(block.groupValues[1]).map { m ->
                    ParsedToolCall(m.groupValues[1], parseArgs(m.groupValues[2]))
                }
            }.toList()
        }
    }
}

/** Minimal, tolerant `a=1, b="x"` → map parser (enough for skill-picking). */
private fun parseArgs(raw: String): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    // Split on commas not inside quotes/parens.
    val parts = mutableListOf<String>()
    var depth = 0; var inStr = false; var cur = StringBuilder()
    for (ch in raw) {
        when {
            ch == '"' -> { inStr = !inStr; cur.append(ch) }
            ch in "([{" -> { depth++; cur.append(ch) }
            ch in ")]}" -> { depth--; cur.append(ch) }
            ch == ',' && depth == 0 && !inStr -> { parts.add(cur.toString()); cur = StringBuilder() }
            else -> cur.append(ch)
        }
    }
    if (cur.isNotBlank()) parts.add(cur.toString())
    for (p in parts) {
        val eq = p.indexOf('=')
        if (eq < 0) continue
        val k = p.substring(0, eq).trim()
        val v = p.substring(eq + 1).trim()
        map[k] = coerceValue(v)
    }
    return map
}

private fun coerceValue(v: String): Any? {
    val t = v.trim()
    return when {
        t.startsWith("\"") && t.endsWith("\"") -> t.substring(1, t.length - 1)
        t == "true" -> true
        t == "false" -> false
        t.toLongOrNull() != null -> t.toLong()
        t.toDoubleOrNull() != null -> t.toDouble()
        else -> t
    }
}

/**
 * The main loop. Streams assistant tokens + tool events as a [Flow] of
 * [AgentEvent] so the UI can render the trajectory live.
 */
sealed interface AgentEvent {
    data class Token(val text: String) : AgentEvent
    data class Think(val text: String) : AgentEvent
    /** Incremental reasoning token — the PWA appends it to the open Think row. */
    data class ThinkDelta(val text: String) : AgentEvent
    data class ToolStart(val name: String, val args: Map<String, Any?>) : AgentEvent
    data class ToolResultEvent(val name: String, val result: ToolResult) : AgentEvent
    data class TurnComplete(val finalText: String) : AgentEvent
}

class AgentLoop(
    private val model: ModelBackend,
    private val registry: ToolRegistry,
    private val parser: ToolCallParser = ToolCallParser.LFM2_5,
    private val maxSteps: Int = 12,
) {
    private val history = mutableListOf<ChatMessage>()

    suspend fun run(
        userMessage: String,
        systemPrompt: String,
        sessionId: String,
        askPermission: suspend (PermissionKind, String) -> Boolean,
        emit: suspend (AgentEvent) -> Unit,
        initialHistory: List<ChatMessage> = emptyList(),
        tools: List<Map<String, Any>> = registry.getDefinitions(),
    ) {
        history.clear()
        // Resume pattern (Claude Code --continue): a resumed session starts
        // from its logged history so context survives app restarts.
        history.addAll(initialHistory)
        if (systemPrompt.isNotBlank() && history.none { it.role == "system" }) {
            history.add(ChatMessage("system", systemPrompt))
        }
        history.add(ChatMessage("user", userMessage))

        var steps = 0
        var turnText = StringBuilder()
        // Reasoning state machine: <think>…</think> blocks are emitted as
        // Think events (clickable in the PWA) and excluded from visible text.
        val thinkBuf = StringBuilder()
        var inThink = false
        val openTag = "<think>"
        val closeTag = "</think>"

        while (steps < maxSteps) {
            steps++
            val raw = model.generate(
                history.toList(),
                tools,
            ) { token ->
                // Route tokens: inside a think block → buffer; else → visible text.
                var i = 0
                while (i < token.length) {
                    if (!inThink) {
                        val idx = token.indexOf(openTag, i)
                        if (idx < 0) {
                            turnText.append(token, i, token.length)
                            break
                        }
                        turnText.append(token, i, idx)
                        inThink = true
                        i = idx + openTag.length
                    } else {
                        val idx = token.indexOf(closeTag, i)
                        if (idx < 0) {
                            // Stream reasoning live so the UI shows thinking
                            // instead of a silent wait.
                            val chunk = token.substring(i)
                            thinkBuf.append(chunk)
                            emit(AgentEvent.ThinkDelta(chunk))
                            break
                        }
                        val chunk = token.substring(i, idx)
                        thinkBuf.append(chunk)
                        if (chunk.isNotEmpty()) emit(AgentEvent.ThinkDelta(chunk))
                        emit(AgentEvent.Think(thinkBuf.toString().trim()))
                        thinkBuf.setLength(0)
                        inThink = false
                        i = idx + closeTag.length
                    }
                }
                emit(AgentEvent.Token(token))
            }

            val calls = parser.parse(raw)
            if (calls.isEmpty()) {
                // No more tools → the task is complete.
                // Flush any UNCLOSED <think> leftover (model hit the token cap
                // mid-reasoning) so the user still sees the thinking instead of
                // an empty answer.
                if (inThink && thinkBuf.isNotEmpty()) {
                    emit(AgentEvent.Think(thinkBuf.toString().trim()))
                    thinkBuf.setLength(0)
                    inThink = false
                }
                emit(AgentEvent.TurnComplete(turnText.toString()))
                history.add(ChatMessage("assistant", raw))
                return
            }

            // Tool call(s) present → execute each, append results, loop.
            for (call in calls) {
                emit(AgentEvent.ToolStart(call.name, call.args))
                val ctx = ToolContext(sessionId, askPermission)
                val result = registry.dispatch(call.name, call.args, ctx)
                emit(AgentEvent.ToolResultEvent(call.name, result))
                val out = when (result) {
                    is ToolResult.Ok -> result.output
                    is ToolResult.Error -> "ERROR: ${result.message}"
                }
                // Assistant turn carries the LFM2.5 tool-call markup.
                history.add(ChatMessage("assistant", Lfm2Format.assistantToolCallMessage(call.name, call.args)))
                history.add(ChatMessage("tool", out))
            }
            turnText.clear()
        }
        emit(AgentEvent.TurnComplete(turnText.toString()))
    }
}
