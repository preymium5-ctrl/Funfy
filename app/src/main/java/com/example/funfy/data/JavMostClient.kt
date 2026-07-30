package com.example.funfy.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * JavMost (www.javmost.ws) — catalog entry [VideoSource.SUPJAV].
 *
 * supjav.com is Cloudflare-blocked from the app (and device curl), so this source
 * uses the working JavMost host for the same free-JAV listing/play role.
 *
 * Play path: page → POST select_part (group/part/code/value/sound=av)
 * → JSON data[embed] (emturbovid) → turbovidhls data-hash m3u8.
 */
class SupJavClient : VideoSourceClient {
    override val source = VideoSource.SUPJAV

    override suspend fun fetchHomeVideos(page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        // Site uses ?page=N ( /page/2/ returns 404 ).
        val urls = if (p <= 1) {
            listOf(
                source.baseUrl + "/",
                "${source.baseUrl}/category/uncensored/",
                "${source.baseUrl}/category/censored/",
            )
        } else {
            listOf(
                "${source.baseUrl}/?page=$p",
                "${source.baseUrl}/category/uncensored/?page=$p",
            )
        }
        for (url in urls) {
            try {
                val items = parseListing(NetworkClient.get(url, source.baseUrl + "/"))
                if (items.isNotEmpty()) return@withContext items
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    override suspend fun search(query: String): List<VideoItem> = search(query, 1)

    override suspend fun search(query: String, page: Int): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        if (q.isBlank()) return@withContext emptyList()
        val p = page.coerceAtLeast(1)
        val urls = listOf(
            if (p <= 1) {
                "${source.baseUrl}/search/?search_value=$q"
            } else {
                "${source.baseUrl}/search/?search_value=$q&page=$p"
            },
            "${source.baseUrl}/?s=$q",
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
        val html = NetworkClient.get(pageUrl, source.baseUrl + "/")
        val title = NetworkClient.decodeHtml(
            NetworkClient.matchFirst(html, """property="og:title"\s+content="([^"]+)"""")
                ?: NetworkClient.matchFirst(html, """<title>([^<]+)</title>""")
                ?: pageUrl.trimEnd('/').substringAfterLast('/'),
        ).substringBefore(" JAV movie").substringBefore(" | ").removePrefix("Watch ").trim()
        val thumb = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            .orEmpty()

        // Primary: select_part API → emturbovid/turbovidhls m3u8 (page has no direct stream)
        var streams = resolveSelectPartStreams(html, pageUrl)
        if (streams.isEmpty()) {
            streams = resolvePageEmbeds(html, pageUrl)
        }
        if (streams.isEmpty()) {
            streams = collectMp4AndHls(html, source.baseUrl).filter {
                isValidMediaUrl(it.url) &&
                    (it.url.contains("m3u8", true) || it.url.contains(".mp4", true)) &&
                    !it.url.contains("file_image", true)
            }
        }
        streams = streams.filter { isValidMediaUrl(it.url) }.distinctBy { it.url }
        if (streams.isEmpty()) {
            // Embed hosts (emturbovid/turbovid) frequently rotate/expire, so select_part
            // resolves but yields no playable m3u8. Mirror the JAV code from XVideos.
            val code = Regex("""[A-Za-z]{2,6}-?\d{2,5}""").find(title)?.value
                ?: pageUrl.trimEnd('/').substringAfterLast('/').uppercase()
            xvideosMirrorFallback(
                title = title,
                pageUrl = pageUrl,
                source = source,
                thumb = thumb,
                related = parseListing(html).filter { it.pageUrl != pageUrl }.take(12),
                extraQueries = listOfNotNull(code.takeIf { it.length in 4..12 }),
            )?.let { return@withContext it }

            val hasSelect = html.contains("select_part", ignoreCase = true)
            val hasValue = !extractPlayValue(html).isNullOrBlank()
            throw IllegalStateException(
                "No direct stream on JavMost (select_part=$hasSelect value=$hasValue)",
            )
        }


        val preferred = streams.sortedByDescending {
            when {
                it.url.contains("m3u8", true) -> 2
                it.url.contains("mp4", true) -> 1
                else -> 0
            }
        }
        VideoDetails(
            streamUrl = preferred.first().url,
            streams = preferred,
            title = title.ifBlank { pageUrl.trimEnd('/').substringAfterLast('/') },
            uploader = "JavMost",
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

    private fun resolvePageEmbeds(html: String, pageUrl: String): List<StreamOption> {
        val out = mutableListOf<StreamOption>()
        val bare = Pattern.compile(
            """(https?://(?:emturbovid\.com|turbovidhls\.com|turboviplay\.com|findjav\.com|dood\.ws|doodstream\.com|mixdrop\.to|voe\.sx|embedwish\.com|playhydrax\.com|hydrax\.net|javmoon\.me)/[^\s"'<>]+)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        val embeds = linkedSetOf<String>()
        while (bare.find()) bare.group(1)?.let { embeds.add(it.trim().replace("\\/", "/")) }
        for (raw in embeds.take(8)) {
            try {
                val resolved = resolveEmbedToStreams(raw, pageUrl)
                if (resolved.isNotEmpty()) {
                    out.addAll(resolved)
                    break
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    private fun resolveEmbedToStreams(embedUrl: String, pageUrl: String): List<StreamOption> {
        val cleaned = embedUrl
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
            .trimEnd('\\', '"', '\'', ')', ']', '}', ',', ' ')
        if (cleaned.isBlank()) return emptyList()

        // Direct media
        if (cleaned.contains(".m3u8", true) && isValidMediaUrl(cleaned)) {
            return listOf(StreamOption("Auto (HLS)", cleaned))
        }
        if (cleaned.contains(".mp4", true) &&
            !cleaned.contains("file_image", true) &&
            isValidMediaUrl(cleaned)
        ) {
            return listOf(StreamOption("MP4", cleaned))
        }

        // Try turbovid family on both host aliases (emturbovid redirects to turbovidhls)
        val candidates = linkedSetOf<String>()
        candidates.add(cleaned)
        if (cleaned.contains("emturbovid.com", true)) {
            candidates.add(cleaned.replace("emturbovid.com", "turbovidhls.com", ignoreCase = true))
        }
        if (cleaned.contains("turbovidhls.com", true)) {
            candidates.add(cleaned.replace("turbovidhls.com", "emturbovid.com", ignoreCase = true))
        }

        for (emb in candidates) {
            if (!emb.startsWith("http")) continue
            try {
                when {
                    emb.contains("turbovid", true) || emb.contains("turboviplay", true) ||
                        emb.contains("emturbovid", true) -> {
                        val resolved = resolveTurbovidEmbed(emb, pageUrl)
                            .ifEmpty { resolveTurbovidEmbed(emb, source.baseUrl + "/") }
                            .filter { isValidMediaUrl(it.url) && it.url.contains("m3u8", true) }
                        if (resolved.isNotEmpty()) return resolved
                    }
                    emb.contains("dood", true) -> {
                        val resolved = resolveDoodStreamEmbed(emb, pageUrl)
                            .filter { isValidMediaUrl(it.url) }
                        if (resolved.isNotEmpty()) return resolved
                    }
                    isStreamWishHost(emb) || emb.contains("wish", true) -> {
                        val resolved = resolveStreamWishEmbed(emb, pageUrl)
                            .filter { isValidMediaUrl(it.url) }
                        if (resolved.isNotEmpty()) return resolved
                    }
                    else -> {
                        val resolved = resolveTurbovidEmbed(emb, pageUrl).ifEmpty {
                            try {
                                collectMp4AndHls(NetworkClient.getAllowingError(emb, pageUrl))
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }.filter { isValidMediaUrl(it.url) }
                        if (resolved.isNotEmpty()) return resolved
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "embed resolve fail $emb: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * JavMost player: POST group/part/code/code2/code3/value/sound → JSON data[embedUrl]
     * then resolve emturbovid/turbovidhls to m3u8.
     */
    private fun resolveSelectPartStreams(html: String, pageUrl: String): List<StreamOption> {
        val path = NetworkClient.matchFirst(html, """YREdIr\s*\+\s*['"]([^'"]+)['"]""")
            ?: NetworkClient.matchFirst(html, """['"](ri[0-9a-z]+/)['"]""")
            ?: "ri3123o235r/"
        val base = NetworkClient.matchFirst(html, """YREdIr\s*=\s*['"](https?://[^'"]+)['"]""")
            ?: (source.baseUrl.trimEnd('/') + "/")
        val endpoint = base.trimEnd('/') + "/" + path.trimStart('/')

        val valueCode = extractPlayValue(html)
        Log.d(TAG, "select_part endpoint=$endpoint valueLen=${valueCode?.length ?: 0}")

        // select_part('part','group',this,'parent|child','code','code2','code3')
        // Also accept double quotes / optional whitespace around this.
        val call = Pattern.compile(
            """select_part\s*\(\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*this\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)

        val out = mutableListOf<StreamOption>()
        var tries = 0
        var lastPostHint = ""
        while (call.find() && tries < 10) {
            tries++
            val part = call.group(1) ?: continue
            val group = call.group(2) ?: continue
            val code = call.group(4) ?: continue
            val code2 = call.group(5) ?: continue
            val code3 = call.group(6).orEmpty()
            val value = pickValue(valueCode, code, code2, code3, html)
            if (value.isBlank()) {
                lastPostHint = "empty value"
                continue
            }

            try {
                // Match browser/jQuery form encoding (encodeURIComponent style).
                val body =
                    "group=${enc(group)}&part=${enc(part)}" +
                        "&code=${enc(code)}&code2=${enc(code2)}" +
                        "&code3=${enc(code3)}" +
                        "&value=${enc(value)}&sound=av"
                val json = try {
                    NetworkClient.postForm(endpoint, body, pageUrl)
                } catch (e: Exception) {
                    lastPostHint = "POST ${e.message}"
                    Log.w(TAG, "select_part POST fail: ${e.message}")
                    continue
                }
                lastPostHint = json.take(120)
                Log.d(TAG, "select_part resp=${json.take(160)}")
                if (json.isBlank()) continue

                val dataUrls = extractDataUrls(json)
                if (dataUrls.isEmpty()) continue

                for (raw in dataUrls) {
                    Log.d(TAG, "embed candidate $raw")
                    val nested = resolveEmbedToStreams(raw, pageUrl)
                    if (nested.isNotEmpty()) {
                        out.addAll(nested)
                        break
                    }
                }
                if (out.isNotEmpty()) break
            } catch (e: Exception) {
                lastPostHint = e.message.orEmpty()
                Log.w(TAG, "select_part loop: ${e.message}")
            }
        }
        if (out.isEmpty()) {
            Log.w(TAG, "select_part empty after $tries tries last=$lastPostHint")
        }
        return out.filter { isValidMediaUrl(it.url) }.distinctBy { it.url }
    }

    /** Parse select_part JSON → embed URLs (handles \/ escapes). */
    private fun extractDataUrls(json: String): List<String> {
        val out = linkedSetOf<String>()
        // Prefer org.json — reliable on escaped slashes
        try {
            val obj = JSONObject(json)
            val data = obj.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val u = data.optString(i, "").replace("\\/", "/").trim()
                    if (u.startsWith("http")) out.add(u)
                }
            }
            // Some responses nest a single string
            if (out.isEmpty()) {
                val single = obj.optString("data", "")
                if (single.startsWith("http")) out.add(single.replace("\\/", "/"))
            }
        } catch (_: Exception) {
        }

        if (out.isEmpty()) {
            val unescaped = json
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
            val dataArr = Pattern.compile(
                """"data"\s*:\s*\[([^\]]*)\]""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(unescaped)
            if (dataArr.find()) {
                val um = Pattern.compile(""""(https?://[^"]+)"""").matcher(dataArr.group(1).orEmpty())
                while (um.find()) um.group(1)?.let { out.add(it) }
            }
            if (out.isEmpty()) {
                val um = Pattern.compile("""(https?://[^\s"'<>\\]+)""").matcher(unescaped)
                while (um.find()) {
                    val u = um.group(1) ?: continue
                    if (u.contains("javmost", true) && !u.contains("/t/", true)) continue
                    out.add(u)
                }
            }
        }
        return out.toList()
    }

    /** Extract ajax "value" token from page globals. */
    private fun extractPlayValue(html: String): String? {
        // 'value':YWRzMQo,'sound':'av'  →  var YWRzMQo = '…'
        // Avoid Kotlin $ string-template pitfalls: build pattern with concatenation.
        val valueVarRe = Pattern.compile(
            "['\"]value['\"]\\s*:\\s*([A-Za-z_\\$][\\w\\$]*)",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        if (valueVarRe.find()) {
            val valueVar = valueVarRe.group(1)
            if (!valueVar.isNullOrBlank()) {
                val assign = Pattern.compile(
                    "var\\s+" + Pattern.quote(valueVar) + "\\s*=\\s*['\"]([^'\"]+)['\"]",
                    Pattern.CASE_INSENSITIVE,
                ).matcher(html)
                if (assign.find()) {
                    val v = assign.group(1)
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        // Common base64-looking ads1 global (name is base64 of ads1…)
        NetworkClient.matchFirst(
            html,
            """var\s+YWRzMQo\s*=\s*['"]([^'"]+)['"]""",
        )?.let { return it }
        // Any var X = 'VTJGc2RHVmtY…' (OpenSSL salted base64 prefix)
        val any = Pattern.compile(
            "var\\s+[A-Za-z_\\$][\\w\\$]*\\s*=\\s*['\"](VTJGc2RHVmtY[A-Za-z0-9+/=]{40,})['\"]",
        ).matcher(html)
        return if (any.find()) any.group(1) else null
    }

    private fun pickValue(
        preferred: String?,
        code: String,
        code2: String,
        code3: String,
        html: String,
    ): String {
        if (!preferred.isNullOrBlank() &&
            preferred != code && preferred != code2 && preferred != code3
        ) {
            return preferred
        }
        val blobs = linkedSetOf<String>()
        val bm = Pattern.compile("""['"](VTJGc2RHVmtY[A-Za-z0-9+/=]{40,})['"]""").matcher(html)
        while (bm.find()) bm.group(1)?.let { blobs.add(it) }
        // Prefer shorter "value" tokens (codes are often 60–120 chars; value ~88)
        return blobs.firstOrNull {
            it != code && it != code2 && it != code3 && it.length in 40..200
        }.orEmpty()
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun parseListing(html: String): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0

        // Card links: real thumbs often live in data-srcset (related genre uses
        // preload.webp as data-src/src until JS lazyloads).
        val card = Pattern.compile(
            """href="(https?://(?:www\.)?javmost\.(?:ws|com)/([A-Za-z0-9][A-Za-z0-9_-]{2,})/)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (card.find()) {
            val href = card.group(1) ?: continue
            val slug = card.group(2) ?: continue
            if (!seen.add(slug.lowercase()) || slug.lowercase() in SKIP) continue
            // Related cards put <source data-srcset> inside the <a>; home cards too.
            val window = html.substring(
                card.start(),
                (card.start() + 1400).coerceAtMost(html.length),
            )
            val thumb = resolveThumbUrl(window, slug)
            val title = NetworkClient.decodeHtml(
                NetworkClient.matchFirst(window, """name="([^"]+)"""")
                    ?: NetworkClient.matchFirst(window, """alt="([^"]+)"""")
                    ?: NetworkClient.matchFirst(window, """card-title[^>]*>\s*([^<]+)""")
                    ?: slug.replace('-', ' '),
            )
            items.add(
                VideoItem(
                    id = slug,
                    title = title,
                    duration = "—",
                    resolution = "HD",
                    views = "—",
                    category = "JavMost",
                    gradientSeed = index++,
                    pageUrl = href,
                    thumbnailUrl = thumb,
                    sourceId = source.id,
                ),
            )
            if (items.size >= 48) return items
        }

        // Image-first: file_image / images/480 CDN paths
        if (items.size < 8) {
            val img = Pattern.compile(
                """(?:data-srcset|data-src|src)=["'](https?://img\d*\.javmost\.[^"']+/([A-Za-z0-9][A-Za-z0-9_-]+)\.(?:jpg|jpeg|png|webp)[^"']*)["']""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (img.find()) {
                val thumb = img.group(1) ?: continue
                val slug = img.group(2) ?: continue
                if (isPlaceholderThumb(thumb)) continue
                if (!seen.add(slug.lowercase()) || slug.lowercase() in SKIP) continue
                items.add(
                    VideoItem(
                        id = slug,
                        title = slug.replace('-', ' '),
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = "JavMost",
                        gradientSeed = index++,
                        pageUrl = source.baseUrl.trimEnd('/') + "/" + slug + "/",
                        thumbnailUrl = thumb,
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 48) break
            }
        }

        // Link-only fallback with stable CDN thumb
        if (items.size < 8) {
            val link = Pattern.compile(
                """href="(https?://(?:www\.)?javmost\.(?:ws|com)/([A-Za-z0-9][A-Za-z0-9_-]{3,})/)"""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (link.find()) {
                val href = link.group(1) ?: continue
                val slug = link.group(2) ?: continue
                if (!seen.add(slug.lowercase()) || slug.lowercase() in SKIP) continue
                if (!slug.any { it.isDigit() } && !slug.contains('-')) continue
                items.add(
                    VideoItem(
                        id = slug,
                        title = slug.replace('-', ' '),
                        duration = "—",
                        resolution = "HD",
                        views = "—",
                        category = "JavMost",
                        gradientSeed = index++,
                        pageUrl = href,
                        thumbnailUrl = defaultThumbForSlug(slug),
                        sourceId = source.id,
                    ),
                )
                if (items.size >= 40) break
            }
        }
        return items
    }

    /**
     * Related genre cards: `<source data-srcset="https://img3…/480/CODE.webp">`
     * while img data-src stays on preload.webp until browser lazyload.
     */
    private fun resolveThumbUrl(window: String, slug: String): String {
        val candidates = mutableListOf<String>()
        val srcset = Pattern.compile(
            """data-srcset=["'](https?://[^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(window)
        while (srcset.find()) srcset.group(1)?.let { candidates.add(it) }
        val dataSrc = Pattern.compile(
            """data-src=["'](https?://[^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(window)
        while (dataSrc.find()) dataSrc.group(1)?.let { candidates.add(it) }
        val src = Pattern.compile(
            """\ssrc=["'](https?://[^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(window)
        while (src.find()) src.group(1)?.let { candidates.add(it) }

        candidates.firstOrNull { !isPlaceholderThumb(it) }?.let { return it }
        // Prefer slug-specific CDN hit from full page blob if present in window later.
        val cdn = NetworkClient.matchFirst(
            window,
            """(https?://img\d*\.javmost\.[^"'\\\s]+/${Pattern.quote(slug)}\.(?:jpg|jpeg|png|webp)[^"'\\\s]*)""",
        )
        if (!cdn.isNullOrBlank() && !isPlaceholderThumb(cdn)) return cdn
        return defaultThumbForSlug(slug)
    }

    private fun isPlaceholderThumb(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("preload") ||
            l.contains("/assets/img/") ||
            l.contains("placeholder") ||
            l.contains("data:image") ||
            l.endsWith(".svg") ||
            l.contains("1x1") ||
            l.contains("spacer")
    }

    private fun defaultThumbForSlug(slug: String): String {
        // Related genre uses img3/480 webp; home often uses img2 file_image jpg.
        // Prefer the more common cover path used on listings.
        return if (slug.contains("UNCENSORED", ignoreCase = true) || slug.length > 20) {
            "https://img2.javmost.ws/file_image/$slug.jpg"
        } else {
            "https://img3.javmost.ws/images/480/$slug.webp"
        }
    }

    companion object {
        private const val TAG = "JavMost"
        private val SKIP = setOf(
            "category", "tag", "tags", "search", "actor", "maker", "genre",
            "page", "login", "register", "assets", "icon", "css", "js",
            "uncensored", "censored", "english", "chinese", "random",
            "allcode", "alldirector", "allmaker", "allactress", "allactor",
            "allcategory", "allcategories", "director", "actress", "studio",
        )
    }
}
