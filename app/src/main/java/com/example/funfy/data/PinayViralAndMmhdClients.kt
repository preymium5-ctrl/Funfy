package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// PinayViral — WordPress article cards (homepage was empty with generic parser)
// ---------------------------------------------------------------------------

class PinayViralClient : VideoSourceClient {
    override val source = VideoSource.PINAYVIRAL

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val urls = if (p <= 1) {
            listOf(source.baseUrl + "/", source.baseUrl + "/category/celebrity/")
        } else {
            listOf("${source.baseUrl}/page/$p/", "${source.baseUrl}/category/celebrity/page/$p/")
        }
        for (url in urls) {
            try {
                val items = parseListing(NetworkClient.get(url, source.baseUrl))
                if (items.isNotEmpty()) return@withContext items
            } catch (_: Exception) {
            }
        }
        emptyList()
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
                ?: "PinayViral",
        ).trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, source.baseUrl)
        // Clean Tube / direct video tags
        streams = (streams + extractCleanTubeStreams(html)).distinctBy { it.url }
        if (streams.isEmpty()) {
            val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
            if (!iframe.isNullOrBlank() && !iframe.contains("googletag")) {
                val emb = NetworkClient.absoluteUrl(pageUrl, iframe)
                try {
                    streams = collectMp4AndHls(NetworkClient.get(emb, pageUrl))
                } catch (_: Exception) {
                }
                if (streams.isEmpty()) streams = listOf(StreamOption("Embed", emb))
            }
        }
        if (streams.isEmpty()) {
            // Article pages may not have video — surface embed of the page for WebView
            streams = listOf(StreamOption("Page", pageUrl))
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "PinayViral",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed" || streams.first().label == "Page") {
                streams.first().url
            } else {
                null
            },
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // WP posts: https://www.pinayviral.org/slug/
        val m = Pattern.compile(
            """href="(https://(?:www\.)?pinayviral\.org/([a-z0-9][a-z0-9-]{3,})/?)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val skip = setOf(
            "feed", "comments", "wp-json", "wp-content", "wp-admin", "category", "tag",
            "author", "page", "contact-us", "disclaimer", "terms-and-conditions",
            "privacy-policy-2", "privacy-policy", "xmlrpc",
        )
        while (m.find()) {
            val href = m.group(1) ?: continue
            val slug = m.group(2) ?: continue
            if (slug in skip || slug.startsWith("category") || slug.startsWith("tag")) continue
            if (!seen.add(slug)) continue
            val window = html.substring(
                (m.start() - 200).coerceAtLeast(0),
                (m.start() + 900).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{4,})"""")
                    ?: slug.replace('-', ' '),
            )
            // Skip pure nav/chrome titles
            if (title.length < 8) continue
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src|data-lazy-src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
            if (thumb.contains("cropped-") && thumb.contains("32x32")) continue
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "PinayViral",
                    gradientSeed = index++,
                    pageUrl = href.trimEnd('/') + "/",
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// MMHDHub — Clean Tube + cloud.mmhd-cdn progressive MP4 (fast-start for seek)
// ---------------------------------------------------------------------------

class MmhdHubClient : VideoSourceClient {
    override val source = VideoSource.MMHDHUB

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
                ?: "MMHDHub",
        ).substringBefore(" - ").trim()
        // og:image is often a wrong site-wide cover; prefer cloud poster / video poster attr.
        val thumb = NetworkClient.matchFirst(
            html,
            """(https?://cloud\.mmhd-cdn\.com/images/[^"'\\\s]+\.(?:jpg|jpeg|png|webp))""",
        ) ?: NetworkClient.matchFirst(
            html,
            """poster=["'](https?://cloud\.mmhd-cdn\.com/[^"']+)["']""",
        ) ?: NetworkClient.matchFirst(
            html,
            """poster=["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""",
        ) ?: run {
            // Decode clean-tube player q= for poster= inside base64 payload
            val q = NetworkClient.matchFirst(
                html,
                """player-x\.php\?q=([A-Za-z0-9_=\-+/%]+)""",
            )
            if (!q.isNullOrBlank()) {
                try {
                    val padded = q.replace('-', '+').replace('_', '/')
                        .let { s -> s + "=".repeat((4 - s.length % 4) % 4) }
                    val raw = runCatching {
                        java.util.Base64.getDecoder().decode(padded)
                    }.getOrElse {
                        java.util.Base64.getUrlDecoder().decode(q)
                    }
                    val decoded = String(raw, Charsets.UTF_8)
                    val unescaped = runCatching {
                        java.net.URLDecoder.decode(decoded, Charsets.UTF_8.name())
                    }.getOrDefault(decoded)
                    NetworkClient.matchFirst(
                        unescaped,
                        """poster=["'](https?://[^"']+)["']""",
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        } ?: NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            ?.takeIf { !it.contains("X-cover", true) && !it.contains("cropped-", true) }
            .orEmpty()
        // Prefer CDN mp4s extracted from clean-tube base64 + bare links
        var streams = (
            extractCleanTubeStreams(html) +
                collectMp4AndHls(html, source.baseUrl)
            )
            .map {
                it.copy(
                    url = NetworkClient.sanitizeMediaUrl(it.url),
                    label = NetworkClient.guessQualityLabel(it.url, it.label).ifBlank { "MP4" },
                )
            }
            .filter {
                it.url.contains(".mp4", true) &&
                    !it.url.contains("preview", true)
            }
            .distinctBy { it.url }
        // Prefer cloud.mmhd-cdn.com over dl.mmhdhub.com (often slower)
        streams = streams.sortedByDescending { s ->
            when {
                s.url.contains("cloud.mmhd-cdn.com") -> 100
                s.url.contains("mmhd-cdn") -> 80
                s.url.contains("dl.mmhdhub") -> 40
                else -> 10
            }
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on MMHDHub")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "MMHDHub",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = NetworkClient.sanitizeMediaUrl(thumb),
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Numeric WP posts: https://mmhdhub.com/40305/
        val m = Pattern.compile(
            """href="(https://mmhdhub\.com/(\d{3,})/)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val window = html.substring(
                (m.start() - 100).coerceAtLeast(0),
                (m.start() + 900).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: "Video $id",
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|data-lazy-src|src)="(https?://cloud\.mmhd-cdn\.com/images/[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|data-lazy-src|src)="(https?://(?:cloud\.)?mmhd-cdn\.com/[^"]+)"""",
            ) ?: extractThumbFromWindow(window)
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "MMHDHub",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) break
        }
        return items
    }
}
