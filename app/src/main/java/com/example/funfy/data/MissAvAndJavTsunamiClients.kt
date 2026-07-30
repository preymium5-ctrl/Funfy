package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// JavTsunami — WP posts + turbovid / streamwish embeds → direct HLS
// ---------------------------------------------------------------------------

class JavTsunamiClient : VideoSourceClient {
    override val source = VideoSource.JAVTSUNAMI

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) "${source.baseUrl}/?s=$q" else "${source.baseUrl}/page/$p/?s=$q"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "JavTsunami",
        ).substringBefore(" - ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        // Page-local mp4/m3u8 — drop invalid hosts (https://.etvp.cc/...)
        var streams = collectMp4AndHls(html, source.baseUrl)
            .filter { isValidMediaUrl(it.url) && (it.url.contains("m3u8", true) || it.url.contains("mp4", true)) }
        val embeds = mutableListOf<String>()
        val ifr = Pattern.compile(
            """iframe[^>]+src=["'](https?://[^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (ifr.find()) {
            val src = ifr.group(1) ?: continue
            if (src.contains("cbox") || src.contains("googletag") || src.contains("/ad")) continue
            embeds.add(src)
        }
        val bare = Pattern.compile(
            """(https?://(?:turbovidhls\.com|turboviplay\.com|hicherri\.com|vide0\.net|playerwish\.com|strwish\.com|streamwish\.to|filemoon\.[a-z]+)/[^\s"'<>]+)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (bare.find()) {
            bare.group(1)?.let { embeds.add(it) }
        }
        var sawDeadEtvp = false
        for (emb in embeds.distinct().take(8)) {
            try {
                // Turbovid first — data-hash m3u8 when available; skip dead .etvp.cc mp4 hosts.
                if (emb.contains("turbovid", true) || emb.contains("turboviplay", true)) {
                    val turbo = resolveTurbovidEmbed(emb, pageUrl)
                        .filter { isValidMediaUrl(it.url) }
                    if (turbo.isNotEmpty()) {
                        streams = turbo
                        break
                    }
                    // Detect dead embed so we can surface a clear error
                    try {
                        val embHtml = NetworkClient.get(emb, pageUrl)
                        if (embHtml.contains("://.etvp.cc", true) ||
                            embHtml.contains("urlPlay = 'https://.", true)
                        ) {
                            sawDeadEtvp = true
                        }
                    } catch (_: Exception) {
                    }
                }
                if (isStreamWishHost(emb)) {
                    val wish = resolveStreamWishEmbed(emb, pageUrl)
                        .filter { isValidMediaUrl(it.url) }
                    if (wish.isNotEmpty()) {
                        streams = wish
                        break
                    }
                }
                val dood = resolveDoodStreamEmbed(emb, pageUrl)
                    .filter { isValidMediaUrl(it.url) }
                if (dood.isNotEmpty()) {
                    streams = dood
                    break
                }
                val nested = NetworkClient.get(emb, pageUrl)
                val nestedStreams = collectMp4AndHls(nested)
                    .filter { isValidMediaUrl(it.url) }
                    .ifEmpty {
                        if (nested.contains("turboviplay", true) || nested.contains(".m3u8") ||
                            nested.contains("data-hash", true)
                        ) {
                            resolveTurbovidEmbed(emb, pageUrl).filter { isValidMediaUrl(it.url) }
                        } else {
                            emptyList()
                        }
                    }
                if (nestedStreams.isNotEmpty()) {
                    streams = nestedStreams
                    break
                }
            } catch (_: Throwable) {
            }
        }
        streams = streams.filter { isValidMediaUrl(it.url) }
        if (streams.isEmpty()) {
            throw IllegalStateException(
                if (sawDeadEtvp) {
                    "No playable stream on JavTsunami (embed CDN host missing)"
                } else {
                    "No playable stream on JavTsunami"
                },
            )
        }
        // Prefer m3u8 over progressive
        val preferred = streams.sortedByDescending {
            when {
                it.url.contains("m3u8", true) -> 2
                it.url.contains("mp4", true) -> 1
                else -> 0
            }
        }
        VideoDetails(
            streamUrl = preferred.first().url,
            streams = preferred.distinctBy { it.url },
            title = title,
            uploader = "JavTsunami",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = preferred.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
            embedUrl = null,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val m = Pattern.compile(
            """href="(https://javtsunami\.com/([a-z0-9][a-z0-9-_%]+)\.html)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val slug = m.group(2) ?: continue
            if (!seen.add(slug)) continue
            val window = html.substring(
                (m.start() - 100).coerceAtLeast(0),
                (m.start() + 900).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """data-lazy-src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://(?:imagerls|pics\.dmm)[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
            val cleanThumb = if (thumb.startsWith("data:") || thumb.contains("svg")) {
                NetworkClient.matchFirst(
                    html.substring(m.start(), (m.start() + 1200).coerceAtMost(html.length)),
                    """data-lazy-src="(https?://[^"]+)"""",
                ).orEmpty()
            } else {
                thumb
            }
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                    ?: slug.replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "JavTsunami",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = cleanThumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) break
        }
        return items
    }
}
