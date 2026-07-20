package com.example.funfy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KvsDecoderTest {
    @Test
    fun sexvidStyleHashIsUnscrambled() {
        val license = "\$451596714898965"
        val scrambled =
            "function/0/https://www.sexvid.xxx/get_file/9/730cbf48b7ef388d365759b0d9dc75f42df71adfdb/3000/3517/3517_720p.mp4/?br=2259"
        val real = KvsDecoder.getRealUrl(scrambled, license)
        assertFalse(real.startsWith("function/"))
        assertTrue(real.contains("/get_file/9/"))
        assertTrue(real.contains("3517_720p.mp4"))
        // First 32 chars of hash path segment must differ from scrambled
        val scrambledHash = "730cbf48b7ef388d365759b0d9dc75f4"
        assertFalse(real.contains("/$scrambledHash"))
        // Known result from yt-dlp algorithm against this license+hash
        assertTrue(real.contains("7788c0fb5055e73dc64379fbdd384f9b"))
    }

    @Test
    fun plainGetFileWithoutFunctionPrefixIsUnchanged() {
        val url = "https://www.analdin.com/get_file/18/6f45e42ac5c5cccb338552adc9dacb19/811000/811636/811636.mp4/"
        val real = KvsDecoder.getRealUrl(url, "\$463045615276685")
        assertEquals(url, real)
    }

    @Test
    fun sanitizeEncodesSpacesInQuery() {
        val raw =
            "https://vid.example.com/v.mp4?dload=EPORNER.COM - [id] Title (240).mp4"
        val fixed = sanitizeStreamUrl(raw)
        assertFalse(fixed.contains(' '))
        assertTrue(fixed.contains("%20"))
        assertTrue(fixed.startsWith("https://vid.example.com/v.mp4?"))
    }
}
