package com.example.funfy.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/**
 * Tiny JSON snapshot of bookmarks / downloads / folders written to **public**
 * Documents storage so it survives app uninstall (unlike SharedPreferences).
 *
 * Only metadata is stored (titles, URLs, thumbs, folder names) — not video
 * files — so the payload stays small (typically tens of KB).
 */
object DurableLibraryStore {
    private const val VERSION = 1
    private const val RELATIVE_DIR = "Funfy"
    private const val FILE_NAME = "library.json"
    private const val MIME = "application/json"
    private val lock = Any()

    data class Snapshot(
        val bookmarksJson: String = "[]",
        val bookmarkFoldersJson: String = "[]",
        val downloadsJson: String = "[]",
        val downloadFoldersJson: String = "[]",
    )

    fun save(
        context: Context,
        bookmarksJson: String,
        bookmarkFoldersJson: String,
        downloadsJson: String,
        downloadFoldersJson: String,
    ) {
        val payload = JSONObject()
            .put("v", VERSION)
            .put("savedAt", System.currentTimeMillis())
            .put("bookmarks", bookmarksJson)
            .put("bookmarkFolders", bookmarkFoldersJson)
            .put("downloads", downloadsJson)
            .put("downloadFolders", downloadFoldersJson)
            .toString()
        // Prefer direct file paths (survives uninstall; works on most devices).
        for (file in candidateFiles()) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(payload, Charsets.UTF_8)
            }
        }
        // MediaStore Documents entry (Android 10+ shared storage).
        runCatching { writeViaMediaStore(context.applicationContext, payload) }
    }

    fun load(context: Context): Snapshot? = synchronized(lock) {
        for (file in candidateFiles()) {
            runCatching {
                if (file.isFile && file.length() in 2..(2_000_000L)) {
                    return@synchronized parse(file.readText(Charsets.UTF_8))
                }
            }
        }
        runCatching { readViaMediaStore(context.applicationContext) }.getOrNull()
    }

    /** Merge-update bookmark fields without clobbering downloads. */
    fun saveBookmarks(context: Context, bookmarksJson: String, foldersJson: String) {
        synchronized(lock) {
            val prev = loadUnlocked(context) ?: Snapshot()
            save(
                context = context,
                bookmarksJson = bookmarksJson,
                bookmarkFoldersJson = foldersJson,
                downloadsJson = prev.downloadsJson,
                downloadFoldersJson = prev.downloadFoldersJson,
            )
        }
    }

    /** Merge-update download fields without clobbering bookmarks. */
    fun saveDownloads(context: Context, downloadsJson: String, foldersJson: String) {
        synchronized(lock) {
            val prev = loadUnlocked(context) ?: Snapshot()
            save(
                context = context,
                bookmarksJson = prev.bookmarksJson,
                bookmarkFoldersJson = prev.bookmarkFoldersJson,
                downloadsJson = downloadsJson,
                downloadFoldersJson = foldersJson,
            )
        }
    }

    private fun loadUnlocked(context: Context): Snapshot? {
        for (file in candidateFiles()) {
            runCatching {
                if (file.isFile && file.length() in 2..(2_000_000L)) {
                    return parse(file.readText(Charsets.UTF_8))
                }
            }
        }
        return runCatching { readViaMediaStore(context.applicationContext) }.getOrNull()
    }

    private fun parse(raw: String): Snapshot? {
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            Snapshot(
                bookmarksJson = o.optString("bookmarks", "[]").ifBlank { "[]" },
                bookmarkFoldersJson = o.optString("bookmarkFolders", "[]").ifBlank { "[]" },
                downloadsJson = o.optString("downloads", "[]").ifBlank { "[]" },
                downloadFoldersJson = o.optString("downloadFolders", "[]").ifBlank { "[]" },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun candidateFiles(): List<File> {
        val names = listOf(FILE_NAME)
        val dirs = buildList {
            runCatching {
                add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), RELATIVE_DIR))
            }
            runCatching {
                add(File(Environment.getExternalStorageDirectory(), RELATIVE_DIR))
            }
            runCatching {
                add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), RELATIVE_DIR))
            }
            runCatching {
                add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), RELATIVE_DIR))
            }
        }
        return dirs.flatMap { dir -> names.map { File(dir, it) } }
    }

    private fun writeViaMediaStore(context: Context, payload: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val existing = findMediaStoreUri(context)
        if (existing != null) {
            resolver.openOutputStream(existing, "wt")?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOCUMENTS + "/$RELATIVE_DIR",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun readViaMediaStore(context: Context): Snapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = findMediaStoreUri(context) ?: return null
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        return parse(text)
    }

    private fun findMediaStoreUri(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(FILE_NAME, "%$RELATIVE_DIR%")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
