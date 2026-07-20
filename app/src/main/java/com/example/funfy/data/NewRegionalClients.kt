package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// QuatVn — post slug-{id} → quatvn.stream/stream/{id}.mp4
// ---------------------------------------------------------------------------

class QuatVnClient : VideoSourceClient {
    override val source = VideoSource.QUATVN

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val url = if (p <= 1) source.baseUrl + "/" else "${source.baseUrl}/page/$p/"
        parseListing(NetworkClient.get(url, source.baseUrl))
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
        ).substringBefore("•").substringBefore("|").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val id = Regex("""-(\d+)/?$""").find(pageUrl.trimEnd('/'))?.groupValues?.get(1)
            ?: NetworkClient.matchFirst(html, """quatvn\.stream/stream/(\d+)\.""")
        var streams = collectMp4AndHls(html, source.baseUrl)
        if (!id.isNullOrBlank()) {
            val mp4 = "https://quatvn.stream/stream/$id.mp4"
            streams = listOf(StreamOption("MP4", mp4)) + streams
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on QuatVn")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "QuatVn",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb.ifBlank {
                id?.let { "https://quatvn.stream/stream/$it.webp" }.orEmpty()
            },
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val skipSlug = setOf(
            "top", "page", "category", "tag", "author", "danh-muc", "phim-sex-vn",
            "phim-sex-trung-quoc", "phim-sex-han-quoc", "phim-sex-us", "feed",
        )
        val m = Pattern.compile(
            """href="(https://quatvn\.asia/([a-z0-9-]+)-(\d+)/)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val slug = m.group(2) ?: continue
            val id = m.group(3) ?: continue
            if (slug in skipSlug) continue
            // top-10 style pages
            if (id.length <= 2 && slug == "top") continue
            if (!seen.add(id)) continue
            val window = html.substring(
                (m.start() - 120).coerceAtLeast(0),
                (m.start() + 800).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(
                        html.substring(m.start(), (m.start() + 200).coerceAtMost(html.length)),
                        """>([^<]{3,80})</a>""",
                    )
                    ?: "$slug $id",
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|data-original|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty().ifBlank { "https://quatvn.stream/stream/$id.webp" }
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = NetworkClient.matchFirst(window, """>([\d.]+[kKmM]?)</strong>\s*<span>\s*Views""")
                        ?: "—",
                    category = "QuatVn",
                    gradientSeed = index++,
                    pageUrl = href,
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
// OK.xxx — KVS tube (Thai-first home via /tags/thai/, multi-quality get_file HLS)
// ---------------------------------------------------------------------------

class OkXxxClient : VideoSourceClient {
    override val source = VideoSource.OKXXX

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        // Prefer Thai content for Thailand region; fall back to newest global.
        val paths = if (p <= 1) {
            listOf("/tags/thai/", "/search/thai/", "/newest/", "/")
        } else {
            listOf(
                "/tags/thai/$p/",
                "/search/thai/?from_videos=$p",
                "/newest/$p/",
                "/$p/",
            )
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
        val paths = if (p <= 1) {
            listOf("/search/$q/", "/tags/$q/")
        } else {
            listOf("/search/$q/?from_videos=$p", "/tags/$q/$p/")
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

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Video",
        ).substringBefore(" - ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        // get_file redirects to CDN multi-bitrate HLS — label with HLS so ExoPlayer uses HlsMediaSource
        val streams = collectMp4AndHls(html, source.baseUrl)
            .filter {
                !it.url.contains("preview", true) &&
                    !it.url.contains("_preview", true) &&
                    !it.url.contains("_vthumb", true)
            }
            .map { opt ->
                var u = opt.url
                if (u.contains("/get_file/", true) && !u.contains("?") && !u.endsWith("/")) {
                    u = "$u/"
                }
                val baseLabel = NetworkClient.guessQualityLabel(u, opt.label).ifBlank { opt.label }
                val label = if (u.contains("/get_file/", true) && !baseLabel.contains("HLS", true)) {
                    "$baseLabel (HLS)"
                } else {
                    baseLabel
                }
                StreamOption(label, u)
            }
            .distinctBy { it.url }
        if (streams.isEmpty()) throw IllegalStateException("No stream on OK.xxx")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "OK.xxx",
            views = "—",
            ratingPercent = "—",
            duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // <a href="/video/759746/" title="…"> <img … data-original="https://static.ok.xxx/…jpg" alt="…">
        val m = Pattern.compile(
            """href="((?:https?://(?:www\.)?ok\.xxx)?/video/(\d+)/[^"]*)"([^>]*)>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val path = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val attrs = m.group(3).orEmpty()
            val window = html.substring(
                m.start(),
                (m.start() + 900).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(attrs, """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                    ?: "Video $id",
            )
            val thumb = NetworkClient.matchFirst(window, """data-original="(https?://[^"]+)"""")
                ?: NetworkClient.matchFirst(
                    window,
                    """(?:data-src|src)="(https?://static\.ok\.xxx/[^"]+)"""",
                )
                ?: NetworkClient.matchFirst(
                    window,
                    """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                ).orEmpty()
            val cleanThumb = if (thumb.startsWith("data:") || thumb.contains("1x1")) "" else thumb
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = NetworkClient.matchFirst(window, """class="time"[^>]*>([^<]+)<""")
                        ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)</""")
                        ?: "—",
                    resolution = "HD",
                    views = "—",
                    category = "OK.xxx",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, path),
                    thumbnailUrl = cleanThumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 80) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// ShenNana — WP listing + admin-ajax ypm_player embed (hidden from picker)
// ---------------------------------------------------------------------------

class ShenNanaClient : VideoSourceClient {
    override val source = VideoSource.SHENNANA

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
                ?: "Video",
        ).trim()
        // Prefer 600px CDN cover; some og images 403 without shennana referer (Coil handles it).
        val slugFromUrl = pageUrl.trimEnd('/').substringAfterLast('/')
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
            .ifBlank {
                NetworkClient.matchFirst(
                    html,
                    """(https?://sn-cdn\.goodhub\.xyz/assets/images/[^"']+/600/thumbnail\.png)""",
                ).orEmpty()
            }
            .ifBlank {
                if (slugFromUrl.isNotBlank()) {
                    "https://sn-cdn.goodhub.xyz/assets/images/$slugFromUrl/600/thumbnail.png"
                } else {
                    ""
                }
            }
        val videoId = NetworkClient.matchFirst(html, """id="video"[^>]*data-id="(\d+)"""")
            ?: NetworkClient.matchFirst(html, """data-id="(\d+)"""")
        val server = NetworkClient.matchFirst(html, """data-sv="(\d+)"""") ?: "1"
        var streams = collectMp4AndHls(html, source.baseUrl)
        var embedUrl: String? = null
        if (!videoId.isNullOrBlank()) {
            try {
                val resp = NetworkClient.postForm(
                    "${source.baseUrl}/wp-admin/admin-ajax.php",
                    "action=ypm_player&id=$videoId&server=$server&sv=1&ios=false",
                    pageUrl,
                )
                val st = NetworkClient.matchFirst(resp, """"st"\s*:\s*"([^"]+)"""")
                val hy = NetworkClient.matchFirst(resp, """"hy"\s*:\s*"([^"]+)"""")
                    ?.replace("\\/", "/")
                val iframe = NetworkClient.matchFirst(resp, """"if"\s*:\s*"([^"]+)"""")
                    ?.replace("\\/", "/")
                if (!st.isNullOrBlank()) {
                    embedUrl = "https://stream.goodhub.xyz/embed/$st"
                } else if (!iframe.isNullOrBlank()) {
                    embedUrl = iframe
                } else if (!hy.isNullOrBlank()) {
                    embedUrl = hy
                }
            } catch (_: Exception) {
            }
        }
        if (streams.isEmpty() && !embedUrl.isNullOrBlank()) {
            try {
                streams = collectMp4AndHls(NetworkClient.get(embedUrl!!, pageUrl))
            } catch (_: Exception) {
            }
            if (streams.isEmpty()) streams = listOf(StreamOption("Embed", embedUrl!!))
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on ShenNana")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "ShenNana",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            // Never use stream embed URL as "image" — use CDN poster only.
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed") embedUrl else null,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Cards: <div class="video-item ..."><a title="..." href="https://shennana.com/slug/">
        //         <img class="lazyload" data-original="https://sn-cdn.../thumbnail.png"
        val m2 = Pattern.compile(
            """href="(https://shennana\.com/([a-z0-9-]{6,})/)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val skip = setOf(
            "page", "feed", "wp-json", "shen-nana", "category", "tag", "author",
            "wp-content", "wp-admin", "comments", "xmlrpc", "ai-qiu", "bai-peiyao",
            "bai-ying", "chu-mengshu", "han-tang", "contact", "privacy",
        )
        while (m2.find()) {
            val href = m2.group(1) ?: continue
            val slug = m2.group(2) ?: continue
            // Category actor pages are short single names; real videos are long slugs
            if (slug in skip || slug.length < 12) continue
            if (!seen.add(slug)) continue
            val window = html.substring(
                (m2.start() - 80).coerceAtLeast(0),
                (m2.start() + 900).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                    ?: slug.replace('-', ' '),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """data-original="(https?://[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://sn-cdn\.goodhub\.xyz/[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty().ifBlank {
                "https://sn-cdn.goodhub.xyz/assets/images/$slug/300/thumbnail.png"
            }
            // Skip 1x1 gif placeholders
            val cleanThumb = if (thumb.startsWith("data:") || thumb.contains("1x1")) {
                "https://sn-cdn.goodhub.xyz/assets/images/$slug/300/thumbnail.png"
            } else {
                thumb
            }
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "ShenNana",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = cleanThumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 80) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// Hanime — freeanime search API + page embed playback
// ---------------------------------------------------------------------------

class HanimeClient : VideoSourceClient {
    override val source = VideoSource.HANIME
    private val searchApi = "https://guest.freeanimehentai.net/api/v11/search_hvs"

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        searchInternal("a", page.coerceAtLeast(1))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        searchInternal(query.ifBlank { "a" }, page.coerceAtLeast(1))
    }

    private fun searchInternal(query: String, page: Int): List<VideoItem> {
        val q = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "$searchApi?query=$q&page=${(page - 1).coerceAtLeast(0)}"
        val body = NetworkClient.get(
            url,
            source.baseUrl,
            extraHeaders = mapOf("Accept" to "application/json"),
        )
        // API may return a large dump; page client-side for stable grids.
        val all = parseSearchJson(body)
        val pageSize = 40
        val from = ((page - 1).coerceAtLeast(0)) * pageSize
        return all.drop(from).take(pageSize).ifEmpty {
            // If server already paged, use the raw list.
            if (page <= 1) all.take(pageSize) else emptyList()
        }
    }

    private fun parseSearchJson(body: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        // Prefer real JSON parse so cover_url is not missed after long descriptions.
        try {
            val arr = when {
                body.trimStart().startsWith("[") -> org.json.JSONArray(body)
                else -> {
                    val wrapped = NetworkClient.matchFirst(body, """"hits"\s*:\s*(\[[\s\S]*\])""")
                        ?: NetworkClient.matchFirst(body, """"data"\s*:\s*(\[[\s\S]*\])""")
                    if (wrapped != null) org.json.JSONArray(wrapped) else org.json.JSONArray(body)
                }
            }
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val slug = obj.optString("slug").trim()
                if (slug.isEmpty() || !seen.add(slug)) continue
                val rawId = obj.opt("id")?.toString().orEmpty()
                val id = rawId.ifBlank { slug }
                val name = NetworkClient.decodeHtml(obj.optString("name")).ifBlank {
                    slug.replace('-', ' ')
                }
                // Title cover art (not the small poster strip).
                val cover = obj.optString("cover_url")
                    .ifBlank { obj.optString("poster_url") }
                    .replace("\\/", "/")
                    .trim()
                items.add(
                    VideoItem(
                        id = id,
                        title = name,
                        duration = "—",
                        resolution = "HD",
                        views = obj.opt("views")?.toString() ?: "—",
                        category = "Hanime",
                        gradientSeed = index++,
                        pageUrl = "https://hanime.tv/videos/hentai/$slug",
                        thumbnailUrl = cover,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 80) break
            }
        } catch (_: Exception) {
            // Fallback regex if payload is not pure JSON.
        }
        if (items.isEmpty()) {
            // Match each object loosely and pull cover_url anywhere inside it.
            val objRe = Pattern.compile("""\{[^{}]*?"slug"\s*:\s*"([^"]+)"[^{}]*?}""", Pattern.DOTALL)
            // Objects can be huge due to description — use span between slugs.
            val slugRe = Pattern.compile(""""slug"\s*:\s*"([^"]+)"""")
            val m = slugRe.matcher(body)
            val positions = mutableListOf<Pair<Int, String>>()
            while (m.find()) {
                positions.add(m.start() to (m.group(1) ?: continue))
            }
            for (idx in positions.indices) {
                val (start, slug) = positions[idx]
                if (!seen.add(slug)) continue
                val end = positions.getOrNull(idx + 1)?.first ?: (start + 4000).coerceAtMost(body.length)
                // Expand backwards to include id/name/cover for this object.
                val objStart = body.lastIndexOf('{', start).coerceAtLeast(0)
                val window = body.substring(objStart, end.coerceAtMost(body.length))
                val name = NetworkClient.matchFirst(window, """"name"\s*:\s*"((?:\\.|[^"\\])*)"""")
                    ?.let { NetworkClient.decodeHtml(it) }
                    ?: slug.replace('-', ' ')
                val id = NetworkClient.matchFirst(window, """"id"\s*:\s*(\d+)""") ?: slug
                val cover = (
                    NetworkClient.matchFirst(window, """"cover_url"\s*:\s*"([^"]+)"""")
                        ?: NetworkClient.matchFirst(window, """"poster_url"\s*:\s*"([^"]+)"""")
                        ?: ""
                    ).replace("\\/", "/")
                items.add(
                    VideoItem(
                        id = id,
                        title = name,
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = "Hanime",
                        gradientSeed = index++,
                        pageUrl = "https://hanime.tv/videos/hentai/$slug",
                        thumbnailUrl = cover,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 80) break
            }
        }
        return items
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Hanime",
        ).substringBefore("|").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
            .ifBlank {
                NetworkClient.matchFirst(html, """data-video-poster="([^"]+)"""")
                    .orEmpty()
            }
        var streams = collectMp4AndHls(html, source.baseUrl)
        // Prefer real media over dumping the whole site into WebView.
        val videoId = NetworkClient.matchFirst(html, """data-video-id="(\d+)"""")
        val slug = NetworkClient.matchFirst(html, """data-video-slug="([^"]+)"""")
            ?: pageUrl.substringAfterLast('/')
        // Attempt common community stream endpoints (may 404; harmless).
        for (api in listOfNotNull(
            videoId?.let { "https://hanime.tv/api/v8/video?id=$it" },
            slug?.let { "https://hanime.tv/api/v8/video?id=$it" },
            videoId?.let { "https://guest.freeanimehentai.net/api/v11/video?id=$it" },
        )) {
            if (streams.isNotEmpty()) break
            try {
                val body = NetworkClient.get(
                    api,
                    source.baseUrl,
                    extraHeaders = mapOf("Accept" to "application/json"),
                )
                if (body.contains("Just a moment", true)) continue
                streams = collectMp4AndHls(body) + extractStreamsFromHanimeJson(body)
            } catch (_: Exception) {
            }
        }
        // Playable mirror: search Hentaigasm for the same title (real mp4).
        if (streams.isEmpty() && title.isNotBlank()) {
            try {
                val mirror = HentaigasmClient().search(title.take(48), page = 1).firstOrNull()
                if (mirror != null) {
                    val det = HentaigasmClient().fetchVideoDetails(mirror.pageUrl)
                    if (det.streams.isNotEmpty()) {
                        return@withContext det.copy(
                            title = title,
                            uploader = "Hanime",
                            thumbnailUrl = thumb.ifBlank { det.thumbnailUrl },
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (streams.isEmpty()) {
            throw IllegalStateException(
                "Hanime has no direct stream for this title. Try Hentaigasm / HentaiCity.",
            )
        }
        // Related filled by DataRepository search fallback; seed with same-title search hits.
        val relatedSeed = try {
            searchInternal(title.take(40).ifBlank { "hentai" }, page = 1)
                .filter { !it.pageUrl.equals(pageUrl, true) }
                .take(12)
        } catch (_: Exception) {
            emptyList()
        }
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "Hanime",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = relatedSeed,
            thumbnailUrl = thumb,
            embedUrl = null,
        )
    }

    private fun extractStreamsFromHanimeJson(body: String): List<StreamOption> {
        val out = mutableListOf<StreamOption>()
        val m = Pattern.compile(
            """"(?:url|file|src|stream_url|manifest)"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(body)
        while (m.find()) {
            val u = m.group(1)?.replace("\\/", "/") ?: continue
            val label = if (u.contains("m3u8")) "Auto" else NetworkClient.guessQualityLabel(u, "MP4")
            out.add(StreamOption(label.ifBlank { "MP4" }, u))
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// HentaiMama — dooplay episodes + get_player_contents → gdvid / javprovider mp4
// ---------------------------------------------------------------------------

class HentaiMamaClient : VideoSourceClient {
    override val source = VideoSource.HENTAIMAMA

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        // Page 1: home + episodes. Page 2+: only /episodes/page/N/ (site /page/N/ is 404).
        val urls = if (p <= 1) {
            listOf("${source.baseUrl}/episodes/", source.baseUrl + "/")
        } else {
            listOf(
                "${source.baseUrl}/episodes/page/$p/",
                "${source.baseUrl}/episodes/?page=$p",
            )
        }
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val items = parseListing(NetworkClient.get(url, source.baseUrl))
                if (items.isNotEmpty()) return@withContext items
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null && p > 1) throw lastError
        emptyList()
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val urls = if (p <= 1) {
            listOf("${source.baseUrl}/?s=$q")
        } else {
            listOf(
                "${source.baseUrl}/page/$p/?s=$q",
                "${source.baseUrl}/?s=$q&paged=$p",
                "${source.baseUrl}/episodes/page/$p/?s=$q",
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

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        // Series page → first episode if needed
        var detailUrl = pageUrl
        var html = NetworkClient.get(detailUrl, source.baseUrl)
        if (detailUrl.contains("/tvshows/", true)) {
            val ep = NetworkClient.matchFirst(
                html,
                """href="(https://hentaimama\.io/episodes/[^"]+)"""",
            )
            if (!ep.isNullOrBlank()) {
                detailUrl = ep
                html = NetworkClient.get(detailUrl, source.baseUrl)
            }
        }
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "HentaiMama",
        ).substringBefore(" with ").substringBefore(" – ").substringBefore(" - ").trim()
        // og:image sometimes has trailing space
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            ?.trim()
            .orEmpty()
            .ifBlank {
                NetworkClient.matchFirst(
                    html,
                    """data-src="(https?://hentaimama\.io/wp-content/uploads/[^"]+)"""",
                ).orEmpty()
            }
        val postId = NetworkClient.matchFirst(html, """postid-(\d+)""")
            ?: NetworkClient.matchFirst(html, """name="idpost"\s+value="(\d+)"""")
        var streams = collectMp4AndHls(html, source.baseUrl)
        if (!postId.isNullOrBlank()) {
            try {
                val resp = NetworkClient.postForm(
                    "${source.baseUrl}/wp-admin/admin-ajax.php",
                    "action=get_player_contents&a=$postId",
                    detailUrl,
                )
                // JSON array of iframe HTML strings
                val arr = org.json.JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val field = arr.optString(i)
                    val iframeSrc = NetworkClient.matchFirst(
                        field,
                        """src=["'](https?://[^"']+)["']""",
                    ) ?: continue
                    try {
                        val playerHtml = NetworkClient.get(iframeSrc, detailUrl)
                        val mp4s = collectMp4AndHls(playerHtml)
                        if (mp4s.isNotEmpty()) {
                            streams = mp4s + streams
                            break
                        }
                        // Direct mp4 in player page
                        val direct = NetworkClient.matchFirst(
                            playerHtml,
                            """(https?://[^"']+\.mp4[^"']*)""",
                        )
                        if (!direct.isNullOrBlank()) {
                            streams = listOf(StreamOption("MP4", direct)) + streams
                            break
                        }
                    } catch (_: Exception) {
                    }
                    if (streams.isEmpty()) {
                        streams = listOf(StreamOption("Embed", iframeSrc))
                    }
                }
            } catch (_: Exception) {
            }
        }
        streams = streams
            .map { it.copy(url = NetworkClient.sanitizeMediaUrl(it.url)) }
            .filter { !it.url.contains("preview", true) }
            .distinctBy { it.url }
        if (streams.isEmpty()) throw IllegalStateException("No stream on HentaiMama")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "HentaiMama",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != detailUrl }.take(12),
            thumbnailUrl = thumb,
            embedUrl = if (streams.first().label == "Embed") streams.first().url else null,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val skip = setOf(
            "feed", "page", "wp-json", "category", "tag", "author", "genres",
            "genres-filter", "hentai-list", "hentai-series", "my-account",
        )
        // Absolute or relative, single or double quotes. Poster is often BEFORE the link.
        val patterns = listOf(
            """href=["']((?:https://hentaimama\.io)?/episodes/([a-z0-9][a-z0-9-]{2,})/)["']""",
            """href=["']((?:https://hentaimama\.io)?/tvshows/([a-z0-9][a-z0-9-]{2,})/)["']""",
        )
        for (pat in patterns) {
            val m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(html)
            while (m.find()) {
                val rawPath = m.group(1) ?: continue
                val slug = m.group(2) ?: continue
                if (slug in skip || slug == "page" || slug.startsWith("page")) continue
                if (!seen.add(slug)) continue
                val href = NetworkClient.absoluteUrl(source.baseUrl, rawPath)
                val window = html.substring(
                    (m.start() - 700).coerceAtLeast(0),
                    (m.start() + 500).coerceAtMost(html.length),
                )
                val title = NetworkClient.decodeHtml(
                    NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                        ?: NetworkClient.matchFirst(window, """<span class="b">([^<]+)</span>""")
                        ?: NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                        ?: slug.replace('-', ' '),
                )
                val thumb = NetworkClient.matchFirst(
                    window,
                    """data-src="(https?://hentaimama\.io/wp-content/uploads/[^"]+)"""",
                ) ?: NetworkClient.matchFirst(
                    window,
                    """src="(https?://hentaimama\.io/wp-content/uploads/[^"]+)"""",
                ) ?: NetworkClient.matchFirst(
                    window,
                    """data-src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
                ) ?: extractThumbFromWindow(window)
                val cleanThumb = thumb.trim().takeIf {
                    it.startsWith("http") && !it.startsWith("data:")
                }.orEmpty()
                items.add(
                    VideoItem(
                        id = slug,
                        title = title,
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = "HentaiMama",
                        gradientSeed = index++,
                        pageUrl = href,
                        thumbnailUrl = cleanThumb,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 80) return items
            }
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// Hentai4K — KVS /video/{id}/slug/ + get_file streams
// ---------------------------------------------------------------------------

class Hentai4kClient : VideoSourceClient {
    override val source = VideoSource.HENTAI4K

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val path = when {
            p <= 1 -> "/latest-updates/"
            else -> "/latest-updates/$p/"
        }
        val items = parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))
        if (items.isNotEmpty()) return@withContext items
        parseListing(NetworkClient.get(source.baseUrl + "/", source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val path = if (p <= 1) "/search/$q/" else "/search/$q/?from_videos=$p"
        parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Hentai4K",
        ).substringBefore(" - ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val streams = collectMp4AndHls(html, source.baseUrl)
            .filter {
                !it.url.contains("preview", true) &&
                    !it.url.contains("_preview", true) &&
                    !it.url.contains("_vthumb", true)
            }
            .map { opt ->
                var u = opt.url
                if (u.contains("/get_file/", true) && !u.contains("?") && !u.endsWith("/")) {
                    u = "$u/"
                }
                StreamOption(
                    NetworkClient.guessQualityLabel(u, opt.label).ifBlank { opt.label },
                    u,
                )
            }
        if (streams.isEmpty()) throw IllegalStateException("No stream on Hentai4K")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "Hentai4K",
            views = "—",
            ratingPercent = "—",
            duration = NetworkClient.matchFirst(html, """class="duration"[^>]*>([^<]+)<""") ?: "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val m = Pattern.compile(
            """href="((?:https://hentai4k\.com)?/video/(\d+)/([^"/]+)/?)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val path = m.group(1) ?: continue
            val id = m.group(2) ?: continue
            if (!seen.add(id)) continue
            val slug = m.group(3).orEmpty()
            val window = html.substring(
                (m.start() - 100).coerceAtLeast(0),
                (m.start() + 800).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                    ?: slug.replace('-', ' '),
            )
            val thumb = NetworkClient.matchFirst(window, """data-original="(https?://[^"]+)"""")
                ?: NetworkClient.matchFirst(window, """\bthumb="(https?://[^"]+)"""")
                ?: extractThumbFromWindow(window)
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = NetworkClient.matchFirst(window, """class="time"[^>]*>([^<]+)<""")
                        ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2})</""")
                        ?: "—",
                    resolution = "HD",
                    views = "—",
                    category = "Hentai4K",
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
// Hentaigasm — WordPress + direct hgasm*.com mp4
// ---------------------------------------------------------------------------

class HentaigasmClient : VideoSourceClient {
    override val source = VideoSource.HENTAIGASM

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
                ?: "Hentaigasm",
        ).substringBefore("|").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, source.baseUrl)
        val file = NetworkClient.matchFirst(html, """file\s*:\s*['"]([^'"]+\.mp4[^'"]*)['"]""")
        if (!file.isNullOrBlank()) {
            val url = file.replace(" ", "%20")
            streams = listOf(StreamOption("MP4", url)) + streams
        }
        if (streams.isEmpty()) throw IllegalStateException("No stream on Hentaigasm")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams.distinctBy { it.url },
            title = title,
            uploader = "Hentaigasm",
            views = "—",
            ratingPercent = "—",
            duration = "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val m = Pattern.compile(
            """href="(https://hentaigasm\.com/([a-z0-9-]+)/)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val skip = setOf(
            "genre", "page", "feed", "wp-json", "author", "tag", "category",
            "upcoming-hentai-preview", "favicon.ico",
        )
        while (m.find()) {
            val href = m.group(1) ?: continue
            val slug = m.group(2) ?: continue
            if (slug in skip || slug.startsWith("genre")) continue
            if (!seen.add(slug)) continue
            val window = html.substring(
                (m.start() - 40).coerceAtLeast(0),
                (m.start() + 700).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """(?:title|alt)="([^"]{2,})"""")
                    ?: slug.replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "Hentaigasm",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = extractThumbFromWindow(window),
                    sourceId = source.id,
                ),
            )
            if (items.size >= 80) break
        }
        return items
    }
}

// ---------------------------------------------------------------------------
// HentaiCity — /click/.../video/slug.html + HLS masters
// ---------------------------------------------------------------------------

class HentaiCityClient : VideoSourceClient {
    override val source = VideoSource.HENTAICITY

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        val path = if (p <= 1) {
            "/videos/straight/all-recent.html"
        } else {
            "/videos/straight/all-recent-$p.html"
        }
        parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val path = if (p <= 1) "/search/$q.html" else "/search/$q-$p.html"
        try {
            val items = parseListing(NetworkClient.get(source.baseUrl + path, source.baseUrl))
            if (items.isNotEmpty()) return@withContext items
        } catch (_: Exception) {
        }
        val slug = query.trim().lowercase().replace(' ', '-')
        val tagPath = if (p <= 1) {
            "/videos/straight/$slug-popular.html"
        } else {
            "/videos/straight/$slug-popular-$p.html"
        }
        try {
            parseListing(NetworkClient.get(source.baseUrl + tagPath, source.baseUrl))
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails = withContext(Dispatchers.IO) {
        val html = NetworkClient.get(pageUrl, source.baseUrl)
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "HentaiCity",
        ).substringBefore("|").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        var streams = collectMp4AndHls(html, source.baseUrl)
            .filter { !it.url.contains("trailer", true) }
        val m3 = Pattern.compile(
            """(https?://hls\.hentaicity\.com/[^"'\s]+\.m3u8[^"'\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m3.find()) {
            streams = listOf(StreamOption("Auto (HLS)", m3.group(1)!!)) + streams
        }
        val mp4 = Pattern.compile(
            """(https?://(?:www\.)?hentaicity\.com/flv/[^"'\s]+\.mp4)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (mp4.find()) {
            streams = streams + StreamOption("MP4", mp4.group(1)!!)
        }
        streams = streams.distinctBy { it.url }
        if (streams.isEmpty()) throw IllegalStateException("No stream on HentaiCity")
        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "HentaiCity",
            views = "—",
            ratingPercent = "—",
            duration = NetworkClient.matchFirst(html, """class="time"[^>]*>([^<]+)<""") ?: "—",
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb,
        )
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        val m = Pattern.compile(
            """href="((?:https://(?:www\.)?hentaicity\.com)?/click/[^"]+/video/([^"]+\.html))"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) {
            val href = m.group(1) ?: continue
            val file = m.group(2) ?: continue
            val id = file.removeSuffix(".html").takeLast(48)
            if (!seen.add(id)) continue
            val window = html.substring(
                (m.start() - 40).coerceAtLeast(0),
                (m.start() + 900).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """alt="([^"]{2,})"""")
                    ?: NetworkClient.matchFirst(window, """title="([^"]{2,})"""")
                    ?: file.removeSuffix(".html").replace('-', ' '),
            )
            val thumb = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            ).orEmpty()
            val duration = NetworkClient.matchFirst(window, """class="time"[^>]*>([^<]+)<""") ?: "—"
            items.add(
                VideoItem(
                    id = id,
                    title = title,
                    duration = duration.trim(),
                    resolution = "HD",
                    views = "—",
                    category = "HentaiCity",
                    gradientSeed = index++,
                    pageUrl = NetworkClient.absoluteUrl(source.baseUrl, href),
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 80) break
        }
        return items
    }
}
