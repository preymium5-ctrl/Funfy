package com.example.funfy.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Shared HTTP helpers for all video source clients. */
object NetworkClient {
    val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            // More concurrent range fetches (moov fast-start + progressive reads).
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                },
            )
            .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
            .build()
    }

    fun get(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8")
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
                builder.header("Cookie", "accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; platform=pc")
            host.contains("redtube") ->
                builder.header("Cookie", "accessAgeDisclaimerRT=1; platform=pc")
        }
        for ((k, v) in extraHeaders) builder.header(k, v)
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
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
            // A styled 4xx/5xx page is still an error; parsing it used to create fake empty results.
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} for $url")
            }
            if (body.isBlank()) {
                throw IllegalStateException("Empty response from $url (HTTP ${response.code})")
            }
            return body
        }
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

    /** application/x-www-form-urlencoded POST (BebasIndo /api/iframe, etc.). */
    fun postForm(url: String, body: String, referer: String? = null): String {
        val media = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body.toRequestBody(media))
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
            builder.header("Origin", siteReferer(referer).trimEnd('/'))
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} for POST $url")
            }
            return text
        }
    }

    /** Best-effort Content-Length via HEAD (or ranged GET). */
    fun contentLength(url: String, referer: String? = null): Long? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .head()
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            http.newCall(builder.build()).execute().use { response ->
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
        // Probe sizes in parallel so multi-quality download sheets fill quickly.
        val results = arrayOfNulls<StreamOption>(streams.size)
        val threads = streams.mapIndexed { index, opt ->
            Thread {
                results[index] = if (opt.sizeBytes != null && opt.sizeBytes > 0L) {
                    opt
                } else {
                    val len = runCatching { contentLength(opt.url, referer) }.getOrNull()
                    if (len != null && len > 0L) opt.copy(sizeBytes = len) else opt
                }
            }.also { it.start() }
        }
        threads.forEach { it.join(12_000) }
        return results.mapIndexed { i, r -> r ?: streams[i] }
    }

    /**
     * Prefer **strict** quality tokens in the URL when the provider label is vague
     * ("MP4", "High"). Avoids false matches like "240" inside "1240" or random ids.
     */
    fun guessQualityLabel(url: String, fallback: String): String {
        val u = url.lowercase()
        // Match bounded tokens: /1080p/, _720., -480p., etc.
        fun hasTier(vararg tokens: String): Boolean {
            return tokens.any { token ->
                Regex("""(?:^|[/_\-.?=])$token(?:p)?(?:[/_\-.?=&]|$)""").containsMatchIn(u)
            }
        }
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
     * Light URL cleanup for media links.
     *
     * Important: do **not** re-encode already percent-encoded paths. Buumal/R2
     * signed URLs break if `%E1…` is turned into `%25E1…`.
     */
    fun sanitizeMediaUrl(raw: String): String {
        if (raw.isBlank()) return raw
        return raw.trim()
            .replace("&amp;", "&")
            .replace(" ", "%20")
    }

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
