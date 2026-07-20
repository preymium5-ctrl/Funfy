package com.example.funfy.data

import java.util.Locale

/** A verified video saved for offline playback. */
data class LocalDownload(
    val id: String,
    val title: String,
    /** Absolute path to the playable local MP4 or HLS playlist. */
    val filePath: String,
    val thumbnailPath: String = "",
    val thumbnailUrl: String = "",
    val duration: String = "—",
    val resolution: String = "HD",
    val sizeBytes: Long = 0L,
    val completedAt: Long = System.currentTimeMillis(),
    /**
     * Absolute path to the file/directory owned by this download.
     *
     * This differs from [filePath] for HLS downloads: [filePath] is the local
     * playlist and [storagePath] is the bundle directory containing its
     * segments. Keeping the ownership boundary explicit makes deletion safe.
     */
    val storagePath: String = filePath,
) {
    val sizeLabel: String
        get() = formatDownloadBytes(sizeBytes)

    val metaLine: String
        get() = listOf(duration, resolution, sizeLabel)
            .filter { it.isNotBlank() && it != "—" }
            .joinToString(" • ")
}

/** Observable lifecycle of an in-app download. */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * A live, immutable snapshot of a download operation.
 *
 * [progress] is always in `0f..1f`. When [totalBytes] is unknown (common for
 * HLS), it is an estimate based on completed media resources while
 * [bytesDownloaded] remains the exact byte count written so far.
 */
data class DownloadTransfer(
    val id: String,
    val title: String,
    val resolution: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val progress: Float = 0f,
    val error: String? = null,
    val localDownload: LocalDownload? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING

    val progressPercent: Int
        get() = (progress.coerceIn(0f, 1f) * 100f).toInt()

    val downloadedLabel: String
        get() = formatDownloadBytes(bytesDownloaded)

    val totalLabel: String?
        get() = totalBytes?.takeIf { it > 0L }?.let(::formatDownloadBytes)
}

internal fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    bytes > 0 -> "$bytes B"
    else -> "—"
}
