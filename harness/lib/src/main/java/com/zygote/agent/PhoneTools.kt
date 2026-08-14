package com.zygote.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * PHONETOOLS — the exact 9-tool set (see SPEC.md §2). Immutable contract.
 *
 * These are the tools a proot/Node harness literally cannot offer:
 * they reach the real Android device via AccessibilityService + Intents.
 *
 * Handlers are thin here because the actual gestures are issued by the
 * active [AccessibilityService] (see PhoneControlService). We resolve the
 * service through a registered callback to avoid holding a hard reference.
 */
class PhoneTools(private val context: Context) {

    /** Set by PhoneControlService when it connects. */
    companion object {
        var accessibility: AccessibilityService? = null
    }

    /** Register all 9 tools into the given registry. */
    fun registerAll(registry: ToolRegistry) {
        registry.register(
            ToolSpec(
                name = "read_screen",
                description = "Dump the current screen's text and UI elements " +
                    "(accessibility tree) as readable lines with pixel bounds.",
                parameters = mapOf(
                    "max_elements" to mapOf(
                        "type" to "integer",
                        "description" to "Max elements to return (default 60)",
                    ),
                ),
                permission = PermissionKind.READ_ONLY,
                handler = { _, args ->
                    val max = (args["max_elements"] as? Number)?.toInt() ?: 60
                    ToolResult.Ok(dumpScreen(max))
                },
            ),
        )
        registry.register(
            ToolSpec(
                name = "tap",
                description = "Tap at screen coordinates (x, y) in pixels.",
                parameters = mapOf(
                    "x" to mapOf("type" to "integer", "description" to "x coordinate in px"),
                    "y" to mapOf("type" to "integer", "description" to "y coordinate in px"),
                ),
                permission = PermissionKind.SENSITIVE,
                handler = { _, args -> gesture(GestureKind.TAP, args) },
            ),
        )
        registry.register(
            ToolSpec(
                name = "long_press",
                description = "Long-press at screen coordinates (x, y) in pixels.",
                parameters = mapOf(
                    "x" to mapOf("type" to "integer", "description" to "x coordinate in px"),
                    "y" to mapOf("type" to "integer", "description" to "y coordinate in px"),
                ),
                permission = PermissionKind.SENSITIVE,
                handler = { _, args -> gesture(GestureKind.LONG_PRESS, args) },
            ),
        )
        registry.register(
            ToolSpec(
                name = "type_text",
                description = "Type text into the currently focused field.",
                parameters = mapOf(
                    "text" to mapOf("type" to "string", "description" to "Text to type"),
                ),
                permission = PermissionKind.SENSITIVE,
                handler = { _, args ->
                    val text = args["text"] as? String ?: return@ToolSpec
                        ToolResult.Error("type_text requires 'text'")
                    typeText(text)
                },
            ),
        )
        registry.register(
            ToolSpec(
                name = "swipe",
                description = "Swipe from (x1,y1) to (x2,y2) over duration ms.",
                parameters = mapOf(
                    "x1" to mapOf("type" to "integer"),
                    "y1" to mapOf("type" to "integer"),
                    "x2" to mapOf("type" to "integer"),
                    "y2" to mapOf("type" to "integer"),
                    "duration_ms" to mapOf("type" to "integer", "description" to "default 300"),
                ),
                permission = PermissionKind.SENSITIVE,
                handler = { _, args -> swipe(args) },
            ),
        )
        registry.register(
            ToolSpec(
                name = "open_app",
                description = "Open an app by Android package name (e.g. com.whatsapp).",
                parameters = mapOf(
                    "package_name" to mapOf("type" to "string"),
                ),
                permission = PermissionKind.SAFE,
                handler = { _, args ->
                    val pkg = args["package_name"] as? String
                        ?: return@ToolSpec ToolResult.Error("open_app requires 'package_name'")
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: return@ToolSpec ToolResult.Error("No launch intent for $pkg")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ToolResult.Ok("Opened $pkg")
                },
            ),
        )
        registry.register(
            ToolSpec(
                name = "call",
                description = "Make a phone call to a tel: number. Requires user confirmation.",
                parameters = mapOf(
                    "number" to mapOf("type" to "string"),
                ),
                permission = PermissionKind.DESTRUCTIVE,
                handler = { _, args ->
                    val num = args["number"] as? String
                        ?: return@ToolSpec ToolResult.Error("call requires 'number'")
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$num"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ToolResult.Ok("Calling $num")
                },
            ),
        )
        registry.register(
            ToolSpec(
                name = "send_sms",
                description = "Send an SMS to a number with a body (opens messenger).",
                parameters = mapOf(
                    "number" to mapOf("type" to "string"),
                    "body" to mapOf("type" to "string"),
                ),
                permission = PermissionKind.DESTRUCTIVE,
                handler = { _, args ->
                    val num = args["number"] as? String
                        ?: return@ToolSpec ToolResult.Error("send_sms requires 'number'")
                    val body = args["body"] as? String ?: ""
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))
                        .putExtra("sms_body", body)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ToolResult.Ok("SMS composer opened to $num")
                },
            ),
        )
        registry.register(
            ToolSpec(
                name = "shell",
                description = "Run a shell command (best-effort, sandboxed, always approved).",
                parameters = mapOf(
                    "command" to mapOf("type" to "string"),
                ),
                permission = PermissionKind.DESTRUCTIVE,
                handler = { _, args ->
                    val cmd = args["command"] as? String
                        ?: return@ToolSpec ToolResult.Error("shell requires 'command'")
                    runShell(cmd)
                },
            ),
        )
    }

    // ---- implementations ----

    private enum class GestureKind { TAP, LONG_PRESS }

    private fun gesture(kind: GestureKind, args: Map<String, Any?>): ToolResult {
        val svc = accessibility
            ?: return ToolResult.Error("Accessibility service not connected")
        val x = (args["x"] as? Number)?.toFloat() ?: return ToolResult.Error("x required")
        val y = (args["y"] as? Number)?.toFloat() ?: return ToolResult.Error("y required")
        val path = Path().apply { moveTo(x, y) }
        val duration = if (kind == GestureKind.LONG_PRESS) 800L else 80L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val ok = svc.dispatchGesture(desc, null, null)
        return if (ok) ToolResult.Ok("Gesture dispatched")
        else ToolResult.Error("dispatchGesture failed (busy?)")
    }

    private fun swipe(args: Map<String, Any?>): ToolResult {
        val svc = accessibility
            ?: return ToolResult.Error("Accessibility service not connected")
        val x1 = (args["x1"] as? Number)?.toFloat() ?: return ToolResult.Error("x1 required")
        val y1 = (args["y1"] as? Number)?.toFloat() ?: return ToolResult.Error("y1 required")
        val x2 = (args["x2"] as? Number)?.toFloat() ?: return ToolResult.Error("x2 required")
        val y2 = (args["y2"] as? Number)?.toFloat() ?: return ToolResult.Error("y2 required")
        val d = (args["duration_ms"] as? Number)?.toLong() ?: 300L
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, d)
        val ok = svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return if (ok) ToolResult.Ok("Swipe dispatched") else ToolResult.Error("dispatchGesture failed")
    }

    private fun typeText(text: String): ToolResult {
        val svc = accessibility
            ?: return ToolResult.Error("Accessibility service not connected")
        // Best-effort: use ACTION_SET_TEXT via the focused node, else paste.
        val focused = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: svc.rootInActiveWindow
            ?: return ToolResult.Error("No active window")
        if (target.className?.toString()?.contains("EditText") == true ||
            target.isEditable
        ) {
            val actionArgs = android.os.Bundle().apply { putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, actionArgs)) {
                return ToolResult.Ok("Typed into focused field")
            }
        }
        return ToolResult.Error("No editable field focused; tap one first")
    }

    private fun dumpScreen(max: Int): String {
        val svc = accessibility
            ?: return "ERROR: Accessibility service not connected"
        val root = svc.rootInActiveWindow ?: return "ERROR: no active window"
        val sb = StringBuilder()
        collect(root, sb, 0, max)
        return if (sb.isBlank()) "No text content on screen" else sb.toString().trim()
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        max: Int,
    ) {
        if (sb.count { it == '\n' } >= max) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = node.viewIdResourceName?.substringAfterLast('/')?.takeLast(24)
        if (!text.isNullOrBlank() || !desc.isNullOrBlank()) {
            val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val kind = when {
                node.isClickable -> "button"
                node.isEditable -> "input"
                node.className?.toString()?.contains("Image") == true -> "image"
                else -> "text"
            }
            sb.append("[$kind ${rect.left},${rect.top} ${rect.width}x${rect.height}]")
            if (label != null) sb.append(" id=$label")
            sb.append(": ").append(text ?: desc).append('\n')
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, sb, depth + 1, max) }
        }
    }

    private fun runShell(command: String): ToolResult {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            val text = if (out.isBlank() && err.isNotBlank()) err else out
            ToolResult.Ok("exit=$code\n${text.trim().take(4000)}")
        } catch (e: Exception) {
            ToolResult.Error("shell failed: ${e.message}")
        }
    }
}
