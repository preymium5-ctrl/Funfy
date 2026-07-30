package com.example.funfy.data

import android.content.Context
import com.example.funfy.data.download.HlsOfflineDownloader
import com.example.funfy.data.download.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Owns persistent offline media and app-lifetime download jobs.
 *
 * Completed items are exposed through [downloads]. In-flight and terminal job
 * snapshots are exposed independently through [transfers], so a partial file
 * can never be mistaken for a playable offline item.
 */
class DownloadStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val videosDir: File
        get() {
            val publicMovies = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                "Funfy",
            )
            if (publicMovies.exists() || publicMovies.mkdirs()) {
                return publicMovies
            }
            val extFiles = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            if (extFiles != null && (extFiles.exists() || extFiles.mkdirs())) {
                return extFiles
            }
            return File(appContext.filesDir, "videos").also { it.mkdirs() }
        }

    private val thumbsDir: File
        get() {
            val publicPictures = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                "Funfy",
            )
            if (publicPictures.exists() || publicPictures.mkdirs()) {
                return publicPictures
            }
            val extFiles = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            if (extFiles != null && (extFiles.exists() || extFiles.mkdirs())) {
                return extFiles
            }
            return File(appContext.filesDir, "thumbs").also { it.mkdirs() }
        }

    private val mutationLock = Any()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val requests = ConcurrentHashMap<String, DownloadRequest>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val workingPaths = ConcurrentHashMap<String, File>()
    private val lastProgressEmitAt = ConcurrentHashMap<String, Long>()

    private val _downloads = MutableStateFlow(loadAll())
    val downloads: StateFlow<List<LocalDownload>> = _downloads.asStateFlow()

    private val _transfers = MutableStateFlow<List<DownloadTransfer>>(emptyList())
    val transfers: StateFlow<List<DownloadTransfer>> = _transfers.asStateFlow()

    private val _folders = MutableStateFlow(loadFolders())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()

    init {
        // A process can be killed while a file is being written. Those files
        // were never registered and are intentionally never resumed as if they
        // were complete.
        videosDir.listFiles()
            ?.filter { it.name.endsWith(PART_SUFFIX) }
            ?.forEach { safeDeleteOwned(it, videosDir) }
        thumbsDir.listFiles()
            ?.filter { it.name.endsWith(PART_SUFFIX) }
            ?.forEach { safeDeleteOwned(it, thumbsDir) }
        restoreFromDurableIfNeeded()
        // Auto-relink local files if app was uninstalled/reinstalled:
        val relinked = _downloads.value.map { d ->
            val currentFile = File(d.filePath)
            if (currentFile.isFile && currentFile.length() > 0L) {
                d
            } else {
                val baseName = currentFile.name
                val candidatePublic = File(videosDir, baseName)
                if (candidatePublic.isFile && candidatePublic.length() > 0L) {
                    d.copy(filePath = candidatePublic.absolutePath)
                } else {
                    d
                }
            }
        }
        // Keep title/list metadata after reinstall so the download history survives.
        val kept = relinked.filter { d ->
            val fileOk = runCatching { File(d.filePath).isFile && File(d.filePath).length() > 0L }.getOrDefault(false)
            fileOk || d.title.isNotBlank()
        }
        if (kept.size != _downloads.value.size) {
            saveAll(kept)
            _downloads.value = kept
        } else {
            // Refresh durable snapshot for current data.
            persistDurable()
        }
    }

    fun createFolder(name: String): MediaFolder? {
        val clean = name.trim().take(48)
        if (clean.isBlank()) return null
        if (_folders.value.any { it.name.equals(clean, ignoreCase = true) }) {
            return _folders.value.first { it.name.equals(clean, ignoreCase = true) }
        }
        val folder = MediaFolder(id = UUID.randomUUID().toString(), name = clean)
        val next = (_folders.value + folder).sortedBy { it.name.lowercase() }
        saveFolders(next)
        _folders.value = next
        return folder
    }

    fun deleteFolder(folderId: String) {
        val nextFolders = _folders.value.filterNot { it.id == folderId }
        saveFolders(nextFolders)
        _folders.value = nextFolders
        val nextDownloads = _downloads.value.map { d ->
            if (d.folderId == folderId) d.copy(folderId = null) else d
        }
        saveAll(nextDownloads)
        _downloads.value = nextDownloads
    }

    fun moveToFolder(id: String, folderId: String?) {
        val target = folderId?.takeIf { fid -> _folders.value.any { it.id == fid } }
        val next = _downloads.value.map { d ->
            if (d.id == id) d.copy(folderId = target) else d
        }
        saveAll(next)
        _downloads.value = next
    }

    fun getAll(): List<LocalDownload> = _downloads.value

    fun getById(id: String): LocalDownload? = _downloads.value.firstOrNull { it.id == id }

    fun getTransfer(id: String): DownloadTransfer? =
        _transfers.value.firstOrNull { it.id == id }

    /**
     * Starts an app-lifetime download and returns its stable transfer id
     * immediately. Navigation between screens does not cancel this job.
     */
    fun enqueue(
        title: String,
        streamUrl: String,
        resolution: String,
        duration: String,
        thumbnailUrl: String,
        referer: String,
        folderId: String? = null,
        onProgress: (Float) -> Unit = {},
    ): String {
        val id = UUID.randomUUID().toString()
        val request = DownloadRequest(
            title = title,
            streamUrl = streamUrl,
            resolution = resolution,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            referer = referer,
            folderId = folderId?.takeIf { fid -> _folders.value.any { it.id == fid } },
        )
        requests[id] = request
        publishQueued(id, request)
        launch(id, request, onProgress)
        return id
    }

    /**
     * Suspends until a download is verified and registered. Callers that need
     * the operation to survive screen disposal should use [enqueue].
     */
    suspend fun download(
        title: String,
        streamUrl: String,
        resolution: String,
        duration: String,
        thumbnailUrl: String,
        referer: String,
        downloadId: String = UUID.randomUUID().toString(),
        onProgress: (Float) -> Unit = {},
    ): LocalDownload = withContext(Dispatchers.IO) {
        val request = DownloadRequest(
            title = title,
            streamUrl = streamUrl,
            resolution = resolution,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            referer = referer,
            folderId = null,
        )
        requests[downloadId] = request
        publishQueued(downloadId, request)
        performDownload(downloadId, request, onProgress)
    }

    /** Cancels an active transfer. Completed files are left untouched. */
    fun cancel(id: String): Boolean {
        val wasActive = synchronized(mutationLock) {
            val transfer = _transfers.value.firstOrNull { it.id == id }
            if (transfer?.isActive != true) {
                false
            } else {
                cancelledIds += id
                replaceTransferLocked(
                    transfer.copy(
                        status = DownloadStatus.CANCELLED,
                        error = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                true
            }
        }
        if (!wasActive) return false
        calls[id]?.cancel()
        jobs[id]?.cancel(CancellationException("Download cancelled"))
        return true
    }

    /** Retries a failed/cancelled transfer with its original request and id. */
    fun retry(id: String): Boolean {
        val request = requests[id] ?: return false
        val previousJob = jobs[id]
        synchronized(mutationLock) {
            val transfer = _transfers.value.firstOrNull { it.id == id } ?: return false
            if (transfer.isActive || transfer.status == DownloadStatus.COMPLETED) return false
            cancelledIds.remove(id)
            replaceTransferLocked(
                transfer.copy(
                    status = DownloadStatus.QUEUED,
                    bytesDownloaded = 0L,
                    totalBytes = null,
                    progress = 0f,
                    error = null,
                    localDownload = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        downloadScope.launch {
            // Cancellation is observable immediately, while cleanup remains
            // owned by the old writer. Never delete a file from under it.
            previousJob?.join()
            safeDeleteOwned(workingPaths.remove(id), videosDir)
            launch(id, request) {}
        }
        return true
    }

    /** Removes a terminal transfer notification without touching saved media. */
    fun dismissTransfer(id: String): Boolean = synchronized(mutationLock) {
        val transfer = _transfers.value.firstOrNull { it.id == id } ?: return false
        if (transfer.isActive) return false
        _transfers.value = _transfers.value.filterNot { it.id == id }
        requests.remove(id)
        cancelledIds.remove(id)
        true
    }

    fun remove(id: String) {
        cancel(id)
        val removed = synchronized(mutationLock) {
            val item = _downloads.value.firstOrNull { it.id == id } ?: return@synchronized null
            val list = _downloads.value.filterNot { it.id == id }
            if (!saveAll(list)) return@synchronized null
            _downloads.value = list
            _transfers.value = _transfers.value.filterNot { it.id == id }
            requests.remove(id)
            item
        } ?: return
        deleteLocalDownload(removed)
    }

    fun clearAll() {
        val transferIds = _transfers.value.map { it.id }
        val activeIds = _transfers.value.filter { it.isActive }.map { it.id }.toSet()
        activeIds.forEach(::cancel)
        val removed = synchronized(mutationLock) {
            val all = _downloads.value
            if (!saveAll(emptyList())) return
            _downloads.value = emptyList()
            _transfers.value = _transfers.value.filter { it.isActive }
            transferIds.forEach {
                requests.remove(it)
                if (it !in activeIds) cancelledIds.remove(it)
            }
            all
        }
        removed.forEach(::deleteLocalDownload)
    }

    private fun launch(
        id: String,
        request: DownloadRequest,
        onProgress: (Float) -> Unit,
    ) {
        val job = downloadScope.launch(start = CoroutineStart.LAZY) {
            try {
                performDownload(id, request, onProgress)
            } catch (_: CancellationException) {
                // State and cleanup are handled by performDownload.
            } catch (_: Throwable) {
                // Failure is represented in the observable transfer state.
            }
        }
        val existing = jobs.putIfAbsent(id, job)
        if (existing != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { jobs.remove(id, job) }
        job.start()
    }

    private suspend fun performDownload(
        id: String,
        request: DownloadRequest,
        onProgress: (Float) -> Unit,
    ): LocalDownload {
        transitionToDownloading(id, request)

        val safeTitle = sanitizeFilePart(request.title).ifBlank { "video" }
        val safeResolution = sanitizeFilePart(request.resolution).ifBlank { "HD" }
        val baseName = "${safeTitle.take(54)}_${safeResolution.take(12)}_$id"
        var workingRoot: File? = null
        var finalizedRoot: File? = null
        var finalizedThumbnail: File? = null

        try {
            currentCoroutineContext().ensureActive()
            check(!cancelledIds.contains(id)) { "Download was cancelled" }

            val result = if (looksLikeHls(request.streamUrl)) {
                val partialDir = File(videosDir, "$baseName.hls$PART_SUFFIX")
                workingRoot = partialDir
                workingPaths[id] = partialDir
                downloadHls(
                    id = id,
                    request = request,
                    partialDir = partialDir,
                    onProgress = onProgress,
                )
            } else {
                val partialFile = File(videosDir, "$baseName.mp4$PART_SUFFIX")
                workingRoot = partialFile
                workingPaths[id] = partialFile
                when (
                    val progressive = downloadProgressive(
                        id = id,
                        url = request.streamUrl,
                        destination = partialFile,
                        referer = request.referer,
                        onProgress = onProgress,
                    )
                ) {
                    is ProgressiveResult.FileResult -> DownloadedPayload.Progressive(
                        partialFile = partialFile,
                        bytesWritten = progressive.bytesWritten,
                    )

                    is ProgressiveResult.HlsManifest -> {
                        safeDeleteOwned(partialFile, videosDir)
                        val partialDir = File(videosDir, "$baseName.hls$PART_SUFFIX")
                        workingRoot = partialDir
                        workingPaths[id] = partialDir
                        downloadHls(
                            id = id,
                            request = request,
                            partialDir = partialDir,
                            initialManifest = progressive.text,
                            initialManifestUrl = progressive.finalUrl,
                            onProgress = onProgress,
                        )
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            check(!cancelledIds.contains(id)) { "Download was cancelled" }

            val playableFile: File
            val storageRoot: File
            val sizeBytes: Long
            when (result) {
                is DownloadedPayload.Progressive -> {
                    val finalFile = File(videosDir, baseName + ".mp4")
                    atomicRename(result.partialFile, finalFile, videosDir)
                    finalizedRoot = finalFile
                    playableFile = finalFile
                    storageRoot = finalFile
                    sizeBytes = finalFile.length()
                }

                is DownloadedPayload.Hls -> {
                    val finalDir = File(videosDir, "$baseName.hls")
                    atomicRename(result.partialDir, finalDir, videosDir)
                    finalizedRoot = finalDir
                    playableFile = File(finalDir, result.playlistRelativePath)
                    storageRoot = finalDir
                    sizeBytes = directorySize(finalDir)
                }
            }

            require(playableFile.isFile && playableFile.length() > 0L && sizeBytes > 0L) {
                "Downloaded media is empty"
            }

            val thumbnailPath = if (request.thumbnailUrl.isNotBlank()) {
                runCatching {
                    downloadThumbnail(id, request.thumbnailUrl, request.referer)
                }.getOrNull()?.also { finalizedThumbnail = it }?.absolutePath.orEmpty()
            } else {
                ""
            }

            val entry = LocalDownload(
                id = id,
                title = request.title,
                filePath = playableFile.absolutePath,
                thumbnailPath = thumbnailPath,
                thumbnailUrl = request.thumbnailUrl,
                duration = request.duration,
                resolution = request.resolution,
                sizeBytes = sizeBytes,
                completedAt = System.currentTimeMillis(),
                storagePath = storageRoot.absolutePath,
                folderId = request.folderId,
            )
            registerCompleted(id, entry)
            workingPaths.remove(id)
            lastProgressEmitAt.remove(id)
            runCatching { onProgress(1f) }
            return entry
        } catch (throwable: Throwable) {
            safeDeleteOwned(workingRoot, videosDir)
            safeDeleteOwned(finalizedRoot, videosDir)
            safeDeleteOwned(finalizedThumbnail, thumbsDir)
            workingPaths.remove(id)
            calls.remove(id)?.cancel()
            lastProgressEmitAt.remove(id)

            val cancelled = cancelledIds.contains(id) ||
                throwable is CancellationException ||
                !currentCoroutineContext().isActive
            if (cancelled) {
                markCancelled(id)
                if (requests[id] == null && getTransfer(id) == null) {
                    cancelledIds.remove(id)
                }
                throw CancellationException("Download cancelled", throwable)
            }
            markFailed(id, throwable)
            throw throwable
        }
    }

    private suspend fun downloadHls(
        id: String,
        request: DownloadRequest,
        partialDir: File,
        initialManifest: String? = null,
        initialManifestUrl: String = request.streamUrl,
        onProgress: (Float) -> Unit,
    ): DownloadedPayload.Hls {
        val result = HlsOfflineDownloader(NetworkClient.http, NetworkClient.USER_AGENT).download(
            url = request.streamUrl,
            referer = request.referer,
            outputDir = partialDir,
            targetResolution = request.resolution,
            initialManifest = initialManifest,
            initialManifestUrl = initialManifestUrl,
            onProgress = { progress ->
                publishProgress(id, progress, onProgress)
            },
            onCallChanged = { call ->
                if (call == null) calls.remove(id) else calls[id] = call
            },
        )
        val rootUri = partialDir.canonicalFile.toURI()
        val playlistUri = result.playlistFile.canonicalFile.toURI()
        val relativeUri = rootUri.relativize(playlistUri)
        require(!relativeUri.isAbsolute && relativeUri != playlistUri) {
            "Offline playlist was written outside its download directory"
        }
        val relative = relativeUri.path
        require(relative.isNotBlank()) { "Offline playlist path is empty" }
        return DownloadedPayload.Hls(
            partialDir = partialDir,
            playlistRelativePath = relative,
        )
    }

    private suspend fun downloadProgressive(
        id: String,
        url: String,
        destination: File,
        referer: String,
        onProgress: (Float) -> Unit,
    ): ProgressiveResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply { if (referer.isNotBlank()) header("Referer", referer) }
            .get()
            .build()
        val call = NetworkClient.http.newCall(request)
        calls[id] = call
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }

        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed (HTTP ${response.code})")
                }
                val body = response.body ?: throw IllegalStateException("Empty download response")
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (isClearlyErrorContent(contentType)) {
                    throw IllegalStateException("Server returned an error page instead of video")
                }
                if (contentType.contains("dash+xml") || url.substringBefore('?').endsWith(".mpd", true)) {
                    throw IllegalStateException("DASH downloads are not supported for offline playback")
                }

                val total = body.contentLength().takeIf { it > 0L }
                val usableSpace = destination.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (total != null && usableSpace < total + MIN_FREE_SPACE_BYTES) {
                    throw IllegalStateException("Not enough storage space for this download")
                }

                val input = body.byteStream()
                val prefix = readPrefix(input, SNIFF_BYTES)
                if (looksLikeHls(prefix, contentType)) {
                    val output = ByteArrayOutputStream()
                    output.write(prefix)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        require(output.size() <= MAX_MANIFEST_BYTES) {
                            "HLS playlist is unexpectedly large"
                        }
                    }
                    return@use ProgressiveResult.HlsManifest(
                        text = output.toString(Charsets.UTF_8.name()),
                        finalUrl = response.request.url.toString(),
                    )
                }
                if (looksLikeErrorDocument(prefix)) {
                    throw IllegalStateException("Server returned an error document instead of video")
                }

                FileOutputStream(destination).use { output ->
                    var written = 0L
                    if (prefix.isNotEmpty()) {
                        output.write(prefix)
                        written += prefix.size
                        publishProgress(
                            id,
                            TransferProgress(written, total, fraction(written, total)),
                            onProgress,
                            force = true,
                        )
                    }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        publishProgress(
                            id,
                            TransferProgress(written, total, fraction(written, total)),
                            onProgress,
                        )
                    }
                    output.flush()
                    output.fd.sync()
                    if (total != null && written != total) {
                        throw IllegalStateException("Incomplete download ($written of $total bytes)")
                    }
                    if (written <= 0L) throw IllegalStateException("Downloaded file is empty")
                    ProgressiveResult.FileResult(written)
                }
            }
        } finally {
            cancellation?.dispose()
            calls.remove(id, call)
        }
    }

    private suspend fun downloadThumbnail(id: String, url: String, referer: String): File {
        val partial = File(thumbsDir, "$id.jpg$PART_SUFFIX")
        val final = File(thumbsDir, "$id.jpg")
        val cleanUrl = NetworkClient.sanitizeMediaUrl(url)
        require(cleanUrl.startsWith("http")) { "Invalid thumbnail URL" }
        // Try page referer first, then image-host origin (Buumal CDN needs this).
        val referers = buildList {
            if (referer.isNotBlank()) add(referer)
            runCatching {
                val u = java.net.URI(cleanUrl)
                add("${u.scheme}://${u.host}/")
                val host = u.host.orEmpty().lowercase()
                when {
                    host.contains("mmhd") -> {
                        add("https://mmhdhub.com/")
                        add("https://cloud.mmhd-cdn.com/")
                    }
                    host.contains("goodhub") || host.contains("sn-cdn") ->
                        add("https://shennana.com/")
                    host.contains("others-cdn") || host.contains("thumb-cdn") ->
                        add("https://pornkai.com/")
                    host.contains("hentaimama") -> add("https://hentaimama.io/")
                    host.contains("buumal") -> {
                        add("https://www.buumal.com/")
                        add("https://img.buumal.com/")
                    }
                }
            }
            add("https://www.buumal.com/")
            add("https://img.buumal.com/")
            add("https://mmhdhub.com/")
            add("https://shennana.com/")
            add("https://pornkai.com/")
        }.distinct()
        var lastError: Exception? = null
        for (ref in referers) {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
                .header("Referer", ref)
                .get()
                .build()
            val call = NetworkClient.http.newCall(request)
            calls[id] = call
            val cancellation = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) error("Thumbnail HTTP ${response.code}")
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    if (type.isNotBlank() &&
                        !type.startsWith("image/") &&
                        !type.contains("octet-stream") &&
                        type != "application/octet-stream" &&
                        !type.startsWith("application/octet")
                    ) {
                        error("Invalid thumbnail type: $type")
                    }
                    val body = response.body ?: error("Empty thumbnail")
                    if (body.contentLength() > MAX_THUMBNAIL_BYTES) error("Thumbnail is too large")
                    body.byteStream().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var written = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                written += read
                                if (written > MAX_THUMBNAIL_BYTES) error("Thumbnail is too large")
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                }
                require(partial.length() > 0L) { "Empty thumbnail" }
                atomicRename(partial, final, thumbsDir)
                return final
            } catch (e: Exception) {
                lastError = e
                if (partial.exists()) safeDeleteOwned(partial, thumbsDir)
            } finally {
                cancellation?.dispose()
                calls.remove(id, call)
            }
        }
        throw lastError ?: IllegalStateException("Thumbnail download failed")
    }

    private fun publishQueued(id: String, request: DownloadRequest) = synchronized(mutationLock) {
        cancelledIds.remove(id)
        val now = System.currentTimeMillis()
        replaceTransferLocked(
            DownloadTransfer(
                id = id,
                title = request.title,
                resolution = request.resolution,
                status = DownloadStatus.QUEUED,
                createdAt = _transfers.value.firstOrNull { it.id == id }?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    private fun transitionToDownloading(id: String, request: DownloadRequest) =
        synchronized(mutationLock) {
            if (cancelledIds.contains(id)) throw CancellationException("Download cancelled")
            val existing = _transfers.value.firstOrNull { it.id == id }
            replaceTransferLocked(
                (existing ?: DownloadTransfer(
                    id = id,
                    title = request.title,
                    resolution = request.resolution,
                    status = DownloadStatus.QUEUED,
                )).copy(
                    status = DownloadStatus.DOWNLOADING,
                    error = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

    private fun publishProgress(
        id: String,
        progress: TransferProgress,
        onProgress: (Float) -> Unit,
        force: Boolean = false,
    ) {
        if (cancelledIds.contains(id)) return
        val now = System.currentTimeMillis()
        val last = lastProgressEmitAt[id] ?: 0L
        if (!force && progress.fraction < 1f && now - last < PROGRESS_EMIT_INTERVAL_MS) return
        lastProgressEmitAt[id] = now
        synchronized(mutationLock) {
            val current = _transfers.value.firstOrNull { it.id == id } ?: return
            if (!current.isActive) return
            replaceTransferLocked(
                current.copy(
                    status = DownloadStatus.DOWNLOADING,
                    bytesDownloaded = progress.bytesWritten.coerceAtLeast(current.bytesDownloaded),
                    totalBytes = progress.totalBytes ?: current.totalBytes,
                    progress = progress.fraction.coerceIn(current.progress, 0.999f),
                    updatedAt = now,
                ),
            )
        }
        runCatching { onProgress(progress.fraction.coerceIn(0f, 1f)) }
    }

    private fun registerCompleted(id: String, entry: LocalDownload) {
        val replaced: List<LocalDownload>
        synchronized(mutationLock) {
            if (cancelledIds.contains(id)) throw CancellationException("Download cancelled")
            require(File(entry.filePath).isFile && File(entry.filePath).length() > 0L) {
                "Downloaded file disappeared before it could be saved"
            }
            // A title is not a media identity. Different providers commonly publish
            // unrelated videos under the same short title and quality, so only an
            // exact persisted ID is replaceable.
            replaced = _downloads.value.filter { it.id == entry.id }
            val newList = listOf(entry) + _downloads.value.filterNot { it.id == entry.id }
            check(saveAll(newList)) { "Could not persist completed download" }
            _downloads.value = newList

            val existing = _transfers.value.firstOrNull { it.id == id }
            replaceTransferLocked(
                (existing ?: DownloadTransfer(
                    id = id,
                    title = entry.title,
                    resolution = entry.resolution,
                    status = DownloadStatus.DOWNLOADING,
                )).copy(
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = entry.sizeBytes,
                    totalBytes = entry.sizeBytes,
                    progress = 1f,
                    error = null,
                    localDownload = entry,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        requests.remove(id)
        replaced.forEach(::deleteLocalDownload)
    }

    private fun markFailed(id: String, throwable: Throwable) {
        synchronized(mutationLock) {
            val current = _transfers.value.firstOrNull { it.id == id } ?: return@synchronized
            if (current.status == DownloadStatus.CANCELLED) return@synchronized
            replaceTransferLocked(
                current.copy(
                    status = DownloadStatus.FAILED,
                    error = userFacingError(throwable),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun markCancelled(id: String) {
        synchronized(mutationLock) {
            val current = _transfers.value.firstOrNull { it.id == id } ?: return@synchronized
            // A queued snapshot means the user already requested a retry while
            // the prior writer was finishing its cancellation cleanup.
            if (current.status == DownloadStatus.QUEUED) return@synchronized
            replaceTransferLocked(
                current.copy(
                    status = DownloadStatus.CANCELLED,
                    error = null,
                    localDownload = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun replaceTransferLocked(transfer: DownloadTransfer) {
        val list = _transfers.value.toMutableList()
        val index = list.indexOfFirst { it.id == transfer.id }
        if (index >= 0) list[index] = transfer else list.add(0, transfer)
        _transfers.value = list
            .sortedByDescending { it.updatedAt }
            .take(MAX_TRANSFER_HISTORY)
    }

    private fun loadAll(): List<LocalDownload> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val path = o.optString("filePath")
                    val playable = File(path)
                    val storagePath = o.optString("storagePath", path)
                    val storage = File(storagePath)
                    val fileOk = path.isNotBlank() && playable.isFile && playable.length() > 0L
                    val folderRaw = o.optString("folderId", "")
                    val title = o.optString("title").ifBlank { "Offline video" }
                    // Keep catalog rows after reinstall even when private files are gone.
                    if (!fileOk && title.isBlank()) continue
                    add(
                        LocalDownload(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = title,
                            filePath = if (fileOk) playable.absolutePath else path,
                            thumbnailPath = o.optString("thumbnailPath").takeIf {
                                it.isNotBlank() && File(it).isFile
                            }.orEmpty(),
                            thumbnailUrl = NetworkClient.sanitizeMediaUrl(
                                o.optString("thumbnailUrl"),
                            ),
                            duration = o.optString("duration", "—"),
                            resolution = o.optString("resolution", "HD"),
                            sizeBytes = o.optLong("sizeBytes").takeIf { it > 0L }
                                ?: if (fileOk && storage.exists()) directorySize(storage) else 0L,
                            completedAt = o.optLong("completedAt").takeIf { it > 0L }
                                ?: if (fileOk) storage.lastModified() else System.currentTimeMillis(),
                            storagePath = if (storage.exists()) storage.absolutePath else storagePath,
                            folderId = folderRaw.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(list: List<LocalDownload>): Boolean {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("title", d.title)
                    .put("filePath", d.filePath)
                    .put("storagePath", d.storagePath)
                    .put("thumbnailPath", d.thumbnailPath)
                    .put("thumbnailUrl", d.thumbnailUrl)
                    .put("duration", d.duration)
                    .put("resolution", d.resolution)
                    .put("sizeBytes", d.sizeBytes)
                    .put("completedAt", d.completedAt)
                    .put("folderId", d.folderId.orEmpty()),
            )
        }
        val ok = prefs.edit().putString(KEY_LIST, arr.toString()).commit()
        if (ok) persistDurable()
        return ok
    }

    private fun loadFolders(): List<MediaFolder> =
        parseMediaFolders(prefs.getString(KEY_FOLDERS, "[]"))

    private fun saveFolders(list: List<MediaFolder>) {
        prefs.edit().putString(KEY_FOLDERS, list.toJsonArray().toString()).apply()
        persistDurable()
    }

    private fun restoreFromDurableIfNeeded() {
        val prefsEmpty = (prefs.getString(KEY_LIST, "[]") ?: "[]").let { it == "[]" || it.isBlank() }
        val foldersEmpty = (prefs.getString(KEY_FOLDERS, "[]") ?: "[]").let { it == "[]" || it.isBlank() }
        if (!prefsEmpty && !foldersEmpty) return
        val snap = DurableLibraryStore.load(appContext) ?: return
        if (prefsEmpty && snap.downloadsJson != "[]" && snap.downloadsJson.isNotBlank()) {
            prefs.edit().putString(KEY_LIST, snap.downloadsJson).commit()
            _downloads.value = loadAll()
        }
        if (foldersEmpty && snap.downloadFoldersJson != "[]" && snap.downloadFoldersJson.isNotBlank()) {
            prefs.edit().putString(KEY_FOLDERS, snap.downloadFoldersJson).commit()
            _folders.value = loadFolders()
        }
    }

    private fun persistDurable() {
        runCatching {
            DurableLibraryStore.saveDownloads(
                context = appContext,
                downloadsJson = prefs.getString(KEY_LIST, "[]") ?: "[]",
                foldersJson = prefs.getString(KEY_FOLDERS, "[]") ?: "[]",
            )
        }
    }

    private fun deleteLocalDownload(item: LocalDownload) {
        safeDeleteOwned(File(item.storagePath), videosDir)
        if (item.thumbnailPath.isNotBlank()) {
            safeDeleteOwned(File(item.thumbnailPath), thumbsDir)
        }
    }

    private fun safeDeleteOwned(file: File?, ownerRoot: File) {
        file ?: return
        runCatching {
            val canonicalRoot = ownerRoot.canonicalFile
            val canonicalFile = file.canonicalFile
            val owned = canonicalFile != canonicalRoot &&
                canonicalFile.path.startsWith(canonicalRoot.path + File.separator)
            if (!owned) return@runCatching
            if (canonicalFile.isDirectory) canonicalFile.deleteRecursively() else canonicalFile.delete()
        }
    }

    private fun atomicRename(from: File, to: File, ownerRoot: File) {
        require(from.exists()) { "Temporary download disappeared" }
        if (to.exists()) safeDeleteOwned(to, ownerRoot)
        check(from.renameTo(to)) { "Could not finalize downloaded file" }
    }

    private fun readPrefix(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(maxBytes)
        val buffer = ByteArray(minOf(512, maxBytes))
        while (output.size() < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (read == -1) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun looksLikeHls(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    private fun looksLikeHls(prefix: ByteArray, contentType: String): Boolean =
        contentType.contains("mpegurl") ||
            prefix.toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U")

    private fun looksLikeErrorDocument(prefix: ByteArray): Boolean {
        val text = prefix.toString(Charsets.UTF_8).trimStart().lowercase()
        return text.startsWith("<!doctype html") || text.startsWith("<html") ||
            text.startsWith("<?xml") || text.startsWith("{\"error\"")
    }

    private fun isClearlyErrorContent(contentType: String): Boolean =
        contentType.contains("text/html") || contentType.contains("application/json") ||
            contentType.contains("text/xml") || contentType.contains("application/xml")

    private fun sanitizeFilePart(value: String): String = value
        .replace(Regex("""[\\/:*?\"<>|\\p{Cntrl}]"""), "_")
        .trim(' ', '.')

    private fun fraction(bytes: Long, total: Long?): Float =
        if (total != null && total > 0L) {
            (bytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 0.999f)
        } else {
            0f
        }

    private fun directorySize(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf(::directorySize) ?: 0L
        else -> 0L
    }

    private fun userFacingError(throwable: Throwable): String {
        val message = throwable.message.orEmpty().trim()
        return message.takeIf { it.isNotBlank() }?.take(180) ?: "Download failed"
    }

    private data class DownloadRequest(
        val title: String,
        val streamUrl: String,
        val resolution: String,
        val duration: String,
        val thumbnailUrl: String,
        val referer: String,
        val folderId: String? = null,
    )

    private sealed interface ProgressiveResult {
        data class FileResult(val bytesWritten: Long) : ProgressiveResult
        data class HlsManifest(val text: String, val finalUrl: String) : ProgressiveResult
    }

    private sealed interface DownloadedPayload {
        data class Progressive(
            val partialFile: File,
            val bytesWritten: Long,
        ) : DownloadedPayload

        data class Hls(
            val partialDir: File,
            val playlistRelativePath: String,
        ) : DownloadedPayload
    }

    companion object {
        private const val PREFS = "funfy_downloads"
        private const val KEY_LIST = "items"
        private const val KEY_FOLDERS = "folders"
        private const val PART_SUFFIX = ".part"
        private const val MAX_TRANSFER_HISTORY = 100
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
        private const val MIN_FREE_SPACE_BYTES = 8L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 20L * 1024L * 1024L
        private const val SNIFF_BYTES = 1024
    }
}
