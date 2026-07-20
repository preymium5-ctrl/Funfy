package com.example.funfy.data

/** One playable quality option for the resolution picker / download dialog. */
data class StreamOption(
    val label: String,
    val url: String,
    /** Optional file size in bytes (shown as e.g. "238 MB"). */
    val sizeBytes: Long? = null,
) {
    fun displayWithSize(): String {
        val size = sizeBytes?.let { formatBytes(it) }
        return if (size != null) "$label - $size" else label
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> "%.0f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

/** Full metadata for the video detail / player screen. */
data class VideoDetails(
    val streamUrl: String,
    val streams: List<StreamOption>,
    val title: String,
    val uploader: String,
    val views: String,
    val ratingPercent: String,
    val duration: String,
    val resolution: String,
    val tags: List<String>,
    val related: List<VideoItem>,
    val thumbnailUrl: String = "",
    /**
     * When direct MP4/HLS cannot be extracted (e.g. Indo18 → jomblo → playmogo CF),
     * play this embed URL in a WebView instead.
     */
    val embedUrl: String? = null,
)
