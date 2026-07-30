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
 *
 * Intentionally conservative: bad packer payloads used to throw [StackOverflowError]
 * (an Error, not Exception) and crash playback on MissAV / JavTsunami embeds.
 */
internal object JsPackerUnpacker {
    /** Max HTML slice we will scan (avoids catastrophic regex on full MissAV pages). */
    private const val MAX_SCAN = 120_000
    private const val MAX_PAYLOAD = 80_000
    private const val MAX_KEYWORDS = 8_000
    private const val MAX_COUNT = 8_000

    fun unpackAll(html: String): String {
        if (html.isBlank()) return ""
        return try {
            if (!html.contains("eval(function(p,a,c,k,e", ignoreCase = true) &&
                !html.contains(".split('|')")
            ) {
                return ""
            }
            // Only scan a bounded window around packer markers — full pages can be 100KB+
            // and the classic packer regex can SOE the matcher on pathological input.
            val scan = extractPackerWindows(html)
            if (scan.isBlank()) return ""
            val sb = StringBuilder()
            var hits = 0
            var from = 0
            while (hits < 6 && from < scan.length) {
                val start = scan.indexOf("}('", from)
                if (start < 0) break
                val unpacked = tryUnpackAt(scan, start)
                if (unpacked != null) {
                    sb.append(unpacked).append('\n')
                    hits++
                }
                from = start + 3
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractPackerWindows(html: String): String {
        val markers = listOf("eval(function(p,a,c,k,e", "}('")
        val windows = StringBuilder()
        for (marker in markers) {
            var idx = html.indexOf(marker)
            var n = 0
            while (idx >= 0 && n < 4) {
                val a = (idx - 32).coerceAtLeast(0)
                val b = (idx + 40_000).coerceAtMost(html.length)
                windows.append(html, a, b).append('\n')
                n++
                idx = html.indexOf(marker, idx + marker.length)
            }
        }
        val s = windows.toString()
        return if (s.length > MAX_SCAN) s.substring(0, MAX_SCAN) else s
    }

    /**
     * Manual parse of: }('payload',radix,count,'k0|k1|...'.split('|')
     * Avoids catastrophic backtracking from nested regex character classes.
     */
    private fun tryUnpackAt(src: String, start: Int): String? {
        return try {
            if (start + 10 >= src.length || src[start] != '}' || src[start + 1] != '(' || src[start + 2] != '\'') {
                return null
            }
            var i = start + 3
            val p = StringBuilder()
            while (i < src.length) {
                val ch = src[i]
                if (ch == '\\' && i + 1 < src.length) {
                    p.append(src[i + 1])
                    i += 2
                    continue
                }
                if (ch == '\'') break
                p.append(ch)
                i++
                if (p.length > MAX_PAYLOAD) return null
            }
            if (i >= src.length || src[i] != '\'') return null
            i++ // after '
            if (i >= src.length || src[i] != ',') return null
            i++
            // radix
            val radixStart = i
            while (i < src.length && src[i].isDigit()) i++
            if (i == radixStart) return null
            val radix = src.substring(radixStart, i).toIntOrNull() ?: return null
            if (i >= src.length || src[i] != ',') return null
            i++
            // count
            val countStart = i
            while (i < src.length && src[i].isDigit()) i++
            if (i == countStart) return null
            val count = src.substring(countStart, i).toIntOrNull() ?: return null
            if (i + 2 >= src.length || src[i] != ',' || src[i + 1] != '\'') return null
            i += 2
            val kBuilder = StringBuilder()
            while (i < src.length) {
                val ch = src[i]
                if (ch == '\\' && i + 1 < src.length) {
                    kBuilder.append(src[i + 1])
                    i += 2
                    continue
                }
                if (ch == '\'') break
                kBuilder.append(ch)
                i++
                if (kBuilder.length > MAX_PAYLOAD) return null
            }
            // expect '.split('|')
            if (i + 12 >= src.length) return null
            val tail = src.substring(i, (i + 20).coerceAtMost(src.length))
            if (!tail.startsWith("'.split('|')") && !tail.startsWith("'.split(\"|\")")) return null

            val payload = p.toString()
            val keywords = kBuilder.toString().split("|")
            if (payload.length < 40 || keywords.size < 5) return null
            if (radix < 2 || radix > 95) return null
            if (count < 5 || count > MAX_COUNT) return null
            if (keywords.size > MAX_KEYWORDS) return null
            unpack(payload, radix, count, keywords)
        } catch (_: Throwable) {
            null
        }
    }

    /** Iterative base-N encode (no recursion → no StackOverflowError). */
    private fun encodeIter(num: Int, radix: Int): String {
        if (num <= 0) return "0"
        var n = num
        val chars = ArrayList<Char>(8)
        while (n > 0) {
            val rem = n % radix
            chars.add(if (rem > 35) (rem + 29).toChar() else rem.digitToChar(36))
            n /= radix
            if (chars.size > 16) break
        }
        chars.reverse()
        return chars.joinToString("")
    }

    private fun unpack(payload: String, radix: Int, count: Int, keywords: List<String>): String? {
        if (payload.isBlank() || radix < 2 || count <= 0) return null
        val limit = minOf(count, keywords.size + 64, MAX_COUNT)
        val dict = HashMap<String, String>(limit * 2)
        for (i in 0 until limit) {
            val key = encodeIter(i, radix)
            val word = keywords.getOrElse(i) { "" }
            dict[key] = word.ifEmpty { key }
        }
        // Manual word replace — avoid Matcher.appendReplacement overhead / bugs on huge blobs.
        val out = StringBuilder(payload.length + 64)
        var i = 0
        while (i < payload.length) {
            val ch = payload[i]
            if (ch.isLetterOrDigit() || ch == '_') {
                val start = i
                while (i < payload.length) {
                    val c = payload[i]
                    if (c.isLetterOrDigit() || c == '_') i++ else break
                }
                val w = payload.substring(start, i)
                out.append(dict[w] ?: w)
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}

/**
 * playerbtc.com mirror hub used by BokepIndoHot — dropdown tokens decode to
 * playmogo / turbovid / abyssplayer / … which we then resolve to direct media.
 */
internal fun resolvePlayerBtcEmbed(embedUrl: String, referer: String): List<StreamOption> {
    return try {
        val html = NetworkClient.get(embedUrl, referer)
        val mirrors = linkedSetOf<String>()
        // data-src="/pages/redirect.php?token=BASE64" or token=BASE64
        val tok = Pattern.compile(
            """token=([A-Za-z0-9_\-+/=]{16,})""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (tok.find()) {
            val raw = tok.group(1) ?: continue
            // URL-safe / padded base64
            val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
            val decoded = try {
                String(java.util.Base64.getDecoder().decode(padded.replace('-', '+').replace('_', '/')), Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            if (decoded.startsWith("http")) mirrors.add(decoded.trim())
        }
        // Also plain mirror URLs on the page
        val bare = Pattern.compile(
            """(https?://(?:turbovidhls\.com|turboviplay\.com|playmogo\.com|doodstream\.com|abyssplayer\.com|hgcloud\.to|morencius\.com)[^"'\\\s]+)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (bare.find()) {
            bare.group(1)?.let { mirrors.add(it) }
        }
        for (mirror in mirrors.take(8)) {
            try {
                when {
                    mirror.contains("turbovid", true) || mirror.contains("turboviplay", true) -> {
                        val s = resolveTurbovidEmbed(mirror, embedUrl)
                        if (s.isNotEmpty()) return s
                    }
                    isDoodFamily(mirror) -> {
                        val s = resolveDoodStreamEmbed(mirror, embedUrl)
                        if (s.isNotEmpty()) return s
                    }
                    isStreamWishHost(mirror) -> {
                        val s = resolveStreamWishEmbed(mirror, embedUrl)
                        if (s.isNotEmpty()) return s
                    }
                    else -> {
                        val nested = NetworkClient.get(mirror, embedUrl)
                        val direct = collectMp4AndHls(nested, mirror)
                        if (direct.isNotEmpty()) return direct
                        val turbo = resolveTurbovidEmbed(mirror, embedUrl)
                        if (turbo.isNotEmpty()) return turbo
                        if (nested.contains("pass_md5", true)) {
                            val dood = resolveDoodStreamEmbed(mirror, embedUrl)
                            if (dood.isNotEmpty()) return dood
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun isDoodHost(url: String): Boolean {
    val h = url.lowercase()
    return h.contains("playmogo") || h.contains("doodstream") || h.contains("dood.") ||
        h.contains("ds2play") || h.contains("doply") || h.contains("doodcdn")
}

private fun isDoodFamily(url: String): Boolean = isDoodHost(url)

/**
 * DoodStream-family embeds (playmogo, doodstream, …):
 * GET /pass_md5/{hash}/{token} → bare CDN URL body, append ?token=
 */
internal fun resolveDoodStreamEmbed(embedUrl: String, referer: String): List<StreamOption> {
    return try {
        val html = NetworkClient.get(embedUrl, referer)
        val passPath = NetworkClient.matchFirst(html, """(/pass_md5/[a-zA-Z0-9_./-]+)""")
            ?: return emptyList()
        val token = NetworkClient.matchFirst(html, """[?&]token=([a-zA-Z0-9]+)""")
            ?: NetworkClient.matchFirst(html, """token['"]?\s*[:=]\s*['"]([a-zA-Z0-9]+)['"]""")
        val origin = try {
            val u = java.net.URI(embedUrl)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            "https://playmogo.com"
        }
        val passUrl = if (passPath.startsWith("http")) passPath else "$origin$passPath"
        val cdnBase = NetworkClient.get(passUrl, embedUrl).trim()
        if (!cdnBase.startsWith("http")) return emptyList()
        val finalUrl = if (!token.isNullOrBlank() && !cdnBase.contains("token=")) {
            if (cdnBase.contains("?")) "$cdnBase&token=$token" else "$cdnBase?token=$token"
        } else {
            cdnBase
        }
        listOf(StreamOption("MP4", finalUrl))
    } catch (_: Throwable) {
        emptyList()
    }
}

/** Turbovid / turboviplay / emturbovid HLS embeds — direct m3u8, no packer. */
internal fun resolveTurbovidEmbed(embedUrl: String, referer: String): List<StreamOption> {
    return try {
        // emturbovid.com permanently redirects to turbovidhls.com
        val url = embedUrl.replace("emturbovid.com", "turbovidhls.com", ignoreCase = true)
        // Prefer page referer (javmost), fall back to turbovid origin
        val html = try {
            NetworkClient.getAllowingError(url, referer)
        } catch (_: Exception) {
            NetworkClient.getAllowingError(url, "https://turbovidhls.com/")
        }
        if (html.isBlank() || html.length < 200) return emptyList()
        val out = linkedMapOf<String, StreamOption>()
        fun addUrl(raw: String?, labelHint: String? = null) {
            val u = raw
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.replace("&amp;", "&")
                ?.trim()
                .orEmpty()
                .trimEnd('\\', '"', '\'', ')', ']', '}', ',', ' ')
            if (u.isBlank() || !isValidMediaUrl(u)) return
            if (u.contains("thumb", true) || u.contains("preview", true)) return
            val label = labelHint ?: when {
                u.contains("1080") -> "1080p"
                u.contains("720") -> "720p"
                u.contains("480") -> "480p"
                u.contains("m3u8") -> "Auto (HLS)"
                else -> "MP4"
            }
            out.putIfAbsent(label + "|" + u, StreamOption(label, u))
        }
        // Working player: <div id="video_player" data-hash="https://cdn.turboviplay.com/data3/...m3u8">
        val dataHash = Pattern.compile(
            """data-hash\s*=\s*["'](https?://[^"']+)["']""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (dataHash.find()) addUrl(dataHash.group(1), "Auto (HLS)")
        // data-hash without quotes
        if (out.isEmpty()) {
            val bareHash = Pattern.compile(
                """data-hash\s*=\s*(https?://\S+)""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (bareHash.find()) addUrl(bareHash.group(1)?.trimEnd('"', '\'', '>', ' '), "Auto (HLS)")
        }
        val m = Pattern.compile(
            """(https?://(?:cdn\.)?turboviplay\.com/[^"'\\\s]+\.m3u8[^"'\\\s]*)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find()) addUrl(m.group(1))
        // urlPlay = 'https://...' — skip dead https://.etvp.cc hosts via isValidMediaUrl
        val urlPlay = Pattern.compile(
            """urlPlay\s*=\s*['"](https?://[^'"]+)['"]""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (urlPlay.find()) addUrl(urlPlay.group(1))
        if (out.isEmpty()) {
            val loose = Pattern.compile(
                """(https?://[^"'\\\s]+\.m3u8[^"'\\\s]*)""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            while (loose.find()) addUrl(loose.group(1), "Auto (HLS)")
        }
        // Prefer HLS over progressive when both present
        val list = out.values.toList()
        val hls = list.filter { it.url.contains("m3u8", true) }
        if (hls.isNotEmpty()) hls else list
    } catch (_: Throwable) {
        emptyList()
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
    // Turbovid pages: extract m3u8 without packer (packer SOE crash).
    if (embedUrl.contains("turbovid", true) || embedUrl.contains("turboviplay", true) ||
        html.contains("turboviplay", true)
    ) {
        val turbo = resolveTurbovidEmbed(embedUrl, referer)
        if (turbo.isNotEmpty()) return turbo
    }
    val unpacked = try {
        JsPackerUnpacker.unpackAll(html)
    } catch (_: Throwable) {
        ""
    }
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
    val mp4 = Pattern.compile(
        """(https?://[^"'\\\s]+\.mp4[^"'\\\s]*)""",
        Pattern.CASE_INSENSITIVE,
    ).matcher(blob)
    while (mp4.find()) {
        val u = mp4.group(1)?.replace("\\/", "/") ?: continue
        if (u.contains("preview") || u.contains("thumb")) continue
        options.putIfAbsent(u, StreamOption("MP4", u))
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

internal fun isStreamWishHost(url: String): Boolean {
    val h = url.lowercase()
    return h.contains("playerwish") ||
        h.contains("strwish") ||
        h.contains("streamwish") ||
        h.contains("swishsrv") ||
        h.contains("hlswish") ||
        h.contains("hicherri") ||
        h.contains("rubyvidhub") ||
        h.contains("streamruby") ||
        h.contains("rubystream") ||
        h.contains("savefiles") ||
        h.contains("bigwarp") ||
        h.contains("filemoon") ||
        h.contains("luluvdo") ||
        h.contains("luluvid") ||
        h.contains("vidhide")
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
