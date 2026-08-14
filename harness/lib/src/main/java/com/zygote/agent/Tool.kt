package com.zygote.agent

/**
 * The immutable tool contract for ZYGOTE.
 *
 * Mirrors the DeepSeek Harness / opencode "seam" pattern:
 * tools are the ONLY interface between the agent and the external world,
 * and every tool is self-registering with a schema + a permission kind.
 */
sealed interface ToolResult {
    data class Ok(val output: String) : ToolResult
    data class Error(val message: String) : ToolResult
}

/** Permission tiers. [PermissionGate] enforces allow / deny / ask per tier. */
enum class PermissionKind {
    /** Harmless, auto-run: open_app, read_screen. */
    SAFE,
    /** Read-only observation: read_screen. */
    READ_ONLY,
    /** Changes device UI state but recoverable: tap, type_text, swipe, long_press. */
    SENSITIVE,
    /** Irreversible / high-impact: shell, call, send_sms. Always ask-gated. */
    DESTRUCTIVE,
}

/**
 * Context handed to every tool handler. Carries the session id and an
 * async permission gate so a tool can request approval mid-run.
 */
data class ToolContext(
    val sessionId: String,
    val askPermission: suspend (PermissionKind, String) -> Boolean,
)

/**
 * A single registered tool. [handler] runs on the harness dispatcher.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
    val permission: PermissionKind,
    val handler: suspend (ToolContext, Map<String, Any?>) -> ToolResult,
)
