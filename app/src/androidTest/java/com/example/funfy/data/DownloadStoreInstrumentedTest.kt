package com.example.funfy.data

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var serverIsRunning = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetDownloadStorage()
        server = MockWebServer()
        server.start()
        serverIsRunning = true
    }

    @After
    fun tearDown() {
        stopServer()
        resetDownloadStorage()
    }

    @Test
    fun progressiveDownloadReportsProgressPersistsAndRemovesVerifiedFile() = runBlocking {
        val payload = ByteArray(4 * 1024) { index -> (index % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(payload)),
        )
        val observedProgress = CopyOnWriteArrayList<Float>()
        val store = DownloadStore(context)
        val id = "progressive-test"

        val saved = store.download(
            title = "Progressive fixture",
            streamUrl = server.url("/media/video.mp4").toString(),
            resolution = "360p",
            duration = "4s",
            thumbnailUrl = "",
            referer = "",
            downloadId = id,
            onProgress = { observedProgress.add(it) },
        )

        assertTrue(
            "A live progress value between zero and completion should be emitted",
            observedProgress.any { it > 0f && it < 1f },
        )
        assertEquals(1f, observedProgress.last(), 0f)
        assertArrayEquals(payload, File(saved.filePath).readBytes())
        assertEquals(payload.size.toLong(), saved.sizeBytes)

        val completed = requireNotNull(store.getTransfer(id))
        assertEquals(DownloadStatus.COMPLETED, completed.status)
        assertEquals(payload.size.toLong(), completed.bytesDownloaded)
        assertEquals(payload.size.toLong(), completed.totalBytes)
        assertEquals(1f, completed.progress, 0f)
        assertEquals(saved, completed.localDownload)

        val reloadedStore = DownloadStore(context)
        val reloaded = requireNotNull(reloadedStore.getById(id))
        assertEquals(saved.filePath, reloaded.filePath)
        assertEquals(saved.storagePath, reloaded.storagePath)
        assertArrayEquals(payload, File(reloaded.filePath).readBytes())

        val playableFile = File(reloaded.filePath)
        val storageRoot = File(reloaded.storagePath)
        reloadedStore.remove(id)

        assertFalse(playableFile.exists())
        assertFalse(storageRoot.exists())
        assertNull(DownloadStore(context).getById(id))
        assertEquals("/media/video.mp4", server.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun cancellationExposesCancelledAndDeletesPartialFile() {
        val payload = ByteArray(16 * 1024) { index -> (index * 17 % 253).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(payload))
                .throttleBody(256, 50, TimeUnit.MILLISECONDS),
        )
        val store = DownloadStore(context)
        val id = store.enqueue(
            title = "Cancellation fixture",
            streamUrl = server.url("/media/slow.mp4").toString(),
            resolution = "360p",
            duration = "16s",
            thumbnailUrl = "",
            referer = "",
        )
        val videos = File(context.filesDir, "videos")

        awaitCondition("The slow response should write observable partial data") {
            (store.getTransfer(id)?.bytesDownloaded ?: 0L) > 0L &&
                videos.listFiles().orEmpty().any { it.name.endsWith(".part") }
        }
        assertTrue(store.cancel(id))
        assertEquals(DownloadStatus.CANCELLED, store.getTransfer(id)?.status)

        awaitCondition("Cancellation should remove its partial media") {
            videos.listFiles().orEmpty().none { it.name.contains(id) }
        }
        assertEquals(DownloadStatus.CANCELLED, store.getTransfer(id)?.status)
        assertTrue(store.getAll().none { it.id == id })
        assertNull(DownloadStore(context).getById(id))
        assertEquals("/media/slow.mp4", server.takeRequest(1, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun hlsMasterIsRewrittenToSelfContainedBundleAndReloadsWithoutServer() = runBlocking {
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
        val firstSegment = ByteArray(188) { index -> if (index == 0) 0x47 else index.toByte() }
        val secondSegment = ByteArray(188) { index -> if (index == 0) 0x47 else (255 - index).toByte() }
        val requestedPaths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestedPaths.add(request.path.orEmpty())
                return when (request.requestUrl?.encodedPath) {
                    "/hls/master.m3u8" -> playlistResponse(master)
                    "/hls/video/playlist.m3u8" -> playlistResponse(media)
                    "/hls/video/segment-0.ts" -> mediaResponse(firstSegment)
                    "/hls/shared/segment-1.ts" -> mediaResponse(secondSegment)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val store = DownloadStore(context)
        val id = "hls-test"

        val saved = store.download(
            title = "HLS fixture",
            streamUrl = server.url("/hls/master.m3u8").toString(),
            resolution = "360p",
            duration = "4s",
            thumbnailUrl = "",
            referer = "",
            downloadId = id,
        )

        assertEquals(
            listOf(
                "/hls/master.m3u8",
                "/hls/video/playlist.m3u8",
                "/hls/video/segment-0.ts",
                "/hls/shared/segment-1.ts?token=fixed",
            ),
            requestedPaths.toList(),
        )
        val storageRoot = File(saved.storagePath).canonicalFile
        val localMaster = File(saved.filePath).canonicalFile
        assertTrue(storageRoot.isDirectory)
        assertTrue(localMaster.isFile)
        assertOwnedBy(storageRoot, localMaster)

        val masterText = localMaster.readText()
        assertFalse(masterText.contains("http://", ignoreCase = true))
        assertFalse(masterText.contains("https://", ignoreCase = true))
        val masterReferences = playlistReferences(masterText)
        assertEquals(listOf("video/index.m3u8"), masterReferences)

        val localMedia = resolveLocalReference(storageRoot, localMaster, masterReferences.single())
        val mediaText = localMedia.readText()
        assertFalse(mediaText.contains("http://", ignoreCase = true))
        assertFalse(mediaText.contains("https://", ignoreCase = true))
        val segmentReferences = playlistReferences(mediaText)
        assertEquals(2, segmentReferences.size)
        val localSegments = segmentReferences.map {
            resolveLocalReference(storageRoot, localMedia, it)
        }
        assertArrayEquals(firstSegment, localSegments[0].readBytes())
        assertArrayEquals(secondSegment, localSegments[1].readBytes())
        assertEquals(4, storageRoot.walkTopDown().count { it.isFile })
        assertEquals(storageRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }, saved.sizeBytes)

        stopServer()
        val reloaded = requireNotNull(DownloadStore(context).getById(id))
        assertEquals(saved.filePath, reloaded.filePath)
        assertEquals(saved.storagePath, reloaded.storagePath)
        val reloadedRoot = File(reloaded.storagePath).canonicalFile
        val reloadedMaster = File(reloaded.filePath).canonicalFile
        assertEquals(masterText, reloadedMaster.readText())
        val reloadedMedia = resolveLocalReference(
            reloadedRoot,
            reloadedMaster,
            playlistReferences(reloadedMaster.readText()).single(),
        )
        val reloadedSegments = playlistReferences(reloadedMedia.readText()).map {
            resolveLocalReference(reloadedRoot, reloadedMedia, it)
        }
        assertArrayEquals(firstSegment, reloadedSegments[0].readBytes())
        assertArrayEquals(secondSegment, reloadedSegments[1].readBytes())
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

    private fun resolveLocalReference(root: File, playlist: File, reference: String): File {
        assertFalse("Playlist reference must not be a network URL: $reference", reference.contains("://"))
        val resolved = File(playlist.parentFile, reference).canonicalFile
        assertOwnedBy(root, resolved)
        assertTrue("Missing local playlist resource: $resolved", resolved.isFile)
        return resolved
    }

    private fun assertOwnedBy(root: File, file: File) {
        assertTrue(
            "$file must remain inside $root",
            file != root && file.path.startsWith(root.path + File.separator),
        )
    }

    private fun awaitCondition(
        description: String,
        timeoutMillis: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        assertTrue(description, condition())
    }

    private fun stopServer() {
        if (!serverIsRunning) return
        server.shutdown()
        serverIsRunning = false
    }

    private fun resetDownloadStorage() {
        context.getSharedPreferences(DOWNLOAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        listOf("videos", "thumbs").forEach { child ->
            File(context.filesDir, child).deleteRecursively()
        }
    }

    private companion object {
        const val DOWNLOAD_PREFS = "funfy_downloads"
        val URI_ATTRIBUTE = Regex("""(?i)URI=\"([^\"]+)\"""")
    }
}
