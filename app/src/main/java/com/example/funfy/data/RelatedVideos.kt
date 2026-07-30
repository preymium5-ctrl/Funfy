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
    val selfSlug = pageUrl.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('#').lowercase()
    val selfTitle = details.title.trim().lowercase()

    fun normalizeUrl(u: String): String = u.trim().trimEnd('/')
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")

    val selfUrlNorm = normalizeUrl(pageUrl)

    val seenKeys = mutableSetOf<String>()
    if (selfUrlNorm.isNotBlank()) seenKeys.add("u:$selfUrlNorm")
    if (selfTitle.length >= 4) seenKeys.add("t:$selfTitle")
    if (selfSlug.length >= 4) seenKeys.add("i:$selfSlug")

    fun isDuplicateOrSelf(item: VideoItem): Boolean {
        val normTitle = item.title.trim().lowercase()
        val normUrl = normalizeUrl(item.pageUrl)
        val normId = item.id.trim().lowercase()

        if (normUrl.isNotBlank() && normUrl == selfUrlNorm) return true
        if (normTitle.isNotBlank() && selfTitle.isNotBlank() && normTitle == selfTitle) return true
        if (selfSlug.length >= 4) {
            if (normId == selfSlug) return true
            if (normUrl.contains(selfSlug)) return true
        }

        val keyUrl = if (normUrl.isNotBlank()) "u:$normUrl" else ""
        val keyId = if (normId.isNotBlank()) "i:$normId" else ""
        val keyTitle = if (normTitle.length >= 5) "t:$normTitle" else ""

        if (keyUrl.isNotBlank() && seenKeys.contains(keyUrl)) return true
        if (keyId.isNotBlank() && seenKeys.contains(keyId)) return true
        if (keyTitle.isNotBlank() && seenKeys.contains(keyTitle)) return true

        if (keyUrl.isNotBlank()) seenKeys.add(keyUrl)
        if (keyId.isNotBlank()) seenKeys.add(keyId)
        if (keyTitle.isNotBlank()) seenKeys.add(keyTitle)
        return false
    }

    val scraped = details.related.filterNot(::isDuplicateOrSelf)

    if (scraped.size >= 40) {
        return details.copy(related = scraped.take(40))
    }

    val merged = mutableListOf<VideoItem>()
    for (item in scraped) {
        merged.add(item.copy(sourceId = item.sourceId.ifBlank { client.source.id }))
    }

    val query = relatedQueryFromTitle(details.title, details.tags)
    if (query.isNotBlank()) {
        try {
            val found = client.search(query, page = 1)
            for (item in found) {
                if (isDuplicateOrSelf(item)) continue
                merged.add(
                    item.copy(
                        sourceId = item.sourceId.ifBlank { client.source.id },
                        category = item.category.ifBlank { client.source.label },
                    ),
                )
                if (merged.size >= 40) break
            }
        } catch (_: Exception) {
        }
    }

    if (merged.size < 10) {
        try {
            val home = client.fetchHomeVideos(1)
            for (item in home) {
                if (isDuplicateOrSelf(item)) continue
                merged.add(
                    item.copy(
                        sourceId = item.sourceId.ifBlank { client.source.id },
                        category = item.category.ifBlank { client.source.label },
                    ),
                )
                if (merged.size >= 40) break
            }
        } catch (_: Exception) {
        }
    }

    return details.copy(related = merged.take(40))
}
