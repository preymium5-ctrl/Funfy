package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Registry of all content sources. Home/search use the selected source;
 * player detail resolves the client from the video page URL when possible.
 */
object SourceRegistry {
    private val clients: Map<VideoSource, VideoSourceClient> by lazy {
        VideoSource.entries.associateWith { create(it) }
    }

    fun client(source: VideoSource): VideoSourceClient = clients[source] ?: create(source)

    fun clientForUrl(url: String, fallback: VideoSource = VideoSource.DEFAULT): VideoSourceClient {
        val fromUrl = VideoSource.fromUrl(url)
        return client(fromUrl ?: fallback)
    }

    private fun create(source: VideoSource): VideoSourceClient = when (source.provider) {
        SourceProvider.XVIDEOS -> XnxxApi(source)
        SourceProvider.EPORNER -> EpornerClient(source)
        SourceProvider.LEGACY -> createLegacy(source)
    }

    /** Direct-site scrapers (listing + page stream extract for play/download). */
    private fun createLegacy(source: VideoSource): VideoSourceClient = when (source) {
        VideoSource.PORNHUB -> PornhubClient()
        VideoSource.REDTUBE -> RedTubeClient()
        VideoSource.HQPORNER -> HqPornerClient()
        VideoSource.PINAYOT -> WordPressTubeClient(VideoSource.PINAYOT)
        VideoSource.PINAYFLIX -> PinayFlixClient()
        VideoSource.PORNKAI -> PornKaiClient()
        VideoSource.PINAYPORNSITE -> WordPressTubeClient(VideoSource.PINAYPORNSITE)
        VideoSource.PINAYVIRAL -> PinayViralClient()
        VideoSource.BUUMAL -> BuumalClient()
        VideoSource.MMHDHUB -> MmhdHubClient()
        VideoSource.BABEXTUBE -> BabeXTubeClient()
        VideoSource.XXXTIME -> XxxTimeClient()
        VideoSource.MISSAV -> MissAvClient()
        VideoSource.JAVFREE -> JavFreeClient()
        VideoSource.JAVTSUNAMI -> JavTsunamiClient()
        VideoSource.ONETWOAV -> OneTwoThreeAvClient()
        VideoSource.JAVSEEN -> JavSeenClient()
        VideoSource.INDO18 -> WordPressTubeClient(VideoSource.INDO18)
        VideoSource.BOKEPBOX -> WordPressTubeClient(VideoSource.BOKEPBOX)
        VideoSource.BOKEPINDOHOT -> WordPressTubeClient(VideoSource.BOKEPINDOHOT)
        VideoSource.BEBASINDO -> BebasIndoClient()
        VideoSource.NONTONBOKEP -> NontonBokepClient()
        VideoSource.THAIPORNTV -> ThaiPornTvClient()
        VideoSource.OKXXX -> OkXxxClient()
        VideoSource.IXXX -> IxxxClient()
        VideoSource.VLXX -> VlxxClient()
        VideoSource.SEXHAY24H -> SexHay24hClient()
        VideoSource.QUATVN -> QuatVnClient()
        VideoSource.SHENNANA -> ShenNanaClient()
        VideoSource.HANIME -> HanimeClient()
        VideoSource.HENTAIMAMA -> HentaiMamaClient()
        VideoSource.HENTAI4K -> Hentai4kClient()
        VideoSource.RULE34VIDEO -> GenericTubeClient(
            source = source,
            homePaths = { p ->
                if (p <= 1) listOf("/latest-updates/", "/") else listOf("/latest-updates/$p/")
            },
            searchPath = { q, p ->
                if (p <= 1) "/search/$q/" else "/search/$q/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$q&from_videos=$p&from_block=$p"
            },
            linkPatterns = listOf(
                """href="(https?://(?:www\.)?rule34video\.com/video/\d+/[^"]+)"""",
                """href="(/video/\d+/[^"]+)"""",
            ),
        )
        VideoSource.HENTAIGASM -> HentaigasmClient()
        VideoSource.HENTAICITY -> HentaiCityClient()
        VideoSource.TNAFLIX -> GenericTubeClient(
            source = source,
            homePaths = { p ->
                if (p <= 1) listOf("/") else listOf("/most-recent/$p", "/featured/$p", "/?page=$p")
            },
            searchPath = { q, _ -> "/search.php?what=$q&tab=" },
            linkPatterns = listOf(
                """href="(https?://(?:www\.)?tnaflix\.com/[^"]+/video\d+)"""",
                """href="(/[^"]+/video\d+)"""",
            ),
        )
        VideoSource.PORNTREX -> GenericTubeClient(
            source = source,
            homePaths = { p ->
                if (p <= 1) listOf("/latest-updates/", "/") else listOf("/latest-updates/$p/")
            },
            searchPath = { q, _ -> "/search/$q/" },
            linkPatterns = listOf(
                """href="(https?://(?:www\.)?porntrex\.com/video/\d+/[^"]+)"""",
                """href="(/video/\d+/[^"]+)"""",
            ),
        )
        VideoSource.SEXVID -> SexvidClient()
        VideoSource.ANALDIN -> AnaldinClient()
        else -> error("${source.label} is not a legacy source")
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/**
 * Clean Tube Player (MMPorns / DrKoGyi / many WP adult themes):
 * `<iframe src=".../player-x.php?q=BASE64...">` where BASE64 decodes to
 * `tag=<video><source src="https://...mp4">`.
 */
internal fun extractCleanTubeStreams(htmlOrUrl: String): List<StreamOption> {
    if (htmlOrUrl.isBlank()) return emptyList()
    val out = linkedMapOf<String, StreamOption>()
    fun add(url: String?) {
        val u = url
            ?.replace("&amp;", "&")
            ?.replace("\\/", "/")
            ?.trim()
            .orEmpty()
        if (!u.startsWith("http")) return
        if (u.contains("trailer", true) && !u.contains("full", true)) return
        val label = when {
            u.contains("1080") -> "1080p"
            u.contains("720") -> "720p"
            u.contains("480") -> "480p"
            u.contains("m3u8") -> "Auto (HLS)"
            else -> "MP4"
        }
        out.putIfAbsent(u, StreamOption(label, u))
    }

    fun decodeQ(raw: String) {
        // NEVER URL-decode raw base64: '+' is valid base64 and URLDecoder turns it into space.
        val cleaned = raw.trim()
            .replace("%2B", "+", ignoreCase = true)
            .replace("%2F", "/", ignoreCase = true)
            .replace("%3D", "=", ignoreCase = true)
            .replace(" ", "+")
            .replace("\n", "")
            .replace("\r", "")
        val decoded = try {
            // Prefer standard base64 first (+ /). URL_SAFE is a separate flag — do not OR them.
            val bytes = try {
                android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                try {
                    android.util.Base64.decode(cleaned, android.util.Base64.URL_SAFE)
                } catch (_: Exception) {
                    // JVM unit tests / odd runtimes: java.util.Base64
                    java.util.Base64.getDecoder().decode(
                        cleaned.replace('-', '+').replace('_', '/').let { s ->
                            val pad = when (s.length % 4) {
                                2 -> "=="
                                3 -> "="
                                else -> ""
                            }
                            s + pad
                        },
                    )
                }
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        // Payload is usually: post_id=…&type=video&tag=<url-encoded video html>
        val tagPart = decoded.substringAfter("tag=", missingDelimiterValue = decoded)
        val plain = try {
            // Decode repeatedly until stable (some sites double-encode).
            var cur = tagPart
            repeat(2) {
                val next = java.net.URLDecoder.decode(cur, Charsets.UTF_8.name())
                if (next == cur) return@repeat
                cur = next
            }
            cur
        } catch (_: Exception) {
            tagPart
        }
        val combined = "$decoded\n$plain"
        val src = Pattern.compile(
            """(?:src|source)\s*=\s*["']?(https?://[^"'>\s]+\.(?:mp4|m3u8)[^"'>\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(combined)
        while (src.find()) add(src.group(1))
        val any = Pattern.compile(
            """https?://[^\s"'<>\\]+\.(?:mp4|m3u8)[^\s"'<>\\]*""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(combined)
        while (any.find()) add(any.group())
        // URL-encoded form still in the raw base64 payload
        val enc = Pattern.compile(
            """https%3A%2F%2F[^\s"'&]+\.mp4[^\s"'&]*""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(decoded)
        while (enc.find()) {
            try {
                add(java.net.URLDecoder.decode(enc.group(), Charsets.UTF_8.name()))
            } catch (_: Exception) {
            }
        }
    }

    // player-x.php?q=…  and HTML-escaped &amp;
    val qInUrl = Pattern.compile(
        """player-x\.php\?(?:[^"'>\s]*?&amp;)?q=([A-Za-z0-9_=\-+/%]+)""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(htmlOrUrl.replace("&amp;", "&"))
    while (qInUrl.find()) {
        decodeQ(qInUrl.group(1).orEmpty())
    }
    // Also plain q= near clean-tube-player
    if (out.isEmpty() && htmlOrUrl.contains("clean-tube", ignoreCase = true)) {
        val qLoose = Pattern.compile(
            """[?&]q=([A-Za-z0-9_=\-+/%]{40,})""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(htmlOrUrl.replace("&amp;", "&"))
        while (qLoose.find() && out.size < 8) {
            decodeQ(qLoose.group(1).orEmpty())
        }
    }
    return out.values.toList()
}

internal fun collectMp4AndHls(html: String, base: String = ""): List<StreamOption> {
    val options = linkedMapOf<String, StreamOption>()
    // KVS license used to unscramble function/0/get_file hashes (Sexvid, etc.)
    val kvsLicense = NetworkClient.matchFirst(html, """license_code\s*:\s*['"]([^'"]+)['"]""")
    fun add(label: String, raw: String?) {
        if (raw.isNullOrBlank()) return
        var url = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
        // KVS / Sexvid: function/0/https://host/get_file/{id}/{scrambled_hash}/…mp4
        if (url.startsWith("function/", ignoreCase = true)) {
            url = KvsDecoder.getRealUrl(url, kvsLicense)
        }
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("/") && base.isNotBlank()) url = NetworkClient.absoluteUrl(base, url)
        if (!url.startsWith("http")) return
        if (url.contains("trailer", true) && !url.contains("full", true)) return
        if (url.contains("_preview", true) || url.contains("short_preview", true)) return
        if (url.contains("_vthumb", true)) return
        // Keep trailing slash on KVS get_file paths (Analdin 404s without it).
        options.putIfAbsent(label, StreamOption(label, url))
    }

    fun labelFor(url: String, explicit: String? = null): String {
        explicit?.takeIf { it.isNotBlank() }?.let { text ->
            val digits = text.filter { it.isDigit() }
            if (digits.isNotEmpty()) return "${digits}p"
            if (text.contains("hls", true) || text.contains("auto", true)) return "Auto (HLS)"
            return text
        }
        return when {
            url.contains("2160") || url.contains("4k", true) -> "2160p"
            url.contains("1440") -> "1440p"
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            url.contains("144") -> "144p"
            url.contains("m3u8") -> "Auto (HLS)"
            else -> "MP4"
        }
    }

    val sourceTag = Pattern.compile(
        """<source[^>]+src=["']([^"']+)["']""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    while (sourceTag.find()) {
        val u = sourceTag.group(1) ?: continue
        add(labelFor(u), u)
    }

    // Kernel Video Sharing flashvars (Porntrex / Analdin / Sexvid / many tubes)
    val kvs = Pattern.compile(
        """video(?:_alt)?_url(?:\d*)\s*:\s*['"]([^'"]+)['"]\s*,\s*(?:postfix:\s*['"][^'"]*['"]\s*,\s*)?video(?:_alt)?_url(?:\d*)_text\s*:\s*['"]([^'"]+)['"]""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    while (kvs.find()) {
        add(labelFor(kvs.group(1).orEmpty(), kvs.group(2)), kvs.group(1))
    }
    val kvsLoose = Pattern.compile(
        """video(?:_alt)?_url(?:\d*)\s*:\s*['"]([^'"]+\.mp4[^'"]*)['"]""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    while (kvsLoose.find()) {
        val u = kvsLoose.group(1) ?: continue
        add(labelFor(u), u)
    }

    // Sexvid / KVS get_file with function/0 wrapper
    val functionMp4 = Pattern.compile(
        """function/\d+/(https?://[^"'\\\s]+?\.mp4[^"'\\\s]*)""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    while (functionMp4.find()) {
        add(labelFor(functionMp4.group(1).orEmpty()), functionMp4.group(1))
    }

    // KT player helpers used by some tubes
    for ((label, pattern) in listOf(
        "High" to """setVideoUrlHigh\(['"]([^'"]+)['"]\)""",
        "Low" to """setVideoUrlLow\(['"]([^'"]+)['"]\)""",
        "Auto (HLS)" to """setVideoHLS\(['"]([^'"]+)['"]\)""",
    )) {
        NetworkClient.matchFirst(html, pattern)?.let { add(label, it) }
    }

    val mp4 = Pattern.compile(
        """["']((?:https?:)?//[^"']+\.mp4[^"']*)["']""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    var i = 0
    while (mp4.find() && i < 16) {
        var u = mp4.group(1) ?: continue
        if (u.startsWith("//")) u = "https:$u"
        add(labelFor(u), u)
        i++
    }

    // Unquoted CDN mp4s (TNAFlix config blobs)
    val bareMp4 = Pattern.compile(
        """(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    var bare = 0
    while (bareMp4.find() && bare < 12) {
        add(labelFor(bareMp4.group(1).orEmpty()), bareMp4.group(1))
        bare++
    }

    val hls = Pattern.compile(
        """["']((?:https?:)?//[^"']+\.m3u8[^"']*)["']""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    while (hls.find()) {
        var u = hls.group(1) ?: continue
        if (u.startsWith("//")) u = "https:$u"
        add("Auto (HLS)", u)
    }

    return options.values.sortedByDescending {
        it.label.filter { c -> c.isDigit() }.toIntOrNull() ?: if (it.label.contains("HLS")) 50 else 0
    }
}

/** Pull a usable card thumbnail from a small HTML window around a video link. */
internal fun extractThumbFromWindow(window: String): String {
    val candidates = listOf(
        """data-main-thumb="([^"]+)"""",
        """data-o_thumb="([^"]+)"""",
        """data-mediumthumb="([^"]+)"""",
        """data-thumb(?:_url)?="([^"]+)"""",
        """data-original="(https?://[^"]+)"""",
        """data-src="(https?://[^"]+)"""",
        """data-srcset="(https?://[^"\s,]+)""",
        """srcset="(https?://[^"\s,]+)""",
        """src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
        """src="(//[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
    )
    for (pattern in candidates) {
        var raw = NetworkClient.matchFirst(window, pattern) ?: continue
        raw = raw.replace("&amp;", "&").trim()
        if (raw.startsWith("//")) raw = "https:$raw"
        if (!raw.startsWith("http")) continue
        // skip placeholders / tracking pixels / flags
        if (raw.contains("placeholder", true) ||
            raw.contains("1x1") ||
            raw.contains("logo") ||
            raw.contains("favicon") ||
            raw.contains("flags_png") ||
            raw.contains("data:image")
        ) continue
        return raw
    }
    return ""
}

// ---------------------------------------------------------------------------
// Eporner - JSON list + /dload/ quality MP4s (real CDN, not trailer)
// ---------------------------------------------------------------------------

class EpornerClient(
    override val source: VideoSource = VideoSource.EPORNER,
) : VideoSourceClient {
    private val fixedKeyword = source.keyword

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        fetchSearch(scopedQuery(""), page.coerceAtLeast(1), perPage = 24)
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, page = 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        fetchSearch(scopedQuery(query), page.coerceAtLeast(1), perPage = 40)
    }

    private fun fetchSearch(query: String, page: Int, perPage: Int): List<VideoItem> {
        val q = java.net.URLEncoder.encode(query.ifBlank { "all" }, Charsets.UTF_8.name())
        val url =
            "${source.baseUrl}/api/v2/video/search/?query=$q&per_page=$perPage&page=$page" +
                "&thumbsize=big&order=latest&gay=0&lq=1&format=json"
        return parseSearchJson(NetworkClient.get(url, source.baseUrl))
    }

    internal fun scopedQuery(query: String): String {
        return combineScopedSearchQuery(query, fixedKeyword)
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val id = extractId(pageUrl) ?: throw IllegalStateException("Invalid Eporner URL")
        val apiUrl = "${source.baseUrl}/api/v2/video/id/?id=$id&format=json&thumbsize=big"
        val json = JSONObject(NetworkClient.get(apiUrl, source.baseUrl))
        val title = json.optString("title", "Video")
        val views = NetworkClient.formatViews(json.optLong("views", 0))
        val duration = json.optString("length_min").ifBlank {
            NetworkClient.formatDurationSec(json.optInt("length_sec", 0))
        }
        val thumb = json.optJSONObject("default_thumb")?.optString("src").orEmpty()
        val rate = json.optString("rate").takeIf { it.isNotBlank() }?.let { "$it â˜…" } ?: "â€”"
        val keywords = json.optString("keywords").split(',').map { it.trim() }.filter { it.isNotBlank() }.take(16)
        val page = json.optString("url").ifBlank { pageUrl }.replace("\\/", "/")

        val relatedQuery = keywords.take(2).joinToString(" ").ifBlank { title }
        val related = runCatching {
            fetchSearch(scopedQuery(relatedQuery), page = 1, perPage = 16)
                .filterNot { it.id.equals(id, ignoreCase = true) }
                .take(16)
        }.getOrDefault(emptyList())

        val html = NetworkClient.get(page, source.baseUrl)
        val streams = parseDloadStreams(html, id)
        if (streams.isEmpty()) {
            throw IllegalStateException("No downloadable streams on Eporner page")
        }

        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "Eporner",
            views = views,
            ratingPercent = rate,
            duration = duration,
            resolution = streams.first().label,
            tags = keywords,
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun parseDloadStreams(html: String, vid: String): List<StreamOption> {
        val options = mutableListOf<StreamOption>()
        val m = Pattern.compile(
            """href="(/dload/$vid/(\d+)/[^"]+\.mp4)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val seen = mutableSetOf<String>()
        while (m.find()) {
            val path = m.group(1) ?: continue
            val quality = m.group(2) ?: continue
            if (!seen.add(quality)) continue
            val absolute = NetworkClient.absoluteUrl(source.baseUrl, path)
            val playUrl = resolveEpornerPlayableUrl(absolute) ?: continue
            options.add(StreamOption("${quality}p", playUrl))
        }
        // Loose fallback if id casing differs in markup
        if (options.isEmpty()) {
            val loose = Pattern.compile(
                """href="(/dload/[^"/]+/(\d+)/[^"]+\.mp4)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (loose.find()) {
                val path = loose.group(1) ?: continue
                val quality = loose.group(2) ?: continue
                if (!seen.add(quality)) continue
                val absolute = NetworkClient.absoluteUrl(source.baseUrl, path)
                val playUrl = resolveEpornerPlayableUrl(absolute) ?: continue
                options.add(StreamOption("${quality}p", playUrl))
            }
        }
        // Prefer free mid qualities first (1080p often redirects to /login/ HTML → extractor error).
        return options.sortedWith(
            compareByDescending<StreamOption> {
                val q = it.label.filter(Char::isDigit).toIntOrNull() ?: 0
                when {
                    q == 720 -> 10_000
                    q == 480 -> 9_000
                    q == 360 -> 8_500
                    q == 1080 -> 8_000
                    else -> q
                }
            },
        )
    }

    /**
     * Follow /dload/ redirects. Skip account-gated qualities that land on /login/ HTML
     * (ExoPlayer then fails with "None of the available extractors could read the stream").
     * Prefer the real CDN mp4 URL when available; otherwise keep /dload/ for free tiers.
     */
    private fun resolveEpornerPlayableUrl(dloadUrl: String): String? {
        val finalUrl = try {
            sanitizeStreamUrl(NetworkClient.resolveFinalUrl(dloadUrl, source.baseUrl + "/"))
        } catch (_: Exception) {
            // Unresolved /dload/ still works for free qualities (ExoPlayer follows 302).
            return dloadUrl
        }
        if (finalUrl.isBlank()) return dloadUrl
        val lower = finalUrl.lowercase()
        // 1080p (and some others) redirect to /login/<base64> HTML interstitial.
        if (lower.contains("/login") || lower.contains("login/")) {
            return null
        }
        // Real CDN host
        if (lower.contains("cdn.eporner") || lower.contains("vid-s") ||
            (lower.contains(".mp4") && !lower.contains("eporner.com/dload"))
        ) {
            return finalUrl
        }
        // Still on /dload/ or same-site — ExoPlayer can follow free redirects.
        if (lower.contains("/dload/")) return dloadUrl
        return finalUrl
    }

    internal fun parseSearchJson(body: String): List<VideoItem> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("videos") ?: return emptyList()
        val items = mutableListOf<VideoItem>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val id = v.optString("id").trim()
            val title = v.optString("title").trim()
            val pageUrl = v.optString("url").replace("\\/", "/").trim()
            if (id.isBlank() || title.isBlank() || pageUrl.isBlank()) continue
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = v.optString("length_min").ifBlank {
                        NetworkClient.formatDurationSec(v.optInt("length_sec", 0))
                    },
                    resolution = "HD",
                    views = NetworkClient.formatViews(v.optLong("views", 0)),
                    category = "Eporner",
                    gradientSeed = i,
                    pageUrl = pageUrl,
                    thumbnailUrl = v.optJSONObject("default_thumb")?.optString("src").orEmpty(),
                    sourceId = source.id,
                ),
            )
        }
        return items.distinctBy(VideoItem::id)
    }

    private fun extractId(url: String): String? {
        for (p in listOf(
            """/video-([A-Za-z0-9]+)""",
            """/hd-porn/([A-Za-z0-9]+)""",
            """/dload/([A-Za-z0-9]+)""",
            """/embed/([A-Za-z0-9]+)""",
        )) {
            NetworkClient.matchFirst(url, p)?.let { return it }
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// Pornhub
// ---------------------------------------------------------------------------

class PornhubClient : VideoSourceClient {
    override val source = VideoSource.PORNHUB

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) {
            "${source.baseUrl}/video?o=tr&t=t"
        } else {
            "${source.baseUrl}/video?o=tr&t=t&page=$p"
        }
        parseListing(NetworkClient.get(url, source.baseUrl))
            .ifEmpty {
                if (p == 1) parseListing(NetworkClient.get("${source.baseUrl}/video", source.baseUrl))
                else emptyList()
            }
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/video/search?search=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - Pornhub").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = extractMediaDefinitions(html)
        if (streams.isEmpty()) {
            throw IllegalStateException("No Pornhub stream (age cookie / region). Try another video.")
        }
        // Labels come only from mediaDefinitions quality field (not URL guessing).
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "Pornhub",
            views = NetworkClient.matchFirst(html, """([\d,.]+)\s*views""") ?: "—",
            ratingPercent = NetworkClient.matchFirst(html, """([\d.]+)%""")?.let { "$it %" } ?: "—",
            duration = NetworkClient.matchFirst(html, """"video:duration"\s+content="(\d+)"""")
                ?.toIntOrNull()?.let { NetworkClient.formatDurationSec(it) } ?: "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).take(18),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val link = Pattern.compile(
            """href="(/view_video\.php\?viewkey=([a-zA-Z0-9]+))"\s+title="([^"]+)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (link.find()) {
            val path = link.group(1) ?: continue
            val id = link.group(2) ?: continue
            if (!seen.add(id)) continue
            val title = NetworkClient.decodeHtml(link.group(3) ?: id)
            val window = html.substring(
                (link.start() - 250).coerceAtLeast(0),
                (link.start() + 1800).coerceAtMost(html.length),
            )
            val thumb = (
                NetworkClient.matchFirst(window, """data-mediumthumb="([^"]+)"""")
                    ?: NetworkClient.matchFirst(window, """data-image="([^"]+)"""")
                    ?: NetworkClient.matchFirst(window, """src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""")
                    ?: ""
                ).replace("&amp;", "&")
            val duration = NetworkClient.matchFirst(window, """class="duration"[^>]*>([^<]+)<""")
                ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2})</""")
                ?: "Ã¢â‚¬â€"
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration.trim(),
                    resolution = "HD",
                    views = "Ã¢â‚¬â€",
                    category = "Pornhub",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 60) break
        }
        return items
    }

    private fun extractMediaDefinitions(html: String): List<StreamOption> {
        val options = linkedMapOf<String, StreamOption>()
        val def = NetworkClient.matchFirst(
            html,
            """"mediaDefinitions"\s*:\s*(\[[\s\S]*?\])\s*,\s*"isVertical"""",
        ) ?: NetworkClient.matchFirst(html, """"mediaDefinitions"\s*:\s*(\[[\s\S]*?\])""")
        if (def == null) return emptyList()
        try {
            val arr = JSONArray(def)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val format = o.optString("format").lowercase()
                // Prefer progressive mp4 quality rungs; keep one HLS as "Auto".
                var videoUrl = o.optString("videoUrl")
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .trim()
                if (videoUrl.isBlank() || videoUrl == "null") continue
                val quality = o.optString("quality").filter { it.isDigit() }
                when {
                    format.contains("hls") || videoUrl.contains(".m3u8") -> {
                        options.putIfAbsent("Auto", StreamOption("Auto", videoUrl))
                    }
                    quality.isNotBlank() -> {
                        val label = "${quality}p"
                        // Prefer mp4 over duplicate quality entries
                        if (!options.containsKey(label) || format.contains("mp4")) {
                            options[label] = StreamOption(label, videoUrl)
                        }
                    }
                    format.contains("mp4") -> {
                        options.putIfAbsent("MP4", StreamOption("MP4", videoUrl))
                    }
                }
            }
        } catch (_: Exception) {
            val quality = Pattern.compile(
                """"quality"\s*:\s*"?(\d+)"?[\s\S]{0,400}?"videoUrl"\s*:\s*"([^"]+)"""",
            ).matcher(def)
            while (quality.find()) {
                val q = quality.group(1) ?: continue
                val u = quality.group(2)?.replace("\\/", "/")?.replace("\\u0026", "&") ?: continue
                if (u.isBlank() || u == "null") continue
                options["${q}p"] = StreamOption("${q}p", u)
            }
        }
        return options.values.sortedByDescending {
            it.label.filter(Char::isDigit).toIntOrNull() ?: if (it.label.equals("Auto", true)) 1 else 0
        }
    }
}

// ---------------------------------------------------------------------------
// RedTube
// ---------------------------------------------------------------------------

class RedTubeClient : VideoSourceClient {
    override val source = VideoSource.REDTUBE

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/?page=$p"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?search=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - RedTube").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = extractStreams(html)
        if (streams.isEmpty()) {
            throw IllegalStateException("No RedTube stream found")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "RedTube",
            views = "Ã¢â‚¬â€",
            ratingPercent = "Ã¢â‚¬â€",
            duration = "Ã¢â‚¬â€",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).take(18),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Prefer cards that carry a real title attribute (homepage often puts title later).
        val titled = Pattern.compile(
            """href="(/(\d{6,}))"[^>]*title="([^"]{4,})"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (titled.find()) {
            val path = titled.group(1) ?: continue
            val id = titled.group(2) ?: continue
            if (!seen.add(id)) continue
            val window = html.substring(
                (titled.start() - 200).coerceAtLeast(0),
                (titled.start() + 1200).coerceAtMost(html.length),
            )
            addRedTubeItem(items, id, path, titled.group(3).orEmpty(), window, index++)
            if (items.size >= 50) break
        }
        // Fallback: bare /id links — pull title/alt from a wider window around the card.
        if (items.size < 12) {
            val m = Pattern.compile(
                """href="(/(\d{6,}))"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (m.find()) {
                val path = m.group(1) ?: continue
                val id = m.group(2) ?: continue
                if (!seen.add(id)) continue
                val window = html.substring(
                    (m.start() - 200).coerceAtLeast(0),
                    (m.start() + 1400).coerceAtMost(html.length),
                )
                val title = NetworkClient.matchFirst(window, """title="([^"]{4,})"""")
                    ?: NetworkClient.matchFirst(window, """alt="([^"]{4,})"""")
                    ?: ""
                // Skip nav/footer noise without a real title
                if (title.length < 4) continue
                addRedTubeItem(items, id, path, title, window, index++)
                if (items.size >= 50) break
            }
        }
        return items
    }

    private fun addRedTubeItem(
        items: MutableList<VideoItem>,
        id: String,
        path: String,
        titleRaw: String,
        window: String,
        seed: Int,
    ) {
        var thumb = extractThumbFromWindow(window)
        if (thumb.isBlank()) {
            val pathTpl = NetworkClient.matchFirst(window, """data-path="([^"]+)"""")
            if (!pathTpl.isNullOrBlank()) {
                thumb = pathTpl.replace("{index}", "1").replace("&amp;", "&")
            }
        }
        if (thumb.isBlank()) {
            thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
        }
        items.add(
            VideoItem(
                id = id,
                title = NetworkClient.decodeHtml(titleRaw),
                duration = NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2})</""") ?: "—",
                resolution = "HD",
                views = "—",
                category = "RedTube",
                gradientSeed = seed,
                pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                thumbnailUrl = thumb,
                sourceId = source.id,
            ),
        )
    }

    private fun extractStreams(html: String): List<StreamOption> {
        val options = linkedMapOf<String, StreamOption>()
        // Direct CDN mp4s embedded in page
        val cdn = Pattern.compile(
            """(https://ev-[^"'\\\s]+\.mp4[^"'\\\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (cdn.find()) {
            val u = cdn.group(1)?.replace("&amp;", "&") ?: continue
            val label = when {
                "1080" in u -> "1080p"
                "720" in u -> "720p"
                "480" in u -> "480p"
                "360" in u -> "360p"
                else -> "MP4"
            }
            options.putIfAbsent(label, StreamOption(label, u))
        }
        // mediaDefinitions relative endpoints
        val def = NetworkClient.matchFirst(html, """"mediaDefinitions"\s*:\s*(\[[\s\S]*?\])""")
        if (def != null) {
            try {
                val arr = JSONArray(def)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val format = o.optString("format")
                    var videoUrl = o.optString("videoUrl").replace("\\/", "/")
                    if (videoUrl.isBlank()) continue
                    if (videoUrl.startsWith("/")) {
                        videoUrl = NetworkClient.absoluteUrl(source.baseUrl, videoUrl)
                    }
                    // Resolve remote JSON that points to real files
                    if (o.optBoolean("remote", false) || videoUrl.contains("/media/")) {
                        try {
                            val body = NetworkClient.get(videoUrl, source.baseUrl + "/")
                            if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                                parseRemoteMediaJson(body, options)
                                continue
                            }
                            // might already be m3u8 text
                            if (body.contains("#EXTM3U")) {
                                options.putIfAbsent("Auto (HLS)", StreamOption("Auto (HLS)", videoUrl))
                                continue
                            }
                        } catch (_: Exception) {
                            // fall through and use endpoint itself
                        }
                    }
                    val label = if (format == "hls") "Auto (HLS)" else "MP4"
                    options.putIfAbsent(label, StreamOption(label, videoUrl))
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        return options.values.sortedByDescending {
            it.label.filter(Char::isDigit).toIntOrNull() ?: if (it.label.contains("HLS")) 50 else 0
        }
    }

    private fun parseRemoteMediaJson(body: String, into: MutableMap<String, StreamOption>) {
        try {
            val root = if (body.trimStart().startsWith("[")) {
                JSONArray(body)
            } else {
                val o = JSONObject(body)
                o.optJSONArray("videos") ?: o.optJSONArray("mediaDefinitions") ?: JSONArray().put(o)
            }
            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                val q = item.optString("quality").ifBlank { item.optString("format") }.ifBlank { "MP4" }
                var u = item.optString("videoUrl").ifBlank { item.optString("videoUrl") }
                    .ifBlank { item.optString("url") }
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                if (u.isBlank()) continue
                if (u.startsWith("/")) u = NetworkClient.absoluteUrl(source.baseUrl, u)
                val label = if (q.all { it.isDigit() }) "${q}p" else q
                into.putIfAbsent(label, StreamOption(label, u))
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}

// ---------------------------------------------------------------------------
// HQPorner (embed on mydaddy.cc Ã¢â€ â€™ bigcdn.cc MP4s)
// ---------------------------------------------------------------------------

class HqPornerClient : VideoSourceClient {
    override val source = VideoSource.HQPORNER

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/$p"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?q=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - HQ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()

        val embed = NetworkClient.matchFirst(html, """iframe[^>]+src=["'](//mydaddy\.cc/video/[^"']+)["']""")
            ?: NetworkClient.matchFirst(html, """iframe[^>]+src=["'](https://mydaddy\.cc/video/[^"']+)["']""")
            ?: throw IllegalStateException("HQPorner embed not found")
        var embedUrl = if (embed.startsWith("//")) "https:$embed" else embed
        // `&alt` mobile player embeds direct bigcdn.cc /{q}.mp4 links.
        if (!embedUrl.contains("alt", true)) {
            embedUrl = embedUrl.trimEnd('/') + "/&alt"
        }
        val embedHtml = NetworkClient.get(embedUrl, pageUrl)
        var streams = extractBigcdnStreams(embedHtml)
        if (streams.isEmpty()) {
            streams = collectMp4AndHls(embedHtml)
                .filter { !it.url.contains("tile.vtt") && !it.url.contains(".vtt") }
        }
        if (streams.isEmpty()) {
            // Retry default embed page
            val plain = NetworkClient.get(
                embedUrl.replace("/&alt", "/").replace("?alt", ""),
                pageUrl,
            )
            streams = extractBigcdnStreams(plain) + collectMp4AndHls(plain)
        }
        streams = streams
            .map {
                val u = if (it.url.startsWith("//")) "https:${it.url}" else it.url
                it.copy(
                    url = u,
                    label = NetworkClient.guessQualityLabel(u, it.label).let { g ->
                        if (g.isBlank()) it.label else g
                    },
                )
            }
            .distinctBy { it.url }
            .sortedByDescending { it.label.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (streams.isEmpty()) {
            throw IllegalStateException("No streams in HQPorner embed")
        }

        // Prefer 720p first for faster start; user can switch quality in player.
        streams = streams.sortedWith(
            compareByDescending<StreamOption> {
                val q = it.label.filter(Char::isDigit).toIntOrNull() ?: 0
                when {
                    q == 720 -> 10_000
                    q == 480 -> 9_000
                    q == 360 -> 8_000
                    q == 1080 -> 7_000
                    else -> q
                }
            },
        )

        val currentId = NetworkClient.matchFirst(pageUrl, """/hdporn/(\d+)-""")
        val related = parseListing(html)
            .filterNot { it.id == currentId }
            .take(18)

        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "HQPorner",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun extractBigcdnStreams(html: String): List<StreamOption> {
        val out = linkedMapOf<String, StreamOption>()
        // //s70.bigcdn.cc/pubs/HASH/360.mp4 or 720.mp4 / 1080.mp4
        val m = Pattern.compile(
            """(//s\d+\.bigcdn\.cc/pubs/[^"'\\\s]+/(\d{3,4})\.mp4)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val path = m.group(1) ?: continue
            val q = m.group(2) ?: "MP4"
            val url = if (path.startsWith("//")) "https:$path" else path
            out["${q}p"] = StreamOption("${q}p", url)
        }
        val m2 = Pattern.compile(
            """(https?://s\d+\.bigcdn\.cc/pubs/[^"'\\\s]+\.mp4)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m2.find()) {
            val url = m2.group(1) ?: continue
            val label = NetworkClient.guessQualityLabel(url, "MP4").ifBlank { "MP4" }
            out.putIfAbsent(label, StreamOption(label, url))
        }
        return out.values.toList()
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val m = Pattern.compile(
            """href="(/hdporn/(\d+)-([^"]+\.html))"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (m.find()) {
            val path = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val slug = m.group(3)?.removeSuffix(".html")?.replace('_', ' ') ?: id
            // Related cards often put img after the link — widen window both sides.
            val window = html.substring(
                (m.start() - 200).coerceAtLeast(0),
                (m.start() + 1200).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(//[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            )?.let { if (it.startsWith("//")) "https:$it" else it }
                ?: NetworkClient.matchFirst(
                    window,
                    """background(?:-image)?\s*:\s*url\(['"]?(https?://[^"')]+)""",
                )
                ?: ""
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """alt="([^"]+)"""")
                    ?: NetworkClient.matchFirst(window, """title="([^"]+)"""")
                    ?: slug,
            )
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)</""")
                        ?: "—",
                    resolution = "HD",
                    views = "—",
                    category = "HQPorner",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 50) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// WordPress tubes (Indo18 / PinayOT) Ã¢â‚¬â€ article cards + direct mp4/source
// ---------------------------------------------------------------------------

class WordPressTubeClient(
    override val source: VideoSource,
) : VideoSourceClient {

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, page = 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val urls = if (p <= 1) {
            listOf(
                "${source.baseUrl}/?s=$q",
                "${source.baseUrl}/search/$q/",
                "${source.baseUrl}/?s=$q&post_type=post",
            )
        } else {
            listOf(
                "${source.baseUrl}/page/$p/?s=$q",
                "${source.baseUrl}/?s=$q&paged=$p",
                "${source.baseUrl}/search/$q/page/$p/",
            )
        }
        val seen = linkedSetOf<String>()
        val out = mutableListOf<VideoItem>()
        for (url in urls) {
            try {
                for (item in parseListing(NetworkClient.get(url, source.baseUrl))) {
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
        val duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "—"
        val views = NetworkClient.extractViews(html)

        // Clean Tube first (MMPorns / DrKoGyi): direct mp4 from player-x.php?q=
        var streams = extractCleanTubeStreams(html)
        if (streams.isEmpty()) {
            streams = collectMp4AndHls(html, source.baseUrl)
                .filter { !it.url.contains("trailer", ignoreCase = true) }
        }
        // Prefer real host /uploads mp4s
        val hostPreferred = streams.filter {
            it.url.contains("/uploads/", true) ||
                it.url.contains("drkogyi", true) ||
                it.url.contains(source.hostHints.first().substringBefore('.'), ignoreCase = true)
        }
        if (hostPreferred.isNotEmpty()) streams = hostPreferred + streams.filter { it !in hostPreferred }

        // Follow iframe embeds (Indo18 → jomblo → playmogo / luluvid / etc.)
        var embedUrl: String? = null
        var depth = 0
        var currentHtml = html
        while (streams.isEmpty() && depth < 4) {
            val iframes = collectIframeSrcs(currentHtml)
                .map { NetworkClient.absoluteUrl(pageUrl, it) }
                .filter { src ->
                    !src.contains("googletag", true) &&
                        !src.contains("/ad", true) &&
                        !src.contains("histats", true) &&
                        !src.contains("doubleclick", true) &&
                        !src.contains("dazedengage", true)
                }
            if (iframes.isEmpty()) break
            val next = iframes.first()
            embedUrl = next
            // Decode clean-tube player payload from the iframe URL itself (no extra hop).
            val fromQ = extractCleanTubeStreams(next)
            if (fromQ.isNotEmpty()) {
                streams = fromQ
                break
            }
            try {
                currentHtml = NetworkClient.get(next, pageUrl)
                val nestedClean = extractCleanTubeStreams(currentHtml)
                val nested = if (nestedClean.isNotEmpty()) {
                    nestedClean
                } else {
                    collectMp4AndHls(currentHtml)
                        .filter { !it.url.contains("trailer", ignoreCase = true) }
                }
                if (nested.isNotEmpty()) {
                    streams = nested
                    break
                }
                depth++
            } catch (_: Exception) {
                depth++
            }
        }

        // Still no direct file: play embed in WebView (works for Indo18 jomblo player)
        if (streams.isEmpty()) {
            val firstIframe = collectIframeSrcs(html)
                .map { NetworkClient.absoluteUrl(pageUrl, it) }
                .firstOrNull {
                    !it.contains("googletag", true) &&
                        !it.contains("histats", true) &&
                        !it.contains("dazedengage", true)
                }
            embedUrl = embedUrl ?: firstIframe
            if (embedUrl.isNullOrBlank()) {
                throw IllegalStateException("No playable stream on ${source.label}")
            }
            streams = listOf(StreamOption(label = "Embed", url = embedUrl!!))
        }

        // Skip slow HEAD size probes for cross-host Clean Tube mp4s (was delaying / stalling play).
        val distinct = streams.distinctBy { it.url }
        val sized = if (
            distinct.any {
                it.url.contains("drkogyi", true) ||
                    it.url.contains("/uploads/", true)
            }
        ) {
            distinct
        } else {
            try {
                NetworkClient.withSizes(distinct, source.baseUrl + "/")
            } catch (_: Exception) {
                distinct
            }
        }

        // Page-local related; DataRepository enriches with title/search if thin.
        val related = parseListing(html).filter { it.pageUrl != pageUrl }.take(18)

        val preferred = pickDefaultStream(sized) ?: sized.first()
        VideoDetails(
            streamUrl = preferred.url,
            streams = sized,
            title = title,
            uploader = source.label,
            views = views,
            ratingPercent = "—",
            duration = duration.trim(),
            resolution = preferred.label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
            embedUrl = if (preferred.label == "Embed") embedUrl else null,
        )
    }

    private fun collectIframeSrcs(html: String): List<String> {
        val out = mutableListOf<String>()
        val m = Pattern.compile(
            """iframe[^>]+src=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val src = m.group(1) ?: continue
            if (src.isNotBlank()) out.add(src)
        }
        return out
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0

        fun addItem(
            href: String,
            titleRaw: String,
            thumb: String,
            duration: String = "—",
            views: String = "—",
            windowHtml: String = "",
        ) {
            if (href.contains("/category/") || href.contains("/tag/") ||
                href.contains("/page/") || href.contains("/feed") ||
                href.contains("wp-json") || href.contains("#") ||
                href.contains("/shorts/") || href.contains("watch-later") ||
                href.contains("/random/") || href.endsWith("/login")
            ) return
            val id = href.trimEnd('/').substringAfterLast('/').ifBlank {
                href.hashCode().toUInt().toString()
            }
            if (id.length < 2 || !seen.add(id)) return
            val viewCount = views.takeIf { it.isNotBlank() && it != "—" }
                ?: NetworkClient.extractViews(windowHtml)
            items.add(
                VideoItem(
                    id = id,
                    title = NetworkClient.decodeHtml(titleRaw).ifBlank { id.replace('-', ' ') },
                    duration = duration,
                    resolution = "HD",
                    views = viewCount,
                    category = source.label,
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
        }

        // 1) Clean-tube style: data-main-thumb is the real preview (MMPorns / DrKoGyi / Indo18).
        // Title attribute is optional; pull title from nearby title=/alt= when missing.
        val article = Pattern.compile(
            """data-main-thumb="([^"]+)"([\s\S]{0,1200}?)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (article.find() && items.size < 60) {
            val thumb = article.group(1).orEmpty()
            val window = article.group(2).orEmpty()
            val href = NetworkClient.matchFirst(window, """href="([^"]+)"""") ?: continue
            if (href.contains("javascript:", true)) continue
            val title = NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                ?: href.trimEnd('/').substringAfterLast('/').replace('-', ' ')
            val duration = NetworkClient.matchFirst(window, """class="duration"[^>]*>([^<]+)<""")
                ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2})</""")
                ?: "—"
            addItem(href, title, thumb, duration, windowHtml = window)
        }

        // 2) PinayPornSite-style: <a href title> … <img data-src alt>
        if (items.size < 12) {
            val pps = Pattern.compile(
                """href="(https?://[^"]+)"\s+title="([^"]{2,})"[\s\S]{0,800}?(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (pps.find() && items.size < 50) {
                val href = pps.group(1).orEmpty()
                if (!source.hostHints.any { href.contains(it.substringBefore('.'), true) } &&
                    !href.contains(source.baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/'))
                ) {
                    // still accept if on same host as baseUrl
                    if (!href.contains(source.baseUrl.substringAfter("://").substringBefore('/'))) continue
                }
                val win = html.substring(pps.start(), (pps.start() + 900).coerceAtMost(html.length))
                addItem(href, pps.group(2).orEmpty(), pps.group(3).orEmpty(), windowHtml = win)
            }
        }

        // 3) Jav.Guru / numeric id posts: /123456/slug/
        if (items.size < 12) {
            val host = source.baseUrl.substringAfter("://").substringBefore('/')
            val numPosts = Pattern.compile(
                """href="(https?://(?:www\.)?""" + Pattern.quote(host) + """/(\d{4,})/([^"/]+)/?)"[\s\S]{0,600}?src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (numPosts.find() && items.size < 50) {
                val href = numPosts.group(1).orEmpty()
                val alt = NetworkClient.matchFirst(
                    html.substring(numPosts.start(), (numPosts.start() + 700).coerceAtMost(html.length)),
                    """alt="([^"]{2,})"""",
                )
                addItem(
                    href,
                    alt ?: numPosts.group(3).orEmpty().replace('-', ' '),
                    numPosts.group(4).orEmpty(),
                )
            }
        }

        // 4) JavFF-style /video/slug + nearby img (prefer DMM covers over placeholders)
        if (items.size < 12) {
            val host = source.baseUrl.substringAfter("://").substringBefore('/')
            val vid2 = Pattern.compile(
                """href="(https?://(?:www\.)?""" + Pattern.quote(host) + """/video/([^"/]+)/?)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (vid2.find() && items.size < 50) {
                val href = vid2.group(1).orEmpty()
                val slug = vid2.group(2).orEmpty()
                val window = html.substring(
                    vid2.start(),
                    (vid2.start() + 1200).coerceAtMost(html.length),
                )
                // Prefer real covers (dmm) over theme placeholder
                val dmm = NetworkClient.matchFirst(window, """(?:data-src|src)="(https?://pics\.dmm[^"]+)"""")
                    ?: NetworkClient.matchFirst(window, """(?:data-src|src)="(https?://[^"]*dmm[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""")
                val thumb = dmm ?: extractThumbFromWindow(window)
                val title = NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: slug.replace('-', ' ')
                addItem(href, title, thumb, windowHtml = window)
            }
        }

        // 5) Generic title links on same host
        if (items.size < 8) {
            val hostHint = source.hostHints.first().substringBefore('.')
            val m = Pattern.compile(
                """href="(https?://[^"]*$hostHint[^"]+)"\s+(?:[^>]*\s)?title="([^"]{3,})"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (m.find() && items.size < 50) {
                val href = m.group(1).orEmpty()
                val window = html.substring(
                    (m.start() - 200).coerceAtLeast(0),
                    (m.start() + 900).coerceAtMost(html.length),
                )
                addItem(href, m.group(2).orEmpty(), extractThumbFromWindow(window))
            }
        }

        return items
    }
}

// ---------------------------------------------------------------------------
// PinayFlix Ã¢â‚¬â€ listing thumbs work; stream via mp4 in page / related uploads
// ---------------------------------------------------------------------------

class PinayFlixClient : VideoSourceClient {
    override val source = VideoSource.PINAYFLIX

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?s=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - ").trim()
        val thumb = NetworkClient.sanitizeMediaUrl(
            NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
                .orEmpty(),
        )

        var streams = collectMp4AndHls(html, source.baseUrl)
            .map { it.copy(url = NetworkClient.sanitizeMediaUrl(it.url)) }
        var embedUrl: String? = null

        // PinayFlix hosts the real player on Flixtream / GooStream embeds.
        val iframeSrcs = mutableListOf<String>()
        val iframeRe = Pattern.compile(
            """iframe[^>]+src=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (iframeRe.find()) {
            val src = iframeRe.group(1) ?: continue
            if (src.contains("googletag") || src.contains("doubleclick") || src.contains("/ad")) continue
            iframeSrcs.add(NetworkClient.absoluteUrl(pageUrl, src))
        }
        // Also catch data-src style embeds
        NetworkClient.matchFirst(html, """data-src=["'](https?://[^"']*flixtream[^"']+)["']""")
            ?.let { iframeSrcs.add(it) }
        NetworkClient.matchFirst(html, """["'](https?://flixtream\.[^"']+)["']""")
            ?.let { iframeSrcs.add(it) }

        for (embed in iframeSrcs.distinct()) {
            embedUrl = embed
            try {
                val embHtml = NetworkClient.get(embed, pageUrl)
                // Direct mp4/hls in embed HTML
                val direct = collectMp4AndHls(embHtml, embed)
                    .map { it.copy(url = NetworkClient.sanitizeMediaUrl(it.url)) }
                if (direct.isNotEmpty()) {
                    streams = direct
                    break
                }
                // Playerjs packed config â†’ file: "https://â€¦m3u8?â€¦"
                val unpacked = NetworkClient.unpackDeanEdwards(embHtml).orEmpty()
                val fileUrl = NetworkClient.matchFirst(
                    unpacked,
                    """file\s*:\s*["'](https?://[^"']+)["']""",
                ) ?: NetworkClient.matchFirst(
                    embHtml,
                    """file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
                )
                if (!fileUrl.isNullOrBlank()) {
                    val clean = NetworkClient.sanitizeMediaUrl(fileUrl)
                    val label = if (clean.contains("m3u8", true)) "Auto (HLS)" else "MP4"
                    streams = listOf(StreamOption(label, clean))
                    break
                }
            } catch (_: Exception) {
            }
        }

        // Last resort: play embed in WebView
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No playable stream on PinayFlix for this video")
        }

        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "PinayFlix",
            views = "â€”",
            ratingPercent = "â€”",
            duration = "â€”",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(18),
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed") embedUrl else null,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Real card:
        // <a href="…/videoid=7705/" title="Umiskor Muna…">
        //   <img … src="…jpg" alt="Umiskor Muna…">
        //   <span class="title">Umiskor Muna…</span>
        // </a>
        val m = Pattern.compile(
            """href="((?:https?://pinayflix\.uk)?/videoid=(\d+)/)"([^>]*)>([\s\S]{0,900}?)</a>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val path = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val aAttrs = m.group(3).orEmpty()
            val inner = m.group(4).orEmpty()
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(aAttrs, """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(inner, """class="title"[^>]*>([^<]{2,})<""")
                    ?: NetworkClient.matchFirst(inner, """alt="([^"]{2,})"""")
                    ?: "Video $id",
            ).trim()
            val thumb = NetworkClient.sanitizeMediaUrl(
                NetworkClient.matchFirst(
                    inner,
                    """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                ).orEmpty(),
            )
            val duration = NetworkClient.matchFirst(inner, """class="duration"[^>]*>([^<]+)<""")
                ?.trim()
                .orEmpty()
                .ifBlank { "—" }
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration,
                    resolution = if (inner.contains("hd-video", true)) "HD" else "SD",
                    views = "—",
                    category = "PinayFlix",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
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
// Buumal
// ---------------------------------------------------------------------------

class BuumalClient : VideoSourceClient {
    override val source = VideoSource.BUUMAL

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        parseListing(NetworkClient.get("${source.baseUrl}/?page=$p", source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?search=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore("|").trim()
        var thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        if (thumb.isBlank()) {
            thumb = NetworkClient.matchFirst(html, """src="(https?://img\.buumal\.com/[^"]+)"""")
                .orEmpty()
        }
        thumb = NetworkClient.sanitizeMediaUrl(thumb)
        var streams = collectMp4AndHls(html, source.baseUrl)
            .map { s ->
                s.copy(
                    url = NetworkClient.sanitizeMediaUrl(s.url),
                    label = NetworkClient.guessQualityLabel(s.url, s.label),
                )
            }
        if (streams.isEmpty()) {
            val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
            if (!iframe.isNullOrBlank() &&
                !iframe.contains("googletag") &&
                !iframe.contains("doubleclick")
            ) {
                try {
                    streams = collectMp4AndHls(
                        NetworkClient.get(NetworkClient.absoluteUrl(pageUrl, iframe), pageUrl),
                    ).map { s ->
                        s.copy(
                            url = NetworkClient.sanitizeMediaUrl(s.url),
                            label = NetworkClient.guessQualityLabel(s.url, s.label),
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No stream found on Buumal")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "Buumal",
            views = "â€”",
            ratingPercent = "â€”",
            duration = "â€”",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).take(12),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Primary: /video/{id} cards with nearby thumb
        val videoLinks = Pattern.compile(
            """href="((?:https?://(?:www\.)?buumal\.com)?/video/([A-Za-z0-9_-]+))"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (videoLinks.find()) {
            val href = videoLinks.group(1) ?: continue
            val id = videoLinks.group(2) ?: continue
            if (!seen.add(id)) continue
            val window = html.substring(
                (videoLinks.start() - 200).coerceAtLeast(0),
                (videoLinks.start() + 900).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.sanitizeMediaUrl(
                NetworkClient.matchFirst(
                    window,
                    """src="(https?://img\.buumal\.com/[^"]+)"""",
                ) ?: NetworkClient.matchFirst(
                    window,
                    """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                ).orEmpty(),
            )
            // Titles live in <p> under card-content, not on img alt (alt is often "Buu Mal").
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """class="content[^"]*"[^>]*>\s*<p>([^<]{2,})</p>""")
                    ?: NetworkClient.matchFirst(window, """<p>([^<]{4,})</p>""")
                    ?: NetworkClient.matchFirst(window, """(?:alt|title)="([^"]{2,})"""")
                        ?.takeIf { !it.equals("Buu Mal", true) && !it.equals("Buumal", true) }
                    ?: run {
                        // Filename-like title on img.buumal.com URL after last /
                        val fromUrl = thumb.substringAfterLast('/').substringBeforeLast('.')
                            .replace(Regex("""^[a-z0-9-]+-\d+\s*"""), "")
                            .trim()
                        fromUrl.ifBlank { "Buumal $id" }
                    },
            )
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "Buumal",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 40) break
        }
        if (items.isEmpty()) {
            // Fallback: img.buumal.com previews + nearest href
            val img = Pattern.compile(
                """src="(https?://img\.buumal\.com/[^"]+)"[^>]*(?:alt="([^"]*)")?""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (img.find()) {
                val thumb = NetworkClient.sanitizeMediaUrl(img.group(1) ?: continue)
                if (!seen.add(thumb)) continue
                val title = NetworkClient.decodeHtml(
                    img.group(2)?.ifBlank { null }
                        ?: thumb.substringAfterLast('/').substringBefore(' ').replace('-', ' '),
                )
                val before = html.substring((img.start() - 400).coerceAtLeast(0), img.start())
                val after = html.substring(img.start(), (img.start() + 400).coerceAtMost(html.length))
                val href = (
                    NetworkClient.matchFirst(before, """href="((?:https?://[^"]*)?/video/[^"]+)"""")
                        ?: NetworkClient.matchFirst(after, """href="((?:https?://[^"]*)?/video/[^"]+)"""")
                        ?: NetworkClient.matchFirst(before, """href="([^"]+)"""")
                    )?.takeIf { !it.contains("dmca") && !it.contains("2257") && it != "/" }
                    ?: continue
                val id = href.trimEnd('/').substringAfterLast('/')
                items.add(
                    VideoItem(
                        id = id.ifBlank { thumb.hashCode().toUInt().toString() },
                        title = title.ifBlank { "Buumal video" },
                        duration = "â€”",
                        resolution = "HD",
                        views = "â€”",
                        category = "Buumal",
                        gradientSeed = index++,
                        pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 40) break
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// XxxTime / Siska-style listing
// ---------------------------------------------------------------------------

class XxxTimeClient : VideoSourceClient {
    override val source = VideoSource.XXXTIME

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) {
            "${source.baseUrl}/videos.php"
        } else {
            "${source.baseUrl}/videos.php?page=$p"
        }
        parseListing(NetworkClient.get(url, source.baseUrl))
            .ifEmpty {
                if (p == 1) parseListing(NetworkClient.get(source.baseUrl + "/", source.baseUrl))
                else emptyList()
            }
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/videos.php?search=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: NetworkClient.matchFirst(html, """title='([^']+)'""")
                ?: "Video",
        ).trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, source.baseUrl)
        var embedUrl: String? = null
        // External player iframes (playmogo etc. may be Cloudflare-gated)
        if (streams.isEmpty()) {
            val iframe = Pattern.compile(
                """iframe[^>]+src=["'](https?://[^"']+)["']""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (iframe.find() && streams.isEmpty()) {
                val src = iframe.group(1) ?: continue
                if (src.contains("googletag") || src.contains("doubleclick") || src.contains("/ad")) continue
                embedUrl = src
                try {
                    streams = collectMp4AndHls(NetworkClient.get(src, pageUrl))
                } catch (_: Exception) {
                }
            }
        }
        if (streams.isEmpty()) {
            embedUrl?.let { streams = listOf(StreamOption("Embed", it)) }
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No playable stream on XxxTime")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "XxxTime",
            views = "â€”",
            ratingPercent = "â€”",
            duration = NetworkClient.matchFirst(html, """th_video_duration['"]?>([^<]+)<""")
                ?: NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""")
                ?: "â€”",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).take(18),
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed") embedUrl else null,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Current layout: video.php?v=ID + data-src thumb + alt title
        val cards = Pattern.compile(
            """href=['"]((?:https?://[^'"]*)?video\.php\?v=([A-Za-z0-9_-]+))['"][^>]*>[\s\S]{0,800}?data-src=['"]([^'"]+)['"][\s\S]{0,200}?alt=['"]([^'"]*)['"]""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (cards.find()) {
            val href = cards.group(1) ?: continue
            val id = cards.group(2) ?: continue
            if (!seen.add(id)) continue
            val thumb = cards.group(3).orEmpty()
            val title = NetworkClient.decodeHtml(cards.group(4)?.ifBlank { null } ?: "Video $id")
            val window = html.substring(
                (cards.start() - 120).coerceAtLeast(0),
                (cards.start() + 500).coerceAtMost(html.length),
            )
            val duration = NetworkClient.matchFirst(window, """th_video_duration['"]?>([^<]+)<""")
                ?: "â€”"
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration.trim(),
                    resolution = "HD",
                    views = "â€”",
                    category = "XxxTime",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 50) break
        }
        if (items.isEmpty()) {
            val loose = Pattern.compile(
                """href=['"]((?:https?://[^'"]*)?video\.php\?v=([A-Za-z0-9_-]+))['"]""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (loose.find()) {
                val href = loose.group(1) ?: continue
                val id = loose.group(2) ?: continue
                if (!seen.add(id)) continue
                val window = html.substring(
                    loose.start(),
                    (loose.start() + 700).coerceAtMost(html.length),
                )
                val thumb = NetworkClient.matchFirst(
                    window,
                    """(?:data-src|src)=['"](https?://[^'"]+\.(?:jpg|jpeg|png|webp)[^'"]*)['"]""",
                ).orEmpty()
                val title = NetworkClient.decodeHtml(
                    NetworkClient.matchFirst(window, """(?:alt|title)=['"]([^'"]+)['"]""")
                        ?: "Video $id",
                )
                items.add(
                    VideoItem(
                        id = id,
                        title = title,
                        duration = "â€”",
                        resolution = "HD",
                        views = "â€”",
                        category = "XxxTime",
                        gradientSeed = index++,
                        pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 40) break
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// Generic tube scraper with page-aware home paths
// ---------------------------------------------------------------------------

class GenericTubeClient(
    override val source: VideoSource,
    private val homePaths: (Int) -> List<String>,
    private val searchPath: (String, Int) -> String,
    private val linkPatterns: List<String>,
) : VideoSourceClient {

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val seen = linkedSetOf<String>()
        val results = mutableListOf<VideoItem>()
        var lastError: Exception? = null
        for (path in homePaths(p)) {
            try {
                val url = NetworkClient.absoluteUrl(source.baseUrl, path)
                val html = NetworkClient.get(url, source.baseUrl)
                for (item in parseListing(html)) {
                    if (seen.add(item.id)) results.add(item)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (results.isEmpty() && p == 1) {
            throw lastError
                ?: IllegalStateException("Could not load videos from ${source.label}")
        }
        results
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, page = 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val paths = listOf(
            searchPath(q, p),
            if (p <= 1) "/search/$q/" else "/search/$q/$p/",
            if (p <= 1) "/?q=$q" else "/?q=$q&page=$p",
            if (p <= 1) "/tags/$q/" else "/tags/$q/$p/",
        ).distinct()
        val seen = linkedSetOf<String>()
        val out = mutableListOf<VideoItem>()
        for (path in paths) {
            try {
                val url = NetworkClient.absoluteUrl(source.baseUrl, path)
                for (item in parseListing(NetworkClient.get(url, source.baseUrl))) {
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
        ).substringBefore("|").substringBefore(" - ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, source.baseUrl)
        if (streams.isEmpty()) {
            val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
            if (!iframe.isNullOrBlank()) {
                try {
                    val emb = NetworkClient.get(NetworkClient.absoluteUrl(pageUrl, iframe), pageUrl)
                    streams = collectMp4AndHls(emb)
                } catch (_: Exception) { }
            }
        }
        if (streams.isEmpty()) {
            val high = NetworkClient.matchFirst(html, """setVideoUrlHigh\(['"]([^'"]+)['"]\)""")
            val low = NetworkClient.matchFirst(html, """setVideoUrlLow\(['"]([^'"]+)['"]\)""")
            val hls = NetworkClient.matchFirst(html, """setVideoHLS\(['"]([^'"]+)['"]\)""")
            streams = listOfNotNull(
                high?.let { StreamOption("High", it) },
                low?.let { StreamOption("Low", it) },
                hls?.let { StreamOption("Auto", it) },
            )
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No playable stream on ${source.label}")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = "â€”",
            ratingPercent = "â€”",
            duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "â€”",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(18),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        for (pattern in linkPatterns) {
            val m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(html)
            while (m.find()) {
                val href = m.group(1) ?: continue
                if (href.length < 4) continue
                if (href.endsWith(".css") || href.endsWith(".js") ||
                    href.contains("/tag/") || href.contains("/category") ||
                    href.contains("login") || href.contains("signup")
                ) continue
                val id = href.trimEnd('/').substringAfterLast('/').ifBlank {
                    href.hashCode().toUInt().toString()
                }
                if (!seen.add(id)) continue
                val windowStart = m.start()
                val window = html.substring(
                    (windowStart - 300).coerceAtLeast(0),
                    (windowStart + 900).coerceAtMost(html.length),
                )
                // Sexvid etc: title is often on the <a>; thumbs live in srcset after the link.
                val windowAfter = html.substring(
                    windowStart,
                    (windowStart + 1400).coerceAtMost(html.length),
                )
                val title = NetworkClient.decodeHtml(
                    NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                        ?: NetworkClient.matchFirst(windowAfter, """title="([^"]{2,})"""")
                        ?: NetworkClient.matchFirst(windowAfter, """alt="([^"]{2,})"""")
                        ?: id.replace('-', ' ').replace('_', ' ').removeSuffix(".html"),
                )
                val thumb = extractThumbFromWindow(windowAfter).ifBlank {
                    extractThumbFromWindow(window)
                }
                val duration = NetworkClient.matchFirst(windowAfter, """class="duration"[^>]*>([^<]+)<""")
                    ?: NetworkClient.matchFirst(windowAfter, """>(\d{1,2}:\d{2})</""")
                    ?: "â€”"
                items.add(
                    VideoItem(
                        id = id.removeSuffix(".html"),
                        title = title,
                        duration = duration.trim(),
                        resolution = "HD",
                        views = "â€”",
                        category = source.label,
                        gradientSeed = index++,
                        pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 80) return items
            }
            if (items.isNotEmpty()) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// JavFree.me â€” /id/code cards + direct CDN mp4
// ---------------------------------------------------------------------------

class JavFreeClient : VideoSourceClient {
    override val source = VideoSource.JAVFREE

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?s=$q", source.baseUrl))
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
        // Same idea as Eporner: never hand ExoPlayer HTML landings.
        // JavFree "…mp4" links (tma.cx) almost always 302 → ExtMatrix *.mp4.html premium pages.
        val candidates = collectMp4AndHls(html, source.baseUrl)
            .filter {
                !it.url.contains("preview", true) &&
                    !it.url.endsWith(".html", true) &&
                    !it.url.contains("oembed", true)
            }
        val streams = candidates.mapNotNull { opt ->
            val playUrl = resolveJavFreePlayableUrl(opt.url.trim(), pageUrl) ?: return@mapNotNull null
            val label = NetworkClient.guessQualityLabel(playUrl, opt.label).ifBlank {
                when {
                    playUrl.contains("720") || opt.url.contains("720") -> "720p"
                    playUrl.contains("1080") || opt.url.contains("1080") -> "1080p"
                    playUrl.contains("demosaic", true) || opt.url.contains("demosaic", true) -> "Demosaic"
                    else -> opt.label
                }
            }
            StreamOption(label, playUrl)
        }.distinctBy { it.url }
        if (streams.isEmpty()) {
            throw IllegalStateException(
                "No playable stream on JavFree (links only open a premium file host page, not a video)",
            )
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "JavFree",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
        )
    }

    /**
     * Follow redirects and keep only real media. Drop ExtMatrix/login/HTML landings
     * (same failure mode as Eporner 1080p → /login/ HTML).
     */
    private fun resolveJavFreePlayableUrl(url: String, referer: String): String? {
        if (url.isBlank()) return null
        if (url.contains(".html", true) && !url.contains(".mp4", true)) return null
        val finalUrl = try {
            sanitizeStreamUrl(NetworkClient.resolveFinalUrl(url, referer))
        } catch (_: Exception) {
            // Unresolvable — only keep if it still looks like a direct CDN file, not a known HTML host.
            return if (looksLikeHtmlFileHost(url)) null else url
        }
        if (finalUrl.isBlank()) return null
        if (looksLikeHtmlFileHost(finalUrl)) return null
        val lower = finalUrl.lowercase()
        if (lower.contains("/login") || lower.contains("premium.php") || lower.contains("register.php")) {
            return null
        }
        // Real progressive/HLS media path
        if (lower.contains(".m3u8") || lower.contains(".mp4")) {
            // tma.cx / extmatrix often keep ".mp4" in the path while serving HTML — already filtered above
            // via looksLikeHtmlFileHost when final ends with .html or host is extmatrix.
            if (lower.endsWith(".html") || lower.contains(".mp4.html")) return null
            return finalUrl
        }
        return null
    }

    private fun looksLikeHtmlFileHost(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("extmatrix.com") ||
            u.contains(".mp4.html") ||
            u.endsWith(".html") ||
            u.contains("/files/") && u.contains(".html") ||
            u.contains("premium cloud") ||
            u.contains("/login")
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val re = Pattern.compile(
            """href="(https://javfree\.me/(\d+)/([a-z0-9-]+))"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (re.find()) {
            val href = re.group(1) ?: continue
            val id = re.group(2) ?: continue
            if (!seen.add(id)) continue
            val code = re.group(3).orEmpty()
            val window = html.substring(
                (re.start() - 500).coerceAtLeast(0),
                (re.start() + 900).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|data-lazy-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(//[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            )?.let { if (it.startsWith("//")) "https:$it" else it }
                // Common JavFree CDN cover pattern by product code
                ?: "https://fourhoi.com/$code/${code}pl.jpg"
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: code.uppercase(),
            )
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "JavFree",
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

// ---------------------------------------------------------------------------
// 123AV â€” /en/v/slug listing + javplayer embed
// ---------------------------------------------------------------------------

class OneTwoThreeAvClient : VideoSourceClient {
    override val source = VideoSource.ONETWOAV

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) "${source.baseUrl}/en" else "${source.baseUrl}/en?page=$p"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/en/search?keyword=$q", source.baseUrl))
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
        var streams = collectMp4AndHls(html, source.baseUrl)
        var embedUrl: String? = null
        // player(JSON.parse('[{...url":"https://javplayer.cc/e/..."}]')) — often unicode-escaped
        val playerJson = NetworkClient.matchFirst(
            html,
            """player\(JSON\.parse\('(\[[\s\S]*?\])'\)""",
        ) ?: NetworkClient.matchFirst(html, """player\(JSON\.parse\("(\[[\s\S]*?\])"\)""")
            ?: NetworkClient.matchFirst(html, """player\(JSON\.parse\('(\[[\s\S]*?\])'\)""")
        val rawPlayer = playerJson
            ?: NetworkClient.matchFirst(html, """(https?:\\?/\\?/javplayer\.cc\\?/e\\?/[A-Za-z0-9]+)""")
        if (rawPlayer != null) {
            val decoded = rawPlayer
                .replace("\\u0022", "\"")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\/", "/")
            val urlMatch = Pattern.compile(
                """https?://javplayer\.cc/e/[A-Za-z0-9]+""",
            ).matcher(decoded)
            val embeds = mutableListOf<String>()
            while (urlMatch.find()) {
                embeds.add(urlMatch.group())
            }
            // Also plain url field
            val field = Pattern.compile(""""url"\s*:\s*"(https?://[^"]+)"""").matcher(decoded)
            while (field.find()) {
                field.group(1)?.let { embeds.add(it.replace("\\/", "/")) }
            }
            for (embed in embeds.distinct()) {
                embedUrl = embed
                try {
                    val embHtml = NetworkClient.get(embed, pageUrl)
                    streams = collectMp4AndHls(embHtml)
                    if (streams.isNotEmpty()) break
                } catch (_: Exception) {
                }
            }
        }
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No stream on 123AV")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "123AV",
            views = "â€”",
            ratingPercent = "â€”",
            duration = "â€”",
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
        val m = Pattern.compile(
            """href="((?:https?://123av\.com)?/en/v/([a-z0-9-]+))"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (m.find()) {
            val path = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            // Related cards use background-image on a sibling span after the link.
            val window = html.substring(
                (m.start() - 150).coerceAtLeast(0),
                (m.start() + 900).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """background-image:\s*url\(['"]?(https?://[^"')]+)""",
            ) ?: NetworkClient.matchFirst(
                window,
                """data-preview=["'](https?://[^"']+)["']""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: id.uppercase(),
            )
            val duration = NetworkClient.matchFirst(
                window,
                """vside__dur[^>]*>([^<]+)<""",
            ) ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)<""")
                ?: "—"
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration,
                    resolution = "HD",
                    views = "—",
                    category = "123AV",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
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
// JavSeen â€” category/list pages
// ---------------------------------------------------------------------------

class JavSeenClient : VideoSourceClient {
    override val source = VideoSource.JAVSEEN
    /** Live listing host (javseen.tv homepage is empty; videos are on javseenz.tv). */
    private val listBase = "https://javseenz.tv"

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) "$listBase/" else "$listBase/?page=$p"
        parseListing(NetworkClient.get(url, listBase))
            .ifEmpty {
                // fall back to main domain category pages
                parseListing(NetworkClient.get("https://javseen.tv/", source.baseUrl))
            }
            .ifEmpty { throw IllegalStateException("Could not load JavSeen") }
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("https://javseen.tv/search/video/?s=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, listBase)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, pageUrl)
        var embedUrl: String? = null
        if (streams.isEmpty()) {
            val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
            if (!iframe.isNullOrBlank() && !iframe.contains("googletag") && !iframe.contains("ad")) {
                embedUrl = NetworkClient.absoluteUrl(pageUrl, iframe)
                try {
                    streams = collectMp4AndHls(NetworkClient.get(embedUrl, pageUrl))
                } catch (_: Exception) {
                }
            }
        }
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No stream on JavSeen")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "JavSeen",
            views = "â€”",
            ratingPercent = "â€”",
            duration = "â€”",
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
        // <li id="video-282208"> â€¦ href="/282208/slug/" â€¦ <img src="https://pics.javseenz.tv/...">
        val re = Pattern.compile(
            """href="((?:https?://javseenz?\.tv)?/(\d{4,})/([^"]+)/?)"[\s\S]{0,500}?src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (re.find()) {
            val path = re.group(1) ?: continue
            val id = re.group(2) ?: continue
            if (!seen.add(id)) continue
            val slug = re.group(3).orEmpty()
            val thumb = re.group(4).orEmpty()
            val window = html.substring(
                (re.start() - 80).coerceAtLeast(0),
                (re.start() + 400).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                    ?: slug.replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = "â€”",
                    resolution = "HD",
                    views = "â€”",
                    category = "JavSeen",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(listBase, path),
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
// BabeXTube (Myanmar) â€” /mm-porn/ slugs + sub.babextube.com mp4
// ---------------------------------------------------------------------------

class BabeXTubeClient : VideoSourceClient {
    override val source = VideoSource.BABEXTUBE

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?s=$q", source.baseUrl))
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
        if (streams.isEmpty()) {
            throw IllegalStateException("No stream on BabeXTube")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "BabeXTube",
            views = "â€”",
            ratingPercent = "â€”",
            duration = "â€”",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val m = Pattern.compile(
            """href="(https://babextube\.com/mm-porn/([^"]+))"[\s\S]{0,500}?(?:data-src|src)="(https?://[^"]+)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (m.find()) {
            val href = m.group(1) ?: continue
            val slug = m.group(2)?.trimEnd('/') ?: continue
            if (!seen.add(slug)) continue
            val thumb = m.group(3).orEmpty()
            val title = NetworkClient.decodeHtml(
                java.net.URLDecoder.decode(slug, Charsets.UTF_8.name())
                    .replace('-', ' ')
                    .take(80),
            )
            items.add(
                VideoItem(
                    id = slug.hashCode().toUInt().toString(),
                    title = title.ifBlank { "BabeXTube video" },
                    duration = "â€”",
                    resolution = "HD",
                    views = "â€”",
                    category = "BabeXTube",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 40) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// BebasIndo â€” /video/slug listing + /api/iframe player
// ---------------------------------------------------------------------------

class BebasIndoClient : VideoSourceClient {
    override val source = VideoSource.BEBASINDO

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) {
            "${source.baseUrl}/category/indonesia/"
        } else {
            "${source.baseUrl}/category/indonesia/page/$p/"
        }
        parseListing(NetworkClient.get(url, source.baseUrl))
            .ifEmpty {
                if (p == 1) parseListing(NetworkClient.get(source.baseUrl + "/", source.baseUrl))
                else emptyList()
            }
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?s=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<h1[^>]*>([^<]+)</h1>""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            ?: NetworkClient.matchFirst(html, """class="player-poster"[^>]+src="([^"]+)"""")
            ?: ""
        var streams = collectMp4AndHls(html, source.baseUrl)
        var embedUrl: String? = null
        // data-server is base64 video id for /api/iframe
        val server = NetworkClient.matchFirst(html, """data-server="([A-Za-z0-9+/=]{4,})"""")
        val menu = NetworkClient.matchFirst(html, """data-menu="(\d+)"""") ?: "1"
        if (streams.isEmpty() && !server.isNullOrBlank()) {
            for (sv in listOf("1", "2", "3")) {
                try {
                    val body = "sv=$sv&server=${java.net.URLEncoder.encode(server, "UTF-8")}" +
                        "&menu=$menu&poster=&skin="
                    val resp = NetworkClient.postForm(
                        "${source.baseUrl}/api/iframe",
                        body,
                        pageUrl,
                    )
                    val json = JSONObject(resp)
                    if (json.optBoolean("success")) {
                        var play = json.optString("url")
                        if (play.startsWith("/")) play = NetworkClient.absoluteUrl(source.baseUrl, play)
                        if (play.isNotBlank()) {
                            embedUrl = play
                            val playHtml = NetworkClient.get(play, pageUrl)
                            streams = collectMp4AndHls(playHtml, play)
                            if (streams.isNotEmpty()) break
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No stream on BebasIndo")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "BebasIndo",
            views = "â€”",
            ratingPercent = "â€”",
            duration = "â€”",
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
        val re = Pattern.compile(
            """href="(https://bebasindo\.top/video/([a-z0-9-]+)/?)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (re.find()) {
            val href = re.group(1) ?: continue
            val id = re.group(2) ?: continue
            if (!seen.add(id)) continue
            val window = html.substring(
                (re.start() - 100).coerceAtLeast(0),
                (re.start() + 900).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: id.replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = "â€”",
                    resolution = "HD",
                    views = "â€”",
                    category = "BebasIndo",
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

// ---------------------------------------------------------------------------
// NontonBokep â€” slug cards + base64 iframe â†’ CDN mp4
// ---------------------------------------------------------------------------

class NontonBokepClient : VideoSourceClient {
    override val source = VideoSource.NONTONBOKEP

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        parseListing(NetworkClient.get("${source.baseUrl}/?s=$q", source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: NetworkClient.matchFirst(html, """<h1[^>]*>([^<]+)</h1>""")
                ?: "Video",
        ).trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, source.baseUrl)
        // iframe src is often base64 of embed host
        val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
        if (streams.isEmpty() && !iframe.isNullOrBlank()) {
            val embed = decodeMaybeBase64Url(iframe)
            try {
                if (embed.endsWith(".mp4", true) || embed.contains(".mp4?")) {
                    streams = listOf(StreamOption("MP4", embed))
                } else {
                    val embHtml = NetworkClient.get(embed, pageUrl)
                    streams = collectMp4AndHls(embHtml, embed)
                    if (streams.isEmpty()) {
                        // try common CDN pattern from decoded host + id
                        val id = embed.trimEnd('/').substringAfterLast('/')
                        val guess = "https://embed.200cdn.top/$id.mp4"
                        streams = listOf(StreamOption("MP4", guess))
                    }
                }
            } catch (_: Exception) {
                if (embed.startsWith("http")) {
                    streams = listOf(StreamOption("Embed", embed))
                }
            }
        }
        if (streams.isEmpty()) {
            throw IllegalStateException("No stream on NontonBokep")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "NontonBokep",
            views = "â€”",
            ratingPercent = "â€”",
            duration = "â€”",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed") streams.first().url else null,
        )
    }

    private fun decodeMaybeBase64Url(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http")) return t
        return try {
            val decoded = String(java.util.Base64.getDecoder().decode(t), Charsets.UTF_8)
            if (decoded.startsWith("http")) decoded else t
        } catch (_: Exception) {
            t
        }
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val m = Pattern.compile(
            """href="(https://nontonbokep\.top/([a-z0-9-]{8,}))"[\s\S]{0,400}?(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (m.find()) {
            val href = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val thumb = m.group(3).orEmpty()
            val title = id.replace(Regex("""-\d+-[a-z0-9]+$"""), "").replace('-', ' ')
            items.add(
                VideoItem(
                    id = id,
                    title = NetworkClient.decodeHtml(title).ifBlank { id },
                    duration = "â€”",
                    resolution = "HD",
                    views = "â€”",
                    category = "NontonBokep",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) break
        }
        if (items.isEmpty()) {
            val loose = Pattern.compile(
                """href="(https://nontonbokep\.top/([a-z0-9-]{10,}))"""",
            ).matcher(html)
            while (loose.find()) {
                val href = loose.group(1) ?: continue
                val id = loose.group(2) ?: continue
                if (!seen.add(id)) continue
                items.add(
                    VideoItem(
                        id = id,
                        title = id.replace('-', ' '),
                        duration = "â€”",
                        resolution = "HD",
                        views = "â€”",
                        category = "NontonBokep",
                        gradientSeed = index++,
                        pageUrl = href,
                        thumbnailUrl = "",
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 40) break
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// VLXX — /video/slug/id/ cards + ajax.php → play.vlstream.net HLS (.vl)
// ---------------------------------------------------------------------------

class VlxxClient : VideoSourceClient {
    override val source = VideoSource.VLXX

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/?page=$p"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val path = if (p <= 1) "/search/$q/" else "/search/$q/?page=$p"
        parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - VLXX").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val videoId = NetworkClient.matchFirst(html, """var\s+vid\s*=\s*(\d+)""")
            ?: NetworkClient.matchFirst(html, """server\(\s*\d+\s*,\s*(\d+)\s*\)""")
            ?: Regex("""/(\d+)/?$""").find(pageUrl.trimEnd('/'))?.groupValues?.get(1)

        var streams = collectMp4AndHls(html, source.baseUrl)
        var embedUrl: String? = null

        // Official player: POST /ajax.php { vlxx_server, id, server } → iframe → window.__SRC
        if (!videoId.isNullOrBlank()) {
            for (server in listOf(1, 2)) {
                if (streams.isNotEmpty()) break
                try {
                    val resp = NetworkClient.postForm(
                        "${source.baseUrl}/ajax.php",
                        "vlxx_server=2&id=$videoId&server=$server",
                        pageUrl,
                    )
                    val playerHtml = NetworkClient.matchFirst(resp, """"player"\s*:\s*"((?:\\.|[^"\\])*)"""")
                        ?.replace("\\/", "/")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\u003C", "<")
                        ?.replace("\\u003E", ">")
                        .orEmpty()
                    val iframe = NetworkClient.matchFirst(
                        playerHtml.ifBlank { resp },
                        """src=["'](https?://[^"']+)["']""",
                    ) ?: NetworkClient.matchFirst(resp, """src=\\?"(https?:\\?/\\?/[^"\\]+)""")
                        ?.replace("\\/", "/")
                    if (!iframe.isNullOrBlank()) {
                        embedUrl = iframe
                        streams = extractVlStreamSources(iframe, pageUrl)
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (streams.isEmpty()) {
            val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
            if (!iframe.isNullOrBlank() && !iframe.contains("ad", true) && !iframe.contains("googletag")) {
                embedUrl = NetworkClient.absoluteUrl(pageUrl, iframe)
                streams = extractVlStreamSources(embedUrl!!, pageUrl)
            }
        }
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on VLXX")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "VLXX",
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

    /** play.vlstream.net embed: window.__SRC = [{file:"…manifest…vl", label, type:"hls"}] */
    private fun extractVlStreamSources(embedUrl: String, pageUrl: String): List<StreamOption> {
        val out = mutableListOf<StreamOption>()
        try {
            val emb = NetworkClient.get(embedUrl, pageUrl)
            out += collectMp4AndHls(emb, embedUrl)
            val srcBlock = NetworkClient.matchFirst(
                emb,
                """window\.__SRC\s*=\s*(\[[\s\S]*?\])\s*;""",
            ) ?: NetworkClient.matchFirst(emb, """__SRC\s*=\s*(\[[\s\S]*?\])""")
            if (!srcBlock.isNullOrBlank()) {
                val fileRe = Pattern.compile(
                    """"file"\s*:\s*"(https?://[^"]+)".*?"label"\s*:\s*"([^"]*)"""",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
                ).matcher(srcBlock)
                while (fileRe.find()) {
                    val file = fileRe.group(1)?.replace("\\/", "/") ?: continue
                    val label = fileRe.group(2).orEmpty().ifBlank { "Auto" }
                    // .vl manifests are HLS — force HLS label so ExoPlayer uses HlsMediaSource
                    val qLabel = if (
                        file.contains(".vl", true) ||
                        file.contains("manifest", true) ||
                        label.contains("auto", true) ||
                        label.contains("hls", true)
                    ) {
                        if (label.contains("HLS", true)) label else "$label (HLS)"
                    } else {
                        label
                    }
                    out.add(StreamOption(qLabel, file))
                }
                // file-only objects
                if (out.isEmpty()) {
                    val onlyFile = Pattern.compile(
                        """"file"\s*:\s*"(https?://[^"]+)"""",
                    ).matcher(srcBlock)
                    while (onlyFile.find()) {
                        val file = onlyFile.group(1)?.replace("\\/", "/") ?: continue
                        out.add(StreamOption("Auto (HLS)", file))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.distinctBy { it.url }
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val m = Pattern.compile(
            """href="((?:https?://vlxx\.[a-z]+)?/video/([^/]+)/(\d+)/?)"([^>]*)>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var index = 0
        while (m.find()) {
            val path = m.group(1) ?: continue
            val slug = m.group(2).orEmpty()
            val id = m.group(3) ?: continue
            if (!seen.add(id)) continue
            val attrs = m.group(4).orEmpty()
            val window = html.substring(
                m.start(),
                (m.start() + 700).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(attrs, """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: slug.replace('-', ' '),
            )
            var thumb = NetworkClient.matchFirst(window, """data-original="(https?://[^"]+)"""")
                ?: NetworkClient.matchFirst(window, """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""")
                .orEmpty()
            if (thumb.startsWith("data:image")) {
                thumb = NetworkClient.matchFirst(window, """data-original="(https?://[^"]+)"""")
                    .orEmpty()
            }
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = NetworkClient.matchFirst(window, """class="duration"[^>]*>([^<]+)<""")
                        ?: "—",
                    resolution = "HD",
                    views = "—",
                    category = "VLXX",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
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
// SexHay24h / SexDepTV — WP cards + javcg → newfeedcdn m3u8
// ---------------------------------------------------------------------------

class SexHay24hClient : VideoSourceClient {
    override val source = VideoSource.SEXHAY24H

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val urls = if (p <= 1) {
            listOf(
                "${source.baseUrl}/",
                "${source.baseUrl}/vi-vn/",
                "https://sexhay24h.net/",
            )
        } else {
            listOf(
                "${source.baseUrl}/page/$p/",
                "${source.baseUrl}/vi-vn/page/$p/",
                "https://sexhay24h.net/page/$p/",
            )
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
        val p = page.coerceAtLeast(1)
        val urls = if (p <= 1) {
            listOf("${source.baseUrl}/?s=$q", "https://sexhay24h.net/?s=$q")
        } else {
            listOf("${source.baseUrl}/page/$p/?s=$q", "https://sexhay24h.net/page/$p/?s=$q")
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

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - VLXX").substringBefore(" | ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, source.baseUrl)
        var embedUrl: String? = null

        // Chain: page iframe → javcg.xyz → abc.newfeedcdn.site player.php → m3u8
        val iframeRe = Pattern.compile(
            """iframe[^>]+src=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val embeds = mutableListOf<String>()
        while (iframeRe.find()) {
            val src = iframeRe.group(1) ?: continue
            if (src.contains("googletag") || src.contains("doubleclick") || src.contains("/ad")) continue
            embeds.add(NetworkClient.absoluteUrl(pageUrl, src))
        }
        NetworkClient.matchFirst(html, """["'](https?://javcg\.[^"']+)["']""")
            ?.let { embeds.add(it) }

        for (embed in embeds.distinct()) {
            embedUrl = embed
            try {
                val embHtml = NetworkClient.get(embed, pageUrl)
                streams = collectMp4AndHls(embHtml, embed) + streams
                // Nested iframe
                val nested = NetworkClient.matchFirst(
                    embHtml,
                    """iframe[^>]+src=["'](https?://[^"']+)["']""",
                ) ?: NetworkClient.matchFirst(embHtml, """src=["'](https?://[^"']*player\.php[^"']+)["']""")
                if (!nested.isNullOrBlank()) {
                    val nestHtml = NetworkClient.get(nested, embed)
                    streams = collectMp4AndHls(nestHtml, nested) + streams
                    // Explicit m3u8 in player page
                    val m3 = Pattern.compile(
                        """(https?://[^"'\\\s]+\.m3u8[^"'\\\s]*)""",
                        Pattern.CASE_INSENSITIVE,
                    ).matcher(nestHtml)
                    while (m3.find()) {
                        val u = m3.group(1)?.replace("\\/", "/") ?: continue
                        streams = listOf(StreamOption("Auto (HLS)", u)) + streams
                    }
                }
                if (streams.isNotEmpty()) break
            } catch (_: Exception) {
            }
        }

        streams = streams.distinctBy { it.url }
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on SexHay24h")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "SexHay24h",
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
        val skip = setOf(
            "vi-vn", "jav", "xnxx", "xvideos", "phim-sex-vietsub", "phim-sex-khong-che",
            "loan-luan", "sex-hiep-dam", "vung-trom-ngoai-tinh", "phim-cap-3",
            "sex-hoc-sinh", "sex-my-chau-au", "feed", "wp-json", "page", "author",
            "huong-dan-danh-cho-nguoi-vo-kieu-ngao", "category", "tag",
        )
        // Absolute links on sexdeptv / sexhay24h
        val m = Pattern.compile(
            """href="(https?://(?:sexdeptv\.com|sexhay24h\.net)/([a-z0-9][a-z0-9-]{8,})/?)"([^>]*)>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val slug = m.group(2) ?: continue
            if (slug in skip || slug.startsWith("page")) continue
            if (href.contains("/tag/") || href.contains("/category/")) continue
            if (!seen.add(slug)) continue
            val window = html.substring(
                (m.start() - 200).coerceAtLeast(0),
                (m.start() + 800).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(m.group(3).orEmpty(), """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """(?:alt|title)="([^"]{8,})"""")
                    ?: slug.replace('-', ' '),
            )
            val thumb = NetworkClient.matchFirst(window, """data-original="(https?://[^"]+)"""")
                ?: NetworkClient.matchFirst(
                    window,
                    """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                ).orEmpty()
            if (thumb.isBlank() || thumb.contains(".gif", true)) {
                // Prefer real poster nearby
                val poster = NetworkClient.matchFirst(
                    window,
                    """data-original="(https?://(?:sexdeptv\.com|sexhay24h\.net)/wp-content/uploads/[^"]+)"""",
                )
                if (poster.isNullOrBlank()) continue
            }
            val cleanThumb = NetworkClient.matchFirst(window, """data-original="(https?://[^"]+)"""")
                ?: thumb
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "SexHay24h",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = cleanThumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) break
        }
        // Fallback: href then data-original poster
        if (items.isEmpty()) {
            val m3 = Pattern.compile(
                """href="(https?://(?:sexdeptv\.com|sexhay24h\.net)/([a-z0-9-]{10,})/?)"[\s\S]{0,500}?data-original="(https?://[^"]+)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (m3.find()) {
                val href = m3.group(1) ?: continue
                val slug = m3.group(2) ?: continue
                if (slug in skip) continue
                if (!seen.add(slug)) continue
                val thumb = m3.group(3).orEmpty()
                val window = html.substring(
                    m3.start(),
                    (m3.start() + 600).coerceAtMost(html.length),
                )
                val title = NetworkClient.decodeHtml(
                    NetworkClient.matchFirst(window, """(?:alt|title)="([^"]{5,})"""")
                        ?: slug.replace('-', ' '),
                )
                items.add(
                    VideoItem(
                        id = slug,
                        title = title,
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = "SexHay24h",
                        gradientSeed = index++,
                        pageUrl = href,
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 48) break
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// PornKai ï¿½ aggregates XVideos / TXXX embeds via /view?key=
// ---------------------------------------------------------------------------

class PornKaiClient : VideoSourceClient {
    override val source = VideoSource.PORNKAI

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        // Homepage is category chips only — real grid is loaded via JSON API.
        fetchApiSearch(query = "", page = page.coerceAtLeast(1), sort = "new")
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        fetchApiSearch(query.trim(), page.coerceAtLeast(1), sort = "new")
    }

    private fun fetchApiSearch(query: String, page: Int, sort: String): List<VideoItem> {
        val q = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val url =
            "${source.baseUrl}/api?method=search&query=$q&page=$page&sort=$sort"
        val body = NetworkClient.get(url, source.baseUrl)
        // API returns {"html":"…","results_remaining":N}
        val html = try {
            val root = org.json.JSONObject(body)
            root.optString("html").ifBlank { body }
        } catch (_: Exception) {
            body
        }
        val items = parseListing(html)
        if (items.isNotEmpty()) return items
        // Fallback: scrape homepage category cards when API is empty
        if (query.isBlank() && page <= 1) {
            return parseListing(NetworkClient.get(source.baseUrl + "/", source.baseUrl))
        }
        return items
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore("|").substringBefore(" - ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
            .ifBlank {
                NetworkClient.matchFirst(
                    html,
                    """(?:data-src|src)=['"](https?://(?:thumb|thumbs)[^'"]+\.(?:jpg|jpeg|png|webp)[^'"]*)['"]""",
                ).orEmpty()
            }
            .ifBlank {
                NetworkClient.matchFirst(
                    html,
                    """(?:data-src|src)=['"](https?://[^'"]+\.(?:jpg|jpeg|png|webp)[^'"]*)['"]""",
                ).orEmpty()
            }
        var streams = collectMp4AndHls(html, source.baseUrl)
        // Prefer embedded XVideos / TXXX players.
        val iframe = NetworkClient.matchFirst(
            html,
            """iframe[^>]+src=["'](https?://(?:www\.)?(?:xvideos\.com/embedframe/[^"']+|videotxxx\.com/embed/[^"']+))["']""",
        ) ?: NetworkClient.matchFirst(html, """iframe[^>]+src=["'](//(?:www\.)?xvideos\.com/embedframe/[^"']+)["']""")
        if (!iframe.isNullOrBlank()) {
            val embUrl = if (iframe.startsWith("//")) "https:$iframe" else iframe
            try {
                val emb = NetworkClient.get(embUrl, pageUrl)
                val high = NetworkClient.matchFirst(emb, """setVideoUrlHigh\(['"]([^'"]+)['"]\)""")
                val low = NetworkClient.matchFirst(emb, """setVideoUrlLow\(['"]([^'"]+)['"]\)""")
                val hls = NetworkClient.matchFirst(emb, """setVideoHLS\(['"]([^'"]+)['"]\)""")
                streams = listOfNotNull(
                    high?.let { StreamOption("High", it) },
                    low?.let { StreamOption("Low", it) },
                    hls?.let { StreamOption("Auto (HLS)", it) },
                ) + collectMp4AndHls(emb) + streams
            } catch (_: Exception) {
                streams = listOf(StreamOption("Embed", embUrl)) + streams
            }
        }
        streams = streams.distinctBy { it.url }
        if (streams.isEmpty()) throw IllegalStateException("No playable stream on PornKai")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title.ifBlank { "PornKai" },
            uploader = "PornKai",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(16),
            thumbnailUrl = thumb,
            embedUrl = streams.firstOrNull { it.label == "Embed" }?.url,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Each video has TWO links with the same key:
        //  1) image card  <a href="/view?key=…"> <img …> duration
        //  2) title card  <a href="/view?key=…" class="thumbnail_title"> <span>Real Title</span>
        // Prefer the title link so we don't keep key ids as titles.
        val titleLink = Pattern.compile(
            """href="(/view\?key=([a-zA-Z0-9_]+))"[^>]*class="[^"]*thumbnail_title[^"]*"[^>]*>\s*<span[^>]*>\s*([^<]+?)\s*</span>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (titleLink.find()) {
            val path = titleLink.group(1) ?: continue
            val key = titleLink.group(2) ?: continue
            if (!seen.add(key)) continue
            val title = NetworkClient.decodeHtml(titleLink.group(3).orEmpty().trim())
            // Thumb/duration live in the PRECEDING image card (same key).
            // lastIndexOf(..., start) includes the title link itself — search before it.
            val searchEnd = (titleLink.start() - 1).coerceAtLeast(0)
            val keyNeedle = """href="/view?key=$key""""
            val keyIdx = if (searchEnd > 0) html.lastIndexOf(keyNeedle, searchEnd) else -1
            val blockIdx = if (searchEnd > 0) html.lastIndexOf("""class="thumbnail"""", searchEnd) else -1
            val windowStart = when {
                keyIdx >= 0 && blockIdx >= 0 -> minOf(keyIdx, blockIdx)
                keyIdx >= 0 -> keyIdx
                blockIdx >= 0 -> blockIdx
                else -> (titleLink.start() - 2200).coerceAtLeast(0)
            }
            val window = html.substring(
                windowStart,
                (titleLink.end() + 80).coerceAtMost(html.length),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)=['"](https?://[^'"]+\.(?:jpg|jpeg|png|webp)[^'"]*)['"]""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)=['"](https?://(?:thumb|thumbs)[^'"]+)['"]""",
            ) ?: NetworkClient.matchFirst(
                window,
                """data-xham=['"](https?://[^;'"]+)""",
            ).orEmpty()
            val duration = NetworkClient.matchFirst(
                window,
                """>\s*(\d{1,2}:\d{2}(?::\d{2})?)\s*<""",
            ) ?: "—"
            items.add(
                VideoItem(
                    id = key,
                    title = title.ifBlank { key },
                    duration = duration,
                    resolution = "HD",
                    views = "—",
                    category = "PornKai",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 80) break
        }
        // Fallback if markup changes (image links only).
        if (items.isEmpty()) {
            val loose = Pattern.compile(
                """href="(/view\?key=([a-zA-Z0-9_]+))"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (loose.find()) {
                val path = loose.group(1) ?: continue
                val key = loose.group(2) ?: continue
                if (!seen.add(key)) continue
                val after = html.substring(loose.start(), (loose.start() + 2000).coerceAtMost(html.length))
                val title = NetworkClient.decodeHtml(
                    NetworkClient.matchFirst(
                        after,
                        """thumbnail_title[^>]*>\s*<span[^>]*>\s*([^<]+?)\s*</span>""",
                    ) ?: NetworkClient.matchFirst(after, """<span[^>]*>\s*([^<\d][^<]{8,180}?)\s*</span>""")
                        ?: key,
                )
                items.add(
                    VideoItem(
                        id = key,
                        title = title,
                        duration = NetworkClient.matchFirst(after, """>\s*(\d{1,2}:\d{2})\s*<""") ?: "—",
                        resolution = "HD",
                        views = "—",
                        category = "PornKai",
                        gradientSeed = index++,
                        pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                        thumbnailUrl = NetworkClient.matchFirst(
                            after,
                            """(?:data-src|src)=['"](https?://[^'"]+\.(?:jpg|jpeg|png|webp)[^'"]*)['"]""",
                        ).orEmpty(),
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 80) break
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// ThaiPornTV ï¿½ data-enc XOR-17 ? techvids HLS
// ---------------------------------------------------------------------------

class ThaiPornTvClient : VideoSourceClient {
    override val source = VideoSource.THAIPORNTV

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val paths = if (p <= 1) {
            listOf("/search/$q/", "/?s=$q", "/tags/$q/")
        } else {
            listOf("/search/$q/page/$p/", "/page/$p/?s=$q", "/tags/$q/page/$p/")
        }
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
        out
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = mutableListOf<StreamOption>()
        val enc = NetworkClient.matchFirst(html, """data-enc=["']([^"']+)["']""")
        if (!enc.isNullOrBlank()) {
            for (opt in decryptTechvids(enc)) streams.add(opt)
        }
        streams += collectMp4AndHls(html, source.baseUrl)
        if (streams.isEmpty()) {
            // Page still plays in WebView via fluidPlayer.
            streams.add(StreamOption("Embed", pageUrl))
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "ThaiPornTV",
            views = "ï¿½",
            ratingPercent = "ï¿½",
            duration = "ï¿½",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed") pageUrl else null,
        )
    }

    private fun decryptTechvids(enc: String): List<StreamOption> {
        return try {
            val b64 = enc.replace('-', '+').replace('_', '/')
            val pad = when (b64.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            val raw = android.util.Base64.decode(b64 + pad, android.util.Base64.DEFAULT)
            val sb = StringBuilder(raw.size)
            for (b in raw) sb.append((b.toInt() xor 17).toChar())
            val json = sb.toString()
            val out = mutableListOf<StreamOption>()
            val m = Pattern.compile(
                """"u"\s*:\s*"([^"]+)"\s*,\s*"q"\s*:\s*"([^"]+)"""",
            ).matcher(json)
            while (m.find()) {
                val url = m.group(1)?.replace("\\/", "/") ?: continue
                val q = m.group(2).orEmpty()
                out.add(StreamOption(q.ifBlank { "HLS" }, url))
            }
            out.ifEmpty {
                val loose = Pattern.compile("""https?://[^"\\\s]+\.m3u8[^"\\\s]*""").matcher(json)
                val fallback = mutableListOf<StreamOption>()
                while (loose.find()) {
                    fallback.add(StreamOption("HLS", loose.group().replace("\\/", "/")))
                }
                fallback
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val m = Pattern.compile(
            """href="((?:https://(?:www\.)?thaiporntv\.com)?/videos/20\d{2}/([^"']+\d+)/?)" """,
            Pattern.CASE_INSENSITIVE,
        ).matcher(html.replace("href=", "href="))
        // Unquoted or quoted hrefs
        val m2 = Pattern.compile(
            """href=(?:["'])?((?:https://(?:www\.)?thaiporntv\.com)?/videos/20\d{2}/([^"'>\s]+))""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m2.find()) {
            val path = m2.group(1) ?: continue
            val slug = m2.group(2)?.trimEnd('/') ?: continue
            val id = slug.substringAfterLast('-').ifBlank { slug }
            if (!seen.add(id)) continue
            if (path.contains("/tags/") || path.contains("/page/")) continue
            val window = html.substring(
                m2.start(),
                (m2.start() + 900).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                    ?: slug.replace('-', ' '),
            )
            val thumb = NetworkClient.matchFirst(window, """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""")
                .orEmpty()
                .ifBlank {
                    // techvids default thumb from numeric id
                    val num = id.filter { it.isDigit() }
                    if (num.isNotEmpty()) "https://web.techvids.top/assets/xn88-$num/default.webp" else ""
                }
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = "ï¿½",
                    resolution = "HD",
                    views = "ï¿½",
                    category = "ThaiPornTV",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 80) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// iXXX ï¿½ tube search aggregator (mobile UA; falls back to Thai XVideos feed)
// ---------------------------------------------------------------------------

class IxxxClient : VideoSourceClient {
    override val source = VideoSource.IXXX
    // Cloudflare often blocks scrapers; Thai keyword feed keeps the source playable.
    private val xvFallback = XnxxApi(VideoSource.XVIDEOS)

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        try {
            val p = page.coerceAtLeast(1)
            val path = if (p <= 1) "/" else "/?page=$p"
            val html = NetworkClient.get(source.baseUrl + path, source.baseUrl)
            val items = parseListing(html)
            if (items.isNotEmpty()) return@withContext items
        } catch (_: Exception) {
        }
        // Cloudflare often challenges desktop scrapers ï¿½ use XVideos Thai results rebranded.
        xvFallback.search("thai", page).map { it.copy(sourceId = source.id, category = "iXXX") }
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        try {
            val path = if (p <= 1) "/search/?q=$q" else "/search/?q=$q&page=$p"
            val html = NetworkClient.get(source.baseUrl + path, source.baseUrl)
            val items = parseListing(html)
            if (items.isNotEmpty()) return@withContext items
        } catch (_: Exception) {
        }
        val scoped = if (query.contains("thai", true)) query else "thai $query"
        xvFallback.search(scoped, page).map { it.copy(sourceId = source.id, category = "iXXX") }
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        if (pageUrl.contains("xvideos.com", true)) {
            return@withContext xvFallback.fetchVideoDetails(pageUrl).let { d ->
                d // streams already playable
            }
        }
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        var streams = collectMp4AndHls(html, source.baseUrl)
        val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
        if (streams.isEmpty() && !iframe.isNullOrBlank()) {
            val emb = NetworkClient.absoluteUrl(pageUrl, iframe)
            try {
                streams = collectMp4AndHls(NetworkClient.get(emb, pageUrl))
                if (streams.isEmpty()) {
                    val high = NetworkClient.matchFirst(
                        NetworkClient.get(emb, pageUrl),
                        """setVideoUrlHigh\(['"]([^'"]+)['"]\)""",
                    )
                    val hls = NetworkClient.matchFirst(
                        NetworkClient.get(emb, pageUrl),
                        """setVideoHLS\(['"]([^'"]+)['"]\)""",
                    )
                    streams = listOfNotNull(
                        high?.let { StreamOption("High", it) },
                        hls?.let { StreamOption("Auto (HLS)", it) },
                    )
                }
            } catch (_: Exception) {
                streams = listOf(StreamOption("Embed", emb))
            }
        }
        // Outbound xvideos links
        val xv = NetworkClient.matchFirst(html, """href="(https?://(?:www\.)?xvideos\.com/video[^"]+)"""")
        if (streams.isEmpty() && !xv.isNullOrBlank()) {
            return@withContext xvFallback.fetchVideoDetails(xv)
        }
        if (streams.isEmpty()) throw IllegalStateException("No playable stream on iXXX")
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        )
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "iXXX",
            views = "ï¿½",
            ratingPercent = "ï¿½",
            duration = "ï¿½",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).take(12),
            thumbnailUrl = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
                .orEmpty(),
            embedUrl = streams.firstOrNull { it.label == "Embed" }?.url,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        if (html.contains("Just a moment", true) && html.contains("cf-chl", true)) {
            return emptyList()
        }
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // iXXX often links out to tube sites
        val patterns = listOf(
            """href="(https?://(?:www\.)?xvideos\.com/video[^"]+)"""",
            """href="(https?://(?:www\.)?ixxx\.com/[^"]*video[^"]+)"""",
            """href="(/video/[^"]+)"""",
        )
        for (pat in patterns) {
            val m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(html)
            while (m.find()) {
                val href = m.group(1) ?: continue
                val id = href.trimEnd('/').substringAfterLast('/').take(64)
                if (!seen.add(id)) continue
                val window = html.substring(m.start(), (m.start() + 800).coerceAtMost(html.length))
                val title = NetworkClient.decodeHtml(
                    NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                        ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                        ?: id.replace('-', ' '),
                )
                items.add(
                    VideoItem(
                        id = id,
                        title = title,
                        duration = "ï¿½",
                        resolution = "HD",
                        views = "ï¿½",
                        category = "iXXX",
                        gradientSeed = index++,
                        pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                        thumbnailUrl = extractThumbFromWindow(window),
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 80) return items
            }
            if (items.isNotEmpty()) break
        }
        return items
    }
}

