package com.example.funfy

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable
data class Player(
  val videoId: String,
  val title: String,
  val pageUrl: String,
  val thumbnailUrl: String = "",
  val duration: String = "",
  val resolution: String = "",
  val views: String = "",
  val uploader: String = "",
  /** When true, [pageUrl] is a local file path for offline playback. */
  val isLocal: Boolean = false,
  /** Resume position when returning from a related video (ms). */
  val resumePositionMs: Long = 0L,
) : NavKey
