package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// xHamster2 — HTML listings + page HLS/MP4 (xhcdn)
// ---------------------------------------------------------------------------

class XHamster2Client : VideoSourceClient {
    override val source = VideoSource.XHAMSTER2

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        // Prefer /newest which ships a full videoThumbProps array in window.initials.
        val paths = if (p <= 1) {
            listOf("/newest", "/", "/best")
        } else {
            listOf("/newest/$p", "/best/$p", "/?page=$p")
        }
        val seen = linkedSetOf<String>()
        val out = mutableListOf<VideoItem>()
        for (path in paths) {
            try {
                val html = NetworkClient.get(source.baseUrl + path, source.baseUrl)
                for (item in parseListing(html)) {
                    if (seen.add(item.id)) out.add(item)
                }
                // JSON listing usually has 40+; stop once we have a full page.
                if (out.size >= PAGE_SIZE) break
            } catch (_: Exception) {
            }
        }
        out.take(PAGE_SIZE)
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) {
            "${source.baseUrl}/search/$q"
        } else {
            "${source.baseUrl}/search/$q?page=$p"
        }
        parseListing(NetworkClient.get(url, source.baseUrl)).take(PAGE_SIZE)
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """"title"\s*:\s*"([^"]{4,})"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore("|").substringBefore(" - ").trim()
        val thumb = pickSharpThumb(
            NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """"imageURL"\s*:\s*"([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """"thumbURL"\s*:\s*"([^"]+)""""),
        )
        val related = parseListing(html)
            .filter {
                it.pageUrl != pageUrl &&
                    it.thumbnailUrl.isNotBlank() &&
                    !it.thumbnailUrl.endsWith(".mp4", true) &&
                    it.title.length >= 3
            }
            .take(18)

        var streams = collectXhStreams(html)
        if (streams.isEmpty()) {
            val fallback = xvideosMirrorFallback(
                title = title,
                pageUrl = pageUrl,
                source = source,
                thumb = thumb,
                related = related,
            )
            if (fallback != null) return@withContext fallback
            throw IllegalStateException("No playable stream was found for this video")
        }

        val preferred = pickDefaultStream(streams) ?: streams.first()
        VideoDetails(
            streamUrl = preferred.url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = NetworkClient.extractViews(html),
            ratingPercent = "—",
            duration = NetworkClient.matchFirst(html, """"duration"\s*:\s*"?(\d+)"""")
                ?.toIntOrNull()
                ?.let { sec -> "%d:%02d".format(sec / 60, sec % 60) }
                ?: NetworkClient.matchFirst(html, """class="[^"]*duration[^"]*"[^>]*>([^<]+)<""")
                ?: "—",
            resolution = preferred.label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    internal fun collectXhStreams(html: String): List<StreamOption> {
        val progressive = mutableListOf<StreamOption>()
        val hls = mutableListOf<StreamOption>()
        fun clean(urlRaw: String?): String {
            var u = urlRaw
                ?.replace("\\u0026", "&")
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.trim()
                .orEmpty()
            if (u.startsWith("//")) u = "https:$u"
            return u
        }
        fun isTrailer(u: String): Boolean =
            u.contains(".t.av1.mp4", true) ||
                u.contains(".t.mp4", true) ||
                u.contains(".t.h264.mp4", true) ||
                u.contains("/preview/", true) ||
                u.contains("/previews/", true) ||
                u.contains("/trailer/", true) ||
                u.contains("thumb-v", true)

        // 1) Match all .m3u8 HLS playlists (supports standard https:// and escaped https:\/\/)
        val m3u8Pattern = Pattern.compile(
            """(https?:[/\\]+[^"'\s]*xhcdn[^"'\s]*\.(?:m3u8)[^"'\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m3u8Pattern.find()) {
            val u = clean(m3u8Pattern.group(1))
            if (u.startsWith("http") && !isTrailer(u)) {
                hls += StreamOption("Auto", u)
            }
        }

        // 2) Match JSON quality key mappings: "720p":"https://..." or "h264":"https:\/\/..."
        val jsonPattern = Pattern.compile(
            """["']?(\d{3,4}p|hls|mp4|h264|fallback|quality_\d+p?)["']?\s*:\s*["'](https?:[/\\]+[^"'\s]+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (jsonPattern.find()) {
            val qKey = jsonPattern.group(1) ?: continue
            val u = clean(jsonPattern.group(2))
            if (!u.startsWith("http") || isTrailer(u)) continue
            val label = when {
                qKey.contains("1080") || u.contains("1080") -> "1080p"
                qKey.contains("720") || u.contains("720") -> "720p"
                qKey.contains("480") || u.contains("480") -> "480p"
                qKey.contains("360") || u.contains("360") -> "360p"
                qKey.contains("240") || u.contains("240") -> "240p"
                u.contains(".m3u8") -> "Auto"
                else -> "MP4"
            }
            if (u.contains(".m3u8")) {
                hls += StreamOption(label, u)
            } else {
                progressive += StreamOption(label, u)
            }
        }

        // 3) Match any xhcdn MP4 URLs in page HTML
        val cdnPattern = Pattern.compile(
            """(https?:[/\\]+[^"'\s]*xhcdn[^"'\s]*\.(?:mp4)[^"'\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (cdnPattern.find()) {
            val u = clean(cdnPattern.group(1))
            if (!u.startsWith("http") || isTrailer(u)) continue
            val label = NetworkClient.guessQualityLabel(u, "MP4")
            progressive += StreamOption(label, u)
        }

        // 4) Generic collectMp4AndHls fallback if specific patterns missed
        if (progressive.isEmpty() && hls.isEmpty()) {
            collectMp4AndHls(html, source.baseUrl).forEach { opt ->
                val u = clean(opt.url)
                if (!isTrailer(u)) {
                    if (u.contains(".m3u8", true)) {
                        hls += StreamOption("Auto", u)
                    } else if (u.contains(".mp4", true)) {
                        progressive += StreamOption(NetworkClient.guessQualityLabel(u, "MP4"), u)
                    }
                }
            }
        }

        val allHls = hls.distinctBy { it.url }
        val allProg = progressive.distinctBy { it.url }
        if (allHls.isNotEmpty() || allProg.isNotEmpty()) {
            return expandMultiQualityStreams((allHls + allProg).distinctBy { it.url })
        }
        return emptyList()
    }

    /**
     * Prefer embedded [window.initials] videoThumbProps (40–50 items, sharp imageURL).
     * HTML cards only expose a handful of links and often wire trailer .mp4 as the first media URL.
     */
    private fun parseListing(html: String): List<VideoItem> {
        val fromJson = parseFromInitials(html)
        if (fromJson.size >= 10) return fromJson
        val fromHtml = parseFromHtmlCards(html)
        if (fromJson.isEmpty()) return fromHtml
        val seen = fromJson.map { it.id }.toMutableSet()
        return fromJson + fromHtml.filter { seen.add(it.id) }
    }

    private fun parseFromInitials(html: String): List<VideoItem> {
        val jsonBlob = extractInitialsJson(html) ?: return emptyList()
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0

        // Match pageURL (relative or absolute xhamster links)
        val pageRe = Pattern.compile(
            """"pageURL"\s*:\s*"(https?:\\?/\\?/[^"]*)?\\?/videos\\?/([^"\\]+)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(jsonBlob)
        while (pageRe.find()) {
            val fullMatch = unescapeJsonUrl(pageRe.group(1) ?: "")
            val slug = pageRe.group(2)?.replace("\\/", "/")?.substringAfterLast('/') ?: continue
            if (!seen.add(slug)) continue
            val rawUrl = if (fullMatch.startsWith("http")) "$fullMatch/videos/$slug" else "${source.baseUrl}/videos/$slug"
            val start = (pageRe.start() - 500).coerceAtLeast(0)
            val end = (pageRe.end() + 700).coerceAtMost(jsonBlob.length)
            val win = jsonBlob.substring(start, end)
            val title = NetworkClient.decodeHtml(
                unescapeJsonUrl(
                    NetworkClient.matchFirst(win, """"title"\s*:\s*"((?:\\.|[^"\\])*)"""")
                        ?: slug.substringBeforeLast("-xh").replace('-', ' '),
                ),
            ).trim()
            if (title.length < 2) continue
            val imageUrl = pickSharpThumb(
                NetworkClient.matchFirst(win, """"imageURL"\s*:\s*"([^"]+)"""")
                    ?: NetworkClient.matchFirst(win, """"thumbURL"\s*:\s*"([^"]+)"""")
                    ?: NetworkClient.matchFirst(win, """"previewThumbURL"\s*:\s*"([^"]+)""""),
            )
            val durationSec = NetworkClient.matchFirst(win, """"duration"\s*:\s*(\d+)""")
                ?.toIntOrNull()
            val duration = if (durationSec != null && durationSec > 0) {
                "%d:%02d".format(durationSec / 60, durationSec % 60)
            } else {
                "—"
            }
            val views = NetworkClient.matchFirst(win, """"views"\s*:\s*(\d+)""") ?: "—"
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = duration,
                    resolution = "HD",
                    views = views,
                    category = source.label,
                    gradientSeed = index++,
                    pageUrl = rawUrl,
                    thumbnailUrl = imageUrl,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) break
        }
        return items
    }

    private fun parseFromHtmlCards(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val m = Pattern.compile(
            """href=["']((?:https?://[^"']*)?/videos/([^"?#]+))["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val fullUrl = NetworkClient.absoluteUrl(source.baseUrl, href)
            val window = html.substring(
                (m.start() - 200).coerceAtLeast(0),
                (m.start() + 1800).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{3,})"""")
                    ?: id.substringBeforeLast("-xh").replace('-', ' '),
            ).trim()
            if (title.length < 2) continue
            val thumb = extractXhThumb(window)
            val duration = NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)<""")
                ?: "—"
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration.trim(),
                    resolution = "HD",
                    views = "—",
                    category = source.label,
                    gradientSeed = index++,
                    pageUrl = fullUrl,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= PAGE_SIZE) break
        }
        return items
    }

    private fun extractInitialsJson(html: String): String? {
        val marker = "window.initials"
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val brace = html.indexOf('{', idx)
        if (brace < 0) return null
        // Cap scan — full initials can be huge; we only need videoListProps region.
        val slice = html.substring(brace, (brace + 900_000).coerceAtMost(html.length))
        // Prefer the videoThumbProps array body if present.
        val thumbsIdx = slice.indexOf("\"videoThumbProps\"")
        if (thumbsIdx >= 0) {
            val arrStart = slice.indexOf('[', thumbsIdx)
            if (arrStart >= 0) {
                return slice.substring(arrStart, (arrStart + 600_000).coerceAtMost(slice.length))
            }
        }
        return slice
    }

    private fun extractXhThumb(window: String): String {
        // Prefer static stills; skip trailer .mp4 / sprite strips (look blurry in Coil).
        val candidates = listOf(
            """data-src="(https?://ic-vt-nss\.xhcdn\.com[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            """srcset="(https?://ic-vt-nss\.xhcdn\.com[^"\s,]+\.(?:jpg|jpeg|png|webp)[^"\s,]*)""",
            """src="(https?://ic-vt-nss\.xhcdn\.com[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            """data-src="(https?://[^"]*xhcdn\.com[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            """src="(https?://[^"]*xhcdn\.com[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
        )
        for (p in candidates) {
            val u = NetworkClient.matchFirst(window, p) ?: continue
            val sharp = pickSharpThumb(u)
            if (sharp.isNotBlank()) return sharp
        }
        return pickSharpThumb(extractThumbFromWindow(window))
    }

    /** Decode JSON-escaped URLs and prefer non-trailer, non-sprite stills. */
    private fun pickSharpThumb(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var u = unescapeJsonUrl(raw).replace("&amp;", "&").trim()
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http")) return ""
        // Animated trailer thumbs / sprites look blurred or fail in image loaders.
        if (u.endsWith(".mp4", true) || u.endsWith(".webm", true)) return ""
        if (u.contains(".t.av1", true) || u.contains(".t.mp4", true)) return ""
        if (u.contains("sprite", true) || u.contains("/526x298.s.", true)) return ""
        if (u.contains("logo", true) || u.contains("avatar", true)) return ""
        // Prefer higher-res stills when URL embeds a size hint we can upgrade.
        u = u.replace(Regex("""s\(w:\d+,h:\d+\)"""), "s(w:640,h:360)")
        return u
    }

    private fun unescapeJsonUrl(value: String): String =
        value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

    companion object {
        /** Target grid size for home / search pages. */
        private const val PAGE_SIZE = 20
    }
}

// ---------------------------------------------------------------------------
// Beeg — store.externulls.com tag listings + CDN HLS
// ---------------------------------------------------------------------------

class BeegClient : VideoSourceClient {
    override val source = VideoSource.BEEG

    private val store = "https://store.externulls.com"
    /**
     * Progressive MP4s are served from video.beeg.com (ahacdn hosts currently 404
     * for the same paths). Prefer progressive over broken multi-HLS templates.
     */
    private val videoCdn = "https://video.beeg.com/"

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val offset = (p - 1) * 40
        val tags = if (p <= 1) listOf("hot", "straight") else listOf("straight", "hot")
        val seen = linkedSetOf<String>()
        val out = mutableListOf<VideoItem>()
        var lastError: Exception? = null
        for (tag in tags) {
            try {
                val url = "$store/tag/videos/$tag?limit=40&offset=$offset"
                val json = NetworkClient.get(
                    url,
                    source.baseUrl,
                    mapOf(
                        "Origin" to source.baseUrl,
                        "Accept" to "application/json",
                    ),
                )
                for (item in parseListJson(json)) {
                    if (item.title.isBlank() || item.thumbnailUrl.isBlank()) continue
                    if (seen.add(item.id)) out.add(item)
                }
                if (out.isNotEmpty()) break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (out.isEmpty() && p == 1) {
            throw lastError ?: IllegalStateException("Could not load Beeg")
        }
        out
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase().replace(Regex("""\s+"""), "")
        if (q.isBlank()) return@withContext emptyList()
        val p = page.coerceAtLeast(1)
        val offset = (p - 1) * 40
        val slug = java.net.URLEncoder.encode(q, Charsets.UTF_8.name())
        try {
            val json = NetworkClient.get(
                "$store/tag/videos/$slug?limit=40&offset=$offset",
                source.baseUrl,
                mapOf("Origin" to source.baseUrl, "Accept" to "application/json"),
            )
            parseListJson(json).filter { it.thumbnailUrl.isNotBlank() && it.title.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val fileId = pageUrl.trimEnd('/').substringAfterLast('/').substringAfter("file/")
            .filter { it.isDigit() }
            .ifBlank {
                NetworkClient.matchFirst(pageUrl, """(\d{6,})""") ?: ""
            }
        if (fileId.isBlank()) throw IllegalStateException("Invalid Beeg URL")

        val json = NetworkClient.get(
            "$store/facts/file/$fileId",
            source.baseUrl,
            mapOf("Origin" to source.baseUrl, "Accept" to "application/json"),
        )
        val root = JSONObject(json)
        val file = root.optJSONObject("file")
            ?: root.optJSONArray("fc_facts")?.optJSONObject(0)?.optJSONObject("file")
            ?: root
        val streams = collectBeegStreams(file)
        if (streams.isEmpty()) throw IllegalStateException("No stream on Beeg")
        val preferred = pickDefaultStream(streams) ?: streams.first()

        val title = buildBeegTitle(root, fileId)
        val durationSec = file.optInt("fl_duration", 0)
        val duration = if (durationSec > 0) {
            "%d:%02d".format(durationSec / 60, durationSec % 60)
        } else {
            "—"
        }
        val thumb = buildBeegThumb(fileId, file)
        val related = try {
            fetchHomeVideos(1)
                .filter { it.id != fileId && it.thumbnailUrl.isNotBlank() && it.title.isNotBlank() }
                .take(16)
        } catch (_: Exception) {
            emptyList()
        }

        VideoDetails(
            streamUrl = preferred.url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = "—",
            ratingPercent = "—",
            duration = duration,
            resolution = preferred.label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun parseListJson(json: String): List<VideoItem> {
        val arr = JSONArray(json)
        val items = mutableListOf<VideoItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val file = obj.optJSONObject("file") ?: continue
            val fileId = file.opt("id")?.toString() ?: continue
            if (fileId.length < 4) continue
            val title = buildBeegTitle(obj, fileId)
            val durationSec = file.optInt("fl_duration", 0)
            val duration = if (durationSec > 0) {
                "%d:%02d".format(durationSec / 60, durationSec % 60)
            } else {
                "—"
            }
            items.add(
                VideoItem(
                    id = fileId,
                    title = title,
                    duration = duration,
                    resolution = "HD",
                    views = "—",
                    category = source.label,
                    gradientSeed = i,
                    pageUrl = "${source.baseUrl}/$fileId",
                    thumbnailUrl = buildBeegThumb(fileId, file),
                    sourceId = source.id,
                ),
            )
        }
        return items
    }

    private fun buildBeegTitle(root: JSONObject, fileId: String): String {
        val tags = root.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val name = tags.optJSONObject(i)?.optString("tg_name")?.trim().orEmpty()
                if (name.length >= 2) return name
            }
        }
        return "Beeg $fileId"
    }

    private fun buildBeegThumb(fileId: String, file: JSONObject?): String {
        val data = file?.optJSONObject("data")
        val fromData = data?.optString("image")?.takeIf { it.startsWith("http") }
            ?: data?.optString("thumb")?.takeIf { it.startsWith("http") }
            ?: data?.optString("poster")?.takeIf { it.startsWith("http") }
        if (!fromData.isNullOrBlank()) return fromData
        // Official thumbs CDN patterns used by the web player.
        return "https://thumbs.externulls.com/videos/$fileId/0.jpg"
    }

    /**
     * Build progressive quality ladder from [file.resources] / [file.fallback].
     * Paths are relative signed URLs served at [videoCdn].
     */
    private fun collectBeegStreams(file: JSONObject): List<StreamOption> {
        val out = linkedMapOf<String, StreamOption>()
        fun add(pathOrUrl: String?, labelHint: String? = null) {
            val raw = pathOrUrl?.trim().orEmpty()
            if (raw.isBlank()) return
            val full = when {
                raw.startsWith("http") -> raw
                else -> videoCdn + raw.removePrefix("/")
            }
            val label = labelHint
                ?: NetworkClient.guessQualityLabel(full, "MP4").let {
                    if (it == "MP4" || it == "Auto") {
                        Regex("""/(\d{3,4})p/""").find(full)?.groupValues?.get(1)?.let { h -> "${h}p" }
                            ?: "MP4"
                    } else {
                        it
                    }
                }
            out.putIfAbsent(label, StreamOption(label, full))
        }

        val resources = file.optJSONObject("resources")
        if (resources != null) {
            val keys = resources.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val path = resources.optString(k, "")
                if (path.isBlank()) continue
                val q = Regex("""fl_cdn_(\d+)""", RegexOption.IGNORE_CASE).find(k)
                    ?.groupValues?.get(1)
                add(path, q?.let { "${it}p" })
            }
        }
        add(file.optString("fallback").takeIf { it.isNotBlank() }, "480p")

        // Also expand a known quality path into sibling tiers (same signature, different /Np/).
        val seed = out.values.firstOrNull()?.url
        if (seed != null && seed.contains(Regex("""/\d{3,4}p/"""))) {
            for (q in listOf(240, 360, 480, 720)) {
                val sibling = seed.replace(Regex("""/\d{3,4}p/"""), "/${q}p/")
                // Only keep seeds we already know or 240/480 from API — skip probing here.
                if (out.values.any { it.url == sibling }) continue
                if (q == 240 || q == 480) add(sibling.removePrefix(videoCdn), "${q}p")
            }
        }

        // Last resort: HLS multi template on video.beeg.com (often "Wrong key" without session).
        if (out.isEmpty()) {
            val hlsPath = file.optJSONObject("hls_resources")?.optString("fl_cdn_multi")
                ?.takeIf { it.isNotBlank() }
                ?: file.optJSONObject("hls_resources_tmp")?.optString("fl_cdn_multi")
            if (!hlsPath.isNullOrBlank()) {
                val master = if (hlsPath.startsWith("http")) hlsPath else videoCdn + hlsPath.removePrefix("/")
                expandMultiQualityStreams(listOf(StreamOption("Auto", master))).forEach {
                    out.putIfAbsent(it.label, it)
                }
            }
        }
        return out.values
            .distinctBy { it.url }
            .sortedByDescending { streamQualityRank(it.label) }
    }
}

// ---------------------------------------------------------------------------
// TXXX — JSON list API + videofile.php (Cyrillic-base64 get_file paths)
// ---------------------------------------------------------------------------

class TxxxClient : VideoSourceClient {
    override val source = VideoSource.TXXX

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = "${source.baseUrl}/api/json/videos2/86400/str/latest-updates/40/..$p.json"
        parseVideosJson(NetworkClient.get(url, source.baseUrl, mapOf("Accept" to "application/json")))
            .filter { it.thumbnailUrl.isNotBlank() && it.title.isNotBlank() }
            .ifEmpty { throw IllegalStateException("Could not load TXXX") }
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val urls = listOf(
            "${source.baseUrl}/api/videos2.php?params=86400/str/relevance/40/search.....$p&s=$q",
            "${source.baseUrl}/api/json/videos2/86400/str/relevance/40/search.....$p.json?s=$q",
        )
        for (url in urls) {
            try {
                val items = parseVideosJson(
                    NetworkClient.get(url, source.baseUrl, mapOf("Accept" to "application/json")),
                ).filter { it.thumbnailUrl.isNotBlank() && it.title.isNotBlank() }
                if (items.isNotEmpty()) return@withContext items
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val id = NetworkClient.matchFirst(pageUrl, """/videos/(\d+)/""")
            ?: pageUrl.trimEnd('/').substringAfterLast('/').filter { it.isDigit() }
                .takeIf { it.length >= 4 }
            ?: throw IllegalStateException("Invalid TXXX URL")

        var title = "TXXX $id"
        var thumb = ""
        var duration = "—"
        var views = "—"
        // Prefer structured listing row for metadata (SPA page has weak HTML).
        try {
            val list = parseVideosJson(
                NetworkClient.get(
                    "${source.baseUrl}/api/json/videos2/86400/str/latest-updates/60/..1.json",
                    source.baseUrl,
                    mapOf("Accept" to "application/json"),
                ),
            )
            list.firstOrNull { it.id == id }?.let { hit ->
                title = hit.title
                thumb = hit.thumbnailUrl
                duration = hit.duration
                views = hit.views
            }
        } catch (_: Exception) {
        }
        try {
            val html = NetworkClient.get(
                if (pageUrl.contains("/videos/")) pageUrl
                else "${source.baseUrl}/videos/$id/",
                source.baseUrl,
            )
            val pageTitle = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                    ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                    ?: "",
            ).substringBefore("|").trim()
            if (pageTitle.length >= 3 && !pageTitle.equals("txxx", true)) title = pageTitle
            val pageThumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            if (!pageThumb.isNullOrBlank()) thumb = pageThumb
        } catch (_: Exception) {
        }
        if (thumb.isBlank()) {
            // Standard TXXX screenshot CDN path.
            val bucket = ((id.toLongOrNull() ?: 0L) / 1000L) * 1000L
            thumb = "https://tn.txxx.tube/contents/videos_screenshots/$bucket/$id/288x162/1.jpg"
        }

        val vf = NetworkClient.get(
            "${source.baseUrl}/api/videofile.php?video_id=$id&lifetime=8640000",
            source.baseUrl,
            mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
        )
        val streams = expandMultiQualityStreams(parseVideofileStreams(vf))
        if (streams.isEmpty()) throw IllegalStateException("No stream on TXXX")
        // Prefer lower ladder when API only exposes HQ + trailer (trailer already filtered).
        val preferred = pickDefaultStream(streams) ?: streams.minByOrNull {
            streamQualityRank(it.label).let { r -> if (r <= 1) 9999 else r }
        } ?: streams.first()

        val related = try {
            parseVideosJson(
                NetworkClient.get(
                    "${source.baseUrl}/api/json/videos2/86400/str/latest-updates/40/..1.json",
                    source.baseUrl,
                    mapOf("Accept" to "application/json"),
                ),
            ).filter {
                it.id != id && it.thumbnailUrl.isNotBlank() && it.title.isNotBlank()
            }.take(16)
        } catch (_: Exception) {
            emptyList()
        }.ifEmpty {
            try {
                search(relatedQueryFromTitle(title), 1)
                    .filter { it.id != id && it.thumbnailUrl.isNotBlank() }
                    .take(16)
            } catch (_: Exception) {
                emptyList()
            }
        }

        VideoDetails(
            streamUrl = preferred.url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = views,
            ratingPercent = "—",
            duration = duration,
            resolution = preferred.label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun parseVideosJson(body: String): List<VideoItem> {
        if (body.isBlank() || body.startsWith("<")) return emptyList()
        val root = JSONObject(body)
        if (root.optInt("error", 0) == 1) return emptyList()
        val arr = root.optJSONArray("videos") ?: return emptyList()
        val items = mutableListOf<VideoItem>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.optString("video_id")
            if (id.isBlank()) continue
            val dir = v.optString("dir").ifBlank { id }
            val title = NetworkClient.decodeHtml(
                v.optString("title").ifBlank { dir.replace('-', ' ') },
            )
            var thumb = v.optString("scr").replace("\\/", "/")
            if (thumb.startsWith("//")) thumb = "https:$thumb"
            if (thumb.isBlank()) {
                val bucket = ((id.toLongOrNull() ?: 0L) / 1000L) * 1000L
                thumb = "https://tn.txxx.tube/contents/videos_screenshots/$bucket/$id/288x162/1.jpg"
            }
            val duration = v.optString("duration").ifBlank { "—" }
            val views = v.optString("video_viewed").takeIf { it.isNotBlank() } ?: "—"
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration,
                    resolution = if (v.optJSONObject("props")?.optString("hd") == "1") "HD" else "SD",
                    views = views,
                    category = source.label,
                    gradientSeed = i,
                    pageUrl = "${source.baseUrl}/videos/$id/$dir/",
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
        }
        return items
    }

    private fun parseVideofileStreams(body: String): List<StreamOption> {
        val arr = try {
            JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = linkedMapOf<String, StreamOption>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val format = o.optString("format")
            if (format.contains("_tr", true) || format.contains("trailer", true)) continue
            val encoded = o.optString("video_url")
            if (encoded.isBlank()) continue
            val path = decodeTxxxVideoUrl(encoded)
            if (path.isBlank()) continue
            var url = if (path.startsWith("http")) path else NetworkClient.absoluteUrl(source.baseUrl, path)
            if (url.contains("/get_file/") && !url.substringBefore('?').endsWith("/")) {
                url = if (url.contains("?")) {
                    url.substringBefore("?") + "/?" + url.substringAfter("?")
                } else {
                    "$url/"
                }
            }
            if (!isValidMediaUrl(url) && !url.contains("/get_file/")) continue
            val label = when {
                format.contains("1080") -> "1080p"
                format.contains("720") || format.contains("_hq", true) -> "720p"
                format.contains("480") -> "480p"
                format.contains("360") -> "360p"
                else -> NetworkClient.guessQualityLabel(url, "MP4").ifBlank { "MP4" }
            }
            out.putIfAbsent(label, StreamOption(label, url))
        }
        return out.values.toList()
    }
}

/**
 * TXXX network encodes get_file paths as base64 with Cyrillic lookalike letters.
 * Map those back to ASCII, then base64-decode to `/get_file/...mp4/?…`.
 */
internal fun decodeTxxxVideoUrl(encoded: String): String {
    if (encoded.isBlank()) return ""
    if (encoded.startsWith("http") || encoded.startsWith("/get_file")) return encoded
    val map = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'Н' to 'H', 'К' to 'K',
        'М' to 'M', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X',
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x',
    )
    val cleaned = buildString(encoded.length) {
        for (ch in encoded) append(map[ch] ?: ch)
    }
    return try {
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8).trim()
    } catch (_: Exception) {
        ""
    }
}

// ---------------------------------------------------------------------------
// Pinay PH WP / watch pages — Clean Tube + embeds + XVideos mirror
// ---------------------------------------------------------------------------

/**
 * Shared client for PH retrotube / clean-tube sites and Pinayum /watch/ listings.
 * Reuses the same stream pipeline as [WordPressTubeClient] without editing that class.
 */
class PhPinaySiteClient(
    override val source: VideoSource,
) : VideoSourceClient {

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) "${source.baseUrl}/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
            .filter { it.title.isNotBlank() }
            .ifEmpty { throw IllegalStateException("Could not load ${source.label}") }
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val urls = if (p <= 1) {
            listOf("${source.baseUrl}/?s=$q", "${source.baseUrl}/search/$q/")
        } else {
            listOf("${source.baseUrl}/page/$p/?s=$q", "${source.baseUrl}/?s=$q&paged=$p")
        }
        val seen = linkedSetOf<String>()
        val out = mutableListOf<VideoItem>()
        for (url in urls) {
            try {
                for (item in parseListing(NetworkClient.get(url, source.baseUrl))) {
                    if (item.title.isBlank()) continue
                    if (seen.add(item.id)) out.add(item)
                }
                if (out.isNotEmpty()) break
            } catch (_: Exception) {
            }
        }
        out
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
            .ifBlank {
                NetworkClient.matchFirst(html, """data-main-thumb="([^"]+)"""")
                    .orEmpty()
            }
        val related = parseListing(html)
            .filter {
                it.pageUrl != pageUrl &&
                    it.title.isNotBlank() &&
                    it.title.length >= 3
            }
            .take(18)

        var streams = extractCleanTubeStreams(html)
        if (streams.isEmpty()) {
            streams = collectMp4AndHls(html, source.baseUrl)
                .filter { !it.url.contains("trailer", true) && !it.url.contains("preview", true) }
        }

        val embedMeta = NetworkClient.matchFirst(html, """"embedUrl"\s*:\s*"([^"]+)"""")
            ?: NetworkClient.matchFirst(html, """itemprop="embedURL"\s+content="([^"]+)"""")
        val embeds = linkedSetOf<String>()
        if (!embedMeta.isNullOrBlank()) embeds.add(embedMeta)
        val iframeM = Pattern.compile(
            """iframe[^>]+src=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (iframeM.find()) {
            val src = iframeM.group(1) ?: continue
            if (src.contains("googletag", true) || src.contains("doubleclick", true)) continue
            embeds.add(NetworkClient.absoluteUrl(pageUrl, src))
        }
        // Bare player links on the page (Pwerta lists multiple hosts)
        val barePlayers = Pattern.compile(
            """(https?://(?:[^"'\\\s]+(?:rubyvid|streamruby|streamwish|filemoon|savefiles|bigwarp|dood|voe|lulu|vidhide|xtremestream)[^"'\\\s]*))""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (barePlayers.find()) {
            barePlayers.group(1)?.let { embeds.add(it) }
        }

        // Prefer hosts that usually work; skip known-dead xtremestream first if others exist.
        val ordered = embeds.sortedBy { emb ->
            when {
                emb.contains("rubyvid", true) || emb.contains("streamruby", true) -> 0
                emb.contains("streamwish", true) || emb.contains("filemoon", true) -> 1
                emb.contains("savefiles", true) || emb.contains("dood", true) -> 2
                emb.contains("bigwarp", true) -> 3
                emb.contains("xtremestream", true) || emb.contains("gargar", true) -> 9
                else -> 5
            }
        }

        for (emb in ordered.take(8)) {
            if (streams.isNotEmpty()) break
            try {
                val fromQ = extractCleanTubeStreams(emb)
                if (fromQ.isNotEmpty()) {
                    streams = fromQ
                    break
                }
                // StreamWish family + rubyvid + savefiles / bigwarp (packer pages)
                if (
                    isStreamWishHost(emb) ||
                    emb.contains("rubyvid", true) ||
                    emb.contains("streamruby", true) ||
                    emb.contains("savefiles", true) ||
                    emb.contains("bigwarp", true) ||
                    emb.contains("filemoon", true) ||
                    emb.contains("lulu", true)
                ) {
                    val wish = resolveStreamWishEmbed(emb, pageUrl)
                    if (wish.isNotEmpty()) {
                        streams = wish
                        break
                    }
                }
                if (isDoodHost(emb)) {
                    val dood = resolveDoodStreamEmbed(emb, pageUrl)
                    if (dood.isNotEmpty()) {
                        streams = dood
                        break
                    }
                }
                // Skip hanging xtremestream when it already failed once — short fail only.
                if (emb.contains("xtremestream", true) || emb.contains("gargar", true)) {
                    val nested = try {
                        NetworkClient.get(emb, pageUrl)
                    } catch (_: Exception) {
                        ""
                    }
                    if (nested.isNotBlank() && !nested.contains("503") && nested.length > 2000) {
                        val direct = collectMp4AndHls(nested, emb)
                        if (direct.isNotEmpty()) {
                            streams = direct
                            break
                        }
                        val wish = resolveStreamWishEmbed(emb, pageUrl)
                        if (wish.isNotEmpty()) {
                            streams = wish
                            break
                        }
                    }
                    continue
                }
                val nested = try {
                    NetworkClient.get(emb, pageUrl)
                } catch (_: Exception) {
                    continue
                }
                val direct = collectMp4AndHls(nested, emb)
                if (direct.isNotEmpty()) {
                    streams = direct
                    break
                }
                val nestedIframes = Pattern.compile(
                    """iframe[^>]+src=["']([^"']+)["']""",
                    Pattern.CASE_INSENSITIVE,
                ).matcher(nested)
                while (nestedIframes.find() && streams.isEmpty()) {
                    val n = NetworkClient.absoluteUrl(emb, nestedIframes.group(1) ?: continue)
                    if (isStreamWishHost(n) || n.contains("rubyvid", true) || n.contains("streamruby", true)) {
                        val w = resolveStreamWishEmbed(n, emb)
                        if (w.isNotEmpty()) streams = w
                    }
                }
            } catch (_: Exception) {
            }
        }

        streams = expandMultiQualityStreams(
            streams
                .map { it.copy(url = NetworkClient.sanitizeMediaUrl(it.url)) }
                .filter { !it.label.equals("Embed", true) }
                .distinctBy { it.url },
        )

        // Pinayum xtremestream is often 503 — fall back to XVideos mirror quickly.
        if (streams.isEmpty()) {
            xvideosMirrorFallback(
                title = title,
                pageUrl = pageUrl,
                source = source,
                thumb = thumb,
                related = related,
            )?.let { return@withContext it }
        }
        if (streams.isEmpty()) throw IllegalStateException("No direct stream on ${source.label}")

        val preferred = pickDefaultStream(streams) ?: streams.minByOrNull {
            streamQualityRank(it.label).let { r -> if (r <= 1) 9999 else r }
        } ?: streams.first()
        VideoDetails(
            streamUrl = preferred.url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = NetworkClient.extractViews(html),
            ratingPercent = "—",
            duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "—",
            resolution = preferred.label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0

        fun add(href: String, titleRaw: String, thumb: String, duration: String = "—") {
            if (href.contains("/category") || href.contains("/tag/") ||
                href.contains("/page/") || href.contains("/feed") ||
                href.contains("partners") || href.contains("/wp-") ||
                href.contains("/categories") || href.contains("/tags/") ||
                href.contains("/contact")
            ) {
                return
            }
            val abs = NetworkClient.absoluteUrl(source.baseUrl, href)
            if (!source.hostHints.any { abs.contains(it.substringBefore('.'), true) } &&
                !abs.contains(source.baseUrl.substringAfter("://").substringBefore('/'))
            ) {
                return
            }
            val id = abs.trimEnd('/').substringAfterLast('/').ifBlank { return }
            if (id.length < 2 || !seen.add(id)) return
            val title = NetworkClient.decodeHtml(titleRaw).ifBlank { id.replace('-', ' ') }
            if (title.length < 2) return
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration,
                    resolution = "HD",
                    views = "—",
                    category = source.label,
                    gradientSeed = index++,
                    pageUrl = abs,
                    thumbnailUrl = NetworkClient.sanitizeMediaUrl(thumb),
                    sourceId = source.id,
                ),
            )
        }

        // Clean-tube / retrotube cards (data-main-thumb is real preview).
        val article = Pattern.compile(
            """data-main-thumb="([^"]+)"([\s\S]{0,1400}?)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (article.find() && items.size < 60) {
            val thumb = article.group(1).orEmpty()
            val window = article.group(2).orEmpty()
            val href = NetworkClient.matchFirst(window, """href="([^"]+)"""") ?: continue
            val title = NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                ?: href.trimEnd('/').substringAfterLast('/').replace('-', ' ')
            val duration = NetworkClient.matchFirst(window, """class="duration"[^>]*>([^<]+)<""") ?: "—"
            add(href, title, thumb, duration)
        }

        // Pinayum /watch/slug/
        if (items.size < 12) {
            val watch = Pattern.compile(
                """href="((?:https?://[^"]+)?/watch/([^"/]+)/?)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (watch.find() && items.size < 60) {
                val href = watch.group(1) ?: continue
                val slug = watch.group(2) ?: continue
                val window = html.substring(
                    (watch.start() - 250).coerceAtLeast(0),
                    (watch.start() + 1400).coerceAtMost(html.length),
                )
                val title = NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: slug.replace('-', ' ')
                add(href, title, extractThumbFromWindow(window))
            }
        }

        // Generic retrotube title links
        if (items.size < 8) {
            val host = source.baseUrl.substringAfter("://").substringBefore('/')
            val m = Pattern.compile(
                """href="(https?://(?:www\.)?""" + Pattern.quote(host) + """/([a-z0-9][a-z0-9-]{3,})/?)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (m.find() && items.size < 50) {
                val href = m.group(1) ?: continue
                val slug = m.group(2) ?: continue
                if (slug in setOf("page", "feed", "partners", "category", "tag", "author", "wp-admin")) continue
                val window = html.substring(
                    (m.start() - 150).coerceAtLeast(0),
                    (m.start() + 1200).coerceAtMost(html.length),
                )
                val title = NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{3,})"""")
                    ?: slug.replace('-', ' ')
                add(href, title, extractThumbFromWindow(window))
            }
        }
        return items
    }
}
