package com.example.funfy.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A video saved from any source for later viewing. */
data class BookmarkedVideo(
    val id: String,
    val title: String,
    val pageUrl: String,
    val thumbnailUrl: String = "",
    val duration: String = "—",
    val resolution: String = "",
    val sourceId: String = VideoSource.DEFAULT.id,
    val sourceLabel: String = "",
    val bookmarkedAt: Long = System.currentTimeMillis(),
) {
    fun toVideoItem(): VideoItem = VideoItem(
        id = id,
        title = title,
        duration = duration,
        resolution = resolution.ifBlank { "HD" },
        views = "—",
        category = sourceLabel.ifBlank { "Bookmark" },
        gradientSeed = id.hashCode(),
        pageUrl = pageUrl,
        thumbnailUrl = thumbnailUrl,
        sourceId = sourceId,
    )
}

/**
 * Persistent cross-source bookmarks.
 *
 * Identity is the page URL so the same clip is only bookmarked once even if
 * opened from different entry points.
 */
class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _bookmarks = MutableStateFlow(loadAll())
    val bookmarks: StateFlow<List<BookmarkedVideo>> = _bookmarks.asStateFlow()

    fun isBookmarked(pageUrl: String): Boolean {
        val key = stableId(pageUrl)
        return _bookmarks.value.any { it.id == key || it.pageUrl == pageUrl }
    }

    fun toggle(video: BookmarkedVideo): Boolean {
        val key = stableId(video.pageUrl)
        val current = _bookmarks.value
        val existing = current.firstOrNull { it.id == key || it.pageUrl == video.pageUrl }
        return if (existing != null) {
            val next = current.filterNot { it.id == existing.id }
            saveAll(next)
            _bookmarks.value = next
            false
        } else {
            val entry = video.copy(id = key, bookmarkedAt = System.currentTimeMillis())
            val next = listOf(entry) + current
            saveAll(next)
            _bookmarks.value = next
            true
        }
    }

    fun remove(id: String) {
        val next = _bookmarks.value.filterNot { it.id == id }
        saveAll(next)
        _bookmarks.value = next
    }

    fun clear() {
        saveAll(emptyList())
        _bookmarks.value = emptyList()
    }

    private fun loadAll(): List<BookmarkedVideo> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pageUrl = o.optString("pageUrl").trim()
                    if (pageUrl.isBlank()) continue
                    add(
                        BookmarkedVideo(
                            id = o.optString("id").ifBlank { stableId(pageUrl) },
                            title = o.optString("title").ifBlank { "Bookmarked video" },
                            pageUrl = pageUrl,
                            thumbnailUrl = o.optString("thumbnailUrl"),
                            duration = o.optString("duration", "—"),
                            resolution = o.optString("resolution"),
                            sourceId = o.optString("sourceId", VideoSource.DEFAULT.id),
                            sourceLabel = o.optString("sourceLabel"),
                            bookmarkedAt = o.optLong("bookmarkedAt").takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                        ),
                    )
                }
            }.sortedByDescending { it.bookmarkedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(list: List<BookmarkedVideo>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("pageUrl", b.pageUrl)
                    .put("thumbnailUrl", b.thumbnailUrl)
                    .put("duration", b.duration)
                    .put("resolution", b.resolution)
                    .put("sourceId", b.sourceId)
                    .put("sourceLabel", b.sourceLabel)
                    .put("bookmarkedAt", b.bookmarkedAt),
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "funfy_bookmarks"
        private const val KEY_LIST = "bookmarks"

        fun stableId(pageUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(pageUrl.trim().lowercase().toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
