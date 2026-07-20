package com.example.funfy.ui.player

import android.app.Activity
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.funfy.AppSettings
import com.example.funfy.FunfyApp
import com.example.funfy.LoopMode
import com.example.funfy.data.BookmarkedVideo
import com.example.funfy.data.DownloadStatus
import com.example.funfy.data.NetworkClient
import com.example.funfy.data.StreamOption
import com.example.funfy.data.availableStreamQualities
import com.example.funfy.data.normalizeStreamQualityLabel
import com.example.funfy.data.pickDefaultStream
import com.example.funfy.data.streamQualityRank
import com.example.funfy.data.VideoDetails
import com.example.funfy.data.VideoItem
import com.example.funfy.theme.*
import com.example.funfy.data.VideoSource
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DetailBg = CookiesmoBg
private val TagBlue = CookiesmoMuted
private val MetaBlue = CookiesmoTextMuted
private val ActionIconBlue = CookiesmoTextMuted
private val DialogBlue = CookiesmoSurface

private fun qualityRank(label: String): Int = streamQualityRank(label)

private fun normalizeQualityLabel(label: String, url: String = ""): String =
  normalizeStreamQualityLabel(label, url)

/**
 * Resolution from decoded frames (source of truth for the player badge).
 * Landscape → height (classic 1080p). Portrait → width.
 */
internal fun renderedResolutionLabel(width: Int, height: Int): String? {
  if (width <= 0 || height <= 0) return null
  val p = if (height >= width) width else height
  // Snap to common ladder so 1078 → 1080p, 719 → 720p, etc.
  val snapped = listOf(2160, 1440, 1080, 720, 480, 360, 240)
    .minByOrNull { kotlin.math.abs(it - p) }
    ?.takeIf { kotlin.math.abs(it - p) <= 48 }
    ?: p
  return "${snapped}p"
}

internal fun renderedDimensionsLabel(width: Int, height: Int): String? {
  if (width <= 0 || height <= 0) return null
  return "$width × $height"
}

/** Selectable qualities (144p…1080p ladder when available). */
private fun availableQualityOptions(streams: List<StreamOption>): List<StreamOption> =
  availableStreamQualities(streams)

private fun isHlsUrl(url: String): Boolean {
  val path = url.substringBefore('?').substringBefore('#')
  return path.endsWith(".m3u8", ignoreCase = true) ||
    path.endsWith(".vl", ignoreCase = true) || // VLXX / vlstream manifests
    (path.contains("/manifest", ignoreCase = true) && path.contains("qooglevideo", ignoreCase = true))
}

private fun isDirectMp4Url(url: String): Boolean =
  url.substringBefore('?').substringBefore('#').endsWith(".mp4", ignoreCase = true)

private fun isDirectMp4Stream(option: StreamOption): Boolean =
  isDirectMp4Url(option.url) || option.label.contains("MP4", ignoreCase = true)

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
  title: String,
  pageUrl: String,
  duration: String = "",
  resolution: String = "",
  views: String = "",
  uploader: String = "",
  thumbnailUrl: String = "",
  isLocal: Boolean = false,
  onBack: () -> Unit,
  onRelatedClick: (VideoItem) -> Unit = {},
  /** Notifies host (MainScreen) so the bottom navbar can hide in immersive fullscreen. */
  onFullscreenChange: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val activity = context as? Activity
  val app = context.applicationContext as FunfyApp
  val transfers by app.downloadStore.transfers.collectAsStateWithLifecycle()
  val bookmarks by app.bookmarkStore.bookmarks.collectAsStateWithLifecycle()
  val autoPlayEnabled = remember(context, pageUrl) { AppSettings.autoPlay(context) }
  val loopMode = remember(context, pageUrl) { AppSettings.loopMode(context) }
  val fullscreenOnRotation = remember(context, pageUrl) {
    AppSettings.fullscreenOnRotation(context)
  }
  val previewsDisabled = remember(context, pageUrl) { AppSettings.disablePreviews(context) }
  val forceMp4 = remember(context, pageUrl) { AppSettings.forceMp4(context) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var details by remember { mutableStateOf<VideoDetails?>(null) }
  var selectedStream by remember { mutableStateOf<StreamOption?>(null) }
  var showSettingsMenu by remember { mutableStateOf(false) }
  var showPlayerQuality by remember { mutableStateOf(false) }
  var showSpeedMenu by remember { mutableStateOf(false) }
  var showDownloadDialog by remember { mutableStateOf(false) }
  var playerScale by remember(pageUrl) { mutableFloatStateOf(1f) }
  var playerOffset by remember(pageUrl) { mutableStateOf(Offset.Zero) }
  val isBookmarked = bookmarks.any {
    it.pageUrl.equals(pageUrl, ignoreCase = true) ||
      it.id == com.example.funfy.data.BookmarkStore.stableId(pageUrl)
  }
  var isFullscreen by remember { mutableStateOf(false) }
  var useEmbed by remember { mutableStateOf(false) }
  var playbackSpeed by remember { mutableStateOf(1.0f) }
  var renderedVideoSize by remember(pageUrl) { mutableStateOf<VideoSize?>(null) }
  var currentDownloadId by remember(pageUrl) { mutableStateOf<String?>(null) }
  var capturedMediaUrl by remember { mutableStateOf<String?>(null) }
  val currentTransfer = currentDownloadId?.let { id -> transfers.firstOrNull { it.id == id } }
  val downloadBusy = currentTransfer?.isActive == true

  val exoPlayer = remember(pageUrl) {
    // Start playback as soon as ~0.5s is buffered; keep a healthy readahead after that.
    val loadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        /* minBufferMs */ 15_000,
        /* maxBufferMs */ 50_000,
        /* bufferForPlaybackMs */ 500,
        /* bufferForPlaybackAfterRebufferMs */ 1_000,
      )
      .setPrioritizeTimeOverSizeThresholds(true)
      .build()
    ExoPlayer.Builder(context)
      .setLoadControl(loadControl)
      .build()
      .apply {
        playWhenReady = autoPlayEnabled
        repeatMode = if (loopMode == LoopMode.ONE) {
          Player.REPEAT_MODE_ONE
        } else {
          Player.REPEAT_MODE_OFF
        }
      }
  }

  fun playStream(option: StreamOption, resumePosition: Long = 0L) {
    val shouldPlay = if (selectedStream == null) autoPlayEnabled else exoPlayer.playWhenReady
    selectedStream = option
    // Do not show the previous stream's dimensions while this one prepares.
    renderedVideoSize = null
    error = null
    useEmbed = !isLocal && (
      option.label.equals("Embed", ignoreCase = true) ||
        details?.embedUrl == option.url
      )
    if (useEmbed) {
      loading = false
      return
    }

    val pos = if (resumePosition > 0) resumePosition else exoPlayer.currentPosition.coerceAtLeast(0L)
    val mediaItem = MediaItem.fromUri(option.url)
    val isFile = option.url.startsWith("file:") ||
      option.url.startsWith("/") ||
      isLocal
    if (isFile) {
      if (isHlsUrl(option.url)) {
        val localFactory = DefaultDataSource.Factory(context)
        val mediaSource = HlsMediaSource.Factory(localFactory).createMediaSource(mediaItem)
        exoPlayer.setMediaSource(mediaSource)
      } else {
        exoPlayer.setMediaItem(mediaItem)
      }
      exoPlayer.prepare()
    } else {
      val lowerUrl = option.url.lowercase()
      val pageRef = NetworkClient.siteReferer(pageUrl)
      val referer = when {
        lowerUrl.contains("goostream") || lowerUrl.contains("corecache") ||
          lowerUrl.contains("flixtream") -> "https://flixtream.top/"
        lowerUrl.contains("cloudflarestorage") || lowerUrl.contains("r2.") ||
          lowerUrl.contains("buumal") -> "https://www.buumal.com/"
        // MMPorns / DrKoGyi Clean Tube hosts mp4s on drkogyi — page referer works best.
        lowerUrl.contains("drkogyi") || pageUrl.contains("mmporns", true) ->
          if (pageUrl.contains("mmporns", true)) "https://mmporns.com/"
          else "https://drkogyi.vip/"
        lowerUrl.contains("bigcdn.cc") -> "https://hqporner.com/"
        lowerUrl.contains("tma.cx") || lowerUrl.contains("extmatrix") -> "https://javfree.me/"
        lowerUrl.contains("mydaddy") -> "https://hqporner.com/"
        lowerUrl.contains("playerwish") || lowerUrl.contains("strwish") ||
          lowerUrl.contains("streamwish") || lowerUrl.contains("hlswish") ||
          lowerUrl.contains("swishsrv") || lowerUrl.contains("premilkyway") ||
          lowerUrl.contains("retailinfrastructure") || lowerUrl.contains("hicherri") ||
          lowerUrl.contains("turbovid") -> "https://playerwish.com/"
        lowerUrl.contains("surrit.com") || lowerUrl.contains("fourhoi.com") ->
          "https://missav.ws/"
        lowerUrl.contains("mmhd-cdn") || lowerUrl.contains("mmhdhub") ||
          lowerUrl.contains("dl.mmhdhub") -> "https://mmhdhub.com/"
        lowerUrl.contains("gdvid.info") || lowerUrl.contains("javprovider.com") ->
          "https://hentaimama.io/"
        lowerUrl.contains("ahcdn.com") || lowerUrl.contains("sexvid") ->
          if (pageUrl.contains("analdin", true)) "https://www.analdin.com/"
          else "https://www.sexvid.xxx/"
        lowerUrl.contains("analdin") -> "https://www.analdin.com/"
        lowerUrl.contains("eporner") -> "https://www.eporner.com/"
        else -> pageRef
      }
      val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(NetworkClient.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12_000)
        .setReadTimeoutMs(20_000)
        .setDefaultRequestProperties(
          mapOf(
            "Referer" to referer,
            "Origin" to referer.trimEnd('/'),
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
          ),
        )
      val mediaSource = if (
        isHlsUrl(option.url) ||
        option.url.contains(".m3u8", ignoreCase = true) ||
        option.label.contains("HLS", ignoreCase = true) ||
        option.label.equals("Auto", ignoreCase = true)
      ) {
        HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
      } else if (FastStartDataSource.shouldTryFastStart(option.url)) {
        // moov-at-end progressive MP4s (MMPorns/DrKoGyi): virtual fast-start layout.
        ProgressiveMediaSource.Factory(
          FastStartDataSourceFactory(
            userAgent = NetworkClient.USER_AGENT,
            defaultReferer = referer,
          ),
        ).createMediaSource(mediaItem)
      } else {
        ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
      }
      exoPlayer.setMediaSource(mediaSource)
      exoPlayer.prepare()
    }
    if (pos > 0) {
      exoPlayer.seekTo(pos)
    }
    exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    exoPlayer.playWhenReady = shouldPlay
  }

  fun setFullscreen(enabled: Boolean, lockOrientation: Boolean = true) {
    isFullscreen = enabled
    onFullscreenChange(enabled)
    // Reset pinch zoom when leaving fullscreen so the next session starts clean.
    if (!enabled) {
      playerScale = 1f
      playerOffset = Offset.Zero
    }
    val act = activity ?: return
    val window = act.window
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (enabled) {
      if (lockOrientation) {
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
      WindowCompat.setDecorFitsSystemWindows(window, false)
      controller.hide(WindowInsetsCompat.Type.systemBars())
      controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
      if (lockOrientation) {
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
      WindowCompat.setDecorFitsSystemWindows(window, true)
      controller.show(WindowInsetsCompat.Type.systemBars())
    }
  }

  fun enqueueDownload(option: StreamOption) {
    if (downloadBusy) return
    if (option.label.equals("Embed", ignoreCase = true) && capturedMediaUrl == null) {
      Toast.makeText(
        context,
        "Still extracting stream… play the video a few seconds then try again.",
        Toast.LENGTH_LONG,
      ).show()
      return
    }
    val url = if (option.label.equals("Embed", true)) {
      capturedMediaUrl
    } else {
      option.url
    }
    if (url.isNullOrBlank()) {
      Toast.makeText(context, "No downloadable file yet.", Toast.LENGTH_SHORT).show()
      return
    }
    val resLabel = normalizeQualityLabel(option.label, option.url).let {
      if (it == "Embed" || it == "Video") "HD" else it
    }
    currentDownloadId = app.downloadStore.enqueue(
      title = details?.title ?: title,
      streamUrl = url,
      resolution = resLabel,
      duration = details?.duration ?: duration,
      thumbnailUrl = NetworkClient.sanitizeMediaUrl(
        details?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: thumbnailUrl,
      ),
      referer = if (isLocal) pageUrl else NetworkClient.siteReferer(pageUrl),
    )
    val sizeHint = option.sizeBytes?.takeIf { it > 0 }?.let {
      " · ${StreamOption.formatBytes(it)}"
    }.orEmpty()
    Toast.makeText(context, "Downloading $resLabel$sizeHint…", Toast.LENGTH_SHORT).show()
  }

  fun onCapturedMedia(url: String) {
    if (url.isBlank()) return
    if (forceMp4 && !isDirectMp4Url(url)) return
    if (capturedMediaUrl == url) return
    capturedMediaUrl = url
    // Prefer real stream over embed black screen
    val opt = StreamOption(label = "HD", url = url)
    val current = details
    if (current != null) {
      val merged = (listOf(opt) + current.streams.filter { !it.label.equals("Embed", true) })
        .distinctBy { it.url }
      details = current.copy(
        streamUrl = url,
        streams = merged,
        embedUrl = null,
        resolution = "HD",
      )
    }
    if (useEmbed || selectedStream?.label.equals("Embed", true) == true) {
      playStream(opt)
    }
  }

  LaunchedEffect(pageUrl, isLocal) {
    loading = true
    error = null
    details = null
    selectedStream = null
    showSettingsMenu = false
    showPlayerQuality = false
    showSpeedMenu = false
    showDownloadDialog = false
    useEmbed = false
    capturedMediaUrl = null
    playbackSpeed = 1.0f
    exoPlayer.stop()
    exoPlayer.clearMediaItems()
    try {
      if (isLocal) {
        val path = pageUrl.removePrefix("file://")
        val localFile = java.io.File(path)
        require(localFile.isFile && localFile.length() > 0L) {
          "This offline file is missing or incomplete"
        }
        val fileUri = Uri.fromFile(localFile).toString()
        val local = StreamOption(
          label = resolution.ifBlank { "Offline" },
          url = fileUri,
        )
        details = VideoDetails(
          streamUrl = fileUri,
          streams = listOf(local),
          title = title,
          uploader = uploader.ifBlank { "Downloads" },
          views = views,
          ratingPercent = "—",
          duration = duration,
          resolution = resolution.ifBlank { "Offline" },
          tags = emptyList(),
          related = emptyList(),
          thumbnailUrl = thumbnailUrl,
        )
        playStream(local)
        loading = false
      } else {
        val result = withContext(Dispatchers.IO) {
          app.repository.fetchVideoDetails(pageUrl)
        }
        // Avoid a second slow withSizes pass when sizes were already probed (or skipped).
        val streams = result.streams.ifEmpty {
          listOf(StreamOption(label = result.resolution.ifBlank { "Auto" }, url = result.streamUrl))
        }
        val sized = if (streams.any { it.sizeBytes != null && it.sizeBytes!! > 0L }) {
          streams
        } else if (
          streams.any {
            it.url.contains("drkogyi", true) ||
              it.url.contains("/uploads/", true) ||
              it.label.equals("Embed", true)
          }
        ) {
          streams
        } else {
          withContext(Dispatchers.IO) {
            try {
              NetworkClient.withSizes(streams, NetworkClient.siteReferer(pageUrl))
            } catch (_: Exception) {
              streams
            }
          }
        }
        // Prefer real views from detail page over listing placeholder "—"
        val viewsResolved = result.views.takeIf { it.isNotBlank() && it != "—" }
          ?: views.takeIf { it.isNotBlank() && it != "—" }
          ?: "—"
        details = result.copy(
          streams = sized,
          views = viewsResolved,
          resolution = pickDefaultStream(sized)?.label
            ?: result.resolution,
        )
        val available = availableQualityOptions(sized)
        val compatible = if (forceMp4) available.filter(::isDirectMp4Stream) else available
        // Default play: 480p → 360p → lower (not 1080p first).
        val first = pickDefaultStream(compatible)
          ?: pickDefaultStream(available)
          ?: if (forceMp4) {
            sized.firstOrNull { isDirectMp4Url(it.url) }
              ?: throw IllegalStateException("No MP4-compatible stream is available for this video")
          } else {
            sized.firstOrNull()
              ?: StreamOption(label = result.resolution.ifBlank { "Auto" }, url = result.streamUrl)
          }
        // For moov-at-end hosts, prewarm the index (parallel range fetch) before prepare
        // so ExoPlayer can start almost immediately after this returns.
        if (
          !first.label.equals("Embed", true) &&
          FastStartDataSource.shouldTryFastStart(first.url)
        ) {
          withContext(Dispatchers.IO) {
            val ref = when {
              first.url.contains("drkogyi", true) && pageUrl.contains("mmporns", true) ->
                "https://mmporns.com/"
              first.url.contains("drkogyi", true) -> "https://drkogyi.vip/"
              else -> NetworkClient.siteReferer(pageUrl)
            }
            FastStartDataSource.prewarm(first.url, ref)
          }
        }
        playStream(first, resumePosition = 0L)
        loading = false
      }
    } catch (t: Throwable) {
      error = t.message ?: "Failed to load video"
      loading = false
    }
  }

  LaunchedEffect(currentTransfer?.status, currentDownloadId) {
    when (currentTransfer?.status) {
      DownloadStatus.COMPLETED ->
        Toast.makeText(context, "Saved to Downloads tab", Toast.LENGTH_SHORT).show()
      DownloadStatus.FAILED -> Toast.makeText(
        context,
        "Download failed: ${currentTransfer.error ?: "Unknown error"}",
        Toast.LENGTH_LONG,
      ).show()
      DownloadStatus.CANCELLED ->
        Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
      else -> Unit
    }
  }

  LaunchedEffect(configuration.orientation, fullscreenOnRotation) {
    if (fullscreenOnRotation) {
      setFullscreen(
        enabled = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        lockOrientation = false,
      )
    }
  }

  val latestRelated by rememberUpdatedState(details?.related.orEmpty())
  val latestRelatedClick by rememberUpdatedState(onRelatedClick)
  DisposableEffect(exoPlayer, loopMode, autoPlayEnabled) {
    val listener = object : Player.Listener {
      override fun onVideoSizeChanged(videoSize: VideoSize) {
        renderedVideoSize = videoSize.takeIf { it.width > 0 && it.height > 0 }
      }

      override fun onPlayerError(playbackException: androidx.media3.common.PlaybackException) {
        // Surface real playback failures instead of spinning forever on a black player.
        val cause = playbackException.cause?.message ?: playbackException.message
        error = cause ?: "Playback failed"
        loading = false
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        // Re-read size when ready — first callback can be 0×0 or a low ladder rung.
        if (playbackState == Player.STATE_READY) {
          loading = false
          error = null
          val f = exoPlayer.videoFormat
          if (f != null && f.width > 0 && f.height > 0) {
            renderedVideoSize = VideoSize(f.width, f.height)
          } else {
            val vs = exoPlayer.videoSize
            if (vs.width > 0 && vs.height > 0) {
              renderedVideoSize = vs
            }
          }
        }
        if (
          playbackState == Player.STATE_ENDED &&
          loopMode == LoopMode.AUTO &&
          autoPlayEnabled
        ) {
          val next = latestRelated.firstOrNull()
          if (next != null) {
            latestRelatedClick(next)
          } else {
            exoPlayer.seekTo(0L)
            exoPlayer.play()
          }
        }
      }
    }
    exoPlayer.addListener(listener)
    onDispose { exoPlayer.removeListener(listener) }
  }

  DisposableEffect(exoPlayer) {
    onDispose {
      setFullscreen(false)
      exoPlayer.release()
    }
  }

  BackHandler {
    when {
      showPlayerQuality -> showPlayerQuality = false
      showSpeedMenu -> showSpeedMenu = false
      showSettingsMenu -> showSettingsMenu = false
      isFullscreen -> setFullscreen(false)
      else -> onBack()
    }
  }

  val displayTitle = details?.title?.takeIf { it.isNotBlank() } ?: title
  val displayUploader = details?.uploader?.takeIf { it.isNotBlank() } ?: uploader.ifBlank { "Channel" }
  val displayDuration = details?.duration?.takeIf { it.isNotBlank() && it != "—" } ?: duration
  val qualityOptions = availableQualityOptions(details?.streams.orEmpty()).let { options ->
    if (forceMp4) options.filter(::isDirectMp4Stream) else options
  }
  val selectedQualityLabel = normalizeQualityLabel(
    selectedStream?.label
      ?: details?.resolution?.takeIf { it.isNotBlank() }
      ?: resolution.ifBlank { "Auto" },
    selectedStream?.url.orEmpty(),
  )
  // Decoder output is always the badge truth once known (fixes wrong 240p labels).
  val renderedResolution = renderedVideoSize?.let {
    renderedResolutionLabel(it.width, it.height)
  }
  val renderedDimensions = renderedVideoSize?.let {
    renderedDimensionsLabel(it.width, it.height)
  }
  val videoIsPortrait = renderedVideoSize?.let { it.height > it.width } == true
  // Decoder output is the badge truth (prevents false 240p/720p from URL noise).
  LaunchedEffect(renderedResolution, selectedStream?.url) {
    val actual = renderedResolution ?: return@LaunchedEffect
    val stream = selectedStream ?: return@LaunchedEffect
    if (!stream.label.equals(actual, true) && !stream.label.equals("Auto", true)) {
      selectedStream = stream.copy(label = actual)
      // Keep details.streams entry labels accurate for quality menu.
      details = details?.let { d ->
        d.copy(
          streams = d.streams.map { s ->
            if (s.url == stream.url) s.copy(label = actual) else s
          },
          resolution = if (d.streamUrl == stream.url) actual else d.resolution,
        )
      }
    }
  }
  val displayResolution = renderedResolution
    ?: selectedQualityLabel.takeIf {
      it.endsWith("p", true) || it.equals("Auto", true)
    }
    ?: selectedQualityLabel
  val currentQualitySummary = buildString {
    append(displayResolution)
    renderedDimensions?.let { dims ->
      append(" · $dims")
      if (videoIsPortrait) append(" portrait")
    }
  }
  val displayViews = details?.views?.takeIf { it.isNotBlank() && it != "—" } ?: views
  val displayRating = details?.ratingPercent?.takeIf { it.isNotBlank() && it != "—" } ?: "—"
  val tags = details?.tags.orEmpty()
  val related = details?.related.orEmpty()
  val streams = details?.streams.orEmpty()
  val downloadStreams = qualityOptions
  val embedUrl = details?.embedUrl

  // Settings menu (Quality / Speed) — opened from player gear
  if (showSettingsMenu) {
    PlayerSettingsMenu(
      currentQuality = currentQualitySummary,
      currentSpeed = playbackSpeed,
      hasQualityOptions = qualityOptions.isNotEmpty(),
      onDismiss = { showSettingsMenu = false },
      onQualityClick = {
        showSettingsMenu = false
        showPlayerQuality = true
      },
      onSpeedClick = {
        showSettingsMenu = false
        showSpeedMenu = true
      },
    )
  }

  if (showSpeedMenu) {
    SpeedPickerDialog(
      current = playbackSpeed,
      onDismiss = { showSpeedMenu = false },
      onSelect = { speed ->
        playbackSpeed = speed
        exoPlayer.playbackParameters = PlaybackParameters(speed)
        showSpeedMenu = false
      },
    )
  }

  if (showPlayerQuality && qualityOptions.isNotEmpty()) {
    QualityPickerDialog(
      title = "Playback quality",
      options = qualityOptions,
      selected = selectedStream?.let { sel ->
        qualityOptions.firstOrNull { it.url == sel.url }
          ?: qualityOptions.firstOrNull {
            qualityRank(it.label) == qualityRank(sel.label)
          }
      },
      confirmLabel = "OK",
      playingResolution = renderedDimensions,
      onDismiss = { showPlayerQuality = false },
      onConfirm = { option ->
        showPlayerQuality = false
        if (option.url != selectedStream?.url) {
          playStream(option)
        }
      },
    )
  }

  if (showDownloadDialog) {
    var downloadOpts by remember(pageUrl, details?.streams, capturedMediaUrl) {
      mutableStateOf(
        when {
          downloadStreams.isNotEmpty() -> downloadStreams
          capturedMediaUrl != null -> listOf(
            StreamOption(label = "HD", url = capturedMediaUrl!!),
          )
          selectedStream != null && !selectedStream!!.label.equals("Embed", true) ->
            listOf(selectedStream!!)
          else -> emptyList()
        },
      )
    }
    LaunchedEffect(showDownloadDialog, downloadStreams, details?.streams) {
      if (!showDownloadDialog) return@LaunchedEffect
      // Prefer full multi-quality list from details, not only currently selected.
      val base = when {
        downloadStreams.isNotEmpty() -> downloadStreams
        details?.streams.orEmpty().isNotEmpty() ->
          availableQualityOptions(details!!.streams)
        capturedMediaUrl != null -> listOf(StreamOption(label = "HD", url = capturedMediaUrl!!))
        selectedStream != null && !selectedStream!!.label.equals("Embed", true) ->
          listOf(selectedStream!!)
        else -> emptyList()
      }
      if (base.isEmpty()) {
        showDownloadDialog = false
        Toast.makeText(
          context,
          "Play the video a few seconds so the stream can be captured, then download.",
          Toast.LENGTH_LONG,
        ).show()
        return@LaunchedEffect
      }
      downloadOpts = base.map {
        it.copy(label = normalizeQualityLabel(it.label, it.url))
      }
      // Always fetch real Content-Length per resolution for the download sheet.
      val sized = withContext(Dispatchers.IO) {
        NetworkClient.withSizes(base, NetworkClient.siteReferer(pageUrl))
          .map { it.copy(label = normalizeQualityLabel(it.label, it.url)) }
          .sortedByDescending { qualityRank(it.label) }
      }
      downloadOpts = sized
      // Keep details.streams sizes for next open of the dialog.
      details = details?.let { d ->
        val byUrl = sized.associateBy { it.url }
        d.copy(
          streams = d.streams.map { s ->
            byUrl[s.url]?.let { s.copy(sizeBytes = it.sizeBytes, label = it.label) } ?: s
          },
        )
      }
    }
    if (downloadOpts.isNotEmpty()) {
      QualityPickerDialog(
        title = "Download quality",
        options = downloadOpts,
        selected = pickDefaultStream(downloadOpts)
          ?: selectedStream?.takeIf { s -> downloadOpts.any { it.url == s.url } }
          ?: downloadOpts.first(),
        confirmLabel = "DOWNLOAD",
        playingResolution = null,
        showSizes = true,
        onDismiss = { showDownloadDialog = false },
        onConfirm = { option ->
          showDownloadDialog = false
          enqueueDownload(option)
        },
      )
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(if (isFullscreen) Color.Black else DetailBg)
      .then(if (isFullscreen) Modifier else Modifier.statusBarsPadding()),
  ) {
    if (!isFullscreen) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(RoyalBlueNav)
          .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
          )
        }
        Text(
          text = displayTitle,
          color = Color.White,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp),
        )
      }
    }

    Box(
      modifier = if (isFullscreen) {
        Modifier
          .fillMaxSize()
          .background(Color.Black)
      } else {
        Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .background(Color.Black)
      },
      contentAlignment = Alignment.Center,
    ) {
      when {
        loading -> {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = RoyalBlueNav, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text("Loading stream…", color = TextMetaBlue, fontSize = 13.sp)
          }
        }
        error != null -> {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
          ) {
            Text("Playback error", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(error.orEmpty(), color = TextMetaBlue, fontSize = 12.sp)
          }
        }
        useEmbed && !embedUrl.isNullOrBlank() -> {
          AndroidView(
            factory = { ctx ->
              EmbedAdFreeWebView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                  ViewGroup.LayoutParams.MATCH_PARENT,
                  ViewGroup.LayoutParams.MATCH_PARENT,
                )
                onMediaUrlDetected = { url -> onCapturedMedia(url) }
                // Prefer full page with player for Indo18 (more reliable than nested embed alone)
                val startUrl = when {
                  pageUrl.contains("indo18", true) -> pageUrl
                  else -> embedUrl
                }
                loadUrl(startUrl)
              }
            },
            update = { web ->
              web.onMediaUrlDetected = { url -> onCapturedMedia(url) }
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
        selectedStream != null -> {
          // xHamster-style: letterbox FIT base, pinch zooms toward fingers, double-tap 1x/2.5x.
          val maxZoom = 4f
          Box(
            modifier = Modifier
              .fillMaxSize()
              .pointerInput(pageUrl) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                  val oldScale = playerScale
                  val next = (oldScale * zoom).coerceIn(1f, maxZoom)
                  if (next <= 1.02f) {
                    playerScale = 1f
                    playerOffset = Offset.Zero
                    return@detectTransformGestures
                  }
                  // Keep the content under the pinch point stable while scaling.
                  val focusX = centroid.x - size.width / 2f
                  val focusY = centroid.y - size.height / 2f
                  val ratio = next / oldScale.coerceAtLeast(0.01f)
                  var ox = (playerOffset.x - focusX) * ratio + focusX + pan.x
                  var oy = (playerOffset.y - focusY) * ratio + focusY + pan.y
                  val maxX = size.width * (next - 1f) * 0.5f
                  val maxY = size.height * (next - 1f) * 0.5f
                  playerScale = next
                  playerOffset = Offset(
                    x = ox.coerceIn(-maxX, maxX),
                    y = oy.coerceIn(-maxY, maxY),
                  )
                }
              }
              .pointerInput(pageUrl) {
                detectTapGestures(
                  onDoubleTap = { tap ->
                    if (playerScale > 1.08f) {
                      playerScale = 1f
                      playerOffset = Offset.Zero
                    } else {
                      // Zoom in around the double-tap point (xHamster-like).
                      val target = 2.5f
                      val focusX = tap.x - size.width / 2f
                      val focusY = tap.y - size.height / 2f
                      playerScale = target
                      val maxX = size.width * (target - 1f) * 0.5f
                      val maxY = size.height * (target - 1f) * 0.5f
                      playerOffset = Offset(
                        x = (-focusX * (target - 1f)).coerceIn(-maxX, maxX),
                        y = (-focusY * (target - 1f)).coerceIn(-maxY, maxY),
                      )
                    }
                  },
                )
              }
              .graphicsLayer {
                scaleX = playerScale
                scaleY = playerScale
                translationX = playerOffset.x
                translationY = playerOffset.y
                clip = false
              },
          ) {
            AndroidView(
              factory = { ctx ->
                PlayerView(ctx).apply {
                  player = exoPlayer
                  layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                  )
                  useController = true
                  // Always FIT (letterbox); pinch/double-tap handle zoom like xHamster.
                  resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                  setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                  setShowNextButton(false)
                  setShowPreviousButton(false)
                  post {
                    val settingsId = resources.getIdentifier(
                      "exo_settings",
                      "id",
                      "androidx.media3.ui",
                    )
                    if (settingsId != 0) {
                      findViewById<View>(settingsId)?.setOnClickListener {
                        showSettingsMenu = true
                      }
                    }
                  }
                }
              },
              update = { view ->
                view.player = exoPlayer
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.post {
                  val settingsId = view.resources.getIdentifier(
                    "exo_settings",
                    "id",
                    "androidx.media3.ui",
                  )
                  if (settingsId != 0) {
                    view.findViewById<View>(settingsId)?.setOnClickListener {
                      showSettingsMenu = true
                    }
                  }
                }
              },
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }

      // Fullscreen only (no extra settings gear at top)
      if (!loading && error == null) {
        Surface(
          shape = CircleShape,
          color = Color.Black.copy(alpha = 0.45f),
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .size(40.dp)
            .clickable { setFullscreen(!isFullscreen) },
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = if (isFullscreen) {
                Icons.Default.FullscreenExit
              } else {
                Icons.Default.Fullscreen
              },
              contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
              tint = Color.White,
              modifier = Modifier.size(22.dp),
            )
          }
        }

        // Keep the active quality visible in both portrait and fullscreen. The
        // badge switches to the decoder-reported resolution once playback starts.
        if (qualityOptions.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
              .align(Alignment.TopStart)
              .padding(8.dp)
              .clickable { showPlayerQuality = true },
          ) {
            Text(
              text = displayResolution,
              color = Color.White,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
          }
        }
      }
    }

    if (!isFullscreen) {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .background(DetailBg),
      ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
          ) {
            Text(
              text = displayTitle,
              color = CookiesmoTextPrimary,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              lineHeight = 24.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, CookiesmoAccent),
              ) {
                Text(
                  text = displayUploader,
                  color = CookiesmoAccent,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
              }

              Spacer(modifier = Modifier.width(10.dp))

              if (displayRating != "—") {
                Text(
                  text = displayRating,
                  color = MetaBlue,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Medium,
                )
                Icon(
                  imageVector = Icons.Default.Star,
                  contentDescription = null,
                  tint = MetaBlue,
                  modifier = Modifier
                    .padding(start = 2.dp, end = 6.dp)
                    .size(14.dp),
                )
              }

              val metaParts = buildList {
                if (displayDuration.isNotBlank() && displayDuration != "—") add(displayDuration)
                if (displayResolution.isNotBlank()) add(displayResolution)
                // Always surface views when known; keep placeholder so row is consistent.
                if (displayViews.isNotBlank()) add(displayViews)
              }
              if (metaParts.isNotEmpty()) {
                Text(
                  text = metaParts.joinToString(" • "),
                  color = MetaBlue,
                  fontSize = 13.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f, fill = false),
                )
                if (displayViews.isNotBlank() && displayViews != "—") {
                  Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = "Views",
                    tint = MetaBlue,
                    modifier = Modifier
                      .padding(start = 4.dp)
                      .size(15.dp),
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = CookiesmoSurface,
                border = BorderStroke(1.dp, if (downloadBusy) CookiesmoAccent else CookiesmoMuted),
                modifier = Modifier
                  .weight(1f)
                  .clickable(enabled = !isLocal && !downloadBusy) {
                    showDownloadDialog = true
                  },
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                  Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Download",
                    tint = if (downloadBusy) CookiesmoAccent else CookiesmoTextMuted,
                    modifier = Modifier.size(22.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = if (isLocal) {
                      "Available offline"
                    } else if (downloadBusy) {
                      if (currentTransfer?.totalBytes == null) {
                        "Downloading"
                      } else {
                        "Downloading ${currentTransfer.progressPercent}%"
                      }
                    } else {
                      "Download"
                    },
                    color = if (downloadBusy) CookiesmoAccent else CookiesmoTextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }

              if (!isLocal) {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = CookiesmoSurface,
                  border = BorderStroke(1.dp, if (isBookmarked) CookiesmoAccent else CookiesmoMuted),
                  modifier = Modifier.clickable {
                    val source = VideoSource.fromUrl(pageUrl)
                      ?: VideoSource.fromId(details?.related?.firstOrNull()?.sourceId)
                    val added = app.bookmarkStore.toggle(
                      BookmarkedVideo(
                        id = "",
                        title = displayTitle,
                        pageUrl = pageUrl,
                        thumbnailUrl = details?.thumbnailUrl ?: thumbnailUrl,
                        duration = displayDuration,
                        resolution = displayResolution,
                        sourceId = source.id,
                        sourceLabel = source.label,
                      ),
                    )
                    Toast.makeText(
                      context,
                      if (added) "Saved to Bookmarks" else "Removed from Bookmarks",
                      Toast.LENGTH_SHORT,
                    ).show()
                  },
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                  ) {
                    Icon(
                      imageVector = if (isBookmarked) {
                        Icons.Default.Bookmark
                      } else {
                        Icons.Default.BookmarkBorder
                      },
                      contentDescription = "Bookmark",
                      tint = if (isBookmarked) CookiesmoAccent else CookiesmoTextMuted,
                      modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = if (isBookmarked) "Saved" else "Bookmark",
                      color = if (isBookmarked) CookiesmoAccent else CookiesmoTextPrimary,
                      fontFamily = FontFamily.Monospace,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }
              }
            }

            currentTransfer?.let { transfer ->
              when (transfer.status) {
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING -> {
                  Spacer(modifier = Modifier.height(8.dp))
                  val progressModifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                  if (transfer.status == DownloadStatus.DOWNLOADING && transfer.totalBytes == null) {
                    LinearProgressIndicator(
                      color = Color(0xFF6B8FD6),
                      trackColor = Color(0xFF24345F),
                      modifier = progressModifier,
                    )
                  } else {
                    LinearProgressIndicator(
                      progress = { transfer.progress.coerceIn(0f, 1f) },
                      color = Color(0xFF6B8FD6),
                      trackColor = Color(0xFF24345F),
                      modifier = progressModifier,
                    )
                  }
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    val byteProgress = transfer.totalLabel?.let { total ->
                      "${transfer.downloadedLabel} / $total"
                    } ?: transfer.downloadedLabel
                    Text(
                      text = if (transfer.status == DownloadStatus.QUEUED) {
                        "Queued"
                      } else {
                        byteProgress
                      },
                      color = MetaBlue,
                      fontSize = 12.sp,
                      modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { app.downloadStore.cancel(transfer.id) }) {
                      Text("Cancel", color = Color(0xFFFFA6A6), fontSize = 12.sp)
                    }
                  }
                }

                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED -> {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Text(
                      text = if (transfer.status == DownloadStatus.FAILED) {
                        transfer.error ?: "Download failed"
                      } else {
                        "Download cancelled"
                      },
                      color = if (transfer.status == DownloadStatus.FAILED) {
                        Color(0xFFFFA6A6)
                      } else {
                        MetaBlue
                      },
                      fontSize = 12.sp,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { app.downloadStore.retry(transfer.id) }) {
                      Text("Retry", color = ActionIconBlue, fontSize = 12.sp)
                    }
                  }
                }

                DownloadStatus.COMPLETED -> {
                  Spacer(modifier = Modifier.height(6.dp))
                  Text(
                    text = "Available offline • ${transfer.localDownload?.sizeLabel ?: transfer.downloadedLabel}",
                    color = MetaBlue,
                    fontSize = 12.sp,
                  )
                }
              }
            }

            if (useEmbed) {
              Spacer(modifier = Modifier.height(10.dp))
              Text(
                text = "Playing via ad-blocked embed player.",
                color = MetaBlue,
                fontSize = 12.sp,
              )
            }

            if (tags.isNotEmpty()) {
              Spacer(modifier = Modifier.height(16.dp))
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                tags.forEach { tag ->
                  Surface(
                    shape = RoundedCornerShape(50),
                    color = TagBlue,
                  ) {
                    Text(
                      text = tag,
                      color = CookiesmoTextPrimary,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Medium,
                      modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                  }
                }
              }
            }

            if (related.isNotEmpty()) {
              Spacer(modifier = Modifier.height(20.dp))
              Text(
                text = "Related videos",
                color = CookiesmoTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }

        items(related, key = { it.id }) { video ->
          RelatedVideoCard(
            video = video,
            showThumbnail = !previewsDisabled,
            onClick = { onRelatedClick(video) },
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun PlayerSettingsMenu(
  currentQuality: String,
  currentSpeed: Float,
  hasQualityOptions: Boolean,
  onDismiss: () -> Unit,
  onQualityClick: () -> Unit,
  onSpeedClick: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = DialogBlue,
      modifier = Modifier.fillMaxWidth(0.92f),
    ) {
      Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
          text = "Settings",
          color = CookiesmoTextPrimary,
          fontWeight = FontWeight.Bold,
          fontSize = 16.sp,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = CookiesmoMuted)

        if (hasQualityOptions) {
          SettingsMenuRow(
            title = "Quality",
            subtitle = currentQuality,
            onClick = onQualityClick,
          )
          HorizontalDivider(color = CookiesmoMuted)
        }

        SettingsMenuRow(
          title = "Speed",
          subtitle = if (currentSpeed == 1.0f) "Normal" else "${currentSpeed}x",
          onClick = onSpeedClick,
        )
      }
    }
  }
}

@Composable
private fun SettingsMenuRow(
  title: String,
  subtitle: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = CookiesmoTextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        color = MetaBlue,
        fontSize = 13.sp,
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
      contentDescription = null,
      tint = CookiesmoTextMuted,
    )
  }
}

@Composable
private fun SpeedPickerDialog(
  current: Float,
  onDismiss: () -> Unit,
  onSelect: (Float) -> Unit,
) {
  val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DialogBlue,
    title = {
      Text("Speed", color = CookiesmoTextPrimary, fontWeight = FontWeight.Bold)
    },
    text = {
      Column {
        speeds.forEach { speed ->
          val label = if (speed == 1.0f) "Normal" else "${speed}x"
          val selected = speed == current
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .selectable(
                selected = selected,
                onClick = { onSelect(speed) },
                role = Role.RadioButton,
              )
              .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = label,
              color = CookiesmoTextPrimary,
              fontSize = 16.sp,
              modifier = Modifier.weight(1f),
            )
            RadioButton(
              selected = selected,
              onClick = { onSelect(speed) },
              colors = RadioButtonDefaults.colors(
                selectedColor = CookiesmoAccent,
                unselectedColor = CookiesmoTextMuted,
              ),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("OK", color = CookiesmoAccent, fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("CANCEL", color = CookiesmoTextPrimary)
      }
    },
  )
}

@Composable
private fun QualityPickerDialog(
  title: String,
  options: List<StreamOption>,
  selected: StreamOption?,
  confirmLabel: String,
  playingResolution: String?,
  showSizes: Boolean = false,
  onDismiss: () -> Unit,
  onConfirm: (StreamOption) -> Unit,
) {
  var picked by remember(options, selected) {
    mutableStateOf(selected ?: options.firstOrNull())
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DialogBlue,
    titleContentColor = CookiesmoTextPrimary,
    textContentColor = CookiesmoTextPrimary,
    title = {
      Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        color = CookiesmoTextPrimary,
      )
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        if (showSizes) {
          Text(
            text = "Choose resolution · size shown when available",
            color = MetaBlue,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        options.forEach { option ->
          val checked = option.url == picked?.url
          val isPlaying = option.url == selected?.url
          val label = normalizeQualityLabel(option.label, option.url)
          val sizeText = option.sizeBytes?.takeIf { it > 0L }?.let {
            StreamOption.formatBytes(it)
          }
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .selectable(
                selected = checked,
                onClick = { picked = option },
                role = Role.RadioButton,
              )
              .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                // Always pair resolution with size in download mode when known.
                text = when {
                  showSizes && sizeText != null -> "$label · $sizeText"
                  showSizes -> label
                  else -> label
                },
                color = CookiesmoTextPrimary,
                fontSize = 16.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
              )
              when {
                isPlaying -> Text(
                  text = playingResolution?.let { "Playing · $it" } ?: "Playing",
                  color = MetaBlue,
                  fontSize = 12.sp,
                  modifier = Modifier.padding(top = 2.dp),
                )
                showSizes && sizeText == null -> Text(
                  text = "Fetching size…",
                  color = MetaBlue.copy(alpha = 0.8f),
                  fontSize = 12.sp,
                  modifier = Modifier.padding(top = 2.dp),
                )
              }
            }
            RadioButton(
              selected = checked,
              onClick = { picked = option },
              colors = RadioButtonDefaults.colors(
                selectedColor = CookiesmoAccent,
                unselectedColor = CookiesmoTextMuted,
              ),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { picked?.let(onConfirm) },
        enabled = picked != null,
      ) {
        Text(confirmLabel, color = CookiesmoAccent, fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("CANCEL", color = CookiesmoTextPrimary)
      }
    },
  )
}

@Composable
private fun RelatedVideoCard(
  video: VideoItem,
  showThumbnail: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1.45f)
        .clip(RoundedCornerShape(12.dp))
        .background(CookiesmoSurface),
    ) {
      if (showThumbnail && video.thumbnailUrl.isNotBlank()) {
        AsyncImage(
          model = ImageRequest.Builder(context)
            .data(video.thumbnailUrl)
            .crossfade(true)
            .build(),
          contentDescription = video.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = video.title,
      color = CookiesmoTextPrimary,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = listOfNotNull(
        video.duration.takeIf { it.isNotBlank() && it != "—" },
        video.resolution.takeIf { it.isNotBlank() && it != "—" },
        video.views.takeIf { it.isNotBlank() && it != "—" },
      ).joinToString(" • "),
      color = MetaBlue,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
