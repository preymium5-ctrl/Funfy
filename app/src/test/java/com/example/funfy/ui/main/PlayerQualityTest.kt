package com.example.funfy.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerQualityTest {
  @Test
  fun renderedResolutionUsesShortEdgeForLandscapeAndPortraitVideo() {
    assertEquals("1080p", renderedResolutionLabel(width = 1920, height = 1080))
    assertEquals("1080p", renderedResolutionLabel(width = 1080, height = 1920))
  }

  @Test
  fun renderedDimensionsReportsExactDecoderSize() {
    assertEquals("1280 × 720", renderedDimensionsLabel(width = 1280, height = 720))
  }

  @Test
  fun invalidDecoderSizeIsNotPresentedAsARealResolution() {
    assertNull(renderedResolutionLabel(width = 0, height = 720))
    assertNull(renderedDimensionsLabel(width = 1920, height = -1))
  }
}
