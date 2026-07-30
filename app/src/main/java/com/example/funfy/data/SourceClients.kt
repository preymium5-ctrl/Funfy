package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
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
        VideoSource.TUBE8 -> Tube8Client()
        VideoSource.TIAVA -> TiavaClient()
        VideoSource.PINAYOT -> WordPressTubeClient(VideoSource.PINAYOT)
        VideoSource.IYOTTUBE -> WordPressTubeClient(VideoSource.IYOTTUBE)
        VideoSource.SPOTIBOLD -> WordPressTubeClient(VideoSource.SPOTIBOLD)
        VideoSource.XTORJACK -> WordPressTubeClient(VideoSource.XTORJACK)
        VideoSource.KANTOTPLUS -> WordPressTubeClient(VideoSource.KANTOTPLUS)
        VideoSource.PINAYVLOG -> WordPressTubeClient(VideoSource.PINAYVLOG)
        VideoSource.KATORSEX -> WordPressTubeClient(VideoSource.KATORSEX)
        VideoSource.JAKOLMAN -> WordPressTubeClient(VideoSource.JAKOLMAN)
        VideoSource.DINOTUBE -> WordPressTubeClient(VideoSource.DINOTUBE)
        VideoSource.PINAYFLIX -> PinayFlixClient()
        VideoSource.PORNKAI -> PornKaiClient()
        VideoSource.PINAYPORNSITE -> WordPressTubeClient(VideoSource.PINAYPORNSITE)
        VideoSource.PINAYVIRAL -> PinayViralClient()
        VideoSource.BUUMAL -> BuumalClient()
        VideoSource.MMHDHUB -> MmhdHubClient()
        VideoSource.BABEXTUBE -> BabeXTubeClient()
        VideoSource.XBURMA -> XBurmaClient()
        VideoSource.KOSARGYI -> WordPressTubeClient(VideoSource.KOSARGYI)
        VideoSource.XGROOVY -> WordPressTubeClient(VideoSource.XGROOVY)
        VideoSource.MRNOEGYI -> WordPressTubeClient(VideoSource.MRNOEGYI)
        VideoSource.MAYNOE -> WordPressTubeClient(VideoSource.MAYNOE)
        VideoSource.APYARGABAR -> WordPressTubeClient(VideoSource.APYARGABAR)
        VideoSource.JABLE -> JableClient()
        VideoSource.SUPJAV -> SupJavClient()
        VideoSource.JAVFREE -> JavFreeClient()
        VideoSource.JAVTSUNAMI -> JavTsunamiClient()
        VideoSource.ONETWOAV -> OneTwoThreeAvClient()
        VideoSource.JAVSEEN -> JavSeenClient()
        VideoSource.JAVTUB -> WordPressTubeClient(VideoSource.JAVTUB)
        VideoSource.INDO18 -> WordPressTubeClient(VideoSource.INDO18)
        VideoSource.BOKEPBOX -> WordPressTubeClient(VideoSource.BOKEPBOX)
        VideoSource.BOKEPINDOHOT -> WordPressTubeClient(VideoSource.BOKEPINDOHOT)
        VideoSource.PROBOKEP -> WordPressTubeClient(VideoSource.PROBOKEP)
        VideoSource.GAIRAHTV -> WordPressTubeClient(VideoSource.GAIRAHTV)
        VideoSource.BOKEPBOZ -> WordPressTubeClient(VideoSource.BOKEPBOZ)
        VideoSource.KINGBOKEP -> WordPressTubeClient(VideoSource.KINGBOKEP)
        VideoSource.HEIBOKEP -> WordPressTubeClient(VideoSource.HEIBOKEP)
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
        VideoSource.XHAMSTER2 -> XHamster2Client()
        VideoSource.BEEG -> BeegClient()
        VideoSource.TXXX -> TxxxClient()
        VideoSource.XXXFILES -> GenericTubeClient(
            source = source,
            homePaths = { p ->
                if (p <= 1) listOf("/latest-updates/", "/") else listOf("/latest-updates/$p/")
            },
            searchPath = { q, p ->
                if (p <= 1) "/search/$q/" else "/search/$q/$p/"
            },
            linkPatterns = listOf(
                """href="(https?://(?:www\.)?xxxfiles\.com/videos/\d+/[^"]+)"""",
                """href="(/videos/\d+/[^"]+)"""",
            ),
        )
        VideoSource.XASIAT -> GenericTubeClient(
            source = source,
            homePaths = { p ->
                if (p <= 1) listOf("/latest-updates/", "/") else listOf("/latest-updates/$p/")
            },
            searchPath = { q, p ->
                if (p <= 1) "/search/$q/" else "/search/$q/$p/"
            },
            linkPatterns = listOf(
                """href="(https?://(?:www\.)?xasiat\.com/videos/\d+/[^"]+)"""",
                """href="(/videos/\d+/[^"]+)"""",
            ),
        )
        VideoSource.KALDAGAN,
        VideoSource.LOOTEDPINAY,
        -> WordPressTubeClient(source)
        VideoSource.PINAYUM,
        VideoSource.PWERTA,
        -> PhPinaySiteClient(source)
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

/** Reject URLs with empty host (e.g. https://.etvp.cc/...) or other unusable forms. */
internal fun isValidMediaUrl(url: String): Boolean {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    // Explicit broken CDN pattern from turbovid dead embeds
    if (url.contains("://.", ignoreCase = false)) return false
    return try {
        val u = java.net.URI(url)
        val host = u.host.orEmpty()
        host.isNotBlank() && !host.startsWith(".") && host.contains('.')
    } catch (_: Exception) {
        false
    }
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
        // Reject broken hosts like https://.etvp.cc/uploads/... (empty subdomain).
        if (!isValidMediaUrl(url)) return
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

    // JWPlayer / Video.js / JS player file: "..." or src: "..." or source: "..."
    val jsFile = Pattern.compile(
        """(?:file|source|video_url|videoUrl|src)\s*:\s*["']((?:https?:)?//[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    while (jsFile.find()) {
        val u = jsFile.group(1) ?: continue
        add(labelFor(u), u)
    }

    val dataVid = Pattern.compile(
        """data-(?:video|src|file|stream)=["']((?:https?:)?//[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(html)
    while (dataVid.find()) {
        val u = dataVid.group(1) ?: continue
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
        val rate = json.optString("rate").takeIf { it.isNotBlank() }?.let { "$it ★" } ?: "—"
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
                ?: "—"
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration.trim(),
                    resolution = "HD",
                    views = "—",
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
            views = "—",
            ratingPercent = "—",
            duration = "—",
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

        // 1) Parse mediaDefinitions (contains the real full video streams)
        val def = NetworkClient.matchFirst(html, """"mediaDefinitions"\s*:\s*(\[[\s\S]*?\])""")
        if (def != null) {
            try {
                val arr = JSONArray(def)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val format = o.optString("format")
                    var videoUrl = o.optString("videoUrl").replace("\\/", "/").replace("&amp;", "&")
                    if (videoUrl.isBlank()) continue
                    if (videoUrl.startsWith("/")) {
                        videoUrl = NetworkClient.absoluteUrl(source.baseUrl, videoUrl)
                    }
                    if (videoUrl.contains("ev-") || videoUrl.contains("preview") || videoUrl.contains("sample")) continue
                    // Resolve remote JSON that points to real files
                    if (o.optBoolean("remote", false) || videoUrl.contains("/media/")) {
                        try {
                            val body = NetworkClient.get(videoUrl, source.baseUrl + "/")
                            if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                                parseRemoteMediaJson(body, options)
                                continue
                            }
                            if (body.contains("#EXTM3U")) {
                                options.putIfAbsent("Auto (HLS)", StreamOption("Auto (HLS)", videoUrl))
                                continue
                            }
                        } catch (_: Exception) {
                        }
                    }
                    val label = if (format == "hls") "Auto (HLS)" else "MP4"
                    options.putIfAbsent(label, StreamOption(label, videoUrl))
                }
            } catch (_: Exception) {
            }
        }

        // 2) Fallback direct full CDN mp4s embedded in page (excluding 9-sec ev- preview clips)
        val cdn = Pattern.compile(
            """(https://(?!ev-)[^"'\\\s]+\.mp4[^"'\\\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (cdn.find()) {
            val u = cdn.group(1)?.replace("&amp;", "&") ?: continue
            if (u.contains("ev-") || u.contains("preview") || u.contains("sample")) continue
            val label = when {
                "1080" in u -> "1080p"
                "720" in u -> "720p"
                "480" in u -> "480p"
                "360" in u -> "360p"
                else -> "MP4"
            }
            options.putIfAbsent(label, StreamOption(label, u))
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
// Tube8 (Direct scraper)
// ---------------------------------------------------------------------------

class Tube8Client : VideoSourceClient {
    override val source = VideoSource.TUBE8

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = search("", page)

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val url = if (q.isBlank()) {
            if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        } else {
            "${source.baseUrl}/searches.html?q=$q&page=$p"
        }
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - Tube8").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = collectMp4AndHls(html, pageUrl)
        if (streams.isEmpty()) {
            throw IllegalStateException("Video has been removed")
        }
        val related = parseListing(html).filter { it.pageUrl != pageUrl }.take(18)
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val matcher = Pattern.compile(
            """<a\s+[^>]*href=["']((?:https?://[^"']*)?/[^"']+)["'][^>]*>[\s\S]{0,800}?<img[^>]+(?:data-src|data-thumb|src)=["']([^"']+)["'][^>]*alt=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var i = 0
        while (matcher.find()) {
            val href = matcher.group(1) ?: continue
            val thumb = matcher.group(2) ?: ""
            val title = NetworkClient.decodeHtml(matcher.group(3) ?: "").trim()
            if (href.contains("/categories/") || href.contains("/tags/") || href.contains("javascript:")) continue
            val fullUrl = NetworkClient.absoluteUrl(source.baseUrl, href)
            val id = fullUrl.trimEnd('/').substringAfterLast('/')
            if (id.isBlank() || !seen.add(id)) continue
            if (title.isNotBlank()) {
                out.add(
                    VideoItem(
                        id = id,
                        title = title,
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = source.label,
                        gradientSeed = i++,
                        pageUrl = fullUrl,
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
            }
        }
        if (out.isEmpty()) {
            val m2 = Pattern.compile(
                """href=["']((?:https?://[^"']*)?/[^"']+)["'][^>]*title=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (m2.find()) {
                val href = m2.group(1) ?: continue
                val title = NetworkClient.decodeHtml(m2.group(2) ?: "").trim()
                if (href.contains("categories") || href.contains("tags") || title.isBlank()) continue
                val fullUrl = NetworkClient.absoluteUrl(source.baseUrl, href)
                val id = fullUrl.trimEnd('/').substringAfterLast('/')
                if (id.isNotBlank() && seen.add(id)) {
                    out.add(
                        VideoItem(
                            id = id,
                            title = title,
                            duration = "—",
                            resolution = "HD",
                            views = "—",
                            category = source.label,
                            gradientSeed = i++,
                            pageUrl = fullUrl,
                            thumbnailUrl = "",
                            sourceId = source.id,
                        ),
                    )
                }
            }
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Tiava (Global Provider)
// ---------------------------------------------------------------------------

class TiavaClient : VideoSourceClient {
    override val source = VideoSource.TIAVA

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = search("", page)

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val url = if (q.isBlank()) {
            if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/videos/$p/"
        } else {
            "${source.baseUrl}/search/$q/$p/"
        }
        parseListing(NetworkClient.get(url, source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - Tiava").substringBefore(" | ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = collectMp4AndHls(html, pageUrl)
        if (streams.isEmpty()) {
            throw IllegalStateException("Video has been removed")
        }
        val related = parseListing(html).filter { it.pageUrl != pageUrl }.take(18)
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        val matcher = Pattern.compile(
            """<a\s+[^>]*href=["']((?:https?://[^"']*)?/[^"']+)["'][^>]*>[\s\S]{0,800}?<img[^>]+(?:data-src|data-lazy-src|src)=["']([^"']+)["'][^>]*alt=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        var i = 0
        while (matcher.find()) {
            val href = matcher.group(1) ?: continue
            val thumb = matcher.group(2) ?: ""
            val title = NetworkClient.decodeHtml(matcher.group(3) ?: "").trim()
            if (href.contains("/categories/") || href.contains("/tags/") || href.contains("javascript:")) continue
            val fullUrl = NetworkClient.absoluteUrl(source.baseUrl, href)
            val id = fullUrl.trimEnd('/').substringAfterLast('/')
            if (id.isBlank() || !seen.add(id)) continue
            if (title.isNotBlank()) {
                out.add(
                    VideoItem(
                        id = id,
                        title = title,
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = source.label,
                        gradientSeed = i++,
                        pageUrl = fullUrl,
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
            }
        }
        return out
    }
}



// ---------------------------------------------------------------------------
// WordPress tubes (Indo18 / PinayOT) — article cards + direct mp4/source
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

        // Follow iframe embeds (Indo18 → jomblo → playmogo/dood, etc.) — resolve to direct media.
        var depth = 0
        var currentHtml = html
        var currentRef = pageUrl
        val queue = ArrayDeque<String>()
        collectIframeSrcs(html)
            .map { NetworkClient.absoluteUrl(pageUrl, it) }
            .filter { isPlayerIframe(it) }
            .forEach { queue.add(it) }
        // Also itemprop embedURL (BokepIndoHot meta)
        NetworkClient.matchFirst(html, """itemprop="embedURL"\s+content="([^"]+)"""")
            ?.let { emb ->
                val u = if (emb.startsWith("http://")) "https://" + emb.removePrefix("http://") else emb
                if (isPlayerIframe(u) && u !in queue) queue.add(u)
            }

        while (streams.isEmpty() && queue.isNotEmpty() && depth < 6) {
            val next = queue.removeFirst()
            depth++
            // Clean-tube base64 payload in iframe URL
            val fromQ = extractCleanTubeStreams(next)
            if (fromQ.isNotEmpty()) {
                streams = fromQ
                break
            }
            // BokepIndoHot: playerbtc is a mirror hub → turbovid / dood / …
            if (next.contains("playerbtc", true)) {
                val btc = resolvePlayerBtcEmbed(next, pageUrl)
                if (btc.isNotEmpty()) {
                    streams = btc
                    break
                }
            }
            // DoodStream family (playmogo / doodstream)
            if (isDoodHost(next)) {
                val dood = resolveDoodStreamEmbed(next, currentRef)
                if (dood.isNotEmpty()) {
                    streams = dood
                    break
                }
            }
            if (next.contains("turbovid") || next.contains("turboviplay")) {
                val turbo = resolveTurbovidEmbed(next, currentRef)
                if (turbo.isNotEmpty()) {
                    streams = turbo
                    break
                }
            }
            if (isStreamWishHost(next)) {
                val wish = resolveStreamWishEmbed(next, currentRef)
                if (wish.isNotEmpty()) {
                    streams = wish
                    break
                }
            }
            try {
                currentHtml = NetworkClient.get(next, currentRef)
                currentRef = next
                val nestedClean = extractCleanTubeStreams(currentHtml)
                val nested = when {
                    nestedClean.isNotEmpty() -> nestedClean
                    else -> collectMp4AndHls(currentHtml)
                        .filter { !it.url.contains("trailer", ignoreCase = true) }
                }
                if (nested.isNotEmpty()) {
                    streams = nested
                    break
                }
                // Nested iframes (jomblo → playmogo)
                collectIframeSrcs(currentHtml)
                    .map { NetworkClient.absoluteUrl(next, it) }
                    .filter { isPlayerIframe(it) }
                    .forEach { queue.add(it) }
                // Dood pass on this page
                if (currentHtml.contains("pass_md5", true)) {
                    val dood = resolveDoodStreamEmbed(next, pageUrl)
                    if (dood.isNotEmpty()) {
                        streams = dood
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }

        streams = streams
            .map { it.copy(url = NetworkClient.sanitizeMediaUrl(it.url)) }
            .filter { !it.label.equals("Embed", true) }
            .distinctBy { it.url }
        // Page-local related; DataRepository enriches with title/search if thin.
        val related = parseListing(html).filter { it.pageUrl != pageUrl }.take(18)

        // BokepBox / BokepIndoHot / PinayOT etc. often use dead playerbtc / dood hosts.
        // Recover playback by mirroring the same clip from XVideos as a last resort.
        if (streams.isEmpty()) {
            xvideosMirrorFallback(
                title = title,
                pageUrl = pageUrl,
                source = source,
                thumb = thumb,
                related = related,
            )?.let { return@withContext it }
        }
        // No WebView embed fallback — user wants real streams only.
        if (streams.isEmpty()) {
            throw IllegalStateException("No direct stream on ${source.label}")
        }


        // Skip slow HEAD size probes for cross-host Clean Tube mp4s (was delaying / stalling play).
        // LootedPinay/pinaydeepweb files are ~100–200MB progressive — HEAD just adds lag.
        val distinct = streams.distinctBy { it.url }
        val sized = if (
            distinct.any {
                it.url.contains("drkogyi", true) ||
                    it.url.contains("/uploads/", true) ||
                    it.url.contains("pinaydeepweb", true) ||
                    it.url.contains("lootedpinay", true) ||
                    it.url.contains("wp-content", true)
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
            embedUrl = null,
        )
    }

    private fun isPlayerIframe(src: String): Boolean {
        val s = src.lowercase()
        if (s.isBlank()) return false
        if (s.contains("googletag") || s.contains("doubleclick") || s.contains("histats")) return false
        if (s.contains("a-ads.com") || s.contains("juicyads") || s.contains("exoclick")) return false
        if (s.contains("/ad") && !s.contains("/admin")) return false
        if (s.contains("dazedengage")) return false
        return true
    }

    private fun collectIframeSrcs(html: String): List<String> {
        val out = mutableListOf<String>()
        val m = Pattern.compile(
            """iframe[^>]+(?:data-src|data-lazy-src|data-url|src)=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val src = m.group(1) ?: continue
            if (src.isNotBlank() && !src.startsWith("about:") && !src.startsWith("javascript:")) {
                out.add(src)
            }
        }
        return out.distinct()
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

        // 6) Universal WP / KVS / WordPress card parser for GairahTV, BokepBoz, XBurma, ProBokep, KingBokep, HeiBokep, etc.
        if (items.size < 12) {
            val cardMatcher = Pattern.compile(
                """<a\s+[^>]*href=["']((?:https?://[^"']*)?/[^"']+)["'][^>]*>[\s\S]{0,800}?<img[^>]+(?:data-src|data-lazy-src|data-original|src)=["']([^"']+\.(?:jpg|jpeg|png|webp|gif)[^"']*)["'][^>]*>""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (cardMatcher.find() && items.size < 60) {
                val href = cardMatcher.group(1).orEmpty()
                val thumb = cardMatcher.group(2).orEmpty()
                val win = html.substring((cardMatcher.start() - 100).coerceAtLeast(0), (cardMatcher.end() + 600).coerceAtMost(html.length))
                val title = NetworkClient.matchFirst(win, """alt=["']([^"']{2,})["']""")
                    ?: NetworkClient.matchFirst(win, """title=["']([^"']{2,})["']""")
                    ?: NetworkClient.matchFirst(win, """<h[234][^>]*>\s*<a[^>]*>([^<]{2,})</a>""")
                    ?: href.trimEnd('/').substringAfterLast('/').replace('-', ' ')
                if (href.isNotBlank() && title.length >= 2) {
                    addItem(href, title, thumb, windowHtml = win)
                }
            }
        }

        return items
    }
}

// ---------------------------------------------------------------------------
// PinayFlix — listing thumbs work; stream via mp4 in page / related uploads
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
                // Playerjs packed config → file: "https://…m3u8?…"
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
            views = "—",
            ratingPercent = "—",
            duration = "—",
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
        // Watch page has no og:image — resolve cover via listing/search or R2 mp4 path.
        var thumb = ThumbnailResolver.fromPage(pageUrl)
        if (thumb.isBlank()) {
            thumb = NetworkClient.sanitizeMediaUrl(
                NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
                    .orEmpty(),
            )
        }
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
            views = "—",
            ratingPercent = "—",
            duration = "—",
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
            // Img is inside the <a> after href; keep a wide window so long Unicode
            // filenames still match.
            val window = html.substring(
                (videoLinks.start() - 100).coerceAtLeast(0),
                (videoLinks.start() + 1400).coerceAtMost(html.length),
            )
            // Listing thumbs include spaces / Myanmar text — capture full attribute value.
            val rawThumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://img\.buumal\.com/[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
            val thumb = NetworkClient.sanitizeMediaUrl(rawThumb)
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
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// XBurma (Direct Myanmar Tube scraper)
// ---------------------------------------------------------------------------

class XBurmaClient : VideoSourceClient {
    override val source = VideoSource.XBURMA

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val paths = if (p <= 1) {
            listOf("/", "/videos/", "/latest-updates/", "/most-recent/")
        } else {
            listOf("/page/$p/", "/videos/page/$p/", "/latest-updates/$p/", "/?page=$p")
        }
        val out = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        for (path in paths) {
            try {
                val html = NetworkClient.get(source.baseUrl + path, source.baseUrl)
                val items = parseListing(html)
                for (item in items) {
                    if (seen.add(item.id)) out.add(item)
                }
                if (out.size >= 12) break
            } catch (_: Exception) {
            }
        }
        out
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
                ?: "Video",
        ).substringBefore(" - XBurma").substringBefore(" | ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = collectMp4AndHls(html, pageUrl)
        if (streams.isEmpty()) {
            throw IllegalStateException("Video has been removed")
        }
        val related = parseListing(html).filter { it.pageUrl != pageUrl }.take(18)
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = source.label,
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0

        // Match all <a> tags with href and nearby img data-src/src
        val pattern = Pattern.compile(
            """<a\s+[^>]*href=["']((?:https?://[^"']*)?/[^"']+)["'][^>]*>[\s\S]{0,1000}?<img[^>]+(?:data-src|data-lazy-src|data-original|src)=["']([^"']+)["'][^>]*>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)

        while (pattern.find()) {
            val href = pattern.group(1) ?: continue
            val thumb = pattern.group(2) ?: ""
            if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/") || href.contains("#") || href.contains("javascript:")) continue
            val fullUrl = NetworkClient.absoluteUrl(source.baseUrl, href)
            val id = fullUrl.trimEnd('/').substringAfterLast('/')
            if (id.length < 2 || !seen.add(id)) continue

            val win = html.substring((pattern.start() - 100).coerceAtLeast(0), (pattern.end() + 600).coerceAtMost(html.length))
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(win, """alt=["']([^"']{2,})["']""")
                    ?: NetworkClient.matchFirst(win, """title=["']([^"']{2,})["']""")
                    ?: NetworkClient.matchFirst(win, """<h[234][^>]*>\s*<a[^>]*>([^<]{2,})</a>""")
                    ?: id.replace('-', ' ')
            ).trim()

            val duration = NetworkClient.matchFirst(win, """class="duration"[^>]*>([^<]+)<""")
                ?: NetworkClient.matchFirst(win, """>(\d{1,2}:\d{2}(?::\d{2})?)</""")
                ?: "—"

            out.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration,
                    resolution = "HD",
                    views = "—",
                    category = source.label,
                    gradientSeed = index++,
                    pageUrl = fullUrl,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
        }

        // Secondary fallback pattern for loose post links
        if (out.isEmpty()) {
            val m2 = Pattern.compile(
                """href=["']((?:https?://[^"']*)?/[^"']+)["'][^>]*title=["']([^"']{3,})["']""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (m2.find()) {
                val href = m2.group(1) ?: continue
                val title = NetworkClient.decodeHtml(m2.group(2) ?: "").trim()
                if (href.contains("/category/") || href.contains("/tag/") || href.contains("/page/") || title.isBlank()) continue
                val fullUrl = NetworkClient.absoluteUrl(source.baseUrl, href)
                val id = fullUrl.trimEnd('/').substringAfterLast('/')
                if (id.length >= 2 && seen.add(id)) {
                    out.add(
                        VideoItem(
                            id = id,
                            title = title,
                            duration = "—",
                            resolution = "HD",
                            views = "—",
                            category = source.label,
                            gradientSeed = index++,
                            pageUrl = fullUrl,
                            thumbnailUrl = "",
                            sourceId = source.id,
                        ),
                    )
                }
            }
        }
        return out
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
            .filter { isPlayableTubeMedia(it.url) }
        if (streams.isEmpty()) {
            val iframe = NetworkClient.matchFirst(html, """iframe[^>]+src=["']([^"']+)["']""")
            if (!iframe.isNullOrBlank()) {
                try {
                    val emb = NetworkClient.get(NetworkClient.absoluteUrl(pageUrl, iframe), pageUrl)
                    streams = collectMp4AndHls(emb).filter { isPlayableTubeMedia(it.url) }
                } catch (_: Exception) { }
            }
        }
        if (streams.isEmpty()) {
            val high = NetworkClient.matchFirst(html, """setVideoUrlHigh\(['"]([^'"]+)['"]\)""")
            val low = NetworkClient.matchFirst(html, """setVideoUrlLow\(['"]([^'"]+)['"]\)""")
            val hls = NetworkClient.matchFirst(html, """setVideoHLS\(['"]([^'"]+)['"]\)""")
            // KVS flashvars video_url / video_alt_url(N)
            val flash = Pattern.compile(
                """video(?:_alt)?_url(?:\d+)?\s*:\s*['"]([^'"]+)['"]""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            val flashUrls = mutableListOf<String>()
            while (flash.find()) flash.group(1)?.let { flashUrls.add(it) }
            streams = buildList {
                high?.let { add(StreamOption(NetworkClient.guessQualityLabel(it, "High"), it)) }
                low?.let { add(StreamOption(NetworkClient.guessQualityLabel(it, "Low"), it)) }
                hls?.let { add(StreamOption("Auto", it)) }
                for (u in flashUrls) {
                    if (isPlayableTubeMedia(u)) {
                        add(StreamOption(NetworkClient.guessQualityLabel(u, "MP4"), u))
                    }
                }
            }
        }
        // Prefer real get_file / CDN mp4s over cast.preview fluff; resolve redirects once.
        streams = streams
            .map { opt ->
                var u = NetworkClient.sanitizeMediaUrl(opt.url)
                if (u.contains("get_file", true) || u.contains("/videos/", true) && u.endsWith("/")) {
                    u = try {
                        NetworkClient.resolveFinalUrl(u, pageUrl)
                    } catch (_: Exception) {
                        u
                    }
                }
                opt.copy(
                    url = u,
                    label = NetworkClient.guessQualityLabel(u, opt.label).ifBlank { opt.label },
                )
            }
            .filter { isPlayableTubeMedia(it.url) }
            .distinctBy { it.url }
            // Prefer higher-bitrate get_file / CDN over cast.xxxfiles previews
            .sortedByDescending { s ->
                when {
                    s.url.contains("get_file", true) || s.url.contains("ahcdn", true) -> 3
                    s.url.contains("_720", true) || s.url.contains("720p", true) -> 2
                    s.url.contains("_480", true) || s.url.contains("480p", true) -> 1
                    s.url.contains("cast.", true) || s.url.contains("preview", true) -> -2
                    else -> 0
                }
            }

        if (streams.isEmpty()) {
            throw IllegalStateException("No playable stream on ${source.label}")
        }
        val related = parseListing(html)
            .filter {
                it.pageUrl != pageUrl &&
                    it.title.isNotBlank() &&
                    it.title.length >= 3
            }
            .take(18)
        val labeled = expandMultiQualityStreams(streams)
        val preferred = pickDefaultStream(labeled) ?: labeled.minByOrNull {
            streamQualityRank(it.label).let { r -> if (r <= 1) 9999 else r }
        } ?: labeled.first()
        VideoDetails(
            streamUrl = preferred.url,
            streams = labeled,
            title = title,
            uploader = source.label,
            views = "—",
            ratingPercent = "—",
            duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "—",
            resolution = preferred.label,
            tags = emptyList(),
            related = related,
            thumbnailUrl = thumb,
        )
    }

    private fun isPlayableTubeMedia(url: String): Boolean {
        if (url.isBlank()) return false
        val u = url.lowercase()
        if (u.contains("preview_480") || u.contains("preview_240")) return false
        if (u.contains("_vthumb") || u.contains("trailer") && !u.contains("full")) return false
        if (u.contains("cast.") && u.contains("preview")) return false
        if (u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png") || u.endsWith(".webp")) {
            return false
        }
        // Reject false-positive "mp4.jpg" poster URLs
        if (u.contains(".mp4.jpg") || u.contains(".mp4.png")) return false
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains("get_file")
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
                    ?: "—"
                items.add(
                    VideoItem(
                        id = id.removeSuffix(".html"),
                        title = title,
                        duration = duration.trim(),
                        resolution = "HD",
                        views = "—",
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
// JavFree.me — /id/code cards + direct CDN mp4
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
            // JavFree links only open premium file-host pages (ExtMatrix/tma.cx) — mirror the
            // JAV code from XVideos, which reliably has the same title.
            val code = Regex("""[A-Za-z]{2,6}-?\d{2,5}""").find(title)?.value
            xvideosMirrorFallback(
                title = title,
                pageUrl = pageUrl,
                source = source,
                thumb = thumb,
                related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
                extraQueries = listOfNotNull(code),
            )?.let { return@withContext it }
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
// 123AV — /en/v/slug listing + javplayer embed
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

        // Global id → cover map. Featured/title anchors are far from <img>; Alpine cards use :src.
        // Covers look like: https://icdn.123av.me/img2/s360/{xx}/{id}/cover.jpg?...
        val thumbById = HashMap<String, String>()
        val coverRe = Pattern.compile(
            """(https?://icdn\.123av\.[^"'\\\s]+/([a-z0-9-]+)/cover\.(?:jpg|jpeg|png|webp)[^"'\\\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (coverRe.find()) {
            val url = coverRe.group(1) ?: continue
            val id = coverRe.group(2)?.lowercase() ?: continue
            // Prefer smaller s360 over s500 when both exist (first wins is fine either way)
            thumbById.putIfAbsent(id, url)
        }
        // vside background-image covers
        val bgRe = Pattern.compile(
            """background-image:url\(['"]?(https?://icdn\.123av\.[^"')]+/([a-z0-9-]+)/cover[^"')]+)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (bgRe.find()) {
            val url = bgRe.group(1) ?: continue
            val id = bgRe.group(2)?.lowercase() ?: continue
            thumbById.putIfAbsent(id, url)
        }

        fun thumbFor(id: String, window: String): String {
            thumbById[id.lowercase()]?.let { return it }
            return NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://icdn\.123av\.[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """background-image:url\(['"]?(https?://icdn\.123av\.[^"')]+)""",
            ).orEmpty()
        }

        fun add(
            path: String,
            id: String,
            titleRaw: String,
            window: String,
        ) {
            if (!seen.add(id)) return
            val title = NetworkClient.decodeHtml(titleRaw)
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifBlank { id.uppercase() }
            // Skip Alpine.js empty shells (x-text filled client-side)
            if (title.equals("item.label", true) || title.isBlank()) return
            val duration = NetworkClient.matchFirst(window, """class="(?:card|featured|vside)__dur"[^>]*>([^<]+)<""")
                ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)<""")
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
                    thumbnailUrl = thumbFor(id, window),
                    sourceId = source.id,
                ),
            )
        }

        // 1) Main grid: <h3 class="card__title"><a href="/en/v/id">Title</a>
        val cardTitle = Pattern.compile(
            """class="card__title"[^>]*>\s*<a[^>]+href="((?:https?://123av\.(?:com|me))?/en/v/([a-z0-9-]+))"[^>]*>([^<]+)</a>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (cardTitle.find()) {
            val path = cardTitle.group(1) ?: continue
            val id = cardTitle.group(2) ?: continue
            val window = html.substring(
                (cardTitle.start() - 900).coerceAtLeast(0),
                (cardTitle.start() + 200).coerceAtMost(html.length),
            )
            add(path, id, cardTitle.group(3).orEmpty(), window)
            if (items.size >= 60) return items
        }

        // 2) Featured swiper: <a href="/en/v/id"><h2 class="featured__title">Title</h2>
        val featured = Pattern.compile(
            """href="((?:https?://123av\.(?:com|me))?/en/v/([a-z0-9-]+))"[^>]*>\s*<h2 class="featured__title">([^<]+)</h2>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (featured.find()) {
            val path = featured.group(1) ?: continue
            val id = featured.group(2) ?: continue
            val window = html.substring(
                (featured.start() - 1200).coerceAtLeast(0),
                (featured.start() + 300).coerceAtMost(html.length),
            )
            add(path, id, featured.group(3).orEmpty(), window)
            if (items.size >= 60) return items
        }

        // 3) Related / sidebar: vside__title
        val vside = Pattern.compile(
            """href="((?:https?://123av\.(?:com|me))?/en/v/([a-z0-9-]+))"[^>]*>[\s\S]{0,500}?class="vside__title">([^<]+)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (vside.find()) {
            val path = vside.group(1) ?: continue
            val id = vside.group(2) ?: continue
            val window = html.substring(
                vside.start(),
                (vside.start() + 600).coerceAtMost(html.length),
            )
            add(path, id, vside.group(3).orEmpty(), window)
            if (items.size >= 60) return items
        }

        // 4) Remaining ids that only appear as poster/href — still attach mapped thumbs
        if (items.size < 30) {
            val m = Pattern.compile(
                """href="((?:https?://123av\.(?:com|me))?/en/v/([a-z0-9-]+))"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (m.find()) {
                val path = m.group(1) ?: continue
                val id = m.group(2) ?: continue
                if (seen.contains(id)) continue
                val window = html.substring(
                    (m.start() - 200).coerceAtLeast(0),
                    (m.start() + 900).coerceAtMost(html.length),
                )
                val nearTitle = NetworkClient.matchFirst(
                    window,
                    """(?:card__title|featured__title|vside__title)[^>]*>(?:<a[^>]*>)?([^<]{3,})""",
                ) ?: id.uppercase()
                add(path, id, nearTitle, window)
                if (items.size >= 60) break
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// JavSeen — category/list pages
// ---------------------------------------------------------------------------

class JavSeenClient : VideoSourceClient {
    override val source = VideoSource.JAVSEEN
    /** Live listing host (javseen.tv homepage is empty; videos are on javseenz.tv). */
    private val listBase = "https://javseenz.tv"

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        // Prefer new-release / category feeds (less sticky sidebar dupes than bare /?page=).
        val urls = if (p <= 1) {
            listOf("$listBase/", "$listBase/new-release/", "https://javseen.tv/")
        } else {
            listOf(
                "$listBase/?page=$p",
                "$listBase/new-release/?page=$p",
                "$listBase/page/$p/",
            )
        }
        var last = emptyList<VideoItem>()
        for (url in urls) {
            try {
                val items = parseListing(NetworkClient.get(url, listBase), page = p)
                if (items.isNotEmpty()) return@withContext items
                last = items
            } catch (_: Exception) {
            }
        }
        last.ifEmpty { throw IllegalStateException("Could not load JavSeen") }
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

    private fun parseListing(html: String, page: Int = 1): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Prefer li#video-{id} blocks (main grid). Sidebar/featured also uses plain hrefs.
        val cardRe = Pattern.compile(
            """id="video-(\d+)"[\s\S]{0,1200}?href="((?:https?://javseenz?\.tv)?/\1/([^"]+)/?)"[\s\S]{0,600}?src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (cardRe.find()) {
            val id = cardRe.group(1) ?: continue
            if (!seen.add(id)) continue
            val path = cardRe.group(2) ?: continue
            val slug = cardRe.group(3).orEmpty()
            val thumb = cardRe.group(4).orEmpty()
            val window = html.substring(
                (cardRe.start() - 40).coerceAtLeast(0),
                (cardRe.start() + 500).coerceAtMost(html.length),
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
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "JavSeen",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(listBase, path),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) break
        }
        if (items.isEmpty()) {
            val re = Pattern.compile(
                """href="((?:https?://javseenz?\.tv)?/(\d{4,})/([^"]+)/?)"[\s\S]{0,500}?src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (re.find()) {
                val path = re.group(1) ?: continue
                val id = re.group(2) ?: continue
                if (!seen.add(id)) continue
                val slug = re.group(3).orEmpty()
                val thumb = re.group(4).orEmpty()
                items.add(
                    VideoItem(
                        id = id,
                        title = slug.replace('-', ' '),
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = "JavSeen",
                        gradientSeed = index++,
                        pageUrl = NetworkClient.absoluteUrl(listBase, path),
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 48) break
            }
        }
        // Page 2+ still embeds "hot" strip of newest IDs at the top — drop a small
        // head chunk that repeats page-1 featured items when we already have many.
        if (page > 1 && items.size > 16) {
            val head = items.take(8).map { it.id }.toSet()
            val tail = items.drop(8)
            // If head ids look contiguous-high (featured), prefer rest of list.
            if (tail.isNotEmpty()) {
                val headMax = head.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L
                val tailMax = tail.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0L
                if (headMax >= tailMax) {
                    return tail
                }
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// BabeXTube (Myanmar) — /mm-porn/ slugs + sub.babextube.com mp4
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
            views = "—",
            ratingPercent = "—",
            duration = "—",
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
                    duration = "—",
                    resolution = "HD",
                    views = "—",
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
// BebasIndo — /video/slug listing + /api/iframe player
// ---------------------------------------------------------------------------

class BebasIndoClient : VideoSourceClient {
    override val source = VideoSource.BEBASINDO

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val urls = if (p <= 1) {
            listOf(
                "${source.baseUrl}/category/indonesia/",
                "${source.baseUrl}/",
                "${source.baseUrl}/category/terbaru/",
            )
        } else {
            listOf(
                "${source.baseUrl}/category/indonesia/page/$p/",
                "${source.baseUrl}/page/$p/",
                "${source.baseUrl}/category/terbaru/page/$p/",
                "${source.baseUrl}/category/indonesia/?page=$p",
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
            listOf("${source.baseUrl}/?s=$q", "${source.baseUrl}/search/$q/")
        } else {
            listOf("${source.baseUrl}/page/$p/?s=$q", "${source.baseUrl}/?s=$q&paged=$p")
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
                    duration = "—",
                    resolution = "HD",
                    views = "—",
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
// NontonBokep — slug cards + base64 iframe → CDN mp4
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
        // iframe src is often base64 of embed host (303in.top / 200cdn)
        val iframeRe = Pattern.compile(
            """iframe[^>]+src=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val embeds = mutableListOf<String>()
        while (iframeRe.find()) {
            val raw = iframeRe.group(1) ?: continue
            if (raw.contains("ad.") || raw.contains("a-ads") || raw.contains("googletag")) continue
            embeds.add(decodeMaybeBase64Url(raw))
        }
        for (embed in embeds.distinct()) {
            if (!embed.startsWith("http")) continue
            try {
                if (embed.contains(".mp4", true)) {
                    streams = listOf(StreamOption("MP4", embed)) + streams
                    break
                }
                val dood = resolveDoodStreamEmbed(embed, pageUrl)
                if (dood.isNotEmpty()) {
                    streams = dood
                    break
                }
                val embHtml = NetworkClient.get(embed, pageUrl)
                var nested = collectMp4AndHls(embHtml, embed)
                // FluidPlayer / source src=
                if (nested.isEmpty()) {
                    val srcTag = Pattern.compile(
                        """<source[^>]+src=["'](https?://[^"']+)["']""",
                        Pattern.CASE_INSENSITIVE,
                    ).matcher(embHtml)
                    while (srcTag.find()) {
                        val u = srcTag.group(1) ?: continue
                        nested = listOf(StreamOption(if (u.contains("m3u8")) "Auto (HLS)" else "MP4", u))
                    }
                }
                if (nested.isEmpty()) {
                    val id = embed.trimEnd('/').substringAfterLast('/').substringBefore('?')
                    if (id.all { it.isDigit() } && id.length >= 6) {
                        nested = listOf(
                            "https://embed.200cdn.top/$id.mp4",
                        ).map { StreamOption("MP4", it) }
                    }
                }
                if (nested.isNotEmpty()) {
                    streams = nested
                    break
                }
            } catch (_: Exception) {
            }
        }
        // embed.200cdn.top only redirects to static.*.id.200cdn.top with playto.303in.top referer.
        // Without resolve + correct referer ExoPlayer gets 403/404.
        streams = streams
            .map { opt ->
                val clean = NetworkClient.sanitizeMediaUrl(opt.url)
                val resolved = try {
                    if (clean.contains("200cdn", true) || clean.contains("303in", true)) {
                        NetworkClient.resolveFinalUrl(clean, "https://playto.303in.top/")
                    } else {
                        clean
                    }
                } catch (_: Exception) {
                    clean
                }
                opt.copy(url = resolved)
            }
            .distinctBy { it.url }
            .filter { !it.label.equals("Embed", true) }
        if (streams.isEmpty()) {
            throw IllegalStateException("No stream on NontonBokep (CDN may block this network)")
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "NontonBokep",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
            thumbnailUrl = thumb,
            embedUrl = null,
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
                    duration = "—",
                    resolution = "HD",
                    views = "—",
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
                        duration = "—",
                        resolution = "HD",
                        views = "—",
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
// PornKai — aggregates XVideos / TXXX embeds via /view?key=
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
        // Keep only real media URLs; a bare xvideos/txxx embed page URL is not playable
        // by ExoPlayer, so drop "Embed" placeholders and mirror from XVideos instead.
        streams = streams
            .filter { !it.label.equals("Embed", true) }
            .distinctBy { it.url }
        if (streams.isEmpty()) {
            xvideosMirrorFallback(
                title = title,
                pageUrl = pageUrl,
                source = source,
                thumb = thumb,
                related = parseListing(html).filter { it.pageUrl != pageUrl }.take(16),
            )?.let { return@withContext it }
            throw IllegalStateException("No playable stream on PornKai")
        }
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
// ThaiPornTV — data-enc XOR-17 ? techvids HLS
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
            views = "—",
            ratingPercent = "—",
            duration = "—",
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
                    duration = "—",
                    resolution = "HD",
                    views = "—",
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
// iXXX — tube search aggregator (mobile UA; falls back to Thai XVideos feed)
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
        // Cloudflare often challenges desktop scrapers — use XVideos Thai results rebranded.
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
            views = "—",
            ratingPercent = "—",
            duration = "—",
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
                        duration = "—",
                        resolution = "HD",
                        views = "—",
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

