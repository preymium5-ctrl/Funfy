package com.example.funfy.data

import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * Resolve the **exact** cover for a video page URL.
 *
 * Important: many sites (esp. Buumal) do **not** put the real cover on the
 * watch page — only on listing cards. Scraping random page images yields the
 * wrong thumbnail (logos / related). This resolver is host-aware.
 */
object ThumbnailResolver {

    fun needsRepair(thumbnailUrl: String): Boolean {
        val u = thumbnailUrl.trim()
        if (u.isBlank()) return true
        if (!u.startsWith("http://") && !u.startsWith("https://")) return true
        if (u.any { it == ' ' || it.code > 127 }) return true
        return isWeakCover(u)
    }

    /**
     * True when the stored thumb is unlikely to be the correct video cover
     * (short CDN logos, related-rail generic assets, etc.).
     */
    fun isWeakCover(url: String): Boolean {
        val name = url.substringAfterLast('/').substringBefore('?')
        if (name.isBlank()) return true
        // Buumal logos: y5dzbUJb.png, KQ8DqkW.jpg
        if (Regex("""^[A-Za-z0-9]{4,14}\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE).matches(name)) {
            return true
        }
        // Partial dated paths without extension often related rail
        if (url.contains("img.buumal.com", true) &&
            !url.contains(".jpg", true) &&
            !url.contains(".jpeg", true) &&
            !url.contains(".png", true) &&
            !url.contains(".webp", true)
        ) {
            return true
        }
        val lower = url.lowercase()
        return lower.contains("logo") ||
            lower.contains("favicon") ||
            lower.contains("sprite") ||
            lower.contains("placeholder") ||
            lower.contains("preload") ||
            lower.contains("/assets/img/") ||
            lower.contains("1x1") ||
            lower.contains("/i/") // buumal /i/samu-xxx rails
    }

    /**
     * Resolve cover for [pageUrl]. Prefer listing/search match for Buumal;
     * og:image for most other hosts. Returns sanitized absolute URL or "".
     */
    fun fromPage(pageUrl: String): String {
        if (pageUrl.isBlank() || pageUrl.startsWith("file:") || pageUrl.startsWith("/")) {
            return ""
        }
        val host = runCatching { java.net.URI(pageUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val raw = when {
            host.contains("buumal") -> resolveBuumal(pageUrl)
            host.contains("javmost") || host.contains("supjav") -> resolveJavMost(pageUrl)
            else -> resolveGeneric(pageUrl)
        }
        return NetworkClient.sanitizeMediaUrl(raw).takeIf {
            it.startsWith("http") && !isWeakCover(it)
        }.orEmpty()
    }

    // ------------------------------------------------------------------ Buumal

    /**
     * Buumal watch pages have no og:image / poster. Real cover is on the listing
     * card for `/video/{id}`. Strategy:
     * 1) Search `?search={id}` (or home pages) for that card + nearby img
     * 2) Derive cover from the R2 mp4 path on the watch page
     */
    private fun resolveBuumal(pageUrl: String): String {
        val id = pageUrl.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (id.isBlank()) return ""

        // 1) Search by id — homepage may rotate; search hits the card reliably.
        val searchUrls = listOf(
            "https://www.buumal.com/?search=$id",
            "https://www.buumal.com/",
            "https://www.buumal.com/?page=1",
            "https://www.buumal.com/?page=2",
            "https://www.buumal.com/?page=3",
        )
        for (url in searchUrls) {
            val html = try {
                NetworkClient.get(url, "https://www.buumal.com/")
            } catch (_: Exception) {
                continue
            }
            val fromCard = extractBuumalCardThumb(html, id)
            if (fromCard.isNotBlank()) return fromCard
        }

        // 2) Derive from mp4 path on the watch page itself
        val pageHtml = try {
            NetworkClient.get(pageUrl, "https://www.buumal.com/")
        } catch (_: Exception) {
            return ""
        }
        val fromMp4 = extractBuumalThumbFromMp4(pageHtml)
        if (fromMp4.isNotBlank()) return fromMp4

        return ""
    }

    /** Card: href="…/video/{id}" … src="https://img.buumal.com/YYYY/MM/…jpg" */
    private fun extractBuumalCardThumb(html: String, videoId: String): String {
        val idEsc = Pattern.quote(videoId)
        // href then nearby image (card layout)
        val after = Pattern.compile(
            """href="(?:https?://(?:www\.)?buumal\.com)?/video/$idEsc"[\s\S]{0,1200}?(?:data-src|src)="(https?://img\.buumal\.com/[^"]+)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        if (after.find()) {
            val t = after.group(1).orEmpty()
            if (t.isNotBlank() && !isWeakCover(t)) return t
        }
        // image then nearby href (alternate markup)
        val before = Pattern.compile(
            """(?:data-src|src)="(https?://img\.buumal\.com/[^"]+)"[\s\S]{0,800}?href="(?:https?://(?:www\.)?buumal\.com)?/video/$idEsc"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        if (before.find()) {
            val t = before.group(1).orEmpty()
            if (t.isNotBlank() && !isWeakCover(t)) return t
        }
        // Window around first occurrence of /video/id
        val marker = "/video/$videoId"
        var idx = html.indexOf(marker, ignoreCase = true)
        if (idx < 0) idx = html.indexOf(videoId, ignoreCase = true)
        if (idx >= 0) {
            val window = html.substring(
                (idx - 400).coerceAtLeast(0),
                (idx + 1000).coerceAtMost(html.length),
            )
            val t = NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://img\.buumal\.com/20\d{2}/\d{2}/[^"]+)"""",
            ) ?: NetworkClient.matchFirst(
                window,
                """(?:data-src|src)="(https?://img\.buumal\.com/[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            )
            if (!t.isNullOrBlank() && !isWeakCover(t)) return t
        }
        return ""
    }

    /**
     * Watch page embeds:
     * `…r2…/mmlovetv/2026/07/mmlovetv-17957%20….mp4?…`
     * Cover is usually:
     * `https://img.buumal.com/2026/07/mmlovetv-17957 ….jpg`
     */
    private fun extractBuumalThumbFromMp4(html: String): String {
        val m = Pattern.compile(
            """https?://[^"'\\\s]+\.r2\.cloudflarestorage\.com/([^"'?\s]+\.mp4)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        if (!m.find()) return ""
        val encodedPath = m.group(1) ?: return ""
        val path = try {
            URLDecoder.decode(encodedPath, Charsets.UTF_8.name())
        } catch (_: Exception) {
            encodedPath.replace("%20", " ")
        }
        // mmlovetv/2026/07/file.mp4  →  2026/07/file.jpg
        val withoutBucket = path.replace(Regex("""^[^/]+/"""), "")
        val jpgRel = withoutBucket.replace(Regex("""\.mp4$""", RegexOption.IGNORE_CASE), ".jpg")
        if (jpgRel.isBlank() || jpgRel == withoutBucket) return ""
        return "https://img.buumal.com/$jpgRel"
    }

    // ---------------------------------------------------------------- JavMost

    private fun resolveJavMost(pageUrl: String): String {
        val html = try {
            NetworkClient.get(pageUrl, "https://www.javmost.ws/")
        } catch (_: Exception) {
            return ""
        }
        // og:image is the correct cover for this title
        NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            ?.takeIf { !isWeakCover(it) }
            ?.let { return it }

        val slug = pageUrl.trimEnd('/').substringAfterLast('/')
        if (slug.isNotBlank()) {
            // Stable CDN path used by listings
            val fileImage = "https://img2.javmost.ws/file_image/$slug.jpg"
            // Prefer data-srcset matching this slug
            val srcset = Pattern.compile(
                """data-srcset=["'](https?://img\d*\.javmost\.[^"']*$slug[^"']*)["']""",
                Pattern.CASE_INSENSITIVE,
            ).matcher(html)
            if (srcset.find()) {
                val t = srcset.group(1).orEmpty()
                if (t.isNotBlank() && !isWeakCover(t)) return t
            }
            return fileImage
        }
        return ""
    }

    // ---------------------------------------------------------------- Generic

    private fun resolveGeneric(pageUrl: String): String {
        val html = try {
            NetworkClient.get(pageUrl, NetworkClient.siteReferer(pageUrl))
        } catch (_: Exception) {
            return ""
        }
        val og = NetworkClient.matchFirst(html, """property="og:image"\s+content="([^"]+)"""")
            ?: NetworkClient.matchFirst(html, """name="twitter:image"\s+content="([^"]+)"""")
            ?: NetworkClient.matchFirst(html, """property="og:image:secure_url"\s+content="([^"]+)"""")
        if (!og.isNullOrBlank() && !isWeakCover(og)) {
            return if (og.startsWith("//")) "https:$og" else og
        }
        // Last resort: first non-weak absolute image (prefer larger CDN paths)
        val imgs = mutableListOf<String>()
        val m = Pattern.compile(
            """(?:data-src|data-original|src)="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        while (m.find() && imgs.size < 12) {
            m.group(1)?.let { imgs.add(it) }
        }
        return imgs.firstOrNull { !isWeakCover(it) }.orEmpty()
    }
}
