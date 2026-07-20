package com.example.funfy.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Saves an offline MP4 into the public Movies/Funfy gallery album
 * (Telegram-style “Save to gallery”).
 */
object GallerySaver {
    /**
     * @return true when the file was written to the system gallery.
     */
    fun saveVideo(context: Context, filePath: String, title: String): Result<Unit> = runCatching {
        val source = File(filePath)
        require(source.isFile && source.length() > 0L) { "Video file is missing" }
        val lower = source.name.lowercase()
        require(lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv")) {
            "Only MP4 offline files can be saved to the gallery (HLS playlists cannot)."
        }
        val mime = when {
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
        val safeName = title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .take(48)
            .ifBlank { "Funfy video" }
        val displayName = if (safeName.contains('.')) safeName else "$safeName.mp4"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Funfy",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values)
            ?: error("Could not create gallery entry")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error("Could not open gallery stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }
}
