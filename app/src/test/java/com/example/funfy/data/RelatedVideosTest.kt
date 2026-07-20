package com.example.funfy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedVideosTest {
    @Test
    fun prefersJavCodeFromTitle() {
        val q = relatedQueryFromTitle(
            "SSIS-850 Beautiful Married Woman And Reverse NTR - MissAV",
        )
        assertTrue(q.uppercase().contains("SSIS"))
        assertTrue(q.contains("850") || q.uppercase().contains("SSIS-850"))
    }

    @Test
    fun usesTagsWhenPresent() {
        val q = relatedQueryFromTitle(
            "Some Long Unrelated Title About Nothing",
            tags = listOf("creampie", "asian"),
        )
        assertEquals("creampie asian", q)
    }

    @Test
    fun stripsStopWordsFromProse() {
        val q = relatedQueryFromTitle("The beautiful girl with her friend after school")
        assertTrue(q.contains("beautiful") || q.contains("girl") || q.contains("friend"))
        assertTrue(!q.lowercase().split(' ').contains("the"))
        assertTrue(!q.lowercase().split(' ').contains("with"))
    }
}
