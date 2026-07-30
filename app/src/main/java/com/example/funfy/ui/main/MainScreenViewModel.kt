package com.example.funfy.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.funfy.data.BookmarkStore
import com.example.funfy.data.BookmarkedVideo
import com.example.funfy.data.ContentTag
import com.example.funfy.data.DataRepository
import com.example.funfy.data.DownloadStore
import com.example.funfy.data.DownloadTransfer
import com.example.funfy.data.LocalDownload
import com.example.funfy.data.MediaFolder
import com.example.funfy.data.SearchHistoryEntry
import com.example.funfy.data.SearchHistoryStore
import com.example.funfy.data.ThumbnailResolver
import com.example.funfy.data.VideoItem
import com.example.funfy.data.VideoSource
import com.example.funfy.data.XvideosTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainScreenViewModel(
  private val dataRepository: DataRepository,
  private val downloadStore: DownloadStore? = null,
  private val bookmarkStore: BookmarkStore? = null,
  private val searchHistoryStore: SearchHistoryStore? = null,
  private val isAutoShuffle: () -> Boolean = { false },
) : ViewModel() {

  private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Loading)
  val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

  val currentSource: StateFlow<VideoSource> =
    dataRepository.currentSource
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dataRepository.getSource())

  private val _searchResults = MutableStateFlow<List<VideoItem>?>(null)
  val searchResults: StateFlow<List<VideoItem>?> = _searchResults.asStateFlow()

  /** Survives player overlay / tab recompose so Back keeps the query + results. */
  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _searchLoading = MutableStateFlow(false)
  val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

  private val _searchError = MutableStateFlow<String?>(null)
  val searchError: StateFlow<String?> = _searchError.asStateFlow()

  private val _searchSource = MutableStateFlow<VideoSource?>(null)
  val searchSource: StateFlow<VideoSource?> = _searchSource.asStateFlow()

  private val _searchPageLoading = MutableStateFlow(false)
  val searchPageLoading: StateFlow<Boolean> = _searchPageLoading.asStateFlow()

  private val _searchHasMore = MutableStateFlow(false)
  val searchHasMore: StateFlow<Boolean> = _searchHasMore.asStateFlow()

  val searchHistory: StateFlow<List<SearchHistoryEntry>> =
    searchHistoryStore?.history
      ?: MutableStateFlow<List<SearchHistoryEntry>>(emptyList()).asStateFlow()

  private val _pageMap = MutableStateFlow<Map<Int, List<VideoItem>>>(emptyMap())
  val pageMap: StateFlow<Map<Int, List<VideoItem>>> = _pageMap.asStateFlow()

  private val _loadedPages = MutableStateFlow(0)
  val loadedPages: StateFlow<Int> = _loadedPages.asStateFlow()

  private val _hasMore = MutableStateFlow(true)
  val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

  private val _pageLoading = MutableStateFlow(false)
  val pageLoading: StateFlow<Boolean> = _pageLoading.asStateFlow()

  /**
   * Highest page number unlocked on the home pager (starts at 6).
   * Tapping the last unlocked page adds [PAGE_EXPAND_STEP] more.
   * The UI only *shows* a sliding window of [PAGE_WINDOW_SIZE] numbers.
   */
  private val _visiblePageCount = MutableStateFlow(INITIAL_VISIBLE_PAGES)
  val visiblePageCount: StateFlow<Int> = _visiblePageCount.asStateFlow()

  /** Home pager index (1-based). Survives player overlay so Back restores page 2/3/…. */
  private val _homePage = MutableStateFlow(1)
  val homePage: StateFlow<Int> = _homePage.asStateFlow()

  fun setHomePage(page: Int) {
    val p = page.coerceAtLeast(1)
    _homePage.value = p
    // Clicking the last unlocked page reveals 3 more (e.g. 6 → 9 → 12).
    if (p >= _visiblePageCount.value && _hasMore.value) {
      _visiblePageCount.value = p + PAGE_EXPAND_STEP
    }
  }

  fun setSearchQuery(query: String) {
    _searchQuery.value = query
  }

  private val _activeTag = MutableStateFlow<ContentTag?>(null)
  val activeTag: StateFlow<ContentTag?> = _activeTag.asStateFlow()

  val downloads: StateFlow<List<LocalDownload>> =
    downloadStore?.downloads ?: MutableStateFlow<List<LocalDownload>>(emptyList()).asStateFlow()

  val downloadTransfers: StateFlow<List<DownloadTransfer>> =
    downloadStore?.transfers ?: MutableStateFlow<List<DownloadTransfer>>(emptyList()).asStateFlow()

  val bookmarks: StateFlow<List<BookmarkedVideo>> =
    bookmarkStore?.bookmarks
      ?: MutableStateFlow<List<BookmarkedVideo>>(emptyList()).asStateFlow()

  val bookmarkFolders: StateFlow<List<MediaFolder>> =
    bookmarkStore?.folders
      ?: MutableStateFlow<List<MediaFolder>>(emptyList()).asStateFlow()

  val downloadFolders: StateFlow<List<MediaFolder>> =
    downloadStore?.folders
      ?: MutableStateFlow<List<MediaFolder>>(emptyList()).asStateFlow()

  fun removeBookmark(id: String) {
    bookmarkStore?.remove(id)
  }

  fun moveBookmarkToFolder(id: String, folderId: String?) {
    bookmarkStore?.moveToFolder(id, folderId)
  }

  fun createBookmarkFolder(name: String) {
    bookmarkStore?.createFolder(name)
  }

  fun deleteBookmarkFolder(folderId: String) {
    bookmarkStore?.deleteFolder(folderId)
  }

  fun moveDownloadToFolder(id: String, folderId: String?) {
    downloadStore?.moveToFolder(id, folderId)
  }

  fun createDownloadFolder(name: String) {
    downloadStore?.createFolder(name)
  }

  fun deleteDownloadFolder(folderId: String) {
    downloadStore?.deleteFolder(folderId)
  }

  fun clearBookmarks() {
    bookmarkStore?.clear()
  }

  /** Call after Auto shuffle setting changes so the next page load reshuffles. */
  fun onAutoShuffleChanged() {
    _homePage.value = 1
    reloadFromSource()
  }

  private var loadJob: Job? = null
  private var searchJob: Job? = null
  private var searchGeneration = 0L
  private var activeSearchQuery = ""
  private var activeSearchSource: VideoSource? = null
  private var activeSearchPage = 0

  private var thumbRepairJob: Job? = null
  private var thumbRepairDone = false

  init {
    reloadFromSource()
    // Repair legacy bookmark covers (wrong logo / unencoded Buumal paths).
    refreshBookmarkThumbnails()
  }

  /**
   * Re-resolve every bookmark cover from its page URL (listing match / og:image).
   * Always rewrites when a better cover is found — previous repairs could save
   * the wrong related-rail image and mark it as “valid”.
   */
  fun refreshBookmarkThumbnails(force: Boolean = false) {
    val store = bookmarkStore ?: return
    if (!force && thumbRepairDone) return
    if (thumbRepairJob?.isActive == true) {
      if (!force) return
      thumbRepairJob?.cancel()
    }
    thumbRepairJob = viewModelScope.launch {
      // Always re-resolve every bookmark: older scrapes often saved related-rail
      // images (Buumal watch pages have no real cover).
      val targets = store.bookmarks.value
      if (targets.isEmpty()) {
        thumbRepairDone = true
        return@launch
      }
      for (bm in targets) {
        try {
          val thumb = withContext(Dispatchers.IO) {
            ThumbnailResolver.fromPage(bm.pageUrl)
          }
          if (thumb.isNotBlank() && !ThumbnailResolver.isWeakCover(thumb)) {
            store.updateThumbnail(bm.id, thumb)
          }
        } catch (_: CancellationException) {
          throw CancellationException()
        } catch (_: Exception) {
          // Keep old thumb; try next bookmark.
        }
      }
      thumbRepairDone = true
    }
  }

  fun setSource(source: VideoSource) {
    if (source == dataRepository.getSource()) return
    dataRepository.setSource(source)
    clearSearch()
    _activeTag.value = null
    _homePage.value = 1
    reloadFromSource()
  }

  fun setTagFilter(tag: ContentTag?) {
    if (_activeTag.value?.label == tag?.label &&
      _activeTag.value?.categoryPath == tag?.categoryPath
    ) {
      return
    }
    _activeTag.value = tag
    _homePage.value = 1
    reloadFromSource()
  }

  fun setTagFilterByLabel(label: String?) {
    setTagFilter(XvideosTags.fromLabel(label))
  }

  fun reloadFromSource() {
    loadJob?.cancel()
    _pageMap.value = emptyMap()
    _loadedPages.value = 0
    _hasMore.value = true
    _visiblePageCount.value = INITIAL_VISIBLE_PAGES
    // Keep _homePage unless caller already reset it (source/tag). Retry should stay on page.
    _uiState.value = MainScreenUiState.Loading
    // Prefetch the first 8 pages so all home page buttons work immediately.
    ensurePageLoaded(INITIAL_VISIBLE_PAGES)
  }

  fun ensurePageLoaded(page: Int) {
    val p = page.coerceAtLeast(1)
    if (_pageMap.value.containsKey(p)) {
      emitSuccess()
      return
    }
    if (!_hasMore.value && p > _loadedPages.value) {
      emitSuccess()
      return
    }

    loadJob?.cancel()
    loadJob = viewModelScope.launch {
      _pageLoading.value = true
      try {
        var next = (_loadedPages.value + 1).coerceAtLeast(1)
        while (next <= p) {
          if (!_pageMap.value.containsKey(next)) {
            val rawItems = fetchPage(next)
            // Auto shuffle only affects home feed order (not tag search pages).
            val items = if (isAutoShuffle() && _activeTag.value == null && rawItems.isNotEmpty()) {
              rawItems.shuffled()
            } else {
              rawItems
            }
            val prev = _pageMap.value[next - 1]
            val isDuplicate =
              prev != null &&
                items.isNotEmpty() &&
                items.map { pageItemKey(it) }.toSet() == prev.map { pageItemKey(it) }.toSet()

            val map = _pageMap.value.toMutableMap()
            map[next] = items
            _pageMap.value = map
            _loadedPages.value = maxOf(_loadedPages.value, next)

            // Keep paging until the site returns an empty page or a full duplicate
            // of the previous page (true end of catalog / broken pagination).
            if (items.isEmpty() || isDuplicate) {
              _hasMore.value = false
              break
            }
            _hasMore.value = true
          }
          next++
        }
        emitSuccess()
      } catch (t: Throwable) {
        if (_pageMap.value.isEmpty()) {
          _uiState.value = MainScreenUiState.Error(t)
        } else {
          _hasMore.value = false
          emitSuccess()
        }
      } finally {
        _pageLoading.value = false
      }
    }
  }

  fun pageItems(page: Int): List<VideoItem> = _pageMap.value[page].orEmpty()

  fun search(query: String) {
    if (query.isBlank()) {
      clearSearch()
      return
    }
    val normalizedQuery = query.trim()
    _searchQuery.value = normalizedQuery
    val source = dataRepository.getSource()
    // Skip re-fetch when returning from player with the same query still active.
    if (
      activeSearchQuery.equals(normalizedQuery, ignoreCase = true) &&
      activeSearchSource == source &&
      _searchResults.value != null &&
      !_searchLoading.value
    ) {
      return
    }
    val generation = ++searchGeneration
    searchJob?.cancel()
    activeSearchQuery = normalizedQuery
    activeSearchSource = source
    activeSearchPage = 0
    _searchResults.value = null
    _searchError.value = null
    _searchSource.value = source
    _searchHasMore.value = false
    _searchPageLoading.value = false
    searchHistoryStore?.add(normalizedQuery, source.id, source.label)
    searchJob = viewModelScope.launch {
      _searchLoading.value = true
      try {
        val results = dataRepository.search(normalizedQuery, page = 1)
        if (generation == searchGeneration) {
          // Never render an item attributed to a different source after a fast
          // picker change or a late network response.
          // Keep all results from this page (XVideos-style). Only drop rows
          // that clearly belong to a different source after a fast switch.
          val filtered = results.filter {
            it.sourceId.isBlank() || it.sourceId.equals(source.id, ignoreCase = true)
          }.map {
            if (it.sourceId.isBlank()) it.copy(sourceId = source.id) else it
          }
          _searchResults.value = filtered.distinctBy(::searchResultKey)
          activeSearchPage = 1
          // Keep paging as long as the provider returned a non-empty page.
          _searchHasMore.value = filtered.isNotEmpty()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (generation == searchGeneration) {
          _searchResults.value = emptyList()
          _searchError.value = error.message ?: "Search failed on ${source.label}"
          _searchHasMore.value = false
        }
      } finally {
        if (generation == searchGeneration) {
          _searchLoading.value = false
        }
      }
    }
  }

  fun removeSearchHistory(query: String) {
    searchHistoryStore?.remove(query)
  }

  fun clearSearchHistory() {
    searchHistoryStore?.clear()
  }

  fun loadMoreSearch() {
    val query = activeSearchQuery
    val source = activeSearchSource ?: return
    if (
      query.isBlank() ||
      _searchLoading.value ||
      _searchPageLoading.value ||
      !_searchHasMore.value
    ) return

    val generation = searchGeneration
    val nextPage = activeSearchPage + 1
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      _searchPageLoading.value = true
      _searchError.value = null
      try {
        val rawPage = dataRepository.search(query, page = nextPage)
        val pageResults = rawPage
          .filter { it.sourceId.isBlank() || it.sourceId.equals(source.id, ignoreCase = true) }
          .map { if (it.sourceId.isBlank()) it.copy(sourceId = source.id) else it }
          .distinctBy(::searchResultKey)
        if (generation == searchGeneration) {
          val existing = _searchResults.value.orEmpty()
          val existingKeys = existing.mapTo(mutableSetOf(), ::searchResultKey)
          val additions = pageResults.filter { existingKeys.add(searchResultKey(it)) }
          _searchResults.value = existing + additions
          activeSearchPage = nextPage
          // Empty provider page → end. Full-duplicate page (no new keys) → end.
          // Partial new results → keep going so we can walk the whole catalog.
          _searchHasMore.value = rawPage.isNotEmpty() && additions.isNotEmpty()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (generation == searchGeneration) {
          // Transient error: keep hasMore so user can retry via scroll/load more
          _searchError.value = error.message ?: "Could not load more from ${source.label}"
        }
      } finally {
        if (generation == searchGeneration) {
          _searchPageLoading.value = false
        }
      }
    }
  }

  fun clearSearch() {
    searchGeneration++
    searchJob?.cancel()
    searchJob = null
    _searchQuery.value = ""
    _searchResults.value = null
    _searchError.value = null
    _searchSource.value = null
    _searchLoading.value = false
    _searchPageLoading.value = false
    _searchHasMore.value = false
    activeSearchQuery = ""
    activeSearchSource = null
    activeSearchPage = 0
  }

  fun removeDownload(id: String) {
    downloadStore?.remove(id)
  }

  fun clearDownloads() {
    downloadStore?.clearAll()
  }

  fun cancelDownload(id: String) {
    downloadStore?.cancel(id)
  }

  fun retryDownload(id: String) {
    downloadStore?.retry(id)
  }

  fun dismissDownloadTransfer(id: String) {
    downloadStore?.dismissTransfer(id)
  }

  private suspend fun fetchPage(page: Int): List<VideoItem> {
    val tag = _activeTag.value
    return if (tag == null) {
      dataRepository.fetchHomePage(page)
    } else {
      dataRepository.fetchByTag(tag, page)
    }
  }

  private fun searchResultKey(item: VideoItem): String =
    "${item.sourceId.lowercase()}:${item.id.ifBlank { item.pageUrl }}"

  private fun pageItemKey(item: VideoItem): String =
    item.id.ifBlank { item.pageUrl }.ifBlank { item.title }

  private fun emitSuccess() {
    val all = _pageMap.value.toSortedMap().values.flatten()
    _uiState.value = MainScreenUiState.Success(all)
  }

  companion object {
    /** First unlock: pages 1–6. */
    const val INITIAL_VISIBLE_PAGES = 6
    /** Each time the user taps the last unlocked page, unlock this many more. */
    const val PAGE_EXPAND_STEP = 3
    /** How many page number chips are drawn at once (sliding window). */
    const val PAGE_WINDOW_SIZE = 6
  }
}

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(val data: List<VideoItem>) : MainScreenUiState
}
