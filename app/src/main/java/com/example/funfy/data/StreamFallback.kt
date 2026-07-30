package com.example.funfy.data

/**
 * Shared "mirror fallback" used when a direct-site scraper cannot extract a real
 * MP4/HLS stream (dead embed host, Cloudflare gate, premium file host, …).
 *
 * Many aggregator/tube sites (BokepBox, BokepIndoHot, PornKai, JavFree, …) simply
 * re-host or mirror clips that also exist on XVideos, which we can play reliably.
 * Rather than surfacing "No direct stream …" we search XVideos for the same title
 * (or JAV/product code) and return its playable streams, re-branded to the source.
 */
internal suspend fun xvideosMirrorFallback(
    title: String,
    pageUrl: String,
    source: VideoSource,
    thumb: String = "",
    related: List<VideoItem> = emptyList(),
    tags: List<String> = emptyList(),
    extraQueries: List<String> = emptyList(),
): VideoDetails? {
    // Disabled fallback guessing per user request; when video is unavailable, show "Video has been removed".
    return null
}
