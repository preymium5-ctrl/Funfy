package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// MissAV — listing /en/{dvd-id} + packed surrit.com HLS
// ---------------------------------------------------------------------------

class MissAvClient : VideoSourceClient {
    override val source = VideoSource.MISSAV

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val paths = if (p <= 1) {
            listOf("/dm539/en/new", "/dm634/en/release", "/en")
        } else {
            listOf("/dm539/en/new?page=$p", "/dm634/en/release?page=$p")
        }
        for (path in paths) {
            try {
                val items = parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))
                if (items.isNotEmpty()) return@withContext items
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val path = if (p <= 1) "/en/search/$q" else "/en/search/$q?page=$p"
        parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "MissAV",
        ).substringBefore(" | ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            ?: NetworkClient.matchFirst(html, """(https?://fourhoi\.com/[^"']+cover[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)""")
            ?: ""
        // Packed player: source='https://surrit.com/{uuid}/playlist.m3u8'
        var streams = extractSurritStreams(html)
        if (streams.isEmpty()) {
            val unpacked = JsPackerUnpacker.unpackAll(html)
            streams = extractSurritStreams(unpacked) + collectMp4AndHls(unpacked)
        }
        if (streams.isEmpty()) {
            streams = collectMp4AndHls(html, source.baseUrl)
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on MissAV")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "MissAV",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb,
        )
    }

    private fun extractSurritStreams(blob: String): List<StreamOption> {
        val out = linkedMapOf<String, StreamOption>()
        // source='…playlist.m3u8' / source842='…/720p/video.m3u8'
        val m = Pattern.compile(
            """source(?:\d*)\s*=\s*['"](https?://(?:surrit\.com|[^"']+)[^"']+\.m3u8[^"']*)['"]""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(blob)
        while (m.find()) {
            val u = m.group(1)?.replace("\\/", "/") ?: continue
            val label = when {
                u.contains("1080") -> "1080p"
                u.contains("720") -> "720p"
                u.contains("480") -> "480p"
                u.contains("360") -> "360p"
                u.contains("playlist") -> "Auto (HLS)"
                else -> "HLS"
            }
            out.putIfAbsent(label, StreamOption(label, u))
        }
        val bare = Pattern.compile(
            """(https?://surrit\.com/[^"'\\\s]+\.m3u8[^"'\\\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(blob)
        while (bare.find()) {
            val u = bare.group(1)?.replace("\\/", "/") ?: continue
            out.putIfAbsent(u, StreamOption("Auto (HLS)", u))
        }
        return out.values.sortedByDescending {
            it.label.filter(Char::isDigit).toIntOrNull() ?: if (it.label.contains("Auto")) 50 else 0
        }
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // https://missav.ws/en/docp-404-2  or /dm539/en/code
        val m = Pattern.compile(
            """href="((?:https://missav\.ws)?/(?:dm\d+/)?en/([a-z0-9][a-z0-9-]{2,}))"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val skip = setOf(
            "new", "release", "search", "vip", "saved", "actresses", "makers",
            "genres", "login", "register", "dm", "cn", "ja", "ko", "ms", "th",
        )
        while (m.find()) {
            val path = m.group(1) ?: continue
            val slug = m.group(2) ?: continue
            if (slug in skip || slug.startsWith("dm")) continue
            if (!seen.add(slug)) continue
            val window = html.substring(
                (m.start() - 200).coerceAtLeast(0),
                (m.start() + 800).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://fourhoi\.com/[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
            if (thumb.contains("flag", true) || thumb.contains("favicon", true)) continue
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:alt|title)="([^"]{2,})"""")
                    ?: slug.uppercase(),
            )
            // Prefer constructed fourhoi cover when missing
            val cover = thumb.ifBlank {
                "https://fourhoi.com/$slug/cover-t.jpg"
            }
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "MissAV",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                    thumbnailUrl = cover,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 60) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// JavTsunami — WP posts ending in .html + multi-host embeds
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
        parseListing(NetworkClient.get("${source.baseUrl}/?s=$q", source.baseUrl))
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
        var streams = collectMp4AndHls(html, source.baseUrl)
        var embedUrl: String? = null
        val embeds = mutableListOf<String>()
        val ifr = Pattern.compile(
            """iframe[^>]+src=["'](https?://[^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (ifr.find()) {
            val src = ifr.group(1) ?: continue
            if (src.contains("cbox") || src.contains("googletag") || src.contains(".js")) continue
            embeds.add(src)
        }
        // bare host embeds
        val bare = Pattern.compile(
            """(https?://(?:turbovidhls\.com|hicherri\.com|vide0\.net|playerwish\.com|strwish\.com|streamwish\.to|filemoon\.[a-z]+|dood\.[a-z]+)/[^\s"'<>]+)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (bare.find()) {
            bare.group(1)?.let { embeds.add(it) }
        }
        for (emb in embeds.distinct().take(6)) {
            embedUrl = emb
            if (isStreamWishHost(emb) || emb.contains("hicherri") || emb.contains("turbovid")) {
                val wish = resolveStreamWishEmbed(emb, pageUrl)
                if (wish.isNotEmpty()) {
                    streams = wish
                    break
                }
            }
            try {
                val nested = NetworkClient.get(emb, pageUrl)
                val nestedStreams = collectMp4AndHls(nested) + resolveStreamWishEmbed(emb, pageUrl)
                if (nestedStreams.isNotEmpty()) {
                    streams = nestedStreams
                    break
                }
            } catch (_: Exception) {
            }
        }
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on JavTsunami")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "JavTsunami",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed") embedUrl else null,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // https://javtsunami.com/thai-subtitle-start588v.html
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
            if (thumb.startsWith("data:image") || thumb.contains("placeholder", true)) {
                // try after link
            }
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

private fun isStreamWishHost(url: String): Boolean {
    val h = url.lowercase()
    return h.contains("playerwish") ||
        h.contains("strwish") ||
        h.contains("streamwish") ||
        h.contains("swishsrv") ||
        h.contains("hlswish") ||
        h.contains("hicherri") ||
        h.contains("turbovid")
}
