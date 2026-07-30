package com.example.funfy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCatalogTest {
    @Test
    fun eachRequestedRegion_hasExpectedDirectSites() {
        val expected = mapOf(
            SourceRegion.JAV to listOf(
                "jable", "javmost", "javfree", "javtsunami", "123av", "javseen", "xasiat",
            ),
            SourceRegion.PHILIPPINES to listOf(
                "pinayot", "pinayflix", "pornkai", "pinaypornsite",
                "kaldagan", "pinayum", "lootedpinay", "pwerta",
            ),
            SourceRegion.INDONESIA to listOf(
                "indo18", "bokepbox", "bokepindohot", "bebasindo", "nontonbokep",
            ),
            SourceRegion.MYANMAR to listOf(
                "buumal", "mmhdhub", "babextube",
            ),
            SourceRegion.THAILAND to listOf(
                "thaiporntv", "okxxx", "ixxx",
            ),
            SourceRegion.VIETNAM to listOf(
                "vlxx", "sexhay24h",
            ),
            SourceRegion.HENTAI to listOf(
                "hanime", "hentaimama", "hentai4k", "rule34video", "hentaigasm", "hentaicity",
            ),
        )
        for ((region, ids) in expected) {
            val sites = VideoSource.regionalCatalogByRegion.getValue(region)
                .filter { it.isSelectable }
            assertTrue(
                "${region.label} missing sites. have=${sites.map { it.id }} need=$ids",
                sites.map { it.id }.containsAll(ids),
            )
            assertTrue(sites.none { it.provider != SourceProvider.LEGACY })
            assertTrue(sites.all { it.keyword == null })
            assertTrue(sites.all { it.baseUrl.startsWith("http") })
        }
    }

    @Test
    fun noRegionalKeywordFeedsRemain() {
        val banned = listOf(
            "jav_xvideos", "jav_eporner",
            "ph_xvideos", "ph_eporner",
            "indonesia", "indo_eporner",
            "myanmar", "myanmar_eporner",
            "vietnam", "viet_eporner",
            "thai", "thai_eporner",
            "javguru", "javff", "hentaihaven", "pinayviral", "shennana", "quatvn",
        )
        val ids = VideoSource.selectable.map { it.id }
        for (id in banned) {
            assertFalse("Removed source still selectable: $id", id in ids)
        }
    }

    @Test
    fun everySelectableSource_hasClientFactory() {
        VideoSource.selectable.forEach { source ->
            val client = SourceRegistry.client(source)
            assertEquals(source, client.source)
        }
    }

    @Test
    fun fromId_migratesRemovedSources() {
        assertEquals(VideoSource.INDO18, VideoSource.fromId("indo18"))
        assertEquals(VideoSource.PORNKAI, VideoSource.fromId("pinayflixhd"))
        assertEquals(VideoSource.THAIPORNTV, VideoSource.fromId("thaipornxxx"))
        assertEquals(VideoSource.VLXX, VideoSource.fromId("vietnam"))
        assertEquals(VideoSource.VLXX, VideoSource.fromId("quatvn"))
        assertEquals(VideoSource.INDO18, VideoSource.fromId("indonesia"))
        assertEquals(VideoSource.BUUMAL, VideoSource.fromId("myanmar"))
        assertEquals(VideoSource.JABLE, VideoSource.fromId("jav_xvideos"))
        assertEquals(VideoSource.JABLE, VideoSource.fromId("javguru"))
        assertEquals(VideoSource.JABLE, VideoSource.fromId("missav"))
        assertEquals(VideoSource.JAVTSUNAMI, VideoSource.fromId("javff"))
        assertEquals(VideoSource.HENTAIMAMA, VideoSource.fromId("hentaihaven"))
        assertEquals(VideoSource.PINAYFLIX, VideoSource.fromId("pinayviral"))
        assertEquals(VideoSource.BOKEPBOX, VideoSource.fromId("bokepbox"))
        assertEquals(VideoSource.DEFAULT, VideoSource.fromId("xnxx"))
        assertEquals(VideoSource.VLXX, VideoSource.fromId("shennana"))
        assertEquals(VideoSource.OKXXX, VideoSource.fromId("okxxx"))
        assertEquals(VideoSource.XHAMSTER2, VideoSource.fromId("xhamster2"))
        assertEquals(VideoSource.XASIAT, VideoSource.fromId("xasiat"))
        assertEquals(VideoSource.KALDAGAN, VideoSource.fromId("kaldagan"))
    }

    @Test
    fun newGlobalSources_areSelectable() {
        val ids = VideoSource.selectable.map { it.id }
        for (id in listOf("xhamster2", "beeg", "txxx", "xxxfiles", "xasiat")) {
            assertTrue("Missing selectable source: $id", id in ids)
        }
    }

    @Test
    fun sharedProviderUrl_resolvesToGlobalClientSource() {
        assertEquals(
            VideoSource.XVIDEOS,
            VideoSource.fromUrl("https://www.xvideos.com/video.fixture/example"),
        )
        assertEquals(
            VideoSource.EPORNER,
            VideoSource.fromUrl("https://www.eporner.com/video-fixture/example/"),
        )
    }

    @Test
    fun absoluteUrl_resolvesRootAndRelativeLinks() {
        assertEquals(
            "https://example.test/video/1",
            NetworkClient.absoluteUrl("https://example.test/path/page", "/video/1"),
        )
    }

    @Test
    fun cleanTube_extractsMp4FromBase64Player() {
        val q = java.util.Base64.getEncoder().encodeToString(
            ("post_id=1&type=video&tag=" +
                java.net.URLEncoder.encode(
                    """<video><source src="https://drkogyi.vip/wp-content/uploads/2026/07/26jul180.mp4" type="video/mp4"/></video>""",
                    Charsets.UTF_8.name(),
                )).toByteArray(),
        )
        val html =
            """<iframe src="https://mmporns.com/wp-content/plugins/clean-tube-player/public/player-x.php?q=$q"></iframe>"""
        val streams = extractCleanTubeStreams(html)
        assertTrue(streams.isNotEmpty())
        assertTrue(streams.any { it.url.contains("drkogyi.vip") && it.url.endsWith(".mp4") })
    }

    @Test
    fun xHamster_extractsJsonEscapedStreams() {
        val html = """
            {"hls":"https:\/\/video-nss.xhcdn.com\/media=hls4\/multi=426x240:240p,854x480:480p\/_TPL_.h264.mp4.m3u8",
             "480p":"https:\/\/video5.xhcdn.com\/key=test\/030\/284\/576\/480p.h264.mp4"}
        """.trimIndent()

        val streams = XHamster2Client().collectXhStreams(html)

        assertTrue(streams.any { it.label == "240p" && it.url.endsWith("240p.h264.mp4.m3u8") })
        assertTrue(streams.any { it.label == "480p" && it.url.endsWith("480p.h264.mp4") })
        assertTrue(streams.all { "\\/" !in it.url })
    }
}
