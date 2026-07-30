package com.example.funfy.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Shared HTTP helpers for all video source clients. */
object NetworkClient {
    val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    /** In-memory cookies (session/play APIs like JavMost select_part need them). */
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val key = url.host
            val list = store.getOrPut(key) { mutableListOf() }
            synchronized(list) {
                for (c in cookies) {
                    list.removeAll { it.name == c.name && it.path == c.path }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = store[url.host] ?: return emptyList()
            synchronized(list) {
                val now = System.currentTimeMillis()
                list.removeAll { it.expiresAt < now }
                return list.filter { it.matches(url) }
            }
        }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            // Cap per-host concurrency so CDNs (turbovid / javmost) don't 429 us.
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 32
                    maxRequestsPerHost = 4
                },
            )
            .connectionPool(okhttp3.ConnectionPool(12, 5, TimeUnit.MINUTES))
            .build()
    }

    fun get(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        return getRaw(url, referer, extraHeaders, allowHttpError = false).body
    }

    /**
     * Like [get] but returns body even on HTTP 4xx (MissAV soft-404 player pages still carry streams).
     */
    fun getAllowingError(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        return getRaw(url, referer, extraHeaders, allowHttpError = true).body
    }

    data class HttpText(val code: Int, val body: String, val finalUrl: String)

    fun getRaw(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        allowHttpError: Boolean = false,
    ): HttpText {
        var lastCode = 0
        var lastBody = ""
        var lastFinal = url
        repeat(RATE_LIMIT_MAX_ATTEMPTS) { attempt ->
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8",
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .get()
            if (!referer.isNullOrBlank()) {
                builder.header("Referer", referer)
            }
            // Age / region gates (Pornhub family, etc.)
            val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            when {
                host.contains("pornhub") ->
                    builder.header(
                        "Cookie",
                        "accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; platform=pc",
                    )
                host.contains("redtube") ->
                    builder.header("Cookie", "accessAgeDisclaimerRT=1; platform=pc")
            }
            for ((k, v) in extraHeaders) builder.header(k, v)
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val finalUrl = response.request.url.toString()
                lastCode = response.code
                lastBody = body
                lastFinal = finalUrl
                val lower = body.lowercase()
                if (
                    lower.contains("prohibitedaccess") ||
                    lower.contains("pldtsmart") ||
                    lower.contains("this website is not available") ||
                    lower.contains("just a moment") && lower.contains("cf-chl-")
                ) {
                    throw IllegalStateException(
                        "The source returned an ISP block or browser challenge page.",
                    )
                }
                if (isRateLimited(response.code) && attempt < RATE_LIMIT_MAX_ATTEMPTS - 1) {
                    sleepBackoff(attempt, response.header("Retry-After"))
                    return@use
                }
                if (!response.isSuccessful && !allowHttpError) {
                    throw rateLimitMessage(response.code, url)
                }
                if (body.isBlank() && !allowHttpError) {
                    throw IllegalStateException("Empty response from $url (HTTP ${response.code})")
                }
                return HttpText(response.code, body, finalUrl)
            }
        }
        if (!allowHttpError) throw rateLimitMessage(lastCode, url)
        return HttpText(lastCode, lastBody, lastFinal)
    }

    /**
     * Follow redirects and return the final URL (used for Eporner /dload/ → CDN mp4).
     * Does not download the full body.
     */
    fun resolveFinalUrl(url: String, referer: String? = null): String {
        val noFollow = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var current = sanitizeStreamUrl(url)
        var hops = 0
        while (hops < 8) {
            // Prefer GET+Range: many CDNs (Eporner /dload, KVS) only redirect on GET.
            val builder = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-0")
                .get()
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            val response = noFollow.newCall(builder.build()).execute()
            response.use {
                val code = it.code
                val loc = it.header("Location")
                when {
                    code in 300..399 && !loc.isNullOrBlank() -> {
                        // Eporner CDN Locations often contain unencoded spaces in ?dload=.
                        current = sanitizeStreamUrl(absoluteUrl(current, loc))
                        hops++
                    }
                    code in 200..299 -> return sanitizeStreamUrl(current)
                    else -> throw IllegalStateException("HTTP $code while resolving $current")
                }
            }
        }
        return sanitizeStreamUrl(current)
    }

    fun siteReferer(pageUrl: String): String {
        return try {
            val u = java.net.URI(pageUrl)
            "${u.scheme}://${u.host}/"
        } catch (_: Exception) {
            pageUrl
        }
    }

    /**
     * Referer preferred by CDNs when playing a resolved media URL.
     * Using the wrong site referer causes HTTP 403 on 200cdn / dood / surrit / turbovid.
     */
    fun mediaReferer(streamUrl: String, pageUrl: String): String {
        val host = runCatching { java.net.URI(streamUrl).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            // NontonBokep CDN only redirects / plays with playto.303in.top referer (not embed.200cdn).
            host.contains("200cdn") || host.contains("303in") || host.contains("embed.200") ->
                "https://playto.303in.top/"
            host.contains("cloudatacdn") || host.contains("doodcdn") || host.contains("doimg") ->
                "https://playmogo.com/"
            host.contains("turboviplay") || host.contains("turbovid") || host.contains("turbosplayer") ->
                "https://turbovidhls.com/"
            host.contains("surrit") || host.contains("fourhoi") ->
                "https://missav.ws/"
            host.contains("jable") ->
                "https://jable.tv/"
            host.contains("javmost") ->
                "https://www.javmost.ws/"
            // LootedPinay / Kaldagan Clean Tube — mp4s live on pinaydeepweb.xyz
            host.contains("pinaydeepweb") || host.contains("lootedpinay") ||
                host.contains("kaldagan") ->
                "https://lootedpinay.com/"
            host.contains("video.beeg") || host.contains("beeg.com") ->
                "https://beeg.com/"
            host.contains("ahacdn.me") ->
                "https://beeg.com/"
            host.contains("ahcdn.com") || host.contains("xxxfiles") || host.contains("porngo") ->
                "https://www.xxxfiles.com/"
            host.contains("rubyvid") || host.contains("bigwarp") || host.contains("savefiles") ->
                "https://pwerta.com/"
            host.contains("quatvn.stream") || host.contains("stream.quatvn") ->
                "https://quatvn.asia/"
            host.contains("vlstream") || host.contains("qooglevideo") || host.contains("vlplayer") ->
                "https://vlxx.moi/"
            else -> siteReferer(pageUrl)
        }
    }

    fun postJson(url: String, json: String, referer: String? = null): String {
        val media = "application/json; charset=UTF-8".toMediaType()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json; charset=UTF-8")
            .post(json.toRequestBody(media))
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
            builder.header("Origin", siteReferer(referer).trimEnd('/'))
        }
        return try {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) "" else response.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** application/x-www-form-urlencoded POST (JavMost select_part, BebasIndo /api/iframe, etc.). */
    fun postForm(url: String, body: String, referer: String? = null): String {
        // Match jQuery $.ajax form posts (charset UTF-8, XHR headers).
        val media = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        var lastCode = 0
        repeat(RATE_LIMIT_MAX_ATTEMPTS) { attempt ->
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body.toRequestBody(media))
            if (!referer.isNullOrBlank()) {
                builder.header("Referer", referer)
                builder.header("Origin", siteReferer(referer).trimEnd('/'))
            }
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                lastCode = response.code
                if (isRateLimited(response.code) && attempt < RATE_LIMIT_MAX_ATTEMPTS - 1) {
                    sleepBackoff(attempt, response.header("Retry-After"))
                    return@use
                }
                if (!response.isSuccessful) {
                    throw rateLimitMessage(response.code, "POST $url")
                }
                return text
            }
        }
        throw rateLimitMessage(lastCode, "POST $url")
    }

    /** Best-effort Content-Length via HEAD (or ranged GET). */
    fun contentLength(url: String, referer: String? = null): Long? {
        // HLS playlists have no useful Content-Length; probing them burns rate limits.
        if (url.contains(".m3u8", ignoreCase = true)) return null
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .head()
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            http.newCall(builder.build()).execute().use { response ->
                if (isRateLimited(response.code)) return null
                val len = response.header("Content-Length")?.toLongOrNull()
                if (len != null && len > 0) return len
                // Some CDNs reject HEAD
                if (response.code in listOf(403, 405, 400)) {
                    return contentLengthGet(url, referer)
                }
                null
            }
        } catch (_: Exception) {
            try {
                contentLengthGet(url, referer)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun contentLengthGet(url: String, referer: String?): Long? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .get()
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        http.newCall(builder.build()).execute().use { response ->
            if (isRateLimited(response.code)) return null
            response.header("Content-Range")?.let { range ->
                // bytes 0-0/123456
                val total = range.substringAfter('/').toLongOrNull()
                if (total != null && total > 0) return total
            }
            return response.header("Content-Length")?.toLongOrNull()
        }
    }

    fun withSizes(streams: List<StreamOption>, referer: String?): List<StreamOption> {
        if (streams.isEmpty()) return streams
        // HLS / playlist URLs: size probe is useless and often triggers CDN 429.
        if (streams.all { it.url.contains(".m3u8", true) || it.label.contains("HLS", true) }) {
            return streams
        }
        // Probe a few progressive MP4s only, sequentially (parallel HEAD storms → 429).
        return streams.map { opt ->
            if (opt.sizeBytes != null && opt.sizeBytes > 0L) return@map opt
            if (opt.url.contains(".m3u8", true)) return@map opt
            val len = runCatching { contentLength(opt.url, referer) }.getOrNull()
            if (len != null && len > 0L) opt.copy(sizeBytes = len) else opt
        }
    }

    private fun isRateLimited(code: Int): Boolean = code == 429 || code == 503 || code == 502

    private fun sleepBackoff(attempt: Int, retryAfterHeader: String?) {
        val fromHeader = retryAfterHeader?.toLongOrNull()?.let { it * 1000L }
        val exp = (700L * (1L shl attempt.coerceAtMost(4))).coerceAtMost(12_000L)
        val delay = (fromHeader ?: exp).coerceIn(400L, 15_000L)
        try {
            Thread.sleep(delay)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun rateLimitMessage(code: Int, target: String): IllegalStateException {
        val msg = when (code) {
            429 -> "Rate limited (HTTP 429). Wait a moment and try again."
            502, 503 -> "Server busy (HTTP $code). Try again shortly."
            else -> "HTTP $code for $target"
        }
        return IllegalStateException(msg)
    }

    private const val RATE_LIMIT_MAX_ATTEMPTS = 4

    /**
     * Prefer **strict** quality tokens in the URL when the provider label is vague
     * ("MP4", "High"). Avoids false matches like "240" inside "1240" or random ids.
     */
    fun guessQualityLabel(url: String, fallback: String): String {
        val u = url.lowercase()
        // Multi-bitrate masters list every tier in the path (e.g. multi=…1080p…720p…).
        // Label those as Auto — never treat the whole master as "1080p".
        if (u.contains("multi=") || u.contains("_tpl_") || u.contains("hls4a/multi")) {
            return if (u.contains(".m3u8")) "Auto" else fallback
        }
        // Match bounded tokens: /1080p/, _720., -480p., etc.
        fun hasTier(vararg tokens: String): Boolean {
            return tokens.any { token ->
                Regex("""(?:^|[/_\-.?=])$token(?:p)?(?:[/_\-.?=&]|$)""").containsMatchIn(u)
            }
        }
        // Count distinct quality heights so "…480p…720p…1080p…" masters don't mis-label.
        val tierHits = listOf("2160", "1440", "1080", "720", "480", "360", "240", "144")
            .count { hasTier(it) }
        if (tierHits >= 2 && u.contains(".m3u8")) return "Auto"
        return when {
            hasTier("2160", "4k") -> "2160p"
            hasTier("1440") -> "1440p"
            hasTier("1080", "fhd") -> "1080p"
            hasTier("720") -> "720p"
            hasTier("480") -> "480p"
            hasTier("360") -> "360p"
            hasTier("240", "250") -> "240p"
            hasTier("144") -> "144p"
            u.substringBefore('?').endsWith(".m3u8") -> "Auto"
            else -> fallback
        }
    }

    /**
     * Extract a human-readable view count from page/card HTML when present.
     * Returns "—" when unknown (callers may keep listing fallbacks).
     */
    fun extractViews(html: String): String {
        if (html.isBlank()) return "—"
        // "1.2M views", "33.7k views", "views 12,345"
        matchFirst(html, """([\d]+(?:[.,]\d+)?\s*[kKmMbB])\s*views?""")?.let { raw ->
            return raw.replace(Regex("""\s+"""), "").uppercase().let { t ->
                when {
                    t.endsWith("M", true) -> t.dropLast(1) + " M"
                    t.endsWith("K", true) -> t.dropLast(1) + " k"
                    t.endsWith("B", true) -> t.dropLast(1) + " B"
                    else -> t
                }
            }
        }
        matchFirst(html, """([\d][\d,.]{0,14})\s*views?""")?.let { raw ->
            val n = raw.replace(",", "").replace(".", "").toLongOrNull()
            if (n != null && n > 0) return formatViews(n)
            return raw
        }
        matchFirst(html, """views?["'\s:=]+([\d,.]+)""")?.let { raw ->
            val n = raw.replace(",", "").toLongOrNull()
            if (n != null && n > 0) return formatViews(n)
        }
        matchFirst(html, """"view(?:s|_count)"\s*:\s*(\d+)""")?.let { raw ->
            val n = raw.toLongOrNull()
            if (n != null && n > 0) return formatViews(n)
        }
        return "—"
    }

    /**
     * Light URL cleanup for media links (thumbs + streams).
     *
     * Buumal (and similar) put spaces and non-ASCII text in path segments.
     * Those must be percent-encoded for OkHttp/Coil, but we must **not**
     * re-encode already-encoded `%xx` sequences (R2 signed URLs break).
     */
    fun sanitizeMediaUrl(raw: String): String {
        if (raw.isBlank()) return raw
        val cleaned = raw.trim().replace("&amp;", "&").replace("\\/", "/")
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            return cleaned.replace(" ", "%20")
        }
        return try {
            val schemeSep = cleaned.indexOf("://")
            val afterScheme = cleaned.substring(schemeSep + 3)
            val pathStart = afterScheme.indexOf('/')
            if (pathStart < 0) return cleaned
            val origin = cleaned.substring(0, schemeSep + 3 + pathStart)
            val pathAndQuery = afterScheme.substring(pathStart)
            val qIdx = pathAndQuery.indexOf('?')
            val hIdx = pathAndQuery.indexOf('#')
            val cut = when {
                qIdx >= 0 && hIdx >= 0 -> minOf(qIdx, hIdx)
                qIdx >= 0 -> qIdx
                hIdx >= 0 -> hIdx
                else -> -1
            }
            val path = if (cut >= 0) pathAndQuery.substring(0, cut) else pathAndQuery
            val suffix = if (cut >= 0) pathAndQuery.substring(cut) else ""
            val encodedPath = path.split('/').joinToString("/") { segment ->
                if (segment.isEmpty()) {
                    ""
                } else if (segment.any { ch ->
                        ch.code > 127 || ch == ' ' || ch == '"' || ch == '<' || ch == '>' || ch == '{' ||
                            ch == '}' || ch == '|' || ch == '\\' || ch == '^' || ch == '`'
                    }
                ) {
                    // Encode segment; keep existing %xx sequences intact.
                    encodePathSegmentPreservePercent(segment)
                } else {
                    segment
                }
            }
            origin + encodedPath + suffix
        } catch (_: Exception) {
            cleaned.replace(" ", "%20")
        }
    }

    /**
     * RFC 3986 path-segment encoding.
     *
     * Important: do **not** use [Char.isLetterOrDigit] — it treats Myanmar/CJK as
     * letters and leaves them raw, which breaks Coil/OkHttp for Buumal thumbs.
     * Only ASCII unreserved chars pass through unencoded.
     */
    private fun encodePathSegmentPreservePercent(segment: String): String {
        val out = StringBuilder(segment.length * 3)
        var i = 0
        while (i < segment.length) {
            val ch = segment[i]
            // Keep existing %XX sequences (already encoded).
            if (ch == '%' && i + 2 < segment.length &&
                isAsciiHex(segment[i + 1]) && isAsciiHex(segment[i + 2])
            ) {
                out.append(segment[i])
                out.append(segment[i + 1].uppercaseChar())
                out.append(segment[i + 2].uppercaseChar())
                i += 3
                continue
            }
            if (isAsciiUnreserved(ch)) {
                out.append(ch)
                i++
                continue
            }
            if (ch == ' ') {
                out.append("%20")
                i++
                continue
            }
            // Encode full code point (handles surrogates).
            val cp = Character.codePointAt(segment, i)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            for (b in bytes) {
                out.append('%')
                val v = b.toInt() and 0xFF
                out.append("0123456789ABCDEF"[v ushr 4])
                out.append("0123456789ABCDEF"[v and 0x0F])
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun isAsciiUnreserved(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
            ch == '-' || ch == '_' || ch == '.' || ch == '~'

    private fun isAsciiHex(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    /**
     * Dean Edwards / packer unpacker used by Playerjs embeds (Flixtream, GooStream).
     * Uses JS-style `\b` so tokens like `kho0h540vueh_n` are not corrupted.
     */
    fun unpackDeanEdwards(html: String): String? {
        val m = java.util.regex.Pattern.compile(
            """return p\}\('((?:\\'|[^'])*)',(\d+),(\d+),'((?:\\'|[^'])*)'\.split\('\|'\)\)""",
            java.util.regex.Pattern.DOTALL,
        ).matcher(html)
        if (!m.find()) return null
        var p = m.group(1) ?: return null
        val a = m.group(2)?.toIntOrNull() ?: return null
        val c = m.group(3)?.toIntOrNull() ?: return null
        val k = (m.group(4) ?: "").split('|')
        if (a < 2 || a > 36 || c <= 0) return null
        for (i in (c - 1) downTo 0) {
            val token = k.getOrNull(i).orEmpty()
            if (token.isEmpty()) continue
            val key = i.toString(a)
            p = p.replace(Regex("""\b${Regex.escape(key)}\b"""), token)
        }
        return p
    }

    fun decodeHtml(value: String): String =
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

    fun matchFirst(input: String, pattern: String): String? {
        val m = java.util.regex.Pattern
            .compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.DOTALL)
            .matcher(input)
        return if (m.find()) m.group(1) else null
    }

    fun absoluteUrl(base: String, href: String): String {
        val value = href.trim()
        if (value.startsWith("//")) return "https:$value"
        if (Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""").containsMatchIn(value)) {
            // Absolute URL may still contain spaces (broken Location headers).
            return sanitizeStreamUrl(value)
        }
        return try {
            sanitizeStreamUrl(java.net.URI(base).resolve(value).toString())
        } catch (_: Exception) {
            sanitizeStreamUrl(base.trimEnd('/') + "/" + value.trimStart('/'))
        }
    }

    fun formatViews(n: Long): String = when {
        n >= 1_000_000 -> "%.1f M".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1f k".format(n / 1_000.0)
        else -> n.toString()
    }

    fun formatDurationSec(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "—"
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m >= 60) {
            val h = m / 60
            "%d:%02d:%02d".format(h, m % 60, s)
        } else if (m == 0) {
            "${s}s"
        } else if (s == 0 || m >= 3) {
            "$m min"
        } else {
            "%d:%02d".format(m, s)
        }
    }
}
