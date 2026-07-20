package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

/**
 * XVideos family client (HTML player markup + search pagination).
 * Also used for regional keyword feeds (Thai / Indonesia / Vietnam / Myanmar).
 *
 * XVideos search page index is **0-based** in `p=`:
 * page 1 → no `p` or `p=0`, page 2 → `p=1`, page 3 → `p=2`, ...
 */
class XnxxApi(
    override val source: VideoSource = VideoSource.XVIDEOS,
) : VideoSourceClient {
    private val baseUrl: String = source.baseUrl
    private val fixedKeyword: String? = source.keyword

    val isScopedFeed: Boolean get() = !fixedKeyword.isNullOrBlank()

    companion object {
        const val BASE_URL = "https://www.xvideos.com"
    }

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = when {
            !fixedKeyword.isNullOrBlank() -> keywordUrl(fixedKeyword, p)
            p == 1 -> "$baseUrl/new/1"
            else -> "$baseUrl/new/$p"
        }
        val html = get(url)
        assertNotBlocked(html, url)
        var list = parseListing(html)
        // Fallback for page 1
        if (list.isEmpty() && p == 1 && fixedKeyword.isNullOrBlank()) {
            list = parseListing(get("$baseUrl/best").also { assertNotBlocked(it, baseUrl) })
        }
        if (list.isEmpty() && p == 1 && fixedKeyword.isNullOrBlank()) {
            list = parseListing(get("$baseUrl/").also { assertNotBlocked(it, baseUrl) })
        }
        list.ifEmpty {
            throw IllegalStateException("Could not load videos from ${source.label}. Check network.")
        }
    }

    /** Search by keyword (1-based [page]). */
    override suspend fun search(query: String): List<VideoItem> = search(query, page = 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val html = get(buildSearchUrl(query, page.coerceAtLeast(1)))
        assertNotBlocked(html, baseUrl)
        parseListing(html)
    }

    internal fun scopedQuery(query: String): String {
        return combineScopedSearchQuery(query, fixedKeyword)
    }

    internal fun buildSearchUrl(query: String, page: Int = 1): String =
        keywordUrl(scopedQuery(query), page.coerceAtLeast(1))

    /**
     * Load an official XVideos category (`/c/Amateur-65`) or tag (`/tags/amateur`).
     * Pagination: page 1 = path, page 2 = path/1, page 3 = path/2, …
     */
    suspend fun fetchByCategoryPath(categoryPath: String, page: Int = 1): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val p = page.coerceAtLeast(1)
            val clean = categoryPath.trim().let { if (it.startsWith("/")) it else "/$it" }
            val url = if (p <= 1) {
                "$baseUrl$clean"
            } else {
                "$baseUrl$clean/${p - 1}"
            }
            val html = get(url)
            assertNotBlocked(html, url)
            parseListing(html)
        }

    /**
     * Load by XVideos tag slug: `/tags/{slug}` with same pagination as categories.
     */
    suspend fun fetchByTagSlug(slug: String, page: Int = 1): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val s = slug.trim().trim('/').lowercase().replace(' ', '-')
            fetchByCategoryPath("/tags/$s", page)
        }

    /** Build XVideos keyword URL with correct 0-based `p` index. */
    private fun keywordUrl(keyword: String, page1Based: Int): String {
        val encoded = java.net.URLEncoder.encode(keyword, Charsets.UTF_8.name())
        // XVideos: first page has no p (or p=0). Second page is p=1.
        return if (page1Based <= 1) {
            "$baseUrl/?k=$encoded"
        } else {
            "$baseUrl/?k=$encoded&p=${page1Based - 1}"
        }
    }

    /**
     * Resolve a playable stream URL from a video page.
     * Prefers high quality MP4, then low, then HLS.
     */
    suspend fun resolveStreamUrl(pageUrl: String): String =
        fetchVideoDetails(pageUrl).streamUrl

    /**
     * Load full detail payload for the player screen: stream, meta, tags, related.
     */
    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = get(pageUrl)
        assertNotBlocked(html, pageUrl)

        fun firstGroup(pattern: String): String? {
            val m = Pattern.compile(pattern).matcher(html)
            return if (m.find()) m.group(1)?.replace("\\/", "/") else null
        }

        val high = firstGroup("""html5player\.setVideoUrlHigh\(['"]([^'"]+)['"]\)""")
        val low = firstGroup("""html5player\.setVideoUrlLow\(['"]([^'"]+)['"]\)""")
        val hls = firstGroup("""html5player\.setVideoHLS\(['"]([^'"]+)['"]\)""")

        val streams = buildStreamOptions(high = high, low = low, hls = hls)
        val stream = streams.firstOrNull()?.url
            ?: throw IllegalStateException("No playable stream found on page")

        val title = decodeHtml(
            firstGroup("""html5player\.setVideoTitle\(['"]([^'"]+)['"]\)""")
                ?: firstGroup("""property="og:title"\s+content="([^"]+)"""")
                ?: firstGroup("""<title>([^<]+)</title>""")
                ?: "Video",
        ).removeSuffix(" - XVIDEOS.COM").trim()

        val uploader = decodeHtml(
            firstGroup("""html5player\.setUploaderName\(['"]([^'"]*)['"]\)""")
                ?: firstGroup("""class="name"[^>]*>([^<]+)<""")
                ?: "Channel",
        )

        val views = (
            firstGroup("""id="v-views"[\s\S]*?<strong class="mobile-hide">([^<]+)</strong>""")
                ?: firstGroup("""id="v-views"[\s\S]*?<strong class="mobile-show-inline">([^<]+)</strong>""")
                ?: firstGroup("""([\d,.]+[kKmMbB]?)\s*</strong>\s*</div>\s*<div class="vote-actions"""")
                ?: "—"
            ).trim()

        val ratingPercent = (
            firstGroup("""class="rating-good-perc[^"]*"[^>]*>\s*([\d.]+)""")
                ?: firstGroup("""rating-good-perc[^>]*>\s*([\d.]+)""")
                ?: ""
            ).let { if (it.isBlank()) "—" else "$it %" }

        val durationSec = firstGroup("""property="og:duration"\s+content="(\d+)"""")
            ?: firstGroup(""""duration"\s+content="(\d+)"""")
        val duration = when {
            durationSec != null -> formatDuration(durationSec.toIntOrNull() ?: 0)
            else -> firstGroup("""class="duration"[^>]*>([^<]+)<""")?.trim() ?: "—"
        }

        val resolution = streams.firstOrNull()?.label
            ?: when {
                html.contains("2160") || html.contains("4k", ignoreCase = true) -> "2160p"
                html.contains("1440") -> "1440p"
                html.contains("1080") -> "1080p"
                html.contains("720") -> "720p"
                else -> "HD"
            }

        val tags = mutableListOf<String>()
        val tagMatcher = Pattern.compile(
            """class="is-keyword[^"]*"[^>]*>([^<]+)</a>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (tagMatcher.find()) {
            val tag = decodeHtml(tagMatcher.group(1) ?: continue).trim()
            if (tag.isNotBlank() && tag !in tags) tags.add(tag)
            if (tags.size >= 16) break
        }

        val thumb = normalizeThumb(
            firstGroup("""html5player\.setThumbUrl\(['"]([^'"]+)['"]\)""")
                ?: firstGroup("""property="og:image"\s+content="([^"]+)"""")
                ?: "",
        )

        VideoDetails(
            streamUrl = stream,
            streams = streams,
            title = title,
            uploader = uploader,
            views = views,
            ratingPercent = ratingPercent,
            duration = duration,
            resolution = resolution,
            tags = tags,
            related = parseRelated(html),
            thumbnailUrl = thumb,
        )
    }

    /**
     * Build quality list from Low/High MP4 + HLS master variants when available.
     */
    private fun buildStreamOptions(
        high: String?,
        low: String?,
        hls: String?,
    ): List<StreamOption> {
        val options = linkedMapOf<String, StreamOption>()

        fun add(label: String, url: String?) {
            if (url.isNullOrBlank()) return
            if (options.values.any { it.url == url }) return
            options.putIfAbsent(label, StreamOption(label = label, url = url))
        }

        // Prefer explicit MP4 ladders first
        if (high != null && low != null && high != low) {
            add(guessMp4Label(high, fallback = "High"), high)
            add(guessMp4Label(low, fallback = "Low"), low)
        } else {
            add(guessMp4Label(high, fallback = "High"), high)
            add(guessMp4Label(low, fallback = "Low"), low)
        }

        if (!hls.isNullOrBlank()) {
            val variants = parseHlsMaster(hls)
            if (variants.isNotEmpty()) {
                for (v in variants) {
                    add(v.label, v.url)
                }
            } else {
                add("Auto (HLS)", hls)
            }
        }

        // Stable order: highest first
        val rank = mapOf(
            "2160p" to 0, "1440p" to 1, "1080p" to 2, "720p" to 3,
            "480p" to 4, "360p" to 5, "High" to 6, "Low" to 7, "Auto (HLS)" to 8, "HD" to 9,
        )
        return options.values.sortedBy { rank[it.label] ?: 50 }
    }

    private fun guessMp4Label(url: String?, fallback: String): String {
        if (url.isNullOrBlank()) return fallback
        val lower = url.lowercase()
        return when {
            "2160" in lower || "4k" in lower -> "2160p"
            "1440" in lower -> "1440p"
            "1080" in lower || "hd" in lower && "mp4_hd" in lower -> "1080p"
            "720" in lower -> "720p"
            "480" in lower -> "480p"
            "360" in lower || "mp4_sd" in lower || "_sd." in lower -> "360p"
            fallback == "High" -> "High"
            fallback == "Low" -> "Low"
            else -> fallback
        }
    }

    /** Parse HLS master playlist for RESOLUTION lines. */
    private fun parseHlsMaster(masterUrl: String): List<StreamOption> {
        return try {
            val body = get(masterUrl)
            if (!body.contains("#EXTM3U")) return emptyList()
            // Not a master (media playlist only)
            if (!body.contains("#EXT-X-STREAM-INF")) return emptyList()

            val lines = body.lines()
            val out = mutableListOf<StreamOption>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                    val label = if (res != null) {
                        val h = res.groupValues[2].toIntOrNull() ?: 0
                        when {
                            h >= 2000 -> "2160p"
                            h >= 1400 -> "1440p"
                            h >= 1000 -> "1080p"
                            h >= 700 -> "720p"
                            h >= 450 -> "480p"
                            h > 0 -> "${h}p"
                            else -> "HLS"
                        }
                    } else {
                        "HLS"
                    }
                    // Next non-empty non-comment line is the URI
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        val uri = lines[j].trim()
                        val absolute = when {
                            uri.startsWith("http") -> uri
                            uri.startsWith("//") -> "https:$uri"
                            else -> resolveRelativeUrl(masterUrl, uri)
                        }
                        out.add(StreamOption(label = label, url = absolute))
                    }
                    i = j + 1
                } else {
                    i++
                }
            }
            out.distinctBy { it.label }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveRelativeUrl(base: String, relative: String): String {
        return try {
            java.net.URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            val slash = base.lastIndexOf('/')
            if (slash > 0) base.substring(0, slash + 1) + relative else relative
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "—"
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m >= 60) {
            val h = m / 60
            val mm = m % 60
            "%d:%02d:%02d".format(h, mm, s)
        } else {
            "%d min".format(maxOf(1, m + if (s >= 30) 1 else 0)).let {
                if (m == 0) "${s}s" else if (s == 0 || m >= 3) "$m min" else "%d:%02d".format(m, s)
            }
        }
    }

    /** Parse `var video_related=[...]` objects into [VideoItem]s. */
    private fun parseRelated(html: String): List<VideoItem> {
        val startMarker = "var video_related="
        val start = html.indexOf(startMarker)
        if (start < 0) return emptyList()
        val arrayStart = html.indexOf('[', start)
        if (arrayStart < 0) return emptyList()

        // Bracket-match the JSON array (objects don't nest braces in this payload).
        var depth = 0
        var end = -1
        for (i in arrayStart until minOf(arrayStart + 500_000, html.length)) {
            when (html[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return emptyList()
        val arrayBody = html.substring(arrayStart + 1, end)

        val items = mutableListOf<VideoItem>()
        val objMatcher = Pattern.compile("""\{([^{}]+)\}""").matcher(arrayBody)
        var index = 0
        while (objMatcher.find()) {
            val obj = objMatcher.group(1) ?: continue
            fun field(key: String): String? {
                val m = Pattern.compile(""""$key"\s*:\s*"(.*?)"""").matcher(obj)
                return if (m.find()) m.group(1)?.replace("\\/", "/")?.replace("\\\"", "\"") else null
            }
            fun fieldAny(key: String): String? {
                val quoted = field(key)
                if (quoted != null) return quoted
                val m = Pattern.compile(""""$key"\s*:\s*([^,}\s]+)""").matcher(obj)
                return if (m.find()) m.group(1) else null
            }

            val eid = field("eid") ?: fieldAny("id") ?: continue
            val path = field("u") ?: continue
            val title = decodeHtml(field("tf") ?: field("t") ?: eid)
            val thumb = normalizeThumb(field("i") ?: field("il") ?: "")
            val duration = field("d") ?: "—"
            val views = field("n") ?: "—"
            val rating = field("r") ?: ""
            val resolution = when {
                fieldAny("hp") == "1" || obj.contains("\"hp\":1") -> "1080p"
                fieldAny("h") == "1" || obj.contains("\"h\":1") -> "720p"
                else -> "HD"
            }
            val uploader = decodeHtml(field("pn") ?: field("p") ?: "Channel")

            items.add(
                VideoItem(
                    id = eid,
                    title = title,
                    duration = duration,
                    resolution = if (rating.isNotBlank()) resolution else resolution,
                    views = views,
                    category = uploader,
                    gradientSeed = index++,
                    pageUrl = if (path.startsWith("http")) path else baseUrl + path,
                    sourceId = source.id,
                    thumbnailUrl = thumb,
                ),
            )
            if (items.size >= 24) break
        }
        return items
    }

    private fun assertNotBlocked(html: String, url: String) {
        val lower = html.lowercase()
        if (
            lower.contains("prohibitedaccess") ||
            lower.contains("this website is not available") ||
            lower.contains("access denied") && lower.contains("pldt")
        ) {
            throw IllegalStateException(
                "Site blocked by your ISP for $url. Try mobile data or a VPN.",
            )
        }
    }

    private fun get(url: String): String = NetworkClient.get(url, referer = "$baseUrl/")

    /**
     * Parse listing HTML (XVideos thumb-block cards; also tolerates XNXX-style /video- links).
     */
    internal fun parseListing(html: String): List<VideoItem> {
        val fromBlocks = parseThumbBlocks(html)
        if (fromBlocks.isNotEmpty()) return fromBlocks
        return parseVideoLinks(html)
    }

    private fun parseThumbBlocks(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()

        // XVideos: id="video_opvelmv3361"  |  XNXX: id="video_123456"
        val openPattern = Pattern.compile(
            """<div[^>]*\bid="video_([a-zA-Z0-9_-]+)"[^>]*class="[^"]*thumb-block[^"]*"[^>]*>""",
            Pattern.CASE_INSENSITIVE,
        )
        val starts = mutableListOf<Pair<Int, String>>()
        val openMatcher = openPattern.matcher(html)
        while (openMatcher.find()) {
            val id = openMatcher.group(1) ?: continue
            starts.add(openMatcher.start() to id)
        }

        if (starts.isEmpty()) {
            val alt = Pattern.compile(
                """<div[^>]*class="[^"]*thumb-block[^"]*"[^>]*\bid="video_([a-zA-Z0-9_-]+)"[^>]*>""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (alt.find()) {
                val id = alt.group(1) ?: continue
                starts.add(alt.start() to id)
            }
        }

        if (starts.isEmpty()) {
            val classOnly = Pattern.compile(
                """<div[^>]*class="[^"]*thumb-block[^"]*"[^>]*>""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (classOnly.find()) {
                starts.add(classOnly.start() to "")
            }
        }

        for (i in starts.indices) {
            val (start, preferredId) = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1].first else minOf(start + 2800, html.length)
            val block = html.substring(start, end)
            val item = parseBlock(block, preferredId.ifBlank { null }, i) ?: continue
            if (seen.add(item.id)) items.add(item)
        }
        return items
    }

    private fun parseBlock(block: String, preferredId: String?, index: Int): VideoItem? {
        // XVideos: /video.ID/...   XNXX: /video-ID/...
        val href = matchFirst(block, """href="(/video[.\-][^"#?\s]+)"""") ?: return null

        val idFromHref = when {
            href.startsWith("/video.") -> href.removePrefix("/video.").substringBefore('/')
            href.startsWith("/video-") -> href.removePrefix("/video-").substringBefore('/')
            else -> preferredId
        }
        val id = preferredId?.takeIf { it.isNotBlank() }
            ?: idFromHref
            ?: href.hashCode().toUInt().toString()

        val title = decodeHtml(
            matchFirst(block, """title="([^"]{2,})"""")
                ?: matchFirst(block, """class="title"[^>]*>\s*<a[^>]*>([\s\S]*?)</a>""")
                    ?.replace(Regex("""<[^>]+>"""), " ")
                ?: matchFirst(block, """alt="([^"]{2,})"""")
                ?: href.substringAfterLast('/').replace('_', ' '),
        ).replace(Regex("""\s+"""), " ").trim()
        if (title.isBlank()) return null

        // Prefer real preview thumbs (data-src / data-mzl), never the blank lightbox gif
        var thumb = matchFirst(block, """data-src="(https?://[^"]+)"""")
            ?: matchFirst(block, """data-mzl="(https?://[^"]+)"""")
            ?: matchFirst(block, """data-src="(//[^"]+)"""")
            ?: matchFirst(block, """src="(https?://[^"]+\.(?:jpg|jpeg|png|webp|avif)[^"]*)"""")
            ?: ""
        thumb = normalizeThumb(thumb)

        val duration = (
            matchFirst(block, """class="duration"[^>]*>([^<]+)<""")
                ?: matchFirst(block, """>(\d{1,3}\s*min)</""")
                ?: "—"
            ).trim()

        val resolution = (
            matchFirst(block, """video-hd-mark"[^>]*>([^<]+)<""")
                ?: matchFirst(block, """\b(2160p|1440p|1080p|720p|480p)\b""")
                ?: when {
                    block.contains("video-hd", ignoreCase = true) -> "HD"
                    else -> "HD"
                }
            ).trim()

        val views = (
            matchFirst(block, """([\d]+(?:[.,]\d+)?\s*[kKmMbB])\s*<span[^>]*>\s*Views""")
                ?: matchFirst(block, """([\d]+(?:[.,]\d+)?\s*[kKmMbB])\s*Views""")
                ?: matchFirst(block, """class="right"[^>]*>([^<]+)<""")
                ?: "—"
            ).trim()

        val uploader = matchFirst(block, """class="name"[^>]*>([^<]+)<""")?.trim()

        return VideoItem(
            id = id,
            title = title,
            duration = duration,
            resolution = resolution,
            views = views,
            category = uploader ?: "Trending",
            gradientSeed = index,
            pageUrl = if (href.startsWith("http")) href else baseUrl + href,
            thumbnailUrl = thumb,
            sourceId = source.id,
        )
    }

    private fun parseVideoLinks(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val linkPattern = Pattern.compile(
            """href="(/video[.\-]([a-zA-Z0-9_-]+)[^"#?]*)"[^>]*(?:title="([^"]*)")?""",
            Pattern.CASE_INSENSITIVE,
        )
        val matcher = linkPattern.matcher(html)
        var index = 0
        while (matcher.find()) {
            val href = matcher.group(1) ?: continue
            val id = matcher.group(2) ?: continue
            if (!seen.add(id)) continue

            val title = decodeHtml(
                matcher.group(3)?.takeIf { it.length >= 2 }
                    ?: href.substringAfterLast('/').replace('_', ' '),
            )

            val windowStart = (matcher.start() - 500).coerceAtLeast(0)
            val windowEnd = (matcher.start() + 1200).coerceAtMost(html.length)
            val window = html.substring(windowStart, windowEnd)

            val thumb = normalizeThumb(
                matchFirst(window, """data-src="(https?://[^"]+)"""")
                    ?: matchFirst(window, """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp|avif)[^"]*)"""")
                    ?: "",
            )
            val duration = matchFirst(window, """class="duration"[^>]*>([^<]+)<""")?.trim() ?: "—"
            val resolution = matchFirst(window, """video-hd-mark"[^>]*>([^<]+)<""")?.trim() ?: "HD"

            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration,
                    resolution = resolution,
                    views = "—",
                    category = "Trending",
                    gradientSeed = index++,
                    pageUrl = baseUrl + href,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 100) break
        }
        return items
    }

    private fun normalizeThumb(raw: String): String {
        var thumb = raw.replace("&amp;", "&").trim()
        if (thumb.startsWith("//")) thumb = "https:$thumb"
        // XVideos placeholder frame index
        if (thumb.contains("THUMBNUM")) {
            thumb = thumb.replace("THUMBNUM", "16")
        }
        // Skip blank lightbox placeholder
        if (thumb.contains("lightbox-blank")) return ""
        return thumb
    }

    private fun matchFirst(input: String, pattern: String): String? {
        val m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(input)
        return if (m.find()) m.group(1) else null
    }

    private fun decodeHtml(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace("&iexcl;", "¡")
            .replace("&ntilde;", "ñ")
            .replace(Regex("""&#(\d+);""")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            .trim()
}
