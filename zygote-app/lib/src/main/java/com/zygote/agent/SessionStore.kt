package com.zygote.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * SessionStore — append-only, durable, replayable session log.
 *
 * Design adopted from Claude Code (one JSONL file per session, every event a
 * line) and opencode (monotonic seq for deterministic replay + admission
 * before visibility). Invariant (DeepSeek-Harness session-log rule):
 * "model-visible ⟺ logged" — everything the model sees is reconstructable
 * from this log, and nothing is logged that wasn't model-visible.
 *
 * Layout: filesDir/sessions/<sessionId>.jsonl
 *   {"seq":1,"kind":"user","text":"...","at":1755123456789}
 *   {"seq":2,"kind":"assistant","text":"...","at":...}
 *
 * Thread-safe; called from the server thread.
 */
class SessionStore(private val root: File) {

    private val seq = AtomicLong(0)

    init {
        root.mkdirs()
    }

    /** Ensures a session file exists; returns the session id. */
    fun create(sessionId: String = newId()): String {
        synchronized(this) {
            val f = file(sessionId)
            if (!f.exists()) f.createNewFile()
            return sessionId
        }
    }

    /**
     * Sets the session title (opencode pattern: generated once from the first
     * user message). Stored in a sidecar meta file — a title is metadata, not
     * model-visible content, so it never pollutes the JSONL event log.
     */
    fun setTitle(sessionId: String, title: String) {
        synchronized(this) {
            if (title.isBlank()) return
            val meta = metaFile(sessionId)
            val o = if (meta.exists()) {
                try { JSONObject(meta.readText()) } catch (_: Exception) { JSONObject() }
            } else JSONObject()
            o.put("title", title)
            o.put("created_at", o.optLong("created_at", System.currentTimeMillis()))
            o.put("updated_at", System.currentTimeMillis())
            meta.writeText(o.toString())
        }
    }

    fun title(sessionId: String): String {
        synchronized(this) {
            val meta = metaFile(sessionId)
            return try {
                if (meta.exists()) JSONObject(meta.readText()).optString("title", "") else ""
            } catch (_: Exception) { "" }
        }
    }

    /** Smart-title heuristic (opencode deriveTitle): first sentence, ≤50 chars. */
    fun deriveTitle(raw: String): String {
        val clean = raw.replace(Regex("<[^>]+>"), "").trim()
        if (clean.isEmpty()) return "New session"
        val firstSentence = Regex("^(.*?[.!?。！？])\\s").find(clean)?.groupValues?.get(1) ?: clean
        val flat = firstSentence.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > 48) flat.take(47) + "…" else flat
    }

    /** Appends one event (one JSONL line) to the session. */
    fun append(sessionId: String, kind: String, text: String) {
        synchronized(this) {
            val s = seq.incrementAndGet()
            val line = JSONObject()
                .put("seq", s)
                .put("kind", kind)
                .put("text", text)
                .put("at", System.currentTimeMillis())
                .toString()
            try {
                file(sessionId).appendText(line + "\n")
            } catch (e: Exception) {
                android.util.Log.e("SessionStore", "append failed: ${e.message}")
            }
        }
    }

    /** Returns the full trajectory as the /v1/session/{id} contract. */
    fun trajectoryJson(sessionId: String): String {
        synchronized(this) {
            val arr = JSONArray()
            readLines(sessionId).forEach { line ->
                try { arr.put(JSONObject(line)) } catch (_: Exception) {}
            }
            return JSONObject()
                .put("session_id", sessionId)
                .put("events", arr)
                .toString()
        }
    }

    /**
     * Reconstructs the model-visible message history (Claude Code --continue
     * pattern): user/assistant/tool events mapped to ChatMessages so a resumed
     * session starts with full context.
     */
    fun messages(sessionId: String): List<ChatMessage> {
        synchronized(this) {
            val out = ArrayList<ChatMessage>()
            readLines(sessionId).forEach { line ->
                try {
                    val o = JSONObject(line)
                    when (o.getString("kind")) {
                        "user" -> out.add(ChatMessage("user", o.getString("text")))
                        "assistant" -> out.add(ChatMessage("assistant", o.getString("text")))
                        "tool_result" -> out.add(ChatMessage("tool", o.getString("text")))
                    }
                } catch (_: Exception) {}
            }
            return out
        }
    }

    /** Session ids, newest-first (for a session picker). */
    fun list(): List<String> =
        root.listFiles()?.filter { it.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension } ?: emptyList()

    /** Sessions with metadata, newest-first: [{session_id, title, updated_at}]. */
    fun listWithMeta(): String {
        synchronized(this) {
            val arr = JSONArray()
            root.listFiles()?.filter { it.extension == "jsonl" }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f ->
                    val id = f.nameWithoutExtension
                    arr.put(
                        JSONObject()
                            .put("session_id", id)
                            .put("title", title(id))
                            .put("updated_at", f.lastModified())
                    )
                }
            return arr.toString()
        }
    }

    /** Claude Code retention default: drop sessions older than 30 days. */
    fun cleanup(maxAgeMs: Long = 30L * 24 * 3600 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun file(id: String): File = File(root, sanitize(id) + ".jsonl")

    private fun metaFile(id: String): File = File(root, sanitize(id) + ".meta.json")

    private fun sanitize(id: String): String = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun readLines(id: String): List<String> =
        try { file(id).readLines().filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }

    private fun newId(): String =
        // ULID-ish: time-sortable prefix + random suffix (Claude Code pattern).
        java.lang.Long.toString(System.currentTimeMillis(), 36) +
            "-" + (Math.random() * 0xFFFFFF).toInt().toString(16) +
            (Math.random() * 0xFFFFFF).toInt().toString(16)
}
