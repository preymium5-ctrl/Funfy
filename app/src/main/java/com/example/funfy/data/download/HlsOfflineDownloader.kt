package com.example.funfy.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal data class HlsDownloadResult(
    val playlistFile: File,
    val bytesWritten: Long,
)

internal data class TransferProgress(
    val bytesWritten: Long,
    val totalBytes: Long?,
    val fraction: Float,
)

/**
 * Saves a finite snapshot of an HLS stream as a self-contained local bundle.
 *
 * A selected variant (and its default audio rendition, when separate) is
 * downloaded. Every segment, encryption key and initialization map is
 * rewritten to a relative local URI, allowing Media3 to play the returned
 * playlist with networking completely unavailable.
 */
internal class HlsOfflineDownloader(
    private val client: OkHttpClient,
    private val userAgent: String,
) {
    suspend fun download(
        url: String,
        referer: String,
        outputDir: File,
        targetResolution: String,
        initialManifest: String? = null,
        initialManifestUrl: String = url,
        onProgress: (TransferProgress) -> Unit,
        onCallChanged: (Call?) -> Unit,
    ): HlsDownloadResult {
        currentCoroutineContext().ensureActive()
        check(outputDir.mkdirs() || outputDir.isDirectory) {
            "Cannot create offline HLS directory"
        }

        val initial = initialManifest?.let {
            ManifestDocument(it, initialManifestUrl, it.toByteArray().size.toLong())
        } ?: fetchManifest(url, referer, onCallChanged)
        require(initial.text.lineSequence().any { it.trim() == "#EXTM3U" }) {
            "The selected stream is not a valid HLS playlist"
        }

        val variants = parseVariants(initial)
        val tracker = ProgressTracker(
            initialBytes = initial.bytes,
            onProgress = onProgress,
        )

        val localPlaylist: File
        if (variants.isEmpty()) {
            val resourceCount = countResources(initial.text)
            require(resourceCount > 0) { "The HLS playlist contains no media" }
            tracker.resourceCount = resourceCount
            localPlaylist = File(outputDir, "index.m3u8")
            writeMediaPlaylist(
                document = initial,
                outputDir = File(outputDir, "media"),
                outputPlaylist = localPlaylist,
                referer = referer,
                tracker = tracker,
                onCallChanged = onCallChanged,
            )
        } else {
            val selected = selectVariant(variants, targetResolution)
            val videoManifest = fetchManifest(selected.url, referer, onCallChanged)
            require(parseVariants(videoManifest).isEmpty()) {
                "Nested HLS master playlists are not supported"
            }
            tracker.bytesWritten += videoManifest.bytes

            val audioRendition = selected.audioGroup?.let { group ->
                parseAudioRenditions(initial, group).let { choices ->
                    choices.firstOrNull { it.isDefault } ?: choices.firstOrNull()
                }
            }
            val audioManifest = audioRendition?.let {
                fetchManifest(it.url, referer, onCallChanged)
            }
            if (audioManifest != null) tracker.bytesWritten += audioManifest.bytes

            val videoResources = countResources(videoManifest.text)
            val audioResources = audioManifest?.let { countResources(it.text) } ?: 0
            require(videoResources + audioResources > 0) {
                "The selected HLS quality contains no media"
            }
            tracker.resourceCount = videoResources + audioResources

            val videoPlaylist = File(outputDir, "video/index.m3u8")
            writeMediaPlaylist(
                document = videoManifest,
                outputDir = File(outputDir, "video/media"),
                outputPlaylist = videoPlaylist,
                referer = referer,
                tracker = tracker,
                onCallChanged = onCallChanged,
            )

            val audioPlaylist = audioManifest?.let {
                File(outputDir, "audio/index.m3u8").also { playlist ->
                    writeMediaPlaylist(
                        document = it,
                        outputDir = File(outputDir, "audio/media"),
                        outputPlaylist = playlist,
                        referer = referer,
                        tracker = tracker,
                        onCallChanged = onCallChanged,
                    )
                }
            }

            localPlaylist = File(outputDir, "index.m3u8")
            writeLocalMaster(
                output = localPlaylist,
                source = initial,
                variant = selected,
                audio = audioRendition,
                hasLocalAudio = audioPlaylist != null,
            )
            tracker.bytesWritten += localPlaylist.length()
        }

        tracker.emit(1f)
        return HlsDownloadResult(
            playlistFile = localPlaylist,
            bytesWritten = directorySize(outputDir),
        )
    }

    private suspend fun writeMediaPlaylist(
        document: ManifestDocument,
        outputDir: File,
        outputPlaylist: File,
        referer: String,
        tracker: ProgressTracker,
        onCallChanged: (Call?) -> Unit,
    ) {
        check(outputDir.mkdirs() || outputDir.isDirectory) {
            "Cannot create HLS media directory"
        }
        check(outputPlaylist.parentFile?.mkdirs() != false || outputPlaylist.parentFile?.isDirectory == true)

        val output = mutableListOf<String>()
        val nextRangeOffset = mutableMapOf<String, Long>()
        var pendingRange: ByteRange? = null
        var resourceNumber = 0

        document.text.lineSequence().forEach { rawLine ->
            currentCoroutineContext().ensureActive()
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    pendingRange = parseByteRange(line.substringAfter(':'))
                    // Each remote range is saved as an independent local file.
                    // Retaining this directive would apply the remote offset to
                    // the new (already-sliced) file a second time.
                }

                line.startsWith("#") && hasUriAttribute(line) -> {
                    val remoteRef = uriAttribute(line) ?: run {
                        output += rawLine
                        return@forEach
                    }
                    if (hasUnsupportedScheme(remoteRef)) {
                        throw IllegalStateException(
                            "DRM-protected or non-HTTP HLS media cannot be saved offline",
                        )
                    }
                    if (!isDownloadableReference(remoteRef)) {
                        output += rawLine
                        return@forEach
                    }
                    val absolute = resolve(document.url, remoteRef)
                    val attrRange = byteRangeAttribute(line)
                    val resolvedRange = resolveRange(absolute, attrRange, nextRangeOffset)
                    val kind = when {
                        line.startsWith("#EXT-X-KEY", true) -> ResourceKind.KEY
                        line.startsWith("#EXT-X-MAP", true) -> ResourceKind.MAP
                        else -> ResourceKind.SEGMENT
                    }
                    val local = File(
                        outputDir,
                        resourceFileName(resourceNumber++, absolute, kind),
                    )
                    downloadResource(
                        url = absolute,
                        destination = local,
                        referer = referer,
                        range = resolvedRange,
                        tracker = tracker,
                        onCallChanged = onCallChanged,
                    )
                    var rewritten = replaceUriAttribute(line, relativePath(outputPlaylist, local))
                    if (attrRange != null) rewritten = removeByteRangeAttribute(rewritten)
                    output += rewritten
                }

                line.isNotBlank() && !line.startsWith("#") -> {
                    if (hasUnsupportedScheme(line)) {
                        throw IllegalStateException("Non-HTTP HLS media cannot be saved offline")
                    }
                    val absolute = resolve(document.url, line)
                    val resolvedRange = resolveRange(absolute, pendingRange, nextRangeOffset)
                    val local = File(
                        outputDir,
                        resourceFileName(resourceNumber++, absolute, ResourceKind.SEGMENT),
                    )
                    downloadResource(
                        url = absolute,
                        destination = local,
                        referer = referer,
                        range = resolvedRange,
                        tracker = tracker,
                        onCallChanged = onCallChanged,
                    )
                    output += relativePath(outputPlaylist, local)
                    pendingRange = null
                }

                else -> output += rawLine
            }
        }

        if (output.none { it.trim().equals("#EXT-X-ENDLIST", ignoreCase = true) }) {
            // Freeze live/event playlists at the downloaded snapshot. Otherwise
            // the local player would repeatedly reload the playlist forever.
            output += "#EXT-X-ENDLIST"
        }
        writeSyncedText(outputPlaylist, output.joinToString("\n", postfix = "\n"))
        tracker.bytesWritten += outputPlaylist.length()
    }

    private suspend fun downloadResource(
        url: String,
        destination: File,
        referer: String,
        range: ByteRange?,
        tracker: ProgressTracker,
        onCallChanged: (Call?) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val request = requestBuilder(url, referer).apply {
            if (range != null) {
                header("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
            }
        }.build()

        execute(request, onCallChanged) { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HLS media request failed (HTTP ${response.code})")
            }
            rejectTextError(response)
            val body = response.body ?: throw IllegalStateException("Empty HLS media response")
            val serverAppliedRange = range != null && response.code == 206
            val expected = range?.length ?: body.contentLength().takeIf { it > 0L }
            val input = body.byteStream()

            if (range != null && !serverAppliedRange) {
                var remaining = range.offset
                while (remaining > 0L) {
                    currentCoroutineContext().ensureActive()
                    val skipped = input.skip(remaining)
                    if (skipped > 0L) {
                        remaining -= skipped
                    } else if (input.read() != -1) {
                        remaining--
                    } else {
                        throw IllegalStateException("HLS byte range starts past end of media")
                    }
                }
            }

            FileOutputStream(destination).use { fileOut ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var resourceWritten = 0L
                while (expected == null || resourceWritten < expected) {
                    currentCoroutineContext().ensureActive()
                    val wanted = if (expected == null) {
                        buffer.size
                    } else {
                        minOf(buffer.size.toLong(), expected - resourceWritten).toInt()
                    }
                    val read = input.read(buffer, 0, wanted)
                    if (read == -1) break
                    fileOut.write(buffer, 0, read)
                    resourceWritten += read
                    tracker.onResourceBytes(resourceWritten, expected)
                }
                fileOut.flush()
                fileOut.fd.sync()
                if (expected != null && resourceWritten != expected) {
                    throw IllegalStateException(
                        "Incomplete HLS resource ($resourceWritten of $expected bytes)",
                    )
                }
                if (resourceWritten == 0L) {
                    throw IllegalStateException("Downloaded HLS resource is empty")
                }
                tracker.finishResource(resourceWritten)
            }
        }
    }

    private suspend fun fetchManifest(
        url: String,
        referer: String,
        onCallChanged: (Call?) -> Unit,
    ): ManifestDocument {
        val request = requestBuilder(url, referer).build()
        return execute(request, onCallChanged) { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HLS playlist request failed (HTTP ${response.code})")
            }
            val body = response.body ?: throw IllegalStateException("Empty HLS playlist response")
            require(body.contentLength() <= MAX_MANIFEST_BYTES || body.contentLength() < 0L) {
                "HLS playlist is unexpectedly large"
            }
            val manifestBytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    manifestBytes.write(buffer, 0, read)
                    require(manifestBytes.size() <= MAX_MANIFEST_BYTES) {
                        "HLS playlist is unexpectedly large"
                    }
                }
            }
            val bytes = manifestBytes.toByteArray()
            val text = bytes.toString(Charsets.UTF_8)
            require(text.lineSequence().any { it.trim() == "#EXTM3U" }) {
                "Invalid HLS playlist response"
            }
            ManifestDocument(text, response.request.url.toString(), bytes.size.toLong())
        }
    }

    private suspend fun <T> execute(
        request: Request,
        onCallChanged: (Call?) -> Unit,
        block: suspend (Response) -> T,
    ): T {
        val call = client.newCall(request)
        onCallChanged(call)
        val cancellation = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        var response: Response? = null
        return try {
            val opened = call.execute()
            response = opened
            block(opened)
        } finally {
            response?.close()
            cancellation?.dispose()
            onCallChanged(null)
        }
    }

    private fun requestBuilder(url: String, referer: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply {
                if (referer.isNotBlank()) header("Referer", referer)
            }

    private fun rejectTextError(response: Response) {
        val type = response.header("Content-Type").orEmpty().lowercase()
        if (type.contains("text/html") || type.contains("application/json")) {
            throw IllegalStateException("Media server returned an error page instead of video")
        }
    }

    private fun writeLocalMaster(
        output: File,
        source: ManifestDocument,
        variant: Variant,
        audio: AudioRendition?,
        hasLocalAudio: Boolean,
    ) {
        val lines = mutableListOf("#EXTM3U")
        source.text.lineSequence()
            .map(String::trim)
            .filter {
                it.startsWith("#EXT-X-VERSION", true) ||
                    it.equals("#EXT-X-INDEPENDENT-SEGMENTS", true)
            }
            .distinct()
            .forEach(lines::add)

        if (audio != null && hasLocalAudio) {
            lines += replaceUriAttribute(audio.sourceLine, "audio/index.m3u8")
        }
        var streamInfo = variant.sourceLine
        if (!hasLocalAudio) streamInfo = removeAttribute(streamInfo, "AUDIO")
        streamInfo = removeAttribute(streamInfo, "SUBTITLES")
        lines += streamInfo
        lines += "video/index.m3u8"
        writeSyncedText(output, lines.joinToString("\n", postfix = "\n"))
    }

    private fun parseVariants(document: ManifestDocument): List<Variant> {
        val lines = document.text.lines()
        val result = mutableListOf<Variant>()
        for (index in lines.indices) {
            val line = lines[index].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue
            val uri = lines.drop(index + 1)
                .firstOrNull { it.trim().isNotBlank() && !it.trim().startsWith("#") }
                ?.trim()
                ?: continue
            val attrs = parseAttributes(line.substringAfter(':'))
            val height = attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()
            val bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                ?: attrs["BANDWIDTH"]?.toLongOrNull()
                ?: 0L
            result += Variant(
                sourceLine = line,
                url = resolve(document.url, uri),
                height = height,
                bandwidth = bandwidth,
                audioGroup = attrs["AUDIO"],
            )
        }
        return result
    }

    private fun selectVariant(variants: List<Variant>, targetResolution: String): Variant {
        val target = Regex("(\\d{3,4})").find(targetResolution)?.groupValues?.get(1)?.toIntOrNull()
        if (target == null) {
            return variants.maxWithOrNull(compareBy<Variant> { it.height ?: 0 }.thenBy { it.bandwidth })!!
        }
        return variants.minWithOrNull(
            compareBy<Variant> { abs((it.height ?: target) - target) }
                .thenByDescending { it.height ?: 0 }
                .thenByDescending { it.bandwidth },
        )!!
    }

    private fun parseAudioRenditions(
        document: ManifestDocument,
        group: String,
    ): List<AudioRendition> = document.text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (!line.startsWith("#EXT-X-MEDIA:", true)) return@mapNotNull null
        val attrs = parseAttributes(line.substringAfter(':'))
        if (!attrs["TYPE"].equals("AUDIO", true) || attrs["GROUP-ID"] != group) {
            return@mapNotNull null
        }
        val uri = attrs["URI"] ?: return@mapNotNull null
        AudioRendition(
            sourceLine = line,
            url = resolve(document.url, uri),
            isDefault = attrs["DEFAULT"].equals("YES", true),
        )
    }.toList()

    private fun countResources(text: String): Int = text.lineSequence().count { raw ->
        val line = raw.trim()
        (line.isNotBlank() && !line.startsWith("#")) ||
            (line.startsWith("#") && hasUriAttribute(line) &&
                uriAttribute(line)?.let(::isDownloadableReference) == true)
    }

    private fun resolve(base: String, reference: String): String {
        if (reference.startsWith("http://", true) || reference.startsWith("https://", true)) {
            return reference
        }
        base.toHttpUrlOrNull()?.resolve(reference)?.let { return it.toString() }
        return URI(base).resolve(reference).toString()
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ATTRIBUTE_REGEX.findAll(value).forEach { match ->
            result[match.groupValues[1].uppercase()] =
                match.groupValues[2].removeSurrounding("\"")
        }
        return result
    }

    private fun hasUriAttribute(line: String): Boolean = URI_ATTRIBUTE_REGEX.containsMatchIn(line)

    private fun uriAttribute(line: String): String? =
        URI_ATTRIBUTE_REGEX.find(line)?.groupValues?.get(1)

    private fun replaceUriAttribute(line: String, uri: String): String =
        URI_ATTRIBUTE_REGEX.replace(line) { "URI=\"$uri\"" }

    private fun byteRangeAttribute(line: String): ByteRange? =
        BYTE_RANGE_ATTRIBUTE_REGEX.find(line)?.groupValues?.get(1)?.let(::parseByteRange)

    private fun removeByteRangeAttribute(line: String): String =
        BYTE_RANGE_ATTRIBUTE_REGEX.replace(line, "").replace(",,", ",").replace(":,", ":")

    private fun removeAttribute(line: String, name: String): String {
        val regex = Regex("(?i),?$name=(?:\"[^\"]*\"|[^,]*)")
        return regex.replace(line, "").replace(",,", ",").replace(":,", ":")
    }

    private fun parseByteRange(value: String): ByteRange? {
        val clean = value.trim().removeSurrounding("\"")
        val length = clean.substringBefore('@').toLongOrNull()?.takeIf { it > 0L } ?: return null
        val offset = clean.substringAfter('@', "").toLongOrNull()
        return ByteRange(length, offset ?: -1L)
    }

    private fun resolveRange(
        url: String,
        range: ByteRange?,
        nextOffsets: MutableMap<String, Long>,
    ): ByteRange? {
        range ?: return null
        val offset = range.offset.takeIf { it >= 0L } ?: nextOffsets[url] ?: 0L
        nextOffsets[url] = offset + range.length
        return range.copy(offset = offset)
    }

    private fun isDownloadableReference(reference: String): Boolean =
        !reference.startsWith("data:", true)

    private fun hasUnsupportedScheme(reference: String): Boolean {
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(reference)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            ?: return false
        return scheme !in setOf("http", "https", "data")
    }

    private fun relativePath(fromPlaylist: File, target: File): String {
        val parent = requireNotNull(fromPlaylist.parentFile) { "Local playlist has no parent" }
        val parentUri = parent.canonicalFile.toURI()
        val targetUri = target.canonicalFile.toURI()
        val relativeUri = parentUri.relativize(targetUri)
        require(!relativeUri.isAbsolute && relativeUri != targetUri) {
            "Offline resource was written outside its playlist directory"
        }
        return relativeUri.path.also {
            require(it.isNotBlank()) { "Offline resource path is empty" }
        }
    }

    private fun resourceFileName(number: Int, url: String, kind: ResourceKind): String {
        val path = url.substringBefore('?').substringBefore('#')
        val rawExtension = path.substringAfterLast('.', "")
        val extension = rawExtension
            .takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?: when (kind) {
                ResourceKind.KEY -> "key"
                ResourceKind.MAP -> "mp4"
                ResourceKind.SEGMENT -> "bin"
            }
        return "resource_${number.toString().padStart(5, '0')}.$extension"
    }

    private fun writeSyncedText(file: File, text: String) {
        check(file.parentFile?.mkdirs() != false || file.parentFile?.isDirectory == true)
        FileOutputStream(file).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun directorySize(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf(::directorySize) ?: 0L
        else -> 0L
    }

    private data class ManifestDocument(
        val text: String,
        val url: String,
        val bytes: Long,
    )

    private data class Variant(
        val sourceLine: String,
        val url: String,
        val height: Int?,
        val bandwidth: Long,
        val audioGroup: String?,
    )

    private data class AudioRendition(
        val sourceLine: String,
        val url: String,
        val isDefault: Boolean,
    )

    private data class ByteRange(
        val length: Long,
        val offset: Long,
    )

    private enum class ResourceKind { SEGMENT, KEY, MAP }

    private class ProgressTracker(
        initialBytes: Long,
        private val onProgress: (TransferProgress) -> Unit,
    ) {
        var bytesWritten: Long = initialBytes
        var resourceCount: Int = 1
        private var completedResources: Int = 0

        fun onResourceBytes(currentBytes: Long, expectedBytes: Long?) {
            val withinResource = if (expectedBytes != null && expectedBytes > 0L) {
                currentBytes.toFloat() / expectedBytes.toFloat()
            } else {
                0f
            }
            emit(
                fraction = (completedResources + withinResource) / resourceCount.toFloat(),
                inFlightBytes = currentBytes,
            )
        }

        fun finishResource(resourceBytes: Long) {
            bytesWritten += resourceBytes
            completedResources++
            emit(completedResources.toFloat() / resourceCount.toFloat())
        }

        fun emit(fraction: Float, inFlightBytes: Long = 0L) {
            onProgress(
                TransferProgress(
                    bytesWritten = bytesWritten + inFlightBytes,
                    totalBytes = null,
                    fraction = fraction.coerceIn(0f, 1f),
                ),
            )
        }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        val ATTRIBUTE_REGEX = Regex("""([A-Za-z0-9-]+)=(\"[^\"]*\"|[^,]*)""")
        val URI_ATTRIBUTE_REGEX = Regex("""(?i)URI=\"([^\"]+)\"""")
        val BYTE_RANGE_ATTRIBUTE_REGEX =
            Regex("""(?i),?BYTERANGE=\"([^\"]+)\"""")
    }
}
