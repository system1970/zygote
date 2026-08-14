package com.zygote.agent

/**
 * LFM2.5 chat-template + tool-call formatting.
 *
 * Exact format per docs.liquid.ai/lfm/key-concepts/chat-template and /tool-use:
 *
 *   <|startoftext|><|im_start|>system\n…<|im_end|>
 *   <|im_start|>user\n…<|im_end|>
 *   <|im_start|>assistant\n<|tool_call_start|>[func(k="v")]<|tool_call_end|>…<|im_end|>
 *   <|im_start|>tool\n<result><|im_end|>
 *   <|im_start|>assistant\n
 *
 * Pure — no Android deps, so it is unit-testable on the JVM.
 */
object Lfm2Format {

    const val START = "<|startoftext|>"
    const val IM_START = "<|im_start|>"
    const val IM_END = "<|im_end|>"
    const val TOOL_CALL_START = "<|tool_call_start|>"
    const val TOOL_CALL_END = "<|tool_call_end|>"

    /**
     * Render a full conversation to the prompt the model sees.
     *
     * @param addGenerationPrompt when true, appends a trailing
     *   `<|im_start|>assistant\n` so the model continues as the assistant.
     */
    fun formatMessages(
        messages: List<ChatMessage>,
        addGenerationPrompt: Boolean = true,
    ): String {
        val sb = StringBuilder(START)
        for (m in messages) {
            sb.append(IM_START).append(m.role).append('\n')
                .append(m.content)
                .append(IM_END).append('\n')
        }
        if (addGenerationPrompt) {
            sb.append(IM_START).append("assistant\n")
        }
        return sb.toString()
    }

    /**
     * Render one tool call in LFM2.5's Pythonic format:
     *   <|tool_call_start|>[func_name(arg="val", n=1)]<|tool_call_end|>
     */
    fun renderToolCall(name: String, args: Map<String, Any?>): String {
        val argStr = args.entries.joinToString(", ") { (k, v) -> "$k=${renderArg(v)}" }
        return "$TOOL_CALL_START" +
            "[$name($argStr)]" +
            "$TOOL_CALL_END"
    }

    private fun renderArg(v: Any?): String = when (v) {
        is String -> "\"${v.replace("\"", "\\\"")}\""
        is Boolean -> v.toString()
        is Number -> v.toString()
        null -> "None"
        else -> "\"$v\""
    }

    /** The content the assistant message carries when it emits a tool call. */
    fun assistantToolCallMessage(name: String, args: Map<String, Any?>): String =
        renderToolCall(name, args)
}
