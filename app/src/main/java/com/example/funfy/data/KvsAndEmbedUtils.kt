package com.example.funfy.data

import java.util.regex.Pattern

/**
 * Kernel Video Sharing (KVS) get_file hash unscrambler.
 * Port of yt-dlp GenericIE._kvs_get_real_url / _kvs_get_license_token.
 */
internal object KvsDecoder {
    fun getRealUrl(videoUrl: String, licenseCode: String?): String {
        if (licenseCode.isNullOrBlank()) {
            // Still strip function/0/ wrapper so the raw path can be tried.
            return stripFunctionPrefix(videoUrl)
        }
        if (!videoUrl.startsWith("function/0/") && !videoUrl.startsWith("function/")) {
            return videoUrl
        }
        val stripped = stripFunctionPrefix(videoUrl)
        return try {
            val uri = java.net.URI(stripped)
            val path = uri.path ?: return stripped
            val parts = path.split('/').toMutableList()
            // /get_file/{id}/{hash}/...
            val hashIdx = parts.indexOfFirst { it.length >= 32 && it.matches(Regex("[0-9a-fA-F]+")) }
            if (hashIdx < 0) return stripped

            val hashPart = parts[hashIdx]
            val hashLen = 32
            val hash = hashPart.substring(0, hashLen.coerceAtMost(hashPart.length))
            if (hash.length < hashLen) return stripped

            val licenseToken = getLicenseToken(licenseCode)
            if (licenseToken.size < hashLen) return stripped

            val indices = IntArray(hashLen) { it }
            var accum = 0
            for (src in (hashLen - 1) downTo 0) {
                accum += licenseToken[src]
                val dest = (src + accum) % hashLen
                val tmp = indices[src]
                indices[src] = indices[dest]
                indices[dest] = tmp
            }
            val decoded = buildString(hashLen) {
                for (i in 0 until hashLen) append(hash[indices[i]])
            }
            parts[hashIdx] = decoded + hashPart.substring(hashLen)
            val newPath = parts.joinToString("/")
            // Preserve query string (e.g. ?br=2259)
            val query = uri.rawQuery
            val base = buildString {
                append(uri.scheme).append("://").append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
                append(newPath)
                if (!query.isNullOrBlank()) append("?").append(query)
            }
            base
        } catch (_: Exception) {
            stripped
        }
    }

    fun stripFunctionPrefix(videoUrl: String): String {
        if (videoUrl.startsWith("function/", ignoreCase = true)) {
            val idx = videoUrl.indexOf("http")
            if (idx >= 0) return videoUrl.substring(idx)
        }
        return videoUrl
    }

    private fun getLicenseToken(licenseCode: String): List<Int> {
        val license = licenseCode.replace("$", "")
        val licenseValues = license.map { it.digitToIntOrNull() ?: 0 }
        var modlicense = license.replace("0", "1")
        val center = modlicense.length / 2
        val fronthalf = modlicense.substring(0, center + 1).toLongOrNull() ?: return emptyList()
        val backhalf = modlicense.substring(center).toLongOrNull() ?: return emptyList()
        modlicense = (4 * kotlin.math.abs(fronthalf - backhalf)).toString()
            .take(center + 1)
        val token = ArrayList<Int>(modlicense.length * 4)
        for (index in modlicense.indices) {
            val current = modlicense[index].digitToIntOrNull() ?: 0
            for (offset in 0 until 4) {
                val lv = licenseValues.getOrElse(index + offset) { 0 }
                token.add((lv + current) % 10)
            }
        }
        return token
    }
}

/**
 * Minimal Dean Edwards /p.a.c.k.e.r unpacker used by StreamWish / PlayerWish embeds.
 */
internal object JsPackerUnpacker {
    // Match Dean Edwards packer argument list: }('payload',radix,count,'k0|k1|...'.split('|')
    private val PACKED_ARGS = Pattern.compile(
        """}\('((?:\\'|[^'])*)',(\d+),(\d+),'((?:\\'|[^'])*)'\.split\('\|'\)""",
        Pattern.CASE_INSENSITIVE,
    )

    fun unpackAll(html: String): String {
        val sb = StringBuilder()
        if (!html.contains("eval(function(p,a,c,k,e", ignoreCase = false) &&
            !html.contains("eval(function(p,a,c,k,e", ignoreCase = true)
        ) {
            // Still try if any packed signature present
            if (!html.contains(".split('|')")) return ""
        }
        val m = PACKED_ARGS.matcher(html)
        while (m.find()) {
            try {
                val p = m.group(1)?.replace("\\'", "'").orEmpty()
                val a = m.group(2)?.toIntOrNull() ?: continue
                val c = m.group(3)?.toIntOrNull() ?: continue
                val k = m.group(4)?.replace("\\'", "'")?.split("|").orEmpty()
                // Skip tiny false positives
                if (p.length < 40 || k.size < 5) continue
                unpack(p, a, c, k)?.let { sb.append(it).append('\n') }
            } catch (_: Exception) {
            }
        }
        return sb.toString()
    }

    private fun unpack(payload: String, radix: Int, count: Int, keywords: List<String>): String? {
        if (payload.isBlank() || radix < 2 || count <= 0) return null
        fun encode(num: Int): String {
            val hi = if (num < radix) "" else encode(num / radix)
            val rem = num % radix
            val lo = if (rem > 35) (rem + 29).toChar().toString() else rem.toString(36)
            return hi + lo
        }
        val dict = HashMap<String, String>(count)
        for (i in 0 until count) {
            val key = encode(i)
            dict[key] = keywords.getOrElse(i) { "" }.ifEmpty { key }
        }
        val word = Pattern.compile("""\b\w+\b""")
        val m = word.matcher(payload)
        val out = StringBuffer()
        while (m.find()) {
            val w = m.group()
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(dict[w] ?: w))
        }
        m.appendTail(out)
        return out.toString()
    }
}

/**
 * Resolve StreamWish-family embed pages (playerwish / strwish / hlswish / …) to HLS.
 */
internal fun resolveStreamWishEmbed(embedUrl: String, referer: String): List<StreamOption> {
    val html = try {
        NetworkClient.get(embedUrl, referer)
    } catch (_: Exception) {
        return emptyList()
    }
    val unpacked = JsPackerUnpacker.unpackAll(html)
    val blob = html + "\n" + unpacked
    val options = linkedMapOf<String, StreamOption>()

    // links={"hls2":"https://...master.m3u8?...","hls3":"..."}
    val hlsMap = Pattern.compile(
        """["']hls(\d+)["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(blob)
    while (hlsMap.find()) {
        val n = hlsMap.group(1) ?: continue
        val u = hlsMap.group(2)?.replace("\\/", "/") ?: continue
        options["HLS$n"] = StreamOption("Auto (HLS)", u)
    }
    val bare = Pattern.compile(
        """(https?://[^"'\\\s]+\.m3u8[^"'\\\s]*)""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(blob)
    while (bare.find()) {
        val u = bare.group(1)?.replace("\\/", "/") ?: continue
        if (u.contains("get_slides") || u.contains("thumb")) continue
        options.putIfAbsent(u, StreamOption("Auto (HLS)", u))
    }
    // file:"https://...m3u8"
    val file = Pattern.compile(
        """file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(blob)
    while (file.find()) {
        val u = file.group(1)?.replace("\\/", "/") ?: continue
        options.putIfAbsent(u, StreamOption("Auto (HLS)", u))
    }
    return options.values.toList()
}

/**
 * Jav.Guru searcho gate → real embed host (302 after reverse-token).
 */
internal fun resolveJavGuruSearcho(searchoGateUrl: String, pageUrl: String): List<String> {
    val embeds = mutableListOf<String>()
    val gateHtml = try {
        NetworkClient.get(searchoGateUrl, pageUrl)
    } catch (_: Exception) {
        return embeds
    }
    val rtype = NetworkClient.matchFirst(gateHtml, """rtype:\s*['"]([a-z])['"]""") ?: return embeds
    val keysBlock = NetworkClient.matchFirst(gateHtml, """keys:\s*\[([^\]]+)\]""") ?: return embeds
    val keys = mutableListOf<String>()
    val km = Pattern.compile("""['"]([^'"]+)['"]""").matcher(keysBlock)
    while (km.find()) keys.add(km.group(1) ?: continue)

    // stream-box tag with data-* pieces
    val boxTag = NetworkClient.matchFirst(
        gateHtml,
        """(<div[^>]+class=["'][^"']*stream-box[^"']*["'][^>]*>)""",
    ) ?: NetworkClient.matchFirst(
        gateHtml,
        """(<div[^>]+stream-box[^>]*>)""",
    ) ?: return embeds

    val parts = keys.map { key ->
        NetworkClient.matchFirst(boxTag, """$key=["']([^"']+)["']""").orEmpty()
    }
    if (parts.any { it.isBlank() }) return embeds
    val fullToken = parts.joinToString("")
    val rev = fullToken.reversed()
    val realSrc = "https://jav.guru/searcho/?${rtype}r=$rev"
    // Follow redirect to embed host without downloading full body
    val finalUrl = try {
        NetworkClient.resolveFinalUrl(realSrc, searchoGateUrl)
    } catch (_: Exception) {
        null
    }
    if (!finalUrl.isNullOrBlank() &&
        !finalUrl.contains("jav.guru/searcho", true) &&
        finalUrl.startsWith("http")
    ) {
        embeds.add(finalUrl)
    }
    // Also try GET (follows redirects) and scrape iframe src from final HTML
    try {
        val finalHtml = NetworkClient.get(realSrc, searchoGateUrl)
        val ifr = Pattern.compile(
            """iframe[^>]+src=["'](https?://[^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(finalHtml)
        while (ifr.find()) {
            val src = ifr.group(1) ?: continue
            if (src.contains("about:blank")) continue
            if (src !in embeds) embeds.add(src)
        }
        // Direct player links on page
        for (host in listOf(
            "javclan.com", "emturbovid.com", "streamhihi.com", "vidara.to",
            "vide0.net", "maxstream.org", "javlesbians.com", "dood", "streamwish",
            "filemoon", "voe.sx", "luluvdo",
        )) {
            val hm = Pattern.compile(
                """(https?://(?:www\.)?${Pattern.quote(host)}[^"'\\\s]+)""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(finalHtml)
            while (hm.find()) {
                val u = hm.group(1) ?: continue
                if (u !in embeds) embeds.add(u)
            }
        }
    } catch (_: Exception) {
    }
    return embeds
}

/** Encode spaces and unsafe chars in a fully-qualified URL (Eporner CDN Location). */
internal fun sanitizeStreamUrl(url: String): String {
    if (url.isBlank()) return url
    // Only rewrite if spaces or unencoded brackets present
    if (!url.contains(' ') && !url.contains('[') && !url.contains(']')) return url
    return try {
        val uri = java.net.URI(url)
        // Rebuild with encoded query
        val scheme = uri.scheme
        val host = uri.host
        val port = uri.port
        val path = uri.rawPath ?: uri.path
        val query = uri.query // decoded
        val frag = uri.rawFragment
        val q = if (query.isNullOrBlank()) {
            null
        } else {
            // encode each query component carefully — keep = and & structure
            query.split("&").joinToString("&") { part ->
                val eq = part.indexOf('=')
                if (eq < 0) {
                    java.net.URLEncoder.encode(part, Charsets.UTF_8.name()).replace("+", "%20")
                } else {
                    val k = part.substring(0, eq)
                    val v = part.substring(eq + 1)
                    val ek = java.net.URLEncoder.encode(k, Charsets.UTF_8.name()).replace("+", "%20")
                    val ev = java.net.URLEncoder.encode(v, Charsets.UTF_8.name()).replace("+", "%20")
                    "$ek=$ev"
                }
            }
        }
        buildString {
            append(scheme).append("://").append(host)
            if (port != -1) append(":").append(port)
            append(path)
            if (!q.isNullOrBlank()) append("?").append(q)
            if (!frag.isNullOrBlank()) append("#").append(frag)
        }
    } catch (_: Exception) {
        url.replace(" ", "%20")
    }
}
