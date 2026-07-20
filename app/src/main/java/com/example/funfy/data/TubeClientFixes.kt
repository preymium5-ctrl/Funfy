package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

// Sexvid â€” absolute .html links + KVS streams (hash decrypt)
// ---------------------------------------------------------------------------

class SexvidClient : VideoSourceClient {
    override val source = VideoSource.SEXVID

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val paths = if (p <= 1) listOf("/", "/most-popular/") else listOf("/$p/", "/most-popular/$p/")
        val seen = linkedSetOf<String>()
        val out = mutableListOf<VideoItem>()
        for (path in paths) {
            try {
                for (item in parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))) {
                    if (seen.add(item.id)) out.add(item)
                }
                if (out.isNotEmpty()) break
            } catch (_: Exception) {
            }
        }
        out.ifEmpty { throw IllegalStateException("Could not load Sexvid") }
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/search/$q/", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = collectMp4AndHls(html, source.baseUrl)
            .filter {
                !it.url.contains("preview", true) &&
                    !it.url.contains("_vthumb", true) &&
                    !it.url.contains("trailer", true)
            }
            .ifEmpty { collectMp4AndHls(html, source.baseUrl) }
        if (streams.isEmpty()) throw IllegalStateException("No stream on Sexvid")
        val labeled = streams.map {
            it.copy(label = NetworkClient.guessQualityLabel(it.url, it.label).ifBlank { it.label })
        }
        VideoDetails(
            streamUrl = labeled.first().url,
            streams = labeled.distinctBy { it.url },
            title = title,
            uploader = "Sexvid",
            views = "â€”",
            ratingPercent = "â€”",
            duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "â€”",
            resolution = labeled.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val m = Pattern.compile(
            """href="(https://(?:www\.)?sexvid\.xxx/([a-z0-9][a-z0-9-]{2,})\.html)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val window = html.substring(
                (m.start() - 100).coerceAtLeast(0),
                (m.start() + 1200).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src|srcset)="(https?://[^"\s,]+\.(?:jpg|jpeg|png|webp)[^"\s,]*)"""",
            ).orEmpty()
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{4,})"""")
                    ?: id.replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = NetworkClient.matchFirst(window, """class="duration"[^>]*>([^<]+)<""")
                        ?: "â€”",
                    resolution = "HD",
                    views = "â€”",
                    category = "Sexvid",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 60) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// Analdin â€” KVS get_file (keep trailing slash) + listing thumbs
// ---------------------------------------------------------------------------

class AnaldinClient : VideoSourceClient {
    override val source = VideoSource.ANALDIN

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/latest-updates/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/search/$q/", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = collectMp4AndHls(html, source.baseUrl)
            .filter {
                !it.url.contains("_vthumb", true) &&
                    !it.url.contains("preview", true) &&
                    !it.url.contains("trailer", true)
            }
            .map { opt ->
                // Critical: Analdin get_file requires trailing slash â†’ 404 without it.
                var u = opt.url
                if (u.contains("/get_file/", true) && !u.contains("?") && !u.endsWith("/")) {
                    u = "$u/"
                }
                // If still wrapped in function/ (shouldn't after collect), decrypt
                if (u.startsWith("function/", true)) {
                    val lic = NetworkClient.matchFirst(html, """license_code\s*:\s*['"]([^'"]+)['"]""")
                    u = KvsDecoder.getRealUrl(u, lic)
                }
                val label = when {
                    u.contains("hd", true) || u.contains("720") || u.contains("1080") ->
                        NetworkClient.guessQualityLabel(u, "HD").ifBlank { "HD" }
                    else -> NetworkClient.guessQualityLabel(u, "SD").ifBlank { "SD" }
                }
                StreamOption(label, u)
            }
        if (streams.isEmpty()) throw IllegalStateException("No stream on Analdin")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "Analdin",
            views = "â€”",
            ratingPercent = "â€”",
            duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "â€”",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Prefer data-original (real jpg); avoid matching vthumb= as thumb=
        val m = Pattern.compile(
            """href="(https://(?:www\.)?analdin\.com/videos/(\d+)/[^"]+/)"([^>]*)>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val attrs = m.group(3).orEmpty()
            val window = html.substring(
                (m.start() - 80).coerceAtLeast(0),
                (m.start() + 700).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(attrs, """\bthumb="(https?://i\.analdin[^"]+)"""")
                ?: NetworkClient.matchFirst(attrs, """\bthumb="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""")
                ?: NetworkClient.matchFirst(window, """data-original="(https?://[^"]+)"""")
                ?: NetworkClient.matchFirst(window, """\bthumb="(https?://i\.analdin[^"]+)"""")
                ?: extractThumbFromWindow(window)
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{3,})"""")
                    ?: href.trimEnd('/').substringAfterLast('/').replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = NetworkClient.matchFirst(window, """class="duration"[^>]*>([^<]+)<""")
                        ?: "â€”",
                    resolution = "HD",
                    views = "â€”",
                    category = "Analdin",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 60) break
        }
        return items
    }
}

