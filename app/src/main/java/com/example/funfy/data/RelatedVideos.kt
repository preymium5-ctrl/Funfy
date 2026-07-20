package com.example.funfy.data

/**
 * Build a search query from a video title so related results actually match
 * the watched content (JAV codes, meaningful words).
 */
internal fun relatedQueryFromTitle(title: String, tags: List<String> = emptyList()): String {
    val tagBits = tags
        .map { it.trim() }
        .filter { it.length in 2..40 }
        .take(2)
    if (tagBits.isNotEmpty()) {
        return tagBits.joinToString(" ")
    }

    val raw = title
        .substringBefore(" | ")
        .substringBefore(" - ")
        .substringBefore(" – ")
        .trim()

    // Prefer product / JAV / FC2 style codes when present.
    val code = Regex(
        """\b([A-Za-z]{2,10}-?\d{2,5}[A-Za-z]?)\b""",
        RegexOption.IGNORE_CASE,
    ).find(raw)?.value
        ?: Regex("""\b(fc2[-_]?ppv[-_]?\d+)\b""", RegexOption.IGNORE_CASE).find(raw)?.value
    if (!code.isNullOrBlank() && code.length >= 4) {
        return code
    }

    val stop = setOf(
        "the", "and", "for", "with", "from", "that", "this", "your", "you", "her", "his",
        "she", "him", "they", "them", "are", "was", "were", "have", "has", "had", "not",
        "but", "all", "can", "out", "our", "how", "who", "why", "when", "what", "into",
        "over", "under", "after", "before", "video", "watch", "free", "hd", "full",
        "uncensored", "censored", "episode", "official", "trailer", "new", "best",
        "porn", "xxx", "jav", "av", "mp4", "online", "stream", "download",
    )
    val words = Regex("""[\p{L}\p{N}]+""")
        .findAll(raw)
        .map { it.value }
        .filter { token ->
            val t = token.lowercase()
            t.length >= 3 && t !in stop && !t.all { it.isDigit() }
        }
        .distinct()
        .take(5)
        .toList()
    return words.joinToString(" ").ifBlank { raw.take(48).trim() }
}

/**
 * Ensure [details] has related videos: keep scraper results, then fill via same-source search.
 */
internal suspend fun enrichRelatedVideos(
    details: VideoDetails,
    pageUrl: String,
    client: VideoSourceClient,
): VideoDetails {
    val selfSlug = pageUrl.trimEnd('/').substringAfterLast('/').substringBefore('?')
        .substringBefore('#')
    fun isSelf(item: VideoItem): Boolean {
        if (item.pageUrl.isNotBlank() && item.pageUrl.equals(pageUrl, ignoreCase = true)) {
            return true
        }
        if (selfSlug.length >= 4) {
            if (item.id.equals(selfSlug, ignoreCase = true)) return true
            if (item.pageUrl.contains(selfSlug, ignoreCase = true)) return true
        }
        return false
    }

    val scraped = details.related
        .filterNot(::isSelf)
        .distinctBy { it.pageUrl.ifBlank { it.id } }

    if (scraped.size >= 10) {
        return details.copy(related = scraped.take(16))
    }

    val merged = linkedMapOf<String, VideoItem>()
    for (item in scraped) {
        merged[item.pageUrl.ifBlank { item.id }] = item.copy(
            sourceId = item.sourceId.ifBlank { client.source.id },
        )
    }

    val query = relatedQueryFromTitle(details.title, details.tags)
    if (query.isNotBlank()) {
        try {
            val found = client.search(query, page = 1)
            for (item in found) {
                if (isSelf(item)) continue
                val key = item.pageUrl.ifBlank { item.id }
                if (key.isBlank() || merged.containsKey(key)) continue
                merged[key] = item.copy(
                    sourceId = item.sourceId.ifBlank { client.source.id },
                    category = item.category.ifBlank { client.source.label },
                )
                if (merged.size >= 16) break
            }
        } catch (_: Exception) {
        }
    }

    // Last resort: same-source home feed so the section is never empty when the site works.
    if (merged.size < 6) {
        try {
            val home = client.fetchHomeVideos(1)
            for (item in home) {
                if (isSelf(item)) continue
                val key = item.pageUrl.ifBlank { item.id }
                if (key.isBlank() || merged.containsKey(key)) continue
                merged[key] = item.copy(
                    sourceId = item.sourceId.ifBlank { client.source.id },
                    category = item.category.ifBlank { client.source.label },
                )
                if (merged.size >= 16) break
            }
        } catch (_: Exception) {
        }
    }

    return details.copy(related = merged.values.toList().take(16))
}
