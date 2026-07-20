package com.example.funfy

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.funfy.ui.main.MainScreen
import com.example.funfy.ui.player.PlayerScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            onItemClick = { navKey -> backStack.add(navKey) },
            modifier = Modifier.fillMaxSize(),
          )
        }
        entry<Player> { key ->
          PlayerScreen(
            title = key.title,
            pageUrl = key.pageUrl,
            duration = key.duration,
            resolution = key.resolution,
            views = key.views,
            uploader = key.uploader,
            thumbnailUrl = key.thumbnailUrl,
            isLocal = key.isLocal,
            // Pop only this player entry so related chain can go back entry-by-entry.
            onBack = { backStack.removeLastOrNull() },
            onRelatedClick = { video ->
              if (video.pageUrl.isNotBlank()) {
                backStack.add(
                  Player(
                    videoId = video.id,
                    title = video.title,
                    pageUrl = video.pageUrl,
                    thumbnailUrl = video.thumbnailUrl,
                    duration = video.duration,
                    resolution = video.resolution,
                    views = video.views,
                    uploader = video.category,
                  ),
                )
              }
            },
            modifier = Modifier.fillMaxSize(),
          )
        }
      },
  )
}
