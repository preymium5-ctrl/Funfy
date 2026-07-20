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
import com.example.funfy.data.VideoItem
import com.example.funfy.data.VideoSource
import com.example.funfy.data.XvideosTags
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainScreenViewModel(
  private val dataRepository: DataRepository,
  private val downloadStore: DownloadStore? = null,
  private val bookmarkStore: BookmarkStore? = null,
) : ViewModel() {

  private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Loading)
  val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

  val currentSource: StateFlow<VideoSource> =
    dataRepository.currentSource
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dataRepository.getSource())

  private val _searchResults = MutableStateFlow<List<VideoItem>?>(null)
  val searchResults: StateFlow<List<VideoItem>?> = _searchResults.asStateFlow()

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

  private val _pageMap = MutableStateFlow<Map<Int, List<VideoItem>>>(emptyMap())
  val pageMap: StateFlow<Map<Int, List<VideoItem>>> = _pageMap.asStateFlow()

  private val _loadedPages = MutableStateFlow(0)
  val loadedPages: StateFlow<Int> = _loadedPages.asStateFlow()

  private val _hasMore = MutableStateFlow(true)
  val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

  private val _pageLoading = MutableStateFlow(false)
  val pageLoading: StateFlow<Boolean> = _pageLoading.asStateFlow()

  /** Home pager index (1-based). Survives player overlay so Back restores page 2/3/…. */
  private val _homePage = MutableStateFlow(1)
  val homePage: StateFlow<Int> = _homePage.asStateFlow()

  fun setHomePage(page: Int) {
    _homePage.value = page.coerceAtLeast(1)
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

  fun removeBookmark(id: String) {
    bookmarkStore?.remove(id)
  }

  fun clearBookmarks() {
    bookmarkStore?.clear()
  }

  private var loadJob: Job? = null
  private var searchJob: Job? = null
  private var searchGeneration = 0L
  private var activeSearchQuery = ""
  private var activeSearchSource: VideoSource? = null
  private var activeSearchPage = 0

  init {
    reloadFromSource()
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
    // Keep _homePage unless caller already reset it (source/tag). Retry should stay on page.
    _uiState.value = MainScreenUiState.Loading
    ensurePageLoaded(_homePage.value.coerceAtLeast(1))
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
            val items = fetchPage(next)
            val prev = _pageMap.value[next - 1]
            val isDuplicate =
              prev != null &&
                items.isNotEmpty() &&
                items.map { it.id }.toSet() == prev.map { it.id }.toSet()

            val map = _pageMap.value.toMutableMap()
            map[next] = items
            _pageMap.value = map
            _loadedPages.value = maxOf(_loadedPages.value, next)

            if (items.isEmpty() || items.size < 6 || isDuplicate) {
              _hasMore.value = false
              break
            }
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
    val source = dataRepository.getSource()
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
          // Keep loading more like XVideos as long as the provider returned a page.
          _searchHasMore.value = results.isNotEmpty()
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
          // Stop only when the provider itself returns an empty page (XVideos-style).
          _searchHasMore.value = rawPage.isNotEmpty() && additions.isNotEmpty()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        if (generation == searchGeneration) {
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

  private fun emitSuccess() {
    val all = _pageMap.value.toSortedMap().values.flatten()
    _uiState.value = MainScreenUiState.Success(all)
  }
}

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(val data: List<VideoItem>) : MainScreenUiState
}
