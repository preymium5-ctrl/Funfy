package com.example.funfy.data.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class HlsOfflineDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun masterMediaAndSegmentsRemainUsableAfterServerStops() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=64000,RESOLUTION=640x360
            video/playlist.m3u8
        """.trimIndent() + "\n"
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            segment-0.ts
            #EXTINF:2.0,
            ../shared/segment-1.ts?token=fixed
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"
        val firstSegment = ByteArray(188) { index ->
            if (index == 0) 0x47 else index.toByte()
        }
        val secondSegment = ByteArray(188) { index ->
            if (index == 0) 0x47 else (255 - index).toByte()
        }
        val requests = CopyOnWriteArrayList<String>()
        val progress = CopyOnWriteArrayList<TransferProgress>()
        val server = MockWebServer()
        var serverRunning = true
        server.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request.path.orEmpty())
                    return when (request.requestUrl?.encodedPath) {
                        "/hls/master.m3u8" -> playlistResponse(master)
                        "/hls/video/playlist.m3u8" -> playlistResponse(media)
                        "/hls/video/segment-0.ts" -> mediaResponse(firstSegment)
                        "/hls/shared/segment-1.ts" -> mediaResponse(secondSegment)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val output = temporaryFolder.newFolder("offline-hls")

            val result = HlsOfflineDownloader(OkHttpClient(), "Funfy-HLS-test").download(
                url = server.url("/hls/master.m3u8").toString(),
                referer = "",
                outputDir = output,
                targetResolution = "360p",
                onProgress = { progress.add(it) },
                onCallChanged = {},
            )

            assertEquals(
                listOf(
                    "/hls/master.m3u8",
                    "/hls/video/playlist.m3u8",
                    "/hls/video/segment-0.ts",
                    "/hls/shared/segment-1.ts?token=fixed",
                ),
                requests.toList(),
            )
            assertTrue(progress.isNotEmpty())
            assertEquals(1f, progress.last().fraction, 0f)
            assertEquals(directorySize(output), result.bytesWritten)

            server.shutdown()
            serverRunning = false

            val root = output.canonicalFile
            val localMaster = result.playlistFile.canonicalFile
            assertOwnedBy(root, localMaster)
            assertTrue(localMaster.isFile)
            val masterText = localMaster.readText()
            assertNoNetworkReferences(masterText)
            val masterReferences = playlistReferences(masterText)
            assertEquals(listOf("video/index.m3u8"), masterReferences)

            val localMedia = resolveLocalReference(root, localMaster, masterReferences.single())
            val mediaText = localMedia.readText()
            assertNoNetworkReferences(mediaText)
            assertTrue(mediaText.contains("#EXT-X-ENDLIST"))
            val segmentReferences = playlistReferences(mediaText)
            assertEquals(2, segmentReferences.size)
            val localSegments = segmentReferences.map { reference ->
                resolveLocalReference(root, localMedia, reference)
            }
            assertArrayEquals(firstSegment, localSegments[0].readBytes())
            assertArrayEquals(secondSegment, localSegments[1].readBytes())
            assertEquals(4, root.walkTopDown().count { it.isFile })
        } finally {
            if (serverRunning) server.shutdown()
        }
    }

    private fun playlistResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
        .setBody(body)

    private fun mediaResponse(body: ByteArray): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "video/mp2t")
        .setBody(Buffer().write(body))

    private fun playlistReferences(text: String): List<String> = buildList {
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) add(line)
            URI_ATTRIBUTE.findAll(line).forEach { add(it.groupValues[1]) }
        }
    }

    private fun assertNoNetworkReferences(text: String) {
        assertFalse(text.contains("http://", ignoreCase = true))
        assertFalse(text.contains("https://", ignoreCase = true))
    }

    private fun resolveLocalReference(root: File, playlist: File, reference: String): File {
        assertFalse("Playlist reference must be local: $reference", reference.contains("://"))
        val resolved = File(playlist.parentFile, reference).canonicalFile
        assertOwnedBy(root, resolved)
        assertTrue("Missing local HLS resource: $resolved", resolved.isFile)
        return resolved
    }

    private fun assertOwnedBy(root: File, file: File) {
        assertTrue(
            "$file must remain inside $root",
            file != root && file.path.startsWith(root.path + File.separator),
        )
    }

    private fun directorySize(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles().orEmpty().sumOf(::directorySize)
        else -> 0L
    }

    private companion object {
        val URI_ATTRIBUTE = Regex("""(?i)URI=\"([^\"]+)\"""")
    }
}
