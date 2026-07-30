package com.example.funfy.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Saves an offline MP4 or HLS bundle into the public Movies/Funfy gallery album
 * (Telegram-style “Save to gallery”).
 */
object GallerySaver {
    /**
     * @return true when the file was written to the system gallery.
     */
    fun saveVideo(
        context: Context,
        filePath: String,
        title: String,
        storagePath: String = filePath,
    ): Result<Unit> = runCatching {
        val fileOrDir = File(storagePath).takeIf { it.exists() } ?: File(filePath)
        require(fileOrDir.exists()) { "Video download is missing" }

        val isDirectory = fileOrDir.isDirectory
        val lower = fileOrDir.name.lowercase()

        val mime = when {
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
        val ext = when {
            lower.endsWith(".webm") -> ".webm"
            lower.endsWith(".mkv") -> ".mkv"
            else -> ".mp4"
        }

        val safeName = title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .take(48)
            .ifBlank { "Funfy video" }
        val displayName = if (safeName.endsWith(ext, ignoreCase = true)) safeName else "$safeName$ext"

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
                if (isDirectory) {
                    val segments = fileOrDir.walkTopDown()
                        .filter { seg ->
                            seg.isFile && (
                                seg.extension.equals("ts", ignoreCase = true) ||
                                    seg.extension.equals("mp4", ignoreCase = true) ||
                                    seg.extension.equals("m4s", ignoreCase = true) ||
                                    seg.name.contains("segment", ignoreCase = true) ||
                                    seg.name.contains("seg", ignoreCase = true)
                            )
                        }
                        .sortedWith(Comparator { f1, f2 ->
                            val n1 = Regex("""\d+""").findAll(f1.name).lastOrNull()?.value?.toLongOrNull() ?: 0L
                            val n2 = Regex("""\d+""").findAll(f2.name).lastOrNull()?.value?.toLongOrNull() ?: 0L
                            n1.compareTo(n2)
                        })
                        .toList()
                    require(segments.isNotEmpty()) { "No video segment files found in offline directory" }
                    for (seg in segments) {
                        FileInputStream(seg).use { input -> input.copyTo(output) }
                    }
                } else {
                    FileInputStream(fileOrDir).use { input -> input.copyTo(output) }
                }
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
