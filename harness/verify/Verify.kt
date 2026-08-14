package com.zygote.agent

/**
 * Pure-JVM verification of Lfm2Format + AgentLoop parser against the documented
 * LFM2.5 examples (docs.liquid.ai). No Android deps. Run with:
 *   kotlinc Lfm2Format.kt AgentLoop.kt Tool.kt ToolRegistry.kt Verify.kt -include-runtime -d v.jar && java -jar v.jar
 */
object Verify {
    private var failures = 0

    private fun check(name: String, got: String, want: String) {
        if (got.trim() == want.trim()) {
            println("  PASS  $name")
        } else {
            failures++
            println("  FAIL  $name\n    got : $got\n    want: $want")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("== Lfm2Format.formatMessages (chat-template doc example) ==")
        val msgs = listOf(
            ChatMessage("system", "You are a helpful assistant trained by Liquid AI."),
            ChatMessage("user", "What is C. elegans?"),
            ChatMessage("assistant", "It's a tiny nematode that lives in temperate soil environments."),
        )
        val rendered = Lfm2Format.formatMessages(msgs, addGenerationPrompt = false)
        check(
            "full transcript",
            rendered,
            """
            <|startoftext|><|im_start|>system
            You are a helpful assistant trained by Liquid AI.<|im_end|>
            <|im_start|>user
            What is C. elegans?<|im_end|>
            <|im_start|>assistant
            It's a tiny nematode that lives in temperate soil environments.<|im_end|>
            """.trimIndent(),
        )

        println("== formatMessages with generation prompt ==")
        val gen = Lfm2Format.formatMessages(listOf(ChatMessage("user", "hi")), addGenerationPrompt = true)
        check("trailing assistant prompt", gen, "<|startoftext|><|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n")

        println("== renderToolCall (tool-use doc example) ==")
        val call = Lfm2Format.renderToolCall("get_candidate_status", mapOf("candidate_id" to "12345"))
        check(
            "pythonic call",
            call,
            "<|tool_call_start|>[get_candidate_status(candidate_id=\"12345\")]<|tool_call_end|>",
        )

        println("== AgentLoop parser round-trip ==")
        val modelOutput =
            "<|tool_call_start|>[get_candidate_status(candidate_id=\"12345\")]<|tool_call_end|>"
        val parsed = ToolCallParser.LFM2_5.parse(modelOutput)
        check("parsed one call", "${parsed.size}", "1")
        check("call name", parsed.firstOrNull()?.name ?: "none", "get_candidate_status")
        check("call arg", parsed.firstOrNull()?.args?.get("candidate_id")?.toString() ?: "none", "12345")

        println("== multi-tool / mixed parse ==")
        val mixed = "Let me check.<|tool_call_start|>[get_weather(location=\"Paris\", unit=\"celsius\")]<|tool_call_end|>" +
            "<|tool_call_start|>[get_stock_price(symbol=\"GOOG\")]<|tool_call_end|>"
        val calls = ToolCallParser.LFM2_5.parse(mixed)
        check("parsed 2 calls", "${calls.size}", "2")
        check("first name", calls[0].name, "get_weather")
        check("first arg", calls[0].args["location"].toString(), "Paris")
        check("second name", calls[1].name, "get_stock_price")

        println("\n${if (failures == 0) "ALL PASS" else "$failures FAILED"}")
        kotlin.system.exitProcess(if (failures == 0) 0 else 1)
    }
}
