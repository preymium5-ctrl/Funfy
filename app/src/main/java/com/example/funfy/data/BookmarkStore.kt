package com.example.funfy.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

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
    /** Null = root (not in a folder). */
    val folderId: String? = null,
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
 * Persistent cross-source bookmarks with optional named folders.
 *
 * Identity is the page URL so the same clip is only bookmarked once even if
 * opened from different entry points.
 */
class BookmarkStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _bookmarks = MutableStateFlow(loadAll())
    val bookmarks: StateFlow<List<BookmarkedVideo>> = _bookmarks.asStateFlow()

    private val _folders = MutableStateFlow(loadFolders())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()

    init {
        // Recover lists from public Documents/Funfy after reinstall / wipe.
        restoreFromDurableIfNeeded()
        // Re-encode legacy thumbs (Buumal spaces/Unicode) so Coil can load them.
        migrateSanitizeThumbnails()
        // Ensure durable snapshot exists for current data.
        persistDurable()
    }

    fun isBookmarked(pageUrl: String): Boolean {
        val key = stableId(pageUrl)
        return _bookmarks.value.any { it.id == key || it.pageUrl == pageUrl }
    }

    /** Update one bookmark's cover after a network repair. */
    fun updateThumbnail(id: String, thumbnailUrl: String): Boolean {
        val clean = NetworkClient.sanitizeMediaUrl(thumbnailUrl.trim())
        if (clean.isBlank()) return false
        var changed = false
        val next = _bookmarks.value.map { b ->
            if (b.id == id && b.thumbnailUrl != clean) {
                changed = true
                b.copy(thumbnailUrl = clean)
            } else {
                b
            }
        }
        if (changed) {
            saveAll(next)
            _bookmarks.value = next
        }
        return changed
    }

    /** Sanitize all stored thumbs in place (no network). */
    fun migrateSanitizeThumbnails() {
        var changed = false
        val next = _bookmarks.value.map { b ->
            val clean = NetworkClient.sanitizeMediaUrl(b.thumbnailUrl)
            if (clean != b.thumbnailUrl) {
                changed = true
                b.copy(thumbnailUrl = clean)
            } else {
                b
            }
        }
        if (changed) {
            saveAll(next)
            _bookmarks.value = next
        }
    }

    fun bookmarksNeedingThumbRepair(): List<BookmarkedVideo> =
        _bookmarks.value.filter { ThumbnailResolver.needsRepair(it.thumbnailUrl) }

    /**
     * Toggle bookmark. When [folderId] is set and the video is newly added,
     * it is placed in that folder.
     * @return true if now bookmarked, false if removed.
     */
    fun toggle(video: BookmarkedVideo, folderId: String? = null): Boolean {
        val key = stableId(video.pageUrl)
        val current = _bookmarks.value
        val existing = current.firstOrNull { it.id == key || it.pageUrl == video.pageUrl }
        return if (existing != null) {
            val next = current.filterNot { it.id == existing.id }
            saveAll(next)
            _bookmarks.value = next
            false
        } else {
            val entry = video.copy(
                id = key,
                bookmarkedAt = System.currentTimeMillis(),
                folderId = folderId?.takeIf { fid -> _folders.value.any { it.id == fid } },
            )
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

    fun moveToFolder(id: String, folderId: String?) {
        val target = folderId?.takeIf { fid -> _folders.value.any { it.id == fid } }
        val next = _bookmarks.value.map { b ->
            if (b.id == id) b.copy(folderId = target) else b
        }
        saveAll(next)
        _bookmarks.value = next
    }

    fun createFolder(name: String): MediaFolder? {
        val clean = name.trim().take(48)
        if (clean.isBlank()) return null
        if (_folders.value.any { it.name.equals(clean, ignoreCase = true) }) {
            return _folders.value.first { it.name.equals(clean, ignoreCase = true) }
        }
        val folder = MediaFolder(id = UUID.randomUUID().toString(), name = clean)
        val next = (_folders.value + folder).sortedBy { it.name.lowercase() }
        saveFolders(next)
        _folders.value = next
        return folder
    }

    fun deleteFolder(folderId: String) {
        val nextFolders = _folders.value.filterNot { it.id == folderId }
        saveFolders(nextFolders)
        _folders.value = nextFolders
        // Unfile videos that lived in the deleted folder
        val nextBookmarks = _bookmarks.value.map { b ->
            if (b.folderId == folderId) b.copy(folderId = null) else b
        }
        saveAll(nextBookmarks)
        _bookmarks.value = nextBookmarks
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
                    val folderRaw = o.optString("folderId", "")
                    add(
                        BookmarkedVideo(
                            id = o.optString("id").ifBlank { stableId(pageUrl) },
                            title = o.optString("title").ifBlank { "Bookmarked video" },
                            pageUrl = pageUrl,
                            thumbnailUrl = NetworkClient.sanitizeMediaUrl(
                                o.optString("thumbnailUrl"),
                            ),
                            duration = o.optString("duration", "—"),
                            resolution = o.optString("resolution"),
                            sourceId = o.optString("sourceId", VideoSource.DEFAULT.id),
                            sourceLabel = o.optString("sourceLabel"),
                            bookmarkedAt = o.optLong("bookmarkedAt").takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                            folderId = folderRaw.takeIf { it.isNotBlank() },
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
                    .put("bookmarkedAt", b.bookmarkedAt)
                    .put("folderId", b.folderId.orEmpty()),
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
        persistDurable()
    }

    private fun loadFolders(): List<MediaFolder> =
        parseMediaFolders(prefs.getString(KEY_FOLDERS, "[]"))

    private fun saveFolders(list: List<MediaFolder>) {
        prefs.edit().putString(KEY_FOLDERS, list.toJsonArray().toString()).apply()
        persistDurable()
    }

    private fun restoreFromDurableIfNeeded() {
        val prefsEmpty = (prefs.getString(KEY_LIST, "[]") ?: "[]").let { it == "[]" || it.isBlank() }
        val foldersEmpty = (prefs.getString(KEY_FOLDERS, "[]") ?: "[]").let { it == "[]" || it.isBlank() }
        if (!prefsEmpty && !foldersEmpty) return
        val snap = DurableLibraryStore.load(appContext) ?: return
        if (prefsEmpty && snap.bookmarksJson != "[]" && snap.bookmarksJson.isNotBlank()) {
            prefs.edit().putString(KEY_LIST, snap.bookmarksJson).commit()
            _bookmarks.value = loadAll()
        }
        if (foldersEmpty && snap.bookmarkFoldersJson != "[]" && snap.bookmarkFoldersJson.isNotBlank()) {
            prefs.edit().putString(KEY_FOLDERS, snap.bookmarkFoldersJson).commit()
            _folders.value = loadFolders()
        }
    }

    private fun persistDurable() {
        runCatching {
            DurableLibraryStore.saveBookmarks(
                context = appContext,
                bookmarksJson = prefs.getString(KEY_LIST, "[]") ?: "[]",
                foldersJson = prefs.getString(KEY_FOLDERS, "[]") ?: "[]",
            )
        }
    }

    companion object {
        private const val PREFS = "funfy_bookmarks"
        private const val KEY_LIST = "bookmarks"
        private const val KEY_FOLDERS = "folders"

        fun stableId(pageUrl: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(pageUrl.trim().lowercase().toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}
