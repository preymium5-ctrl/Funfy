package com.example.funfy.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface DataRepository {
  val currentSource: Flow<VideoSource>
  fun getSource(): VideoSource
  fun setSource(source: VideoSource)
  suspend fun fetchHomePage(page: Int): List<VideoItem>
  /** Search current source; [page] is 1-based. */
  suspend fun search(query: String, page: Int = 1): List<VideoItem>
  /**
   * Fetch videos for an official tag/category.
   * On XVideos sources uses `/c/...` paths so results are truly related to that tag.
   */
  suspend fun fetchByTag(tag: ContentTag, page: Int = 1): List<VideoItem>
  suspend fun fetchVideoDetails(pageUrl: String): VideoDetails
}

class DefaultDataRepository(
  private val prefs: SourcePreferences,
) : DataRepository {

  private val sourceState = MutableStateFlow(prefs.getSource())

  override val currentSource: Flow<VideoSource> = sourceState

  override fun getSource(): VideoSource = sourceState.value

  override fun setSource(source: VideoSource) {
    val selectableSource = source.takeIf { it.isSelectable } ?: VideoSource.DEFAULT
    prefs.setSource(selectableSource)
    sourceState.value = selectableSource
  }

  override suspend fun fetchHomePage(page: Int): List<VideoItem> =
    SourceRegistry.client(sourceState.value).fetchHomeVideos(page.coerceAtLeast(1))

  override suspend fun search(query: String, page: Int): List<VideoItem> {
    val client = SourceRegistry.client(sourceState.value)
    return client.search(query, page.coerceAtLeast(1))
  }

  override suspend fun fetchByTag(tag: ContentTag, page: Int): List<VideoItem> {
    val p = page.coerceAtLeast(1)
    val client = SourceRegistry.client(sourceState.value)
    // A regional feed must retain its fixed provider query for every category chip.
    if (client is XnxxApi && client.isScopedFeed) {
      return client.search(tag.keyword, p)
    }
    // Prefer real XVideos category pages for accurate related results
    if (client is XnxxApi && !tag.categoryPath.isNullOrBlank()) {
      val items = client.fetchByCategoryPath(tag.categoryPath, p)
      if (items.isNotEmpty()) return items
    }
    // Tag slug path (e.g. /tags/amateur)
    if (client is XnxxApi) {
      val slug = tag.keyword.replace(' ', '-').lowercase()
      val byTag = client.fetchByTagSlug(slug, p)
      if (byTag.isNotEmpty()) return byTag
      return client.search(tag.keyword, p)
    }
    // Other sources: keyword search
    return client.search(tag.keyword, p)
  }

  override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails {
    if (pageUrl.startsWith("file:") || pageUrl.startsWith("/")) {
      throw IllegalStateException("Local file — play offline without remote fetch")
    }
    val client = SourceRegistry.clientForUrl(pageUrl, fallback = sourceState.value)
    val details = client.fetchVideoDetails(pageUrl)
    // Universal related: every source gets a related row, query-matched when possible.
    return try {
      enrichRelatedVideos(details, pageUrl, client)
    } catch (_: Exception) {
      details
    }
  }
}
