package com.example.funfy.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.funfy.data.VideoSource
import org.junit.Rule
import org.junit.Test

/** Focused UI checks for the selected-source search control. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun searchShowsSelectedSourceAndOpensPicker() {
    composeTestRule.setContent {
      MaterialTheme {
        SearchScreenContent(
          remoteResults = null,
          searchQuery = "",
          onSearchQueryChange = {},
          searchLoading = false,
          searchError = null,
          currentSource = VideoSource.XVIDEOS,
          resultSource = null,
          searchHistory = emptyList(),
          onSearch = {},
          onClearSearch = {},
          onSourceSelected = {},
          onVideoClick = {},
        )
      }
    }

    composeTestRule.onNodeWithText("Searching only XVideos (XVideos)").assertExists()
    composeTestRule.onNodeWithContentDescription("Choose search source").performClick()
    composeTestRule.onNodeWithText("Search source").assertExists()
    composeTestRule.onNodeWithText("PHILIPPINES").assertExists()
  }
}
