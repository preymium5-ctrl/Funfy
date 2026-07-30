package com.example.funfy.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.example.funfy.data.Mp4FastStart
import com.example.funfy.data.NetworkClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves a virtual fast-start layout for moov-at-end MP4s prepared by [Mp4FastStart].
 * Falls back to [DefaultHttpDataSource] when the file is already fast-start.
 *
 * Remote mdat reads use a **1MB readahead cache** so ExoPlayer's small sequential
 * reads don't open a new HTTP Range request every few KB (that pattern causes
 * constant buffering on hosts like pinaydeepweb / lootedpinay Clean Tube).
 */
@OptIn(UnstableApi::class)
class FastStartDataSource(
    private val userAgent: String,
    private val defaultReferer: String?,
) : BaseDataSource(/* isNetwork= */ true) {

    private var plan: Mp4FastStart.Plan? = null
    private var fallback: DefaultHttpDataSource? = null
    private var dataSpec: DataSpec? = null
    private var bytesRemaining: Long = 0
    private var virtualPosition: Long = 0
    private var opened = false
    private var transferStarted = false

    // Sequential readahead for UpstreamMap.Remote
    private var remoteBuf: ByteArray? = null
    private var remoteBufStart: Long = -1L
    private var remoteBufEnd: Long = -1L // exclusive

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        val referer = dataSpec.httpRequestHeaders["Referer"]
            ?: defaultReferer
            ?: NetworkClient.siteReferer(url)

        // Reset readahead on every open (seek / new media).
        remoteBuf = null
        remoteBufStart = -1L
        remoteBufEnd = -1L

        plan = plans[url]
        if (plan == null && shouldTryFastStart(url)) {
            val built = Mp4FastStart.prepare(url, referer, userAgent)
            if (built != null) {
                plans[url] = built
                plan = built
            }
        }

        val p = plan
        if (p == null) {
            val fb = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(
                    buildMap {
                        put("Accept", "*/*")
                        put("Accept-Encoding", "identity")
                        if (!referer.isNullOrBlank()) {
                            put("Referer", referer)
                            // Don't send Origin — some WP hosts throttle/CORS-stall on it.
                        }
                    },
                )
                .createDataSource()
            fallback = fb
            val len = fb.open(dataSpec)
            opened = true
            transferStarted = true
            return len
        }

        virtualPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (p.virtualLength - dataSpec.position).coerceAtLeast(0L)
        }
        opened = true
        transferStarted = false
        transferStarted(dataSpec)
        transferStarted = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let { return it.read(buffer, offset, length) }

        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val p = plan ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = readFromPlan(p, buffer, offset, toRead)
        if (read > 0) {
            virtualPosition += read
            bytesRemaining -= read
            bytesTransferred(read)
        }
        return read
    }

    private fun readFromPlan(
        p: Mp4FastStart.Plan,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val map = p.mapVirtualToUpstream(virtualPosition) ?: return C.RESULT_END_OF_INPUT
        return when (map) {
            is Mp4FastStart.UpstreamMap.Memory -> {
                val can = minOf(length, map.maxLength)
                if (can <= 0) return C.RESULT_END_OF_INPUT
                System.arraycopy(map.bytes, map.offset, buffer, offset, can)
                can
            }
            is Mp4FastStart.UpstreamMap.Remote -> {
                readRemote(
                    url = p.url,
                    position = map.originalPosition,
                    buffer = buffer,
                    offset = offset,
                    length = length,
                    referer = p.referer,
                    originalLength = p.originalLength,
                )
            }
        }
    }

    private fun readRemote(
        url: String,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        referer: String?,
        originalLength: Long,
    ): Int {
        // Serve from readahead when possible (sequential playback path).
        val cached = remoteBuf
        if (
            cached != null &&
            position >= remoteBufStart &&
            position < remoteBufEnd
        ) {
            val off = (position - remoteBufStart).toInt()
            val can = minOf(length, (remoteBufEnd - position).toInt())
            if (can > 0) {
                System.arraycopy(cached, off, buffer, offset, can)
                return can
            }
        }

        // Prefetch a large contiguous range — Exo often asks for 8–32 KB at a time.
        val prefetch = PREFETCH_BYTES.toLong()
        val end = minOf(originalLength - 1, position + prefetch - 1)
        if (end < position) return C.RESULT_END_OF_INPUT

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$position-$end")
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            .get()
            .build()
        return try {
            NetworkClient.http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: return C.RESULT_END_OF_INPUT
                if (bytes.isEmpty()) return C.RESULT_END_OF_INPUT
                val actualStart = if (resp.code == 206) {
                    val cr = resp.header("Content-Range")
                    val parsed = cr?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    parsed ?: position
                } else {
                    0L
                }
                remoteBuf = bytes
                remoteBufStart = actualStart
                remoteBufEnd = actualStart + bytes.size
                if (position < remoteBufStart || position >= remoteBufEnd) {
                    return C.RESULT_END_OF_INPUT
                }
                val off = (position - remoteBufStart).toInt()
                val n = minOf(length, bytes.size - off)
                if (n <= 0) return C.RESULT_END_OF_INPUT
                System.arraycopy(bytes, off, buffer, offset, n)
                n
            }
        } catch (e: IOException) {
            remoteBuf = null
            throw e
        } catch (e: Exception) {
            remoteBuf = null
            throw IOException("Read failed at $position", e)
        }
    }

    override fun getUri(): Uri? = dataSpec?.uri ?: fallback?.uri

    override fun close() {
        fallback?.let {
            it.close()
            fallback = null
        }
        if (opened && transferStarted && plan != null) {
            transferEnded()
        }
        opened = false
        transferStarted = false
        plan = null
        dataSpec = null
        remoteBuf = null
        remoteBufStart = -1L
        remoteBufEnd = -1L
    }

    companion object {
        /** Process-wide plan cache so revisiting a video is instant. */
        private val plans = ConcurrentHashMap<String, Mp4FastStart.Plan>()

        /** 1.5 MB readahead — fewer HTTP round-trips on progressive CDNs. */
        private const val PREFETCH_BYTES = 1_572_864

        fun prewarm(url: String, referer: String?): Mp4FastStart.Plan? {
            plans[url]?.let { return it }
            if (!shouldTryFastStart(url)) return null
            val built = Mp4FastStart.prepare(url, referer, NetworkClient.USER_AGENT)
            if (built != null) plans[url] = built
            return built
        }

        fun shouldTryFastStart(url: String): Boolean {
            val u = url.lowercase()
            // Progressive MP4s with moov-at-end (MMHDHub / Clean Tube / R2 / PH WP hosts)
            // need virtual fast-start so the player doesn't range-GET EOF first.
            return u.contains(".mp4") && (
                u.contains("drkogyi") ||
                    u.contains("/uploads/") ||
                    u.contains("mmporns") ||
                    u.contains("mmhd") ||
                    u.contains("mmhd-cdn") ||
                    u.contains("cloud.mmhd") ||
                    u.contains("dl.mmhdhub") ||
                    u.contains("wp-content") ||
                    u.contains("gdvid.info") ||
                    u.contains("javprovider.com") ||
                    u.contains("pinaydeepweb") ||
                    u.contains("lootedpinay") ||
                    u.contains("kaldagan") ||
                    u.contains("pinayum") ||
                    u.contains("pwerta") ||
                    u.contains("rubyvid") ||
                    u.contains("streamruby") ||
                    u.contains("savefiles") ||
                    u.contains("bigwarp") ||
                    u.contains("video.beeg") ||
                    u.contains("ahcdn.com") ||
                    u.contains("xxxfiles")
                )
        }

        fun clearCache() = plans.clear()
    }
}

@OptIn(UnstableApi::class)
class FastStartDataSourceFactory(
    private val userAgent: String = NetworkClient.USER_AGENT,
    private val defaultReferer: String? = null,
) : androidx.media3.datasource.DataSource.Factory {
    override fun createDataSource(): androidx.media3.datasource.DataSource =
        FastStartDataSource(userAgent, defaultReferer)
}
