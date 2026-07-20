package com.example.funfy.data

data class VideoItem(
    val id: String,
    val title: String,
    val duration: String,
    val resolution: String,
    val views: String,
    val category: String,
    val gradientSeed: Int,
    /** Full page URL (used to resolve stream). */
    val pageUrl: String = "",
    /** Thumbnail image URL. */
    val thumbnailUrl: String = "",
    /** Which source this item came from (see [VideoSource.id]). */
    val sourceId: String = VideoSource.DEFAULT.id,
)
