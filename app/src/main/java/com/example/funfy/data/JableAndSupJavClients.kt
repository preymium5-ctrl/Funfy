package com.example.funfy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// Jable.tv — listing cards (/videos/{slug}/) + hlsUrl m3u8 player
// ---------------------------------------------------------------------------

class JableClient : VideoSourceClient {
    override val source = VideoSource.JABLE

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        // KVS async blocks return card HTML even when full pages are CF-challenged.
        val urls = listOf(
            "${source.baseUrl}/latest-updates/?mode=async&function=get_block" +
                "&block_id=list_videos_latest_videos_list&sort_by=post_date&from=$p",
            if (p <= 1) "${source.baseUrl}/latest-updates/" else "${source.baseUrl}/latest-updates/$p/",
            if (p <= 1) "${source.baseUrl}/" else "${source.baseUrl}/latest-updates/$p/",
            "${source.baseUrl}/categories/chinese-subtitle/?mode=async&function=get_block" +
                "&block_id=list_videos_common_videos_list&sort_by=post_date&from=$p",
            if (p <= 1) "${source.baseUrl}/hot/" else "${source.baseUrl}/hot/$p/",
        )
        for (url in urls) {
            try {
                val html = NetworkClient.get(url, source.baseUrl + "/")
                val items = parseListing(html)
                if (items.isNotEmpty()) return@withContext items
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = query.trim().trim('/').replace(Regex("""\s+"""), "-")
        if (q.isEmpty()) return@withContext emptyList()
        val enc = java.net.URLEncoder.encode(q, Charsets.UTF_8.name())
        val p = page.coerceAtLeast(1)
        val urls = listOf(
            "${source.baseUrl}/search/$enc/?mode=async&function=get_block" +
                "&block_id=list_videos_videos_list_search_result&q=$enc&from_videos=$p&from_block=$p",
            if (p <= 1) "${source.baseUrl}/search/$enc/" else "${source.baseUrl}/search/$enc/$p/",
            if (p <= 1) "${source.baseUrl}/search/$q/" else "${source.baseUrl}/search/$q/$p/",
        )
        for (url in urls) {
            try {
                val items = parseListing(NetworkClient.get(url, source.baseUrl + "/"))
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
                ?: NetworkClient.matchFirst(html, """<h4[^>]*>([^<]+)</h4>""")
                ?: NetworkClient.matchFirst(html, """class="[^"]*title[^"]*"[^>]*>([^<]+)""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: "Jable",
        ).substringBefore(" - Jable").substringBefore(" | ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()
        val duration = NetworkClient.matchFirst(html, """class="[^"]*duration[^"]*"[^>]*>([^<]+)""")
            ?: "—"

        var streams = extractHlsUrls(html)
        if (streams.isEmpty()) {
            streams = collectMp4AndHls(html, source.baseUrl)
                .filter { isValidMediaUrl(it.url) && it.url.contains("m3u8", true) }
        }
        // Nested player iframe (rare)
        if (streams.isEmpty()) {
            val ifr = Pattern.compile(
                """iframe[^>]+src=["'](https?://[^"']+)["']""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (ifr.find()) {
                val src = ifr.group(1) ?: continue
                if (src.contains("googletag") || src.contains("/ad")) continue
                try {
                    val nested = NetworkClient.get(src, pageUrl)
                    streams = extractHlsUrls(nested)
                    if (streams.isEmpty()) {
                        streams = collectMp4AndHls(nested).filter { isValidMediaUrl(it.url) }
                    }
                    if (streams.isNotEmpty()) break
                } catch (_: Exception) {
                }
            }
        }
        streams = streams.filter { isValidMediaUrl(it.url) }.distinctBy { it.url }
        if (streams.isEmpty()) throw IllegalStateException("No stream on Jable.tv")

        VideoDetails(
            streamUrl = streams.first().url,
            streams = streams,
            title = title,
            uploader = "Jable.tv",
            views = "—",
            ratingPercent = "—",
            duration = duration.trim(),
            resolution = streams.first().label,
            tags = emptyList(),
            related = parseListing(html).filter { it.pageUrl != pageUrl }.take(14),
            thumbnailUrl = thumb,
        )
    }

    private fun extractHlsUrls(html: String): List<StreamOption> {
        val out = linkedMapOf<String, StreamOption>()
        fun add(raw: String?) {
            val u = raw?.replace("\\/", "/")?.replace("&amp;", "&")?.trim().orEmpty()
            if (!isValidMediaUrl(u)) return
            if (!u.contains("m3u8", true) && !u.contains(".mp4", true)) return
            if (u.contains("preview", true) || u.contains("thumb", true)) return
            val label = when {
                u.contains("1080") -> "1080p"
                u.contains("720") -> "720p"
                u.contains("480") -> "480p"
                u.contains("360") -> "360p"
                u.contains("m3u8") -> "Auto (HLS)"
                else -> "MP4"
            }
            out.putIfAbsent(u, StreamOption(label, u))
        }
        // Primary signal used by Jable player scripts
        for (pat in listOf(
            """hlsUrl\s*=\s*['"]([^'"]+)['"]""",
            """var\s+hlsUrl\s*=\s*['"]([^'"]+)['"]""",
            """["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """source\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""",
            """src\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""",
            """<(?:source|video)[^>]+src=["'](https?://[^"']+\.m3u8[^"']*)["']""",
        )) {
            val m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(html)
            while (m.find()) add(m.group(1))
        }
        // Bare m3u8 URLs (CDN)
        val bare = Pattern.compile(
            """(https?://[^"'\\\s>]+\.m3u8[^"'\\\s>]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (bare.find()) add(bare.group(1))
        return out.values.toList()
    }

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0

        // Best signal: cover image lives inside the video link.
        // <a href=".../videos/slug/"><img class="lazyload" data-src="//cdn.../x.jpg" alt="Title">
        val coverInLink = Pattern.compile(
            """href=["']((?:https?:)?//(?:www\.)?jable\.tv)?(/videos/([a-z0-9][a-z0-9_-]+)/?)["'][^>]*>\s*<img[^>]+>""",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        ).matcher(html)
        while (coverInLink.find()) {
            val slug = coverInLink.group(3)?.lowercase() ?: continue
            if (!seen.add(slug) || slug in SKIP) continue
            val imgTag = coverInLink.group(0) ?: continue
            val title = NetworkClient.decodeHtml(
                extractAttr(imgTag, "alt")
                    ?: extractAttr(imgTag, "title")
                    ?: slug.replace('-', ' '),
            ).ifBlank { slug.replace('-', ' ') }
            val thumb = normalizeMediaUrl(
                extractAttr(imgTag, "data-src")
                    ?: extractAttr(imgTag, "data-original")
                    ?: extractAttr(imgTag, "data-lazy-src")
                    ?: firstHttpFromSrcset(extractAttr(imgTag, "data-srcset") ?: extractAttr(imgTag, "srcset"))
                    ?: extractAttr(imgTag, "src"),
            )
            val window = html.substring(
                coverInLink.start(),
                (coverInLink.end() + 500).coerceAtMost(html.length),
            )
            val duration = NetworkClient.matchFirst(window, """class="label"[^>]*>([^<]+)""")
                ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)<""")
                ?: "—"
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = duration.trim(),
                    resolution = "HD",
                    views = "—",
                    category = "Jable.tv",
                    gradientSeed = index++,
                    pageUrl = "${source.baseUrl}/videos/$slug/",
                    thumbnailUrl = thumb.orEmpty(),
                    sourceId = source.id,
                ),
            )
            if (items.size >= 60) return items
        }

        // Title line: <h6 class="title"><a href="/videos/slug/" title="...">Title</a>
        val titleRe = Pattern.compile(
            """class="[^"]*title[^"]*"[^>]*>\s*<a[^>]+href=["']((?:https?:)?//(?:www\.)?jable\.tv)?(/videos/([a-z0-9][a-z0-9_-]+)/?)["'][^>]*>([^<]+)</a>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (titleRe.find()) {
            val slug = titleRe.group(3)?.lowercase() ?: continue
            if (!seen.add(slug) || slug in SKIP) continue
            val title = NetworkClient.decodeHtml(titleRe.group(4).orEmpty()).trim()
                .ifBlank { slug.replace('-', ' ') }
            val window = html.substring(
                (titleRe.start() - 1400).coerceAtLeast(0),
                (titleRe.start() + 200).coerceAtMost(html.length),
            )
            val thumb = findThumbInWindow(window)
            val duration = NetworkClient.matchFirst(window, """class="label"[^>]*>([^<]+)""")
                ?: NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)<""")
                ?: "—"
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = duration.trim(),
                    resolution = "HD",
                    views = "—",
                    category = "Jable.tv",
                    gradientSeed = index++,
                    pageUrl = "${source.baseUrl}/videos/$slug/",
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 60) return items
        }

        // Last resort: any /videos/slug/ + nearby image/alt
        val linkRe = Pattern.compile(
            """href=["'](?:https?://(?:www\.)?jable\.tv)?(/videos/([a-z0-9][a-z0-9_-]+)/?)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (linkRe.find()) {
            val slug = linkRe.group(2)?.lowercase() ?: continue
            if (!seen.add(slug) || slug in SKIP) continue
            val window = html.substring(
                (linkRe.start() - 200).coerceAtLeast(0),
                (linkRe.start() + 900).coerceAtMost(html.length),
            )
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """alt=["']([^"']{2,})["']""")
                    ?: NetworkClient.matchFirst(window, """title=["']([^"']{2,})["']""")
                    ?: NetworkClient.matchFirst(window, """>([^<]{3,120})</a>""")
                    ?: slug.replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = NetworkClient.matchFirst(window, """>(\d{1,2}:\d{2}(?::\d{2})?)<""") ?: "—",
                    resolution = "HD",
                    views = "—",
                    category = "Jable.tv",
                    gradientSeed = index++,
                    pageUrl = "${source.baseUrl}/videos/$slug/",
                    thumbnailUrl = findThumbInWindow(window),
                    sourceId = source.id,
                ),
            )
            if (items.size >= 60) break
        }
        return items
    }

    private fun findThumbInWindow(window: String): String {
        // Prefer lazy-load attributes; skip data: placeholders
        for (attr in listOf("data-src", "data-original", "data-lazy-src", "data-bg", "src")) {
            val re = Pattern.compile(
                """$attr=["']((?:https?:)?//[^"']+|https?://[^"']+)["']""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(window)
            while (re.find()) {
                val u = normalizeMediaUrl(re.group(1)) ?: continue
                if (u.startsWith("data:", ignoreCase = true)) continue
                if (u.contains("svg", true) && !u.contains("jpg", true)) continue
                if (u.contains("logo", true) || u.contains("avatar", true) || u.contains("icon", true)) continue
                return u
            }
        }
        val srcset = Pattern.compile(
            """(?:data-srcset|srcset)=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(window)
        if (srcset.find()) {
            normalizeMediaUrl(firstHttpFromSrcset(srcset.group(1)))?.let { return it }
        }
        return ""
    }

    private fun extractAttr(tag: String, name: String): String? {
        val m = Pattern.compile(
            """$name=["']([^"']*)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(tag)
        return if (m.find()) m.group(1) else null
    }

    private fun firstHttpFromSrcset(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        return srcset.split(',')
            .map { it.trim().substringBefore(' ').trim() }
            .firstOrNull { it.startsWith("http") || it.startsWith("//") }
    }

    private fun normalizeMediaUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var u = raw.trim().replace("&amp;", "&")
        if (u.startsWith("data:", ignoreCase = true)) return null
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http")) return null
        return u
    }

    companion object {
        private val SKIP = setOf(
            "categories", "tags", "models", "search", "hot", "latest-updates",
            "new-release", "categories", "user", "login",
        )
    }
}
