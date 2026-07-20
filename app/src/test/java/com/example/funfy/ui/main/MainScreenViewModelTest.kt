package com.example.funfy.ui.main

import com.example.funfy.data.ContentTag
import com.example.funfy.data.DataRepository
import com.example.funfy.data.VideoDetails
import com.example.funfy.data.VideoItem
import com.example.funfy.data.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun initialLoadPublishesRepositoryItems() = runTest(dispatcher) {
    val repository = FakeDataRepository()
    val viewModel = MainScreenViewModel(repository)

    advanceUntilIdle()

    val success = viewModel.uiState.value as MainScreenUiState.Success
    assertEquals(listOf("home"), success.data.map(VideoItem::id))
  }

  @Test
  fun searchKeepsOnlyItemsAttributedToSelectedSource() = runTest(dispatcher) {
    val repository = FakeDataRepository()
    val viewModel = MainScreenViewModel(repository)
    viewModel.setSource(VideoSource.EPORNER)
    repository.searchItems = listOf(
      video("right", VideoSource.EPORNER),
      video("late-from-old-source", VideoSource.XVIDEOS),
    )

    viewModel.search("query")
    advanceUntilIdle()

    assertEquals(VideoSource.EPORNER, repository.searchSourceAtCall)
    assertEquals(listOf("right"), viewModel.searchResults.value?.map(VideoItem::id))
    assertEquals(VideoSource.EPORNER, viewModel.searchSource.value)
    assertNull(viewModel.searchError.value)
  }

  @Test
  fun sourceChangeClearsExistingSearchState() = runTest(dispatcher) {
    val repository = FakeDataRepository()
    val viewModel = MainScreenViewModel(repository)
    viewModel.search("query")
    advanceUntilIdle()
    assertTrue(viewModel.searchResults.value?.isNotEmpty() == true)

    viewModel.setSource(VideoSource.EPORNER)

    assertNull(viewModel.searchResults.value)
    assertNull(viewModel.searchSource.value)
  }

  @Test
  fun searchLoadsAdditionalPagesWithoutDuplicates() = runTest(dispatcher) {
    val repository = FakeDataRepository().apply {
      searchItemsByPage = mapOf(
        1 to listOf(video("first", VideoSource.XVIDEOS)),
        2 to listOf(
          video("first", VideoSource.XVIDEOS),
          video("second", VideoSource.XVIDEOS),
        ),
        3 to emptyList(),
      )
    }
    val viewModel = MainScreenViewModel(repository)

    viewModel.search("query")
    advanceUntilIdle()
    viewModel.loadMoreSearch()
    advanceUntilIdle()

    assertEquals(listOf("first", "second"), viewModel.searchResults.value?.map(VideoItem::id))
    assertEquals(listOf(1, 2), repository.searchPages)
    assertTrue(viewModel.searchHasMore.value)

    viewModel.loadMoreSearch()
    advanceUntilIdle()

    assertEquals(listOf(1, 2, 3), repository.searchPages)
    assertEquals(false, viewModel.searchHasMore.value)
  }
}

private class FakeDataRepository : DataRepository {
  private val source = MutableStateFlow(VideoSource.XVIDEOS)
  override val currentSource: Flow<VideoSource> = source
  var searchItems: List<VideoItem> = listOf(video("search", VideoSource.XVIDEOS))
  var searchItemsByPage: Map<Int, List<VideoItem>> = emptyMap()
  var searchSourceAtCall: VideoSource? = null
  val searchPages = mutableListOf<Int>()

  override fun getSource(): VideoSource = source.value

  override fun setSource(source: VideoSource) {
    this.source.value = source
  }

  override suspend fun fetchHomePage(page: Int): List<VideoItem> =
    if (page == 1) listOf(video("home", source.value)) else emptyList()

  override suspend fun search(query: String, page: Int): List<VideoItem> {
    searchSourceAtCall = source.value
    searchPages += page
    return searchItemsByPage[page] ?: searchItems
  }

  override suspend fun fetchByTag(tag: ContentTag, page: Int): List<VideoItem> = emptyList()

  override suspend fun fetchVideoDetails(pageUrl: String): VideoDetails =
    error("Not needed by MainScreenViewModel tests")
}

private fun video(id: String, source: VideoSource) = VideoItem(
  id = id,
  title = id,
  duration = "1:00",
  resolution = "720p",
  views = "1",
  category = source.label,
  gradientSeed = 1,
  pageUrl = "${source.baseUrl}/$id",
  sourceId = source.id,
)
