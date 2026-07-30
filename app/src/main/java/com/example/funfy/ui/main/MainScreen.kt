package com.example.funfy.ui.main

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.funfy.AppSettings
import com.example.funfy.FunfyApp
import com.example.funfy.LauncherIdentity
import com.example.funfy.LauncherIdentityManager
import com.example.funfy.LoopMode
import com.example.funfy.Player
import com.example.funfy.data.BookmarkedVideo
import com.example.funfy.data.ContentTag
import com.example.funfy.data.DownloadStatus
import com.example.funfy.data.DownloadTransfer
import com.example.funfy.data.GallerySaver
import com.example.funfy.data.LocalDownload
import com.example.funfy.data.MediaFolder
import com.example.funfy.data.VideoItem
import com.example.funfy.data.VideoSource
import com.example.funfy.data.XvideosTags
import com.example.funfy.ads.NativeAdCard
import com.example.funfy.theme.*
import java.io.File

private val LocalVideoPreviewsEnabled = staticCompositionLocalOf { true }

// Define navigation tabs
enum class Tab(val title: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Search("Search", Icons.Default.Search),
    Bookmarks("Saved", Icons.Default.Bookmark),
    Downloads("Downloads", Icons.Default.ArrowDownward),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val viewModel: MainScreenViewModel = viewModel {
    val app = context.applicationContext as FunfyApp
    MainScreenViewModel(
      app.repository,
      app.downloadStore,
      app.bookmarkStore,
      app.searchHistoryStore,
      isAutoShuffle = { AppSettings.autoShuffle(app) },
    )
  }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
  val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
  val searchLoading by viewModel.searchLoading.collectAsStateWithLifecycle()
  val searchError by viewModel.searchError.collectAsStateWithLifecycle()
  val searchSource by viewModel.searchSource.collectAsStateWithLifecycle()
  val searchPageLoading by viewModel.searchPageLoading.collectAsStateWithLifecycle()
  val searchHasMore by viewModel.searchHasMore.collectAsStateWithLifecycle()
  val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
  val currentSource by viewModel.currentSource.collectAsStateWithLifecycle()
  val loadedPages by viewModel.loadedPages.collectAsStateWithLifecycle()
  val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
  val pageLoading by viewModel.pageLoading.collectAsStateWithLifecycle()
  val pageMap by viewModel.pageMap.collectAsStateWithLifecycle()
  val homePage by viewModel.homePage.collectAsStateWithLifecycle()
  val visiblePageCount by viewModel.visiblePageCount.collectAsStateWithLifecycle()
  val activeTag by viewModel.activeTag.collectAsStateWithLifecycle()
  val downloads by viewModel.downloads.collectAsStateWithLifecycle()
  val downloadTransfers by viewModel.downloadTransfers.collectAsStateWithLifecycle()
  val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
  val bookmarkFolders by viewModel.bookmarkFolders.collectAsStateWithLifecycle()
  val downloadFolders by viewModel.downloadFolders.collectAsStateWithLifecycle()
  var selectedTab by remember { mutableStateOf(Tab.Home) }
  var previewsDisabled by remember(context) {
    mutableStateOf(AppSettings.disablePreviews(context))
  }
  // Player history stack: related clicks push; Back pops to previous video, then home.
  val playerStack = remember { mutableStateListOf<Player>() }
  // pageUrl → last playback position (ms) so related → back keeps progress.
  val watchProgressMs = remember { mutableStateMapOf<String, Long>() }
  var playerFullscreen by remember { mutableStateOf(false) }
  val activePlayer = playerStack.lastOrNull()

  fun pushPlayer(player: Player) {
    playerFullscreen = false
    val resume = watchProgressMs[player.pageUrl] ?: player.resumePositionMs
    playerStack.add(player.copy(resumePositionMs = resume))
  }

  fun popPlayer() {
    playerFullscreen = false
    if (playerStack.isNotEmpty()) {
      playerStack.removeAt(playerStack.lastIndex)
    }
  }

  fun clearPlayerStack() {
    playerFullscreen = false
    playerStack.clear()
  }

  fun openVideo(video: VideoItem) {
    if (video.pageUrl.isBlank()) {
      return
    }
    // Opening from home/search/bookmarks starts a fresh stack.
    playerStack.clear()
    pushPlayer(
      Player(
        videoId = video.id,
        title = video.title,
        pageUrl = video.pageUrl,
        thumbnailUrl = com.example.funfy.data.NetworkClient.sanitizeMediaUrl(video.thumbnailUrl),
        duration = video.duration,
        resolution = video.resolution,
        views = video.views,
        uploader = video.category,
        resumePositionMs = watchProgressMs[video.pageUrl] ?: 0L,
      ),
    )
  }

  fun openLocalDownload(item: com.example.funfy.data.LocalDownload) {
    playerStack.clear()
    pushPlayer(
      Player(
        videoId = item.id,
        title = item.title,
        pageUrl = item.filePath,
        thumbnailUrl = item.thumbnailPath.ifBlank { item.thumbnailUrl },
        duration = item.duration,
        resolution = item.resolution,
        views = item.sizeLabel,
        uploader = "Downloads",
        isLocal = true,
      ),
    )
  }

  CompositionLocalProvider(LocalVideoPreviewsEnabled provides !previewsDisabled) {
  Scaffold(
      modifier = modifier.fillMaxSize(),
      containerColor = CookiesmoBg,
      bottomBar = {
          // Hide app navbar in true player fullscreen (xHamster-style immersive).
          if (!playerFullscreen) {
          Column(modifier = Modifier.fillMaxWidth()) {
              HorizontalDivider(color = CookiesmoMuted, thickness = 1.dp)
              Surface(
                  modifier = Modifier.fillMaxWidth(),
                  color = CookiesmoSurface,
                  tonalElevation = 0.dp
              ) {
                  Row(
                      modifier = Modifier
                          .fillMaxWidth()
                          .navigationBarsPadding()
                          .padding(vertical = 8.dp),
                      horizontalArrangement = Arrangement.SpaceAround,
                      verticalAlignment = Alignment.CenterVertically
                  ) {
                      Tab.values().forEach { tab ->
                          val isSelected = selectedTab == tab && activePlayer == null
                          val tintColor = if (isSelected) CookiesmoAccent else CookiesmoTextMuted
                          
                          Column(
                              modifier = Modifier
                                  .weight(1f)
                                  .clickable {
                                      // Switching tabs from the player closes it and shows that tab.
                                      clearPlayerStack()
                                      selectedTab = tab
                                      if (tab == Tab.Bookmarks) {
                                          // Force re-resolve so wrong covers from older scrapes get fixed.
                                          viewModel.refreshBookmarkThumbnails(force = true)
                                      }
                                  }
                                  .padding(vertical = 4.dp),
                              horizontalAlignment = Alignment.CenterHorizontally,
                              verticalArrangement = Arrangement.Center
                          ) {
                              Icon(
                                  imageVector = tab.icon,
                                  contentDescription = tab.title,
                                  tint = tintColor,
                                  modifier = Modifier.size(24.dp)
                              )
                              Spacer(modifier = Modifier.height(4.dp))
                              Text(
                                  text = tab.title,
                                  color = tintColor,
                                  fontSize = 11.sp,
                                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                              )
                          }
                      }
                  }
              }
          }
          }
      }
  ) { innerPadding ->
      // Tab content always works — Home can fail without locking other tabs
      val videos = (state as? MainScreenUiState.Success)?.data.orEmpty()
      Box(
          modifier = Modifier
              .fillMaxSize()
              .then(if (activePlayer != null && playerFullscreen) Modifier else Modifier.padding(innerPadding))
      ) {
          val player = activePlayer
          if (player != null) {
              key(player.pageUrl, player.videoId, playerStack.size) {
                  com.example.funfy.ui.player.PlayerScreen(
                      title = player.title,
                      pageUrl = player.pageUrl,
                      duration = player.duration,
                      resolution = player.resolution,
                      views = player.views,
                      uploader = player.uploader,
                      thumbnailUrl = player.thumbnailUrl,
                      isLocal = player.isLocal,
                      // Prefer latest saved progress (related → back), not the stale stack value.
                      initialPositionMs = watchProgressMs[player.pageUrl]
                          ?: player.resumePositionMs,
                      onProgressSave = { pos ->
                          if (player.pageUrl.isNotBlank() && pos > 1_000L) {
                              watchProgressMs[player.pageUrl] = pos
                          }
                      },
                      onBack = { popPlayer() },
                      onFullscreenChange = { playerFullscreen = it },
                      onRelatedClick = { video ->
                          if (video.pageUrl.isNotBlank()) {
                              // Push related on the stack so Back returns to previous video
                              // with its saved watch position.
                              pushPlayer(
                                  Player(
                                      videoId = video.id,
                                      title = video.title,
                                      pageUrl = video.pageUrl,
                                      thumbnailUrl = com.example.funfy.data.NetworkClient
                                          .sanitizeMediaUrl(video.thumbnailUrl),
                                      duration = video.duration,
                                      resolution = video.resolution,
                                      views = video.views,
                                      uploader = video.category,
                                      resumePositionMs = watchProgressMs[video.pageUrl] ?: 0L,
                                  ),
                              )
                          }
                      },
                      modifier = Modifier.fillMaxSize(),
                  )
              }
          } else when (selectedTab) {
              Tab.Home -> {
                  when {
                      state is MainScreenUiState.Loading && loadedPages == 0 -> {
                          Column(
                              modifier = Modifier.align(Alignment.Center),
                              horizontalAlignment = Alignment.CenterHorizontally,
                          ) {
                              CircularProgressIndicator(color = RoyalBlueNav)
                              Spacer(modifier = Modifier.height(12.dp))
                              Text("Loading videos…", color = TextMetaBlue, fontSize = 13.sp)
                          }
                      }
                      state is MainScreenUiState.Error && loadedPages == 0 -> {
                          Column(
                              modifier = Modifier
                                  .align(Alignment.Center)
                                  .padding(24.dp),
                              horizontalAlignment = Alignment.CenterHorizontally,
                          ) {
                              Text(
                                  text = "Failed to load videos",
                                  color = Color.White,
                                  fontWeight = FontWeight.Bold,
                                  fontSize = 16.sp,
                              )
                              Spacer(modifier = Modifier.height(8.dp))
                              Text(
                                  text = (state as MainScreenUiState.Error).throwable.message
                                      ?: "Source unavailable. Switch source in Settings.",
                                  color = Color(0xFFFF8A80),
                                  fontSize = 13.sp,
                              )
                              Spacer(modifier = Modifier.height(16.dp))
                              TextButton(
                                  onClick = { selectedTab = Tab.Settings },
                                  colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                              ) {
                                  Text("Open Settings", fontWeight = FontWeight.Bold)
                              }
                              TextButton(
                                  onClick = { viewModel.reloadFromSource() },
                                  colors = ButtonDefaults.textButtonColors(contentColor = TextMetaBlue),
                              ) {
                                  Text("Retry")
                              }
                          }
                      }
                      else -> {
                          HomeScreenContent(
                              pageMap = pageMap,
                              loadedPages = loadedPages.coerceAtLeast(1),
                              visiblePageCount = visiblePageCount,
                              hasMore = hasMore,
                              pageLoading = pageLoading,
                              activeTag = activeTag,
                              currentPage = homePage,
                              onPageChange = { page ->
                                  viewModel.setHomePage(page)
                                  viewModel.ensurePageLoaded(page)
                              },
                              onRequestPage = viewModel::ensurePageLoaded,
                              onTagSelected = { tag -> viewModel.setTagFilter(tag) },
                              onVideoClick = ::openVideo,
                          )
                      }
                  }
              }
              Tab.Search -> {
                  SearchScreenContent(
                      remoteResults = searchResults,
                      searchQuery = searchQuery,
                      onSearchQueryChange = viewModel::setSearchQuery,
                      searchLoading = searchLoading,
                      searchError = searchError,
                      currentSource = currentSource,
                      resultSource = searchSource,
                      searchHistory = searchHistory,
                      onSearch = viewModel::search,
                      onClearSearch = viewModel::clearSearch,
                      onRemoveHistory = viewModel::removeSearchHistory,
                      onClearHistory = viewModel::clearSearchHistory,
                      searchPageLoading = searchPageLoading,
                      searchHasMore = searchHasMore,
                      onLoadMore = viewModel::loadMoreSearch,
                      onSourceSelected = viewModel::setSource,
                      onVideoClick = ::openVideo,
                  )
              }
              Tab.Bookmarks -> BookmarksScreenContent(
                  bookmarks = bookmarks,
                  folders = bookmarkFolders,
                  onOpen = { bm -> openVideo(bm.toVideoItem()) },
                  onDelete = viewModel::removeBookmark,
                  onMoveToFolder = viewModel::moveBookmarkToFolder,
                  onCreateFolder = viewModel::createBookmarkFolder,
                  onDeleteFolder = viewModel::deleteBookmarkFolder,
              )
              Tab.Downloads -> DownloadsScreenContent(
                  downloads = downloads,
                  folders = downloadFolders,
                  transfers = downloadTransfers,
                  onOpen = ::openLocalDownload,
                  onDelete = viewModel::removeDownload,
                  onClearAll = viewModel::clearDownloads,
                  onCancel = viewModel::cancelDownload,
                  onRetry = viewModel::retryDownload,
                  onDismissTransfer = viewModel::dismissDownloadTransfer,
                  onMoveToFolder = viewModel::moveDownloadToFolder,
                  onCreateFolder = viewModel::createDownloadFolder,
                  onDeleteFolder = viewModel::deleteDownloadFolder,
                  onSaveToGallery = { item ->
                      val result = GallerySaver.saveVideo(context, item.filePath, item.title, item.storagePath)
                      result.fold(
                          onSuccess = {
                              Toast.makeText(
                                  context,
                                  "Saved to gallery (Movies/Funfy)",
                                  Toast.LENGTH_SHORT,
                              ).show()
                          },
                          onFailure = { err ->
                              Toast.makeText(
                                  context,
                                  err.message ?: "Could not save to gallery",
                                  Toast.LENGTH_LONG,
                              ).show()
                          },
                      )
                  },
              )
              Tab.Settings -> MoreScreenContent(
                  currentSource = currentSource,
                  onSourceSelected = viewModel::setSource,
                  downloadsCount = downloads.size,
                  disablePreview = previewsDisabled,
                  onDisablePreviewChange = { disabled ->
                      AppSettings.setDisablePreviews(context, disabled)
                      previewsDisabled = disabled
                  },
                  onClearDownloads = viewModel::clearDownloads,
                  onAutoShuffleChanged = viewModel::onAutoShuffleChanged,
              )
          }
      }
  }
  }
}

@Composable
fun HomeScreenContent(
    pageMap: Map<Int, List<VideoItem>>,
    loadedPages: Int,
    visiblePageCount: Int = MainScreenViewModel.INITIAL_VISIBLE_PAGES,
    hasMore: Boolean,
    pageLoading: Boolean,
    activeTag: ContentTag?,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    onRequestPage: (Int) -> Unit,
    onTagSelected: (ContentTag?) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
) {
    val userName = AppSettings.userName(LocalContext.current)
    var showTagFilter by remember { mutableStateOf(false) }

    // Parent owns page index so player overlay does not reset it to 1.
    LaunchedEffect(currentPage) {
        onRequestPage(currentPage)
    }

    // Recompose when pageMap updates for this page
    val pageVideos = pageMap[currentPage].orEmpty()
    // Unlocked range grows by +3 when the user taps the last page.
    val maxPageNumber = if (hasMore) {
        maxOf(visiblePageCount, currentPage, loadedPages, 1)
    } else {
        maxOf(loadedPages, currentPage, 1)
    }
    // Sliding window of 6 chips: start 1–6; after expand to 9 show 4–9 (hid 1–3);
    // after expand to 12 show 7–12, etc. Going back early still reveals 1–6.
    val windowSize = MainScreenViewModel.PAGE_WINDOW_SIZE
    var windowStart = if (maxPageNumber <= windowSize) {
        1
    } else {
        maxOf(1, minOf(currentPage - 2, maxPageNumber - windowSize + 1))
    }
    var windowEnd = minOf(maxPageNumber, windowStart + windowSize - 1)
    if (windowEnd - windowStart + 1 < windowSize && maxPageNumber >= windowSize) {
        windowStart = maxPageNumber - windowSize + 1
        windowEnd = maxPageNumber
    }

    if (showTagFilter) {
        TagFilterDialog(
            activeTag = activeTag,
            onDismiss = { showTagFilter = false },
            onSelect = { tag ->
                showTagFilter = false
                onTagSelected(tag)
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar with filter
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CookiesmoSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName.takeIf { it.isNotBlank() }?.let { "Hi, $it" } ?: "Home",
                    color = CookiesmoTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (activeTag != null) {
                    Text(
                        text = "Tag: ${activeTag.label}",
                        color = CookiesmoAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
            if (activeTag != null) {
                Text(
                    text = "Clear",
                    color = CookiesmoTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickable { onTagSelected(null) },
                )
            }
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = "Filter tags",
                tint = if (activeTag != null) CookiesmoAccent else CookiesmoTextPrimary,
                modifier = Modifier
                    .size(26.dp)
                    .clickable { showTagFilter = true },
            )
        }
        HorizontalDivider(color = CookiesmoMuted, thickness = 1.dp)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) {
            if (pageVideos.isEmpty() && pageLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = RoyalBlueNav)
                    }
                }
            } else if (pageVideos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "No videos on this page",
                        color = TextMetaBlue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    )
                }
            } else {
                pageVideos.forEachIndexed { index, video ->
                    item(key = "${video.sourceId}_${video.id}_${currentPage}_$index") {
                        VideoCard(
                            video = video,
                            onClick = { onVideoClick(video) },
                        )
                    }
                    if (index == 3 || (index > 3 && (index - 3) % 6 == 0)) {
                        item(
                            key = "native_ad_${currentPage}_$index",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            NativeAdCard()
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(12.dp))
                val canPrev = currentPage > 1 && !pageLoading
                val canNext = !pageLoading && (currentPage < maxPageNumber || hasMore)
                // Fixed Prev / Next chips + scrollable page numbers so buttons never
                // get squeezed off-screen when many page slots are unlocked.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (canPrev) CookiesmoSurface else CookiesmoSurface.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (canPrev) CookiesmoAccent else CookiesmoMuted,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = canPrev) {
                                onPageChange(currentPage - 1)
                            },
                    ) {
                        Text(
                            text = "Prev",
                            color = if (canPrev) CookiesmoTextPrimary else CookiesmoTextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (p in windowStart..windowEnd) {
                            val isCurrent = p == currentPage
                            val enabled = p <= maxPageNumber && !pageLoading
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isCurrent) CookiesmoAccent else Color.Transparent,
                                    )
                                    .clickable(enabled = enabled) { onPageChange(p) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = p.toString(),
                                    color = when {
                                        isCurrent -> CookiesmoBg
                                        enabled -> CookiesmoTextPrimary
                                        else -> CookiesmoTextMuted
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (canNext) CookiesmoSurface else CookiesmoSurface.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (canNext) CookiesmoAccent else CookiesmoMuted,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = canNext) {
                                onPageChange(currentPage + 1)
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (pageLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = CookiesmoAccent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = "Next",
                                color = if (canNext) CookiesmoTextPrimary else CookiesmoTextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagFilterDialog(
    activeTag: ContentTag?,
    onDismiss: () -> Unit,
    onSelect: (ContentTag?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        title = {
            Text("XVideos tags", color = CookiesmoTextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Official categories from xvideos.com — results load only videos in that tag.",
                    color = TextMetaBlue,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                val allSelected = activeTag == null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (allSelected) RoyalBlueNav.copy(alpha = 0.4f) else Color.Transparent)
                        .clickable { onSelect(null) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "All (no filter)",
                        color = Color.White,
                        fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (allSelected) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                XvideosTags.ALL.forEach { tag ->
                    val selected = activeTag?.label == tag.label
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) RoyalBlueNav.copy(alpha = 0.4f) else Color.Transparent)
                            .clickable { onSelect(tag) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tag.label,
                            color = Color.White,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.White)
            }
        },
    )
}

@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    showSourceBadge: Boolean = false,
) {
    val context = LocalContext.current
    val previewsEnabled = LocalVideoPreviewsEnabled.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // Thumbnail Aspect Ratio 16:9
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clip(RoundedCornerShape(12.dp))
                .background(CookiesmoSurface)
        ) {
            // Fallback gradient while image loads / if missing
            val startColor = getGradientColor1(video.gradientSeed)
            val endColor = getGradientColor2(video.gradientSeed)
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(startColor, endColor),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                )
            }

            if (previewsEnabled && video.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context.applicationContext)
                        .data(
                            com.example.funfy.data.NetworkClient.sanitizeMediaUrl(
                                video.thumbnailUrl,
                            ),
                        )
                        // Decode near card size so big DrKoGyi/WP PNGs load faster.
                        .size(480)
                        .crossfade(120)
                        .allowHardware(true)
                        .build(),
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (showSourceBadge) {
                val sourceLabel = VideoSource.entries
                    .firstOrNull { it.id.equals(video.sourceId, ignoreCase = true) }
                    ?.label
                    ?: video.sourceId
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .background(CookiesmoSurface, RoundedCornerShape(12.dp))
                        .border(1.dp, CookiesmoAccent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .align(Alignment.TopEnd),
                ) {
                    Text(
                        text = sourceLabel,
                        color = CookiesmoAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }

            // Play overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2
                val centerY = size.height / 2
                val circleRadius = 18.dp.toPx()
                drawCircle(
                    color = Color.Black.copy(alpha = 0.4f),
                    radius = circleRadius,
                    center = Offset(centerX, centerY),
                )
                val arrowPath = Path().apply {
                    val arrowWidth = 10.dp.toPx()
                    val arrowHeight = 12.dp.toPx()
                    moveTo(centerX - arrowWidth / 3, centerY - arrowHeight / 2)
                    lineTo(centerX + arrowWidth * 2 / 3, centerY)
                    lineTo(centerX - arrowWidth / 3, centerY + arrowHeight / 2)
                    close()
                }
                drawPath(path = arrowPath, color = Color.White)
            }

            // Duration badge bottom-right
            if (video.duration.isNotBlank() && video.duration != "—") {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                        .align(Alignment.BottomEnd),
                ) {
                    Text(
                        text = video.duration,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Category / quality badge top-left
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
                    .align(Alignment.TopStart),
            ) {
                Text(
                    text = video.resolution.ifBlank { video.category },
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // Title
        Text(
            text = video.title,
            color = CookiesmoTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        
        Spacer(modifier = Modifier.height(2.dp))
        
        // Metadata: duration • quality • views + eye icon when views known
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val viewsText = video.views.takeIf { it.isNotBlank() && it != "—" }
            Text(
                text = listOfNotNull(
                    video.duration.takeIf { it.isNotBlank() && it != "—" },
                    video.resolution.takeIf { it.isNotBlank() && it != "—" },
                    viewsText,
                ).joinToString(" • ").ifBlank { video.category.ifBlank { "Video" } },
                color = CookiesmoTextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            
            if (viewsText != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = "Views",
                    tint = CookiesmoTextMuted,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

// Helpers for gorgeous gradients
fun getGradientColor1(seed: Int): Color {
    val colors = listOf(
        Color(0xFF8E2DE2), // Purple-violet
        Color(0xFF00c6ff), // Cyan-blue
        Color(0xFFf857a6), // Magenta
        Color(0xFF11998e), // Green-teal
        Color(0xFFF7971E), // Orange-yellow
        Color(0xFFe65c00), // Coral
        Color(0xFF3a7bd5), // Soft blue
        Color(0xFF141E30)  // Slate dark blue
    )
    return colors[seed % colors.size]
}

fun getGradientColor2(seed: Int): Color {
    val colors = listOf(
        Color(0xFF4A00E0),
        Color(0xFF0072ff),
        Color(0xFFff5858),
        Color(0xFF38ef7d),
        Color(0xFFFFD200),
        Color(0xFFF9D423),
        Color(0xFF3a6073),
        Color(0xFF243B55)
    )
    return colors[seed % colors.size]
}

@Composable
fun SearchScreenContent(
    remoteResults: List<VideoItem>?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchLoading: Boolean,
    searchError: String?,
    currentSource: VideoSource,
    resultSource: VideoSource?,
    searchHistory: List<com.example.funfy.data.SearchHistoryEntry> = emptyList(),
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onRemoveHistory: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    searchPageLoading: Boolean = false,
    searchHasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onSourceSelected: (VideoSource) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
) {
    var showSourcePicker by remember { mutableStateOf(false) }

    if (showSourcePicker) {
        SourcePickerDialog(
            current = currentSource,
            title = "Search source",
            description = "Your query will be sent only to the source you select.",
            onDismiss = { showSourcePicker = false },
            onSelected = { source ->
                showSourcePicker = false
                onSourceSelected(source)
            },
        )
    }

    // Debounced search. Query lives in the ViewModel so Back from the player
    // restores both the text field and results (no clear-on-recompose).
    LaunchedEffect(searchQuery, currentSource.id) {
        if (searchQuery.isBlank()) {
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(450)
        onSearch(searchQuery)
    }

    val displayVideos = remoteResults.orEmpty()
    val displayedSource = resultSource ?: currentSource
    val awaitingResults = searchQuery.isNotBlank() &&
        remoteResults == null && searchError == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search videos…", color = InactiveTabBlue) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = InactiveTabBlue)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = {
                                onSearchQueryChange("")
                                onClearSearch()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = InactiveTabBlue,
                            )
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CookiesmoTextPrimary,
                    unfocusedTextColor = CookiesmoTextPrimary,
                    focusedContainerColor = CookiesmoSurface,
                    unfocusedContainerColor = CookiesmoSurface,
                    focusedBorderColor = CookiesmoAccent,
                    unfocusedBorderColor = CookiesmoMuted,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = CookiesmoSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CookiesmoMuted),
            ) {
                IconButton(onClick = { showSourcePicker = true }) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = "Choose search source",
                        tint = Color.White,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSourcePicker = true }
                .padding(top = 8.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = null,
                tint = TextMetaBlue,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Searching only ${currentSource.label} (${currentSource.provider.label})",
                color = TextMetaBlue,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        if (searchQuery.isBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent searches",
                    color = CookiesmoTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (searchHistory.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        color = CookiesmoAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onClearHistory() },
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (searchHistory.isEmpty()) {
                Text(
                    text = "Your past searches will show up here.",
                    color = CookiesmoTextMuted,
                    fontSize = 13.sp,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(searchHistory, key = { it.query + it.searchedAt }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CookiesmoSurface)
                                .clickable {
                                    onSearchQueryChange(entry.query)
                                    onSearch(entry.query)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = TextMetaBlue,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.query,
                                    color = CookiesmoTextPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.sourceLabel.isNotBlank()) {
                                    Text(
                                        text = entry.sourceLabel,
                                        color = TextMetaBlue,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            IconButton(onClick = { onRemoveHistory(entry.query) }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = CookiesmoTextMuted,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                text = if (searchLoading || awaitingResults) {
                    "Searching ${currentSource.label}…"
                } else {
                    "${displayVideos.size} results from ${displayedSource.label}"
                },
                color = CookiesmoTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(10.dp))
            
            if ((searchLoading || awaitingResults) && displayVideos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = RoyalBlueNav)
                }
            } else if (searchError != null && displayVideos.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "${displayedSource.label} could not complete this search.",
                        color = CookiesmoTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = searchError,
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { onSearch(searchQuery) }) {
                        Text("Retry", color = CookiesmoAccent)
                    }
                }
            } else if (displayVideos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No results for \"$searchQuery\" on ${displayedSource.label}",
                        color = CookiesmoTextMuted,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = displayVideos,
                        key = { _, video -> "${video.sourceId}_${video.id}" },
                    ) { index, video ->
                        if (
                            searchHasMore &&
                            !searchPageLoading &&
                            index >= (displayVideos.lastIndex - 3).coerceAtLeast(0)
                        ) {
                            LaunchedEffect(displayVideos.size, index) { onLoadMore() }
                        }
                        VideoCard(
                            video = video,
                            onClick = { onVideoClick(video) },
                            showSourceBadge = true,
                        )
                    }
                    if (searchPageLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = RoyalBlueNav,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                    if (searchError != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(searchError, color = Color(0xFFFF8A80), fontSize = 12.sp)
                                TextButton(onClick = onLoadMore) {
                                    Text("Retry loading more", color = CookiesmoAccent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarksScreenContent(
    bookmarks: List<BookmarkedVideo>,
    folders: List<MediaFolder>,
    onOpen: (BookmarkedVideo) -> Unit,
    onDelete: (String) -> Unit,
    onMoveToFolder: (id: String, folderId: String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    val context = LocalContext.current
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var browsingFolder by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var moveTargetId by remember { mutableStateOf<String?>(null) }

    // Root list = only unfiled. Folder members leave the main list until you open that folder.
    val unfiled = remember(bookmarks) { bookmarks.filter { it.folderId == null } }
    val visible = remember(bookmarks, openFolderId, browsingFolder) {
        if (!browsingFolder) {
            unfiled
        } else if (openFolderId == null) {
            unfiled
        } else {
            bookmarks.filter { it.folderId == openFolderId }
        }
    }
    val folderCounts = remember(bookmarks, folders) {
        folders.associate { f -> f.id to bookmarks.count { it.folderId == f.id } }
    }
    val unfiledCount = unfiled.size
    val folderTitle = folders.firstOrNull { it.id == openFolderId }?.name

    if (showCreate) {
        CreateFolderDialog(
            title = "New saved folder",
            onDismiss = { showCreate = false },
            onCreate = { name ->
                onCreateFolder(name)
                showCreate = false
                Toast.makeText(context, "Folder created", Toast.LENGTH_SHORT).show()
            },
        )
    }
    moveTargetId?.let { videoId ->
        FolderPickerDialog(
            title = "Move to folder",
            folders = folders,
            rootLabel = "Unfiled (no folder)",
            onDismiss = { moveTargetId = null },
            onPick = { folderId ->
                onMoveToFolder(videoId, folderId)
                moveTargetId = null
                Toast.makeText(context, "Moved", Toast.LENGTH_SHORT).show()
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetailBgLike),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RoyalBlueNav)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (browsingFolder) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable {
                            browsingFolder = false
                            openFolderId = null
                        }
                        .padding(end = 4.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (browsingFolder) {
                    folderTitle ?: "Unfiled"
                } else {
                    "Saved"
                },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        if (!browsingFolder) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                FolderListHeader(
                    folders = folders,
                    counts = folderCounts,
                    unfiledCount = unfiledCount,
                    onOpenRoot = {
                        openFolderId = null
                        browsingFolder = true
                    },
                    onOpenFolder = { f ->
                        openFolderId = f.id
                        browsingFolder = true
                    },
                    onCreateFolder = { showCreate = true },
                    onDeleteFolder = { f ->
                        onDeleteFolder(f.id)
                        Toast.makeText(context, "Folder deleted", Toast.LENGTH_SHORT).show()
                    },
                )
                if (bookmarks.isEmpty()) {
                    Text(
                        text = "No bookmarks yet.\nTap Bookmark on any video to save it here.",
                        color = TextMetaBlue,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    Text(
                        text = "${unfiledCount} unfiled · ${bookmarks.size - unfiledCount} in folders",
                        color = TextMetaBlue,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                    // Main list = unfiled only; moved items leave this list.
                    if (unfiled.isEmpty()) {
                        Text(
                            text = "All saved videos are in folders.\nOpen a folder above to watch them.",
                            color = TextMetaBlue,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    } else {
                        unfiled.forEach { item ->
                            BookmarkRow(
                                item = item,
                                onOpen = { onOpen(item) },
                                onMove = { moveTargetId = item.id },
                                onDelete = { onDelete(item.id) },
                            )
                        }
                    }
                }
            }
        } else if (visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("No videos in this folder", color = TextMetaBlue, fontSize = 15.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = "${visible.size} video(s)",
                    color = TextMetaBlue,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                visible.forEach { item ->
                    BookmarkRow(
                        item = item,
                        onOpen = { onOpen(item) },
                        onMove = { moveTargetId = item.id },
                        onDelete = { onDelete(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    item: BookmarkedVideo,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(118.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CookiesmoSurface),
            ) {
                val thumb = com.example.funfy.data.NetworkClient.sanitizeMediaUrl(
                    item.thumbnailUrl,
                )
                if (thumb.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context.applicationContext)
                            .data(thumb)
                            // Key off URL so Coil reloads when repair updates the thumb.
                            .memoryCacheKey(thumb)
                            .diskCacheKey(thumb)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = CookiesmoTextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = CookiesmoTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = listOfNotNull(
                        item.sourceLabel.takeIf { it.isNotBlank() },
                        item.duration.takeIf { it.isNotBlank() && it != "—" },
                        item.resolution.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "Saved video" },
                    color = CookiesmoTextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CookiesmoSurface,
                        border = BorderStroke(1.dp, CookiesmoMuted),
                        modifier = Modifier.clickable(onClick = onMove),
                    ) {
                        Text(
                            text = "Move",
                            color = CookiesmoTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CookiesmoSurface,
                        border = BorderStroke(1.dp, Color(0xFFEF4444)),
                        modifier = Modifier.clickable(onClick = onDelete),
                    ) {
                        Text(
                            text = "Delete",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            color = CookiesmoMuted.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 142.dp),
        )
    }
}

@Composable
fun DownloadsScreenContent(
    downloads: List<LocalDownload>,
    folders: List<MediaFolder> = emptyList(),
    transfers: List<DownloadTransfer>,
    onOpen: (LocalDownload) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDismissTransfer: (String) -> Unit,
    onMoveToFolder: (id: String, folderId: String?) -> Unit = { _, _ -> },
    onCreateFolder: (String) -> Unit = {},
    onDeleteFolder: (String) -> Unit = {},
    onSaveToGallery: (LocalDownload) -> Unit = {},
) {
    val context = LocalContext.current
    val pendingTransfers = transfers.filter { it.status != DownloadStatus.COMPLETED }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var browsingFolder by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var moveTargetId by remember { mutableStateOf<String?>(null) }

    // Root list = only unfiled. Folder members leave the main list until you open that folder.
    val unfiled = remember(downloads) { downloads.filter { it.folderId == null } }
    val visible = remember(downloads, openFolderId, browsingFolder) {
        if (!browsingFolder) unfiled
        else if (openFolderId == null) unfiled
        else downloads.filter { it.folderId == openFolderId }
    }
    val folderCounts = remember(downloads, folders) {
        folders.associate { f -> f.id to downloads.count { it.folderId == f.id } }
    }
    val unfiledCount = unfiled.size
    val folderTitle = folders.firstOrNull { it.id == openFolderId }?.name

    if (showCreate) {
        CreateFolderDialog(
            title = "New downloads folder",
            onDismiss = { showCreate = false },
            onCreate = { name ->
                onCreateFolder(name)
                showCreate = false
                Toast.makeText(context, "Folder created", Toast.LENGTH_SHORT).show()
            },
        )
    }
    moveTargetId?.let { videoId ->
        FolderPickerDialog(
            title = "Move to folder",
            folders = folders,
            rootLabel = "Unfiled (no folder)",
            onDismiss = { moveTargetId = null },
            onPick = { folderId ->
                onMoveToFolder(videoId, folderId)
                moveTargetId = null
                Toast.makeText(context, "Moved", Toast.LENGTH_SHORT).show()
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DetailBgLike)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RoyalBlueNav)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (browsingFolder) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable {
                            browsingFolder = false
                            openFolderId = null
                        }
                        .padding(end = 4.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (browsingFolder) folderTitle ?: "Unfiled" else "Downloads",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!browsingFolder && downloads.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear all",
                    tint = Color.White,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onClearAll() },
                )
            }
        }

        if (downloads.isEmpty() && pendingTransfers.isEmpty() && !browsingFolder) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                FolderListHeader(
                    folders = folders,
                    counts = folderCounts,
                    unfiledCount = unfiledCount,
                    onOpenRoot = {
                        openFolderId = null
                        browsingFolder = true
                    },
                    onOpenFolder = { f ->
                        openFolderId = f.id
                        browsingFolder = true
                    },
                    onCreateFolder = { showCreate = true },
                    onDeleteFolder = { f ->
                        onDeleteFolder(f.id)
                        Toast.makeText(context, "Folder deleted", Toast.LENGTH_SHORT).show()
                    },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No downloaded video yet",
                        color = TextMetaBlue,
                        fontSize = 16.sp,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                if (!browsingFolder) {
                    FolderListHeader(
                        folders = folders,
                        counts = folderCounts,
                        unfiledCount = unfiledCount,
                        onOpenRoot = {
                            openFolderId = null
                            browsingFolder = true
                        },
                        onOpenFolder = { f ->
                            openFolderId = f.id
                            browsingFolder = true
                        },
                        onCreateFolder = { showCreate = true },
                        onDeleteFolder = { f ->
                            onDeleteFolder(f.id)
                            Toast.makeText(context, "Folder deleted", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                if (!browsingFolder) {
                    pendingTransfers.forEach { transfer ->
                        DownloadTransferRow(
                            transfer = transfer,
                            onCancel = { onCancel(transfer.id) },
                            onRetry = { onRetry(transfer.id) },
                            onDismiss = { onDismissTransfer(transfer.id) },
                        )
                    }
                    if (pendingTransfers.isNotEmpty() && downloads.isNotEmpty()) {
                        HorizontalDivider(
                            color = TextMetaBlue.copy(alpha = 0.25f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        Text(
                            text = "Available offline",
                            color = TextMetaBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                if (!browsingFolder && downloads.isNotEmpty()) {
                    Text(
                        text = "${unfiledCount} unfiled · ${downloads.size - unfiledCount} in folders",
                        color = TextMetaBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
                if (visible.isEmpty() && (browsingFolder || pendingTransfers.isEmpty())) {
                    Text(
                        text = if (browsingFolder) {
                            "No videos in this folder"
                        } else if (downloads.isNotEmpty()) {
                            "All downloads are in folders.\nOpen a folder above to play them."
                        } else {
                            "No downloaded video yet"
                        },
                        color = TextMetaBlue,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                visible.forEach { item ->
                    DownloadItemRow(
                        item = item,
                        onOpen = { onOpen(item) },
                        onMove = { moveTargetId = item.id },
                        onDelete = { onDelete(item.id) },
                        onSaveToGallery = { onSaveToGallery(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItemRow(
    item: LocalDownload,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onSaveToGallery: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(CookiesmoSurface),
        ) {
            val localThumb = item.thumbnailPath
                .takeIf { it.isNotBlank() }
                ?.let { path -> File(path.removePrefix("file://")) }
                ?.takeIf { it.isFile && it.length() > 0L }
            val thumbModel: Any? = when {
                localThumb != null -> localThumb
                item.thumbnailUrl.isNotBlank() ->
                    com.example.funfy.data.NetworkClient.sanitizeMediaUrl(item.thumbnailUrl)
                else -> null
            }
            if (thumbModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumbModel)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = CookiesmoTextMuted.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = CookiesmoTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.metaLine,
                color = CookiesmoTextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CookiesmoSurface,
                    border = BorderStroke(1.dp, CookiesmoMuted),
                    modifier = Modifier.clickable(onClick = onMove),
                ) {
                    Text(
                        text = "Move",
                        color = CookiesmoTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                val canSaveGallery = java.io.File(item.storagePath).exists() || java.io.File(item.filePath).exists()
                if (canSaveGallery) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CookiesmoSurface,
                        border = BorderStroke(1.dp, CookiesmoMuted),
                        modifier = Modifier.clickable(onClick = onSaveToGallery),
                    ) {
                        Text(
                            text = "Gallery",
                            color = CookiesmoTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CookiesmoSurface,
                    border = BorderStroke(1.dp, Color(0xFFEF4444)),
                    modifier = Modifier.clickable(onClick = onDelete),
                ) {
                    Text(
                        text = "Delete",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadTransferRow(
    transfer: DownloadTransfer,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val statusLabel = when (transfer.status) {
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.DOWNLOADING -> if (transfer.totalBytes == null) {
            "Downloading"
        } else {
            "Downloading · ${transfer.progressPercent}%"
        }
        DownloadStatus.FAILED -> "Download failed"
        DownloadStatus.CANCELLED -> "Download cancelled"
        DownloadStatus.COMPLETED -> "Complete"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(statusLabel)
                        if (transfer.resolution.isNotBlank()) append(" · ${transfer.resolution}")
                        if (transfer.bytesDownloaded > 0L) {
                            append(" · ${transfer.downloadedLabel}")
                            transfer.totalLabel?.let { append(" / $it") }
                        }
                    },
                    color = when (transfer.status) {
                        DownloadStatus.FAILED -> Color(0xFFFF8A80)
                        DownloadStatus.CANCELLED -> TextMetaBlue
                        else -> Color(0xFF9DB3FF)
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                transfer.isActive -> TextButton(onClick = onCancel) {
                    Text("Cancel", color = Color(0xFFFFB4AB), fontSize = 12.sp)
                }
                transfer.status == DownloadStatus.FAILED ||
                    transfer.status == DownloadStatus.CANCELLED -> {
                    TextButton(onClick = onRetry) {
                        Text("Retry", color = Color.White, fontSize = 12.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss transfer",
                            tint = TextMetaBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        if (transfer.isActive) {
            Spacer(modifier = Modifier.height(7.dp))
            val progressModifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
            if (transfer.status == DownloadStatus.DOWNLOADING && transfer.totalBytes == null) {
                LinearProgressIndicator(
                    modifier = progressModifier,
                    color = Color(0xFF6688FF),
                    trackColor = Color(0xFF24345F),
                )
            } else {
                LinearProgressIndicator(
                    progress = { transfer.progress.coerceIn(0f, 1f) },
                    modifier = progressModifier,
                    color = Color(0xFF6688FF),
                    trackColor = Color(0xFF24345F),
                )
            }
        }
        transfer.error?.takeIf { transfer.status == DownloadStatus.FAILED }?.let { error ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error,
                color = Color(0xFFFF8A80),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val DetailBgLike = CookiesmoBg

@Composable
fun MoreScreenContent(
    currentSource: VideoSource,
    onSourceSelected: (VideoSource) -> Unit,
    downloadsCount: Int,
    disablePreview: Boolean,
    onDisablePreviewChange: (Boolean) -> Unit,
    onClearDownloads: () -> Unit,
    onAutoShuffleChanged: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var showSourcePicker by remember { mutableStateOf(false) }
    var showSetPasscode by remember { mutableStateOf(false) }
    var showRemovePasscode by remember { mutableStateOf(false) }
    var showIdentityPicker by remember { mutableStateOf(false) }
    var showLoopPicker by remember { mutableStateOf(false) }
    var showNameEditor by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var hasPasscode by remember(context) { mutableStateOf(AppSettings.hasPasscode(context)) }
    var currentIdentity by remember(context) {
        mutableStateOf(AppSettings.launcherIdentity(context))
    }
    var loopMode by remember(context) { mutableStateOf(AppSettings.loopMode(context)) }
    var userName by remember(context) { mutableStateOf(AppSettings.userName(context)) }
    var fullscreenRotation by remember(context) {
        mutableStateOf(AppSettings.fullscreenOnRotation(context))
    }
    var forceMp4 by remember(context) { mutableStateOf(AppSettings.forceMp4(context)) }
    var autoPlay by remember(context) { mutableStateOf(AppSettings.autoPlay(context)) }
    var autoShuffle by remember(context) { mutableStateOf(AppSettings.autoShuffle(context)) }
    val currentIdentityLabel = stringResource(currentIdentity.labelRes)
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "Unknown" }
    }

    if (showSourcePicker) {
        SourcePickerDialog(
            current = currentSource,
            title = "Content sources",
            description = "Home and search load only from the source you select.",
            onDismiss = { showSourcePicker = false },
            onSelected = { source ->
                onSourceSelected(source)
                showSourcePicker = false
                Toast.makeText(context, "Source: ${source.label}", Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showSetPasscode) {
        SetPasscodeDialog(
            onDismiss = { showSetPasscode = false },
            onSaved = { pin ->
                if (AppSettings.setPasscode(context, pin)) {
                    hasPasscode = true
                    showSetPasscode = false
                    Toast.makeText(context, "App lock enabled", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    if (showRemovePasscode) {
        VerifyPasscodeDialog(
            title = "Turn off app lock",
            actionLabel = "Turn off",
            onDismiss = { showRemovePasscode = false },
            onVerified = {
                AppSettings.clearPasscode(context)
                hasPasscode = false
                showRemovePasscode = false
                Toast.makeText(context, "App lock disabled", Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (showIdentityPicker) {
        LauncherIdentityDialog(
            current = currentIdentity,
            onDismiss = { showIdentityPicker = false },
            onSelected = { identity ->
                LauncherIdentityManager.select(context, identity)
                    .onSuccess {
                        currentIdentity = identity
                        showIdentityPicker = false
                        Toast.makeText(
                            context,
                            "Launcher name and icon updated",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: "Could not change launcher icon",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            },
        )
    }
    if (showLoopPicker) {
        LoopModeDialog(
            current = loopMode,
            onDismiss = { showLoopPicker = false },
            onSelected = { selected ->
                AppSettings.setLoopMode(context, selected)
                loopMode = selected
                showLoopPicker = false
            },
        )
    }
    if (showNameEditor) {
        UserNameDialog(
            current = userName,
            onDismiss = { showNameEditor = false },
            onSaved = { value ->
                AppSettings.setUserName(context, value)
                userName = AppSettings.userName(context)
                showNameEditor = false
            },
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = CookiesmoSurface,
            title = { Text("Delete offline videos?", color = CookiesmoTextPrimary) },
            text = {
                Text(
                    "This permanently deletes $downloadsCount downloaded " +
                        if (downloadsCount == 1) "video." else "videos.",
                    color = CookiesmoTextMuted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearDownloads()
                        showDeleteConfirmation = false
                    },
                ) {
                    Text("Delete all", color = Color(0xFFFF8A80))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel", color = CookiesmoTextPrimary)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(vertical = 12.dp)
    ) {
        SettingsHeader("Privacy")
        SettingsClickableRow(
            title = "App passcode",
            subtext = if (hasPasscode) {
                "Required when the app returns from the background"
            } else {
                "Protect the app with a 4–8 digit PIN"
            },
            trailing = if (hasPasscode) "On" else "Off",
            icon = Icons.Default.Lock,
            onClick = {
                if (hasPasscode) showRemovePasscode = true else showSetPasscode = true
            },
        )
        SettingsClickableRow(
            title = "Discreet icon",
            subtext = "Changes the launcher name and icon",
            trailing = currentIdentityLabel,
            icon = Icons.Default.VerifiedUser,
            onClick = { showIdentityPicker = true },
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        SettingsHeader("Content")
        SettingsClickableRow(
            title = "Sources",
            subtext = "Choose the provider used by Home and Search",
            trailing = currentSource.label,
            onClick = { showSourcePicker = true },
        )
        SettingsSwitchRow(
            title = "Auto shuffle",
            subtext = "Show a random order of videos on the home page",
            checked = autoShuffle,
            onCheckedChange = {
                autoShuffle = it
                AppSettings.setAutoShuffle(context, it)
                onAutoShuffleChanged()
            },
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        SettingsHeader("Playback")
        SettingsClickableRow(
            title = "Loop",
            subtext = "What happens when a video ends",
            trailing = loopMode.displayName,
            onClick = { showLoopPicker = true },
        )
        SettingsClickableRow(
            title = "Your name (optional)",
            subtext = userName.ifBlank { "Not set" },
            onClick = { showNameEditor = true },
        )
        SettingsSwitchRow(
            title = "Fullscreen on rotation",
            checked = fullscreenRotation,
            onCheckedChange = {
                fullscreenRotation = it
                AppSettings.setFullscreenOnRotation(context, it)
            },
        )
        SettingsSwitchRow(
            title = "Disable previews",
            subtext = "Hide thumbnail images in video grids",
            checked = disablePreview,
            onCheckedChange = onDisablePreviewChange,
        )
        SettingsSwitchRow(
            title = "Force MP4",
            subtext = "Play and download only direct MP4 streams",
            checked = forceMp4,
            onCheckedChange = {
                forceMp4 = it
                AppSettings.setForceMp4(context, it)
            },
        )
        SettingsSwitchRow(
            title = "Auto play",
            checked = autoPlay,
            onCheckedChange = {
                autoPlay = it
                AppSettings.setAutoPlay(context, it)
            },
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        SettingsHeader("Downloads")
        SettingsClickableRow(
            title = "Delete all downloads",
            subtext = if (downloadsCount == 0) "No offline videos" else "$downloadsCount offline videos",
            icon = Icons.Default.Delete,
            enabled = downloadsCount > 0,
            onClick = { showDeleteConfirmation = true },
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        SettingsHeader("About")
        SettingsInfoRow(
            title = "Version",
            value = versionName,
        )
    }
}

@Composable
private fun SetPasscodeDialog(
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        title = { Text("Create app passcode", color = CookiesmoTextPrimary) },
        text = {
            Column {
                Text(
                    "Enter and confirm a 4–8 digit PIN. It will be required when the app returns from the background.",
                    color = TextMetaBlue,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter(Char::isDigit).take(8)
                        error = null
                    },
                    label = { Text("New PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { value ->
                        confirmation = value.filter(Char::isDigit).take(8)
                        error = null
                    },
                    label = { Text("Confirm PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = when {
                        pin.length !in 4..8 -> "PIN must contain 4–8 digits."
                        pin != confirmation -> "PINs do not match."
                        else -> {
                            onSaved(pin)
                            null
                        }
                    }
                },
            ) {
                Text("Save", color = CookiesmoAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMetaBlue) }
        },
    )
}

@Composable
private fun VerifyPasscodeDialog(
    title: String,
    actionLabel: String,
    onDismiss: (() -> Unit)?,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        containerColor = CookiesmoSurface,
        title = { Text(title, color = CookiesmoTextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter(Char::isDigit).take(8)
                        error = null
                    },
                    label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (AppSettings.verifyPasscode(context, pin)) {
                        onVerified()
                    } else {
                        error = "Incorrect PIN."
                    }
                },
                enabled = pin.length in 4..8,
            ) {
                Text(actionLabel, color = CookiesmoAccent)
            }
        },
        dismissButton = onDismiss?.let { dismiss ->
            @Composable {
                TextButton(onClick = dismiss) { Text("Cancel", color = TextMetaBlue) }
            }
        },
    )
}

@Composable
internal fun PasscodeUnlockDialog(onUnlocked: () -> Unit) {
    VerifyPasscodeDialog(
        title = "App locked",
        actionLabel = "Unlock",
        onDismiss = null,
        onVerified = onUnlocked,
    )
}

@Composable
private fun LauncherIdentityDialog(
    current: LauncherIdentity,
    onDismiss: () -> Unit,
    onSelected: (LauncherIdentity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        title = { Text("Discreet launcher", color = CookiesmoTextPrimary) },
        text = {
            Column {
                Text(
                    "Choose one launcher name and icon. Some launchers may take a few seconds to refresh.",
                    color = CookiesmoTextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LauncherIdentity.entries.forEach { identity ->
                    val selected = identity == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) CookiesmoAccent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { onSelected(identity) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = launcherIdentityIcon(identity),
                            contentDescription = null,
                            tint = when (identity) {
                                LauncherIdentity.FUNFY -> Color(0xFF0891B2)
                                LauncherIdentity.CALCULATOR -> Color(0xFF78909C)
                                LauncherIdentity.NOTES -> Color(0xFFFBC02D)
                                LauncherIdentity.WEATHER -> Color(0xFF1E88E5)
                                LauncherIdentity.FILES -> Color(0xFF1565C0)
                            },
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(identity.labelRes),
                            color = CookiesmoTextPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, "Selected", tint = CookiesmoAccent)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = CookiesmoAccent) }
        },
    )
}

private fun launcherIdentityIcon(identity: LauncherIdentity): ImageVector = when (identity) {
    LauncherIdentity.FUNFY -> Icons.Default.PlayCircle
    LauncherIdentity.CALCULATOR -> Icons.Default.Calculate
    LauncherIdentity.NOTES -> Icons.Default.Description
    LauncherIdentity.WEATHER -> Icons.Default.WbSunny
    LauncherIdentity.FILES -> Icons.Default.Folder
}

@Composable
private fun LoopModeDialog(
    current: LoopMode,
    onDismiss: () -> Unit,
    onSelected: (LoopMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        title = { Text("Loop playback", color = CookiesmoTextPrimary) },
        text = {
            Column {
                LoopMode.entries.forEach { mode ->
                    val selected = mode == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(mode) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = mode.displayName,
                            color = CookiesmoTextPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(Icons.Default.Check, "Selected", tint = CookiesmoAccent)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = CookiesmoAccent) }
        },
    )
}

@Composable
private fun UserNameDialog(
    current: String,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        title = { Text("Your name", color = CookiesmoTextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSaved(value) }) { Text("Save", color = CookiesmoAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMetaBlue) }
        },
    )
}

@Composable
fun SourcePickerDialog(
    current: VideoSource,
    title: String = "Sources",
    description: String = "Videos will load only from the source you pick.",
    onDismiss: () -> Unit,
    onSelected: (VideoSource) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = description,
                    color = TextMetaBlue,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                val providerSources = VideoSource.selectable.filter { it.region == null }
                if (providerSources.isNotEmpty()) {
                    SourcePickerSectionLabel("Providers")
                    providerSources.forEach { source ->
                        SourcePickerRow(source, source == current, onSelected)
                    }
                }
                VideoSource.regionalCatalogByRegion.forEach { (region, sources) ->
                    if (sources.isNotEmpty()) {
                        SourcePickerSectionLabel(region.label)
                        sources.forEach { source ->
                            SourcePickerRow(source, source == current, onSelected)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = CookiesmoAccent)
            }
        },
    )
}

@Composable
private fun SourcePickerSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        color = CookiesmoAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun SourcePickerRow(
    source: VideoSource,
    selected: Boolean,
    onSelected: (VideoSource) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) CookiesmoAccent.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onSelected(source) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.label,
                color = CookiesmoTextPrimary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                text = source.provider.label,
                color = CookiesmoTextMuted,
                fontSize = 11.sp,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = CookiesmoAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = CookiesmoAccent,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
fun SettingsClickableRow(
    title: String,
    subtext: String? = null,
    trailing: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) CookiesmoTextPrimary else CookiesmoTextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) CookiesmoTextPrimary else CookiesmoTextMuted.copy(alpha = 0.5f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal
            )
            if (subtext != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtext,
                    color = if (enabled) CookiesmoTextMuted else CookiesmoTextMuted.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                color = if (enabled) CookiesmoAccent else CookiesmoTextMuted.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (enabled) CookiesmoTextMuted else CookiesmoTextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = CookiesmoTextPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = CookiesmoTextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtext: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) CookiesmoTextPrimary else CookiesmoTextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) CookiesmoTextPrimary else CookiesmoTextMuted.copy(alpha = 0.5f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal
            )
            if (subtext != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtext,
                    color = if (enabled) CookiesmoTextMuted else CookiesmoTextMuted.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CookiesmoBg,
                checkedTrackColor = CookiesmoAccent,
                uncheckedThumbColor = CookiesmoTextMuted,
                uncheckedTrackColor = CookiesmoMuted,
                uncheckedBorderColor = Color.Transparent,
                disabledCheckedThumbColor = CookiesmoBg.copy(alpha = 0.5f),
                disabledCheckedTrackColor = CookiesmoAccent.copy(alpha = 0.5f),
                disabledUncheckedThumbColor = CookiesmoTextMuted.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = CookiesmoMuted.copy(alpha = 0.5f)
            )
        )
    }
}
