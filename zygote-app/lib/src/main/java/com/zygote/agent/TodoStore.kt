package com.zygote.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TodoStore — opencode-style per-session to-do list.
 *
 * Mirrors opencode's TodoTable: (session_id, content, status, priority,
 * position). Status: "todo" | "in-progress" | "done" | "canceled".
 * Persisted as JSON under filesDir/todos/<sessionId>.json so todos survive
 * restarts and the PWA's TodosPanel can render them.
 *
 * Thread-safe; called from the server thread and tool handlers.
 */
class TodoStore(private val root: File) {

    init {
        root.mkdirs()
    }

    data class Todo(
        val content: String,
        val status: String,
        val priority: String,
        val position: Int,
    )

    /** OpenCode semantics: upsert by position; empty content deletes the slot. */
    fun write(sessionId: String, content: String, status: String, priority: String, position: Int): List<Todo> {
        synchronized(this) {
            val todos = read(sessionId).toMutableList()
            val idx = todos.indexOfFirst { it.position == position }
            if (content.isBlank()) {
                if (idx >= 0) todos.removeAt(idx)
            } else if (idx >= 0) {
                todos[idx] = Todo(content, status, priority, position)
            } else {
                todos.add(Todo(content, status, priority, position))
            }
            todos.sortBy { it.position }
            persist(sessionId, todos)
            return todos
        }
    }

    fun read(sessionId: String): List<Todo> {
        synchronized(this) {
            val f = file(sessionId)
            if (!f.exists()) return emptyList()
            return try {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Todo(
                        o.optString("content", ""),
                        o.optString("status", "todo"),
                        o.optString("priority", "normal"),
                        o.optInt("position", i),
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun toJson(sessionId: String): String {
        val arr = JSONArray()
        read(sessionId).forEach { t ->
            arr.put(
                JSONObject()
                    .put("content", t.content)
                    .put("status", t.status)
                    .put("priority", t.priority)
                    .put("position", t.position)
            )
        }
        return arr.toString()
    }

    private fun persist(sessionId: String, todos: List<Todo>) {
        try {
            val arr = JSONArray()
            todos.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("content", t.content)
                        .put("status", t.status)
                        .put("priority", t.priority)
                        .put("position", t.position)
                )
            }
            file(sessionId).writeText(arr.toString())
        } catch (e: Exception) {
            android.util.Log.e("TodoStore", "persist failed: ${e.message}")
        }
    }

    private fun file(id: String): File = File(root, id.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json")
}
