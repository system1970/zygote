# ZYGOTE — On-Device Agent Harness
### Native ARM harness · PhoneTools · Minimal PWA (DeepSeek-Harness-style UI)

> **Zygote** — double meaning, both true:
> 1. **Android-specific:** the `zygote` is the system process every app process
>    is *forked from* — the origin of all processes on the device. An on-device
>    agent named Zygote lives at the heart of Android itself.
> 2. **Biology:** the first cell of a new organism — two things fuse into one,
>    totipotent, grows into everything.
>
> Together: the agent is the origin point from which everything on the device
> grows. It is Android-native by name and by nature.
>
> This doc is the immutable contract for the harness core and the UI.

---

## 1. Architecture (native-first, "as close to hardware as possible")

```
┌────────────────────────────────────────────────────────────┐
│  PWA (React, static, localhost-only)                       │
│  · replicates DeepSeek Harness UI exactly (see §4)          │
│  · talks only to app's local server via fetch               │
└──────────────┬─────────────────────────────────────────────┘
               │ http://127.0.0.1:<port>/v1/*
┌──────────────▼─────────────────────────────────────────────┐
│  NATIVE ANDROID APP (Kotlin)                                │
│  · AgentLoop (goal → model → tool → observe → repeat)       │
│  · ToolRegistry (self-registering tools + permissions)      │
│  · SessionStore (Room/SQLite, replayable trajectory)        │
│  · ContextManager (compaction / summarization)              │
│  · ModelRouter (LFM2.5-230M fast ↔ LFM2.5-2.6B heavy)       │
│  · PermissionGate (allow/deny/ask → Android prompt)         │
│  · SkillLoader (runtime-loadable skill bundles)             │
│  · Local server: /v1/chat/completions, /v1/agent/run,       │
│    /v1/telemetry, /v1/skills, /v1/session/{id}              │
└──────────────┬─────────────────────────────────────────────┘
               │ JNI
┌──────────────▼─────────────────────────────────────────────┐
│  NATIVE ARM (existing lib/, already working)                │
│  · llama.cpp .so (arm64-v8a, KleidiAI=ON, OpenMP)           │
│  · InferenceEngine: loadModel/setSystemPrompt/              │
│    sendUserPrompt(Flow<String>)/bench                       │
└─────────────────────────────────────────────────────────────┘
```

- **No Node, no proot, no Linux-server layer.** Inference is the existing
  llama.cpp `.so` via JNI. The harness is Kotlin in the same process.
- **We adopt DeepSeek Harness / Tau design patterns** (tool registry, sessions,
  MCP, skills, permissions, trajectory) as a native port — NOT a from-scratch loop.

---

## 2. PHONETOOLS — THE EXACT CONTRACT (immutable)

Every tool self-registers into `ToolRegistry`. Shape (Kotlin):

```kotlin
data class ToolSpec(
    val name: String,               // snake_case, unique, model-facing
    val description: String,        // authoritative model-facing schema description
    val parameters: Map<String, Any>, // JSON Schema for args
    val permission: PermissionKind,   // SAFE | READ_ONLY | SENSITIVE | DESTRUCTIVE
    val handler: suspend (ToolContext, Map<String, Any?>) -> ToolResult
)

data class ToolContext(
    val sessionId: String,
    val askPermission: suspend (PermissionKind, String) -> Boolean, // gates via PermissionGate
)

sealed class ToolResult {
    data class Ok(val output: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
}
```

### The set (exactly 9 tools, no more, no less)

| # | name | description (model-facing) | permission | implementation |
|---|------|---------------------------|-----------|----------------|
| 1 | `read_screen` | "Dump the current screen's text and UI elements (accessibility tree) as readable lines with bounds." | READ_ONLY | AccessibilityService → view hierarchy (text + bounds). No vision model needed. |
| 2 | `tap` | "Tap at screen coordinates (x, y) in px." | SENSITIVE | AccessibilityService.dispatchGesture(TAP) |
| 3 | `long_press` | "Long-press at (x, y) in px." | SENSITIVE | dispatchGesture(LONG_PRESS) |
| 4 | `type_text` | "Type text into the focused field." | SENSITIVE | dispatchGesture + clipboard paste / per-char actions |
| 5 | `swipe` | "Swipe from (x1,y1) to (x2,y2) over duration ms." | SENSITIVE | dispatchGesture(SWIPE) |
| 6 | `open_app` | "Open an app by package name." | SAFE | Intent / startActivity(packageName) |
| 7 | `call` | "Make a phone call to a number (tel:)." | DESTRUCTIVE | Intent.ACTION_CALL (requires user confirm) |
| 8 | `send_sms` | "Send an SMS to a number with a body." | DESTRUCTIVE | Intent.ACTION_SENDTO (sms:) |
| 9 | `shell` | "Run a shell command (best-effort, sandboxed)." | DESTRUCTIVE | foreground service exec / Runtime.exec with allowlist + approval |

**Invariants:**
- Tool names are **final** — the model-facing schema never changes shape.
- Every tool has exactly one `permission` kind; `PermissionGate` enforces it.
- `shell`, `call`, `send_sms` are **always** `ask`-gated (never auto-run).
- Adding a tool = adding one `ToolSpec` + handler to `PhoneTools.kt`. No core changes.

---

## 3. HARNESS CORE FILES (exact paths)

```
lib/src/main/java/com/zygote/agent/
├── AgentLoop.kt
├── ToolRegistry.kt
├── Tool.kt            (ToolSpec, ToolContext, ToolResult)
├── SessionStore.kt
├── ContextManager.kt
├── PermissionGate.kt
├── ModelRouter.kt
├── PhoneTools.kt      (the 9 tools above)
└── Skills/
    └── SkillLoader.kt
```

---

## 4. UI — DeepSeek Harness look, exact spec

**Palette:** near-black bg (#0A0A0B), charcoal panels (#161617), thin gray dividers,
white primary text, blue accent (#3B82F6) for active tab / status / stop button,
gray (#8A8A8E) for tool calls / reasoning / metadata.

**Layout (left → right, top → bottom):**

1. **Left nav rail** (~48px, vertical): whale/logo top; then `+` (new chat), document
   (new session), magnifier (search); gear (settings) bottom. Dark, thin divider.
2. **Header:** project title ("Greetings from the user") in large white; right-aligned
   small "Standard mode" chip with a nodes icon; a rounded **"Session log"** button
   with download icon at far top-right.
3. **Tabs:** **Chat** (blue, blue underline) · **Trajectory** (gray). Divider below.
4. **Conversation stream** (scrollable). Message parts, in order:
   - user bubble (right, dark rounded) + copy icon
   - `Context injection · AGENTS.md` (muted, doc icon)
   - `Context injection · @…-system-prompt`
   - `Context injection · skill-catalog`
   - `Think · <truncated reasoning>` (atom icon, gray)
   - **assistant text** (large, bright)
   - action icons: copy / thumbs-up / thumbs-down / share
   - `Update to-do list · 0/7 completed · …` (checklist icon)
   - `Bash · <command>` (terminal icon) + optional `Think` interleaved
   - status line in blue: `Deep diving…` + elapsed `37s`
5. **To-dos panel** (above composer, collapsible): `To-dos · 1 in progress · 6 pending`.
6. **Composer:** big rounded input, placeholder "Message the agent"; left `+`;
   `shield · Full access ⌄` (permissions); right `DeepSeek-V4-Pro · Max ⌄` (model);
   blue circular **stop** button (running state).
7. **Bottom status bar:** `2 turns · 3 steps · LLM 37.3s · Tool call 0.7s ·
   TTFT avg 1.4s · 72 tok/s · Cache hit 66% · Input 38.7K`.

For our app the model selector shows the **local** model (LFM2.5-2.6B / 230M) and
the status bar shows **real on-device** tok/s, TTFT, RAM, battery.

---

## 5. Build order

1. AgentLoop + ToolRegistry + `shell` tool → first real on-device tool-call turn
2. PhoneTools (9 tools) via AccessibilityService + Intents
3. Local server (/v1/* endpoints)
4. PWA (React, exact UI above)
5. Skills + SessionStore + PermissionGate
6. Docs / artifacts / submission
