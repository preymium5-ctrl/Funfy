package com.example.funfy.data

/** Contract each content source must implement. */
interface VideoSourceClient {
    val source: VideoSource

    /** 1-based page of home listings. */
    suspend fun fetchHomeVideos(page: Int = 1): List<VideoItem>

    suspend fun search(query: String): List<VideoItem>

    /**
     * Paged search when the provider supports it. The default keeps legacy clients honest by
     * returning no synthetic duplicate pages.
     */
    suspend fun search(query: String, page: Int): List<VideoItem> =
        if (page <= 1) search(query) else emptyList()

    suspend fun fetchVideoDetails(pageUrl: String): VideoDetails

    fun matchesUrl(url: String): Boolean =
        source.hostHints.any { url.contains(it, ignoreCase = true) }
}

/** Adds a regional scope unless it already exists as complete, adjacent words. */
internal fun combineScopedSearchQuery(query: String, fixedKeyword: String?): String {
    val typed = query.trim().replace(Regex("""\s+"""), " ")
    val scope = fixedKeyword?.trim()?.replace(Regex("""\s+"""), " ").orEmpty()
    if (scope.isBlank()) return typed
    if (typed.isBlank()) return scope

    fun words(value: String): List<String> = Regex("""[\p{L}\p{N}]+""")
        .findAll(value.lowercase())
        .map { it.value }
        .toList()

    val typedWords = words(typed)
    val scopeWords = words(scope)
    val containsScope = scopeWords.isNotEmpty() &&
        typedWords.windowed(scopeWords.size).any { it == scopeWords }
    return if (containsScope) typed else "$scope $typed"
}
