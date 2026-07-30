package com.example.funfy.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent search queries (cross-source), most recent first.
 * Cap keeps the list small for the Search empty-state UI.
 */
class SearchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<SearchHistoryEntry>> = _history.asStateFlow()

    fun add(query: String, sourceId: String = "", sourceLabel: String = "") {
        val q = query.trim()
        if (q.isBlank()) return
        val now = System.currentTimeMillis()
        val entry = SearchHistoryEntry(
            query = q,
            sourceId = sourceId,
            sourceLabel = sourceLabel,
            searchedAt = now,
        )
        val next = buildList {
            add(entry)
            _history.value
                .filterNot { it.query.equals(q, ignoreCase = true) }
                .take(MAX - 1)
                .forEach { add(it) }
        }
        save(next)
        _history.value = next
    }

    fun remove(query: String) {
        val next = _history.value.filterNot { it.query.equals(query, ignoreCase = true) }
        save(next)
        _history.value = next
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        _history.value = emptyList()
    }

    private fun load(): List<SearchHistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val q = o.optString("query").trim()
                    if (q.isBlank()) continue
                    add(
                        SearchHistoryEntry(
                            query = q,
                            sourceId = o.optString("sourceId"),
                            sourceLabel = o.optString("sourceLabel"),
                            searchedAt = o.optLong("searchedAt").takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                        ),
                    )
                }
            }.take(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<SearchHistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("query", e.query)
                    .put("sourceId", e.sourceId)
                    .put("sourceLabel", e.sourceLabel)
                    .put("searchedAt", e.searchedAt),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "funfy_search_history"
        private const val KEY = "history"
        private const val MAX = 30
    }
}

data class SearchHistoryEntry(
    val query: String,
    val sourceId: String = "",
    val sourceLabel: String = "",
    val searchedAt: Long = System.currentTimeMillis(),
)
