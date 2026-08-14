package com.zygote.agent

/**
 * Central registry of all tools. Mirrors DeepSeek Harness / opencode registry:
 * tools self-register by name, the schema list given to the model is built from
 * [getDefinitions], and dispatch resolves a name to its handler + permission.
 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, ToolSpec>()

    /** Register a tool. Returns false (no-op) if the name is already taken. */
    fun register(tool: ToolSpec): Boolean {
        if (tools.containsKey(tool.name)) return false
        tools[tool.name] = tool
        return true
    }

    fun get(name: String): ToolSpec? = tools[name]

    val all: List<ToolSpec> get() = tools.values.toList()

    /** Model-facing tool definitions (OpenAI-style function schema). */
    fun getDefinitions(): List<Map<String, Any>> =
        tools.values.map { t ->
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to t.name,
                    "description" to t.description,
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to t.parameters,
                        "additionalProperties" to false,
                    ),
                ),
            )
        }

    /**
     * Resolve + run a tool call.
     * @throws IllegalArgumentException if tool name is unknown.
     */
    suspend fun dispatch(
        name: String,
        args: Map<String, Any?>,
        ctx: ToolContext,
    ): ToolResult {
        val tool = tools[name] ?: return ToolResult.Error("Unknown tool: $name")
        // Gate first — DESTRUCTIVE always asks; others follow the gate policy.
        val allowed = ctx.askPermission(tool.permission, tool.description)
        if (!allowed) return ToolResult.Error("Permission denied for tool: $name")
        return try {
            tool.handler(ctx, args)
        } catch (e: Exception) {
            ToolResult.Error("${tool.name} failed: ${e.message}")
        }
    }
}
