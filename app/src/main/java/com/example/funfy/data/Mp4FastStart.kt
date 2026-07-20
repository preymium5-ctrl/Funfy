package com.example.funfy.data

import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Speeds up progressive playback of "moov-at-end" MP4s (common on MMPorns/DrKoGyi).
 *
 * Those files force the player to range-GET ~0.5MB from the end of a 60MB+ object
 * before the first frame — often the whole wait the user feels as "slow loading".
 *
 * We:
 *  1. Parse the first box list (ftyp / free / mdat…)
 *  2. Parallel-fetch the trailing [moov]
 *  3. Expose a **virtual** layout: [ftyp][moov][mdat] with rewritten chunk offsets
 *     so ExoPlayer can start immediately without seeking to EOF.
 */
object Mp4FastStart {

    data class Plan(
        val url: String,
        val referer: String?,
        /** Original file size (Content-Length). */
        val originalLength: Long,
        val ftyp: ByteArray,
        /** moov with stco/co64 rewritten for the virtual layout. */
        val moov: ByteArray,
        /** Byte offset of mdat payload in the **original** file (after mdat header). */
        val originalMdatDataStart: Long,
        /** Size of mdat payload (not including 8-byte box header). */
        val mdatDataSize: Long,
        /** Prefetched start of mdat payload for instant first GOPs. */
        val mdatHead: ByteArray,
    ) {
        val virtualLength: Long get() = ftyp.size.toLong() + moov.size.toLong() + 8L + mdatDataSize

        fun mapVirtualToUpstream(virtualPos: Long): UpstreamMap? {
            val ftypEnd = ftyp.size.toLong()
            val moovEnd = ftypEnd + moov.size
            val mdatHeaderEnd = moovEnd + 8
            val end = virtualLength
            return when {
                virtualPos < 0 || virtualPos >= end -> null
                virtualPos < ftypEnd ->
                    UpstreamMap.Memory(ftyp, virtualPos.toInt(), (ftypEnd - virtualPos).toInt())
                virtualPos < moovEnd -> {
                    val off = (virtualPos - ftypEnd).toInt()
                    UpstreamMap.Memory(moov, off, (moovEnd - virtualPos).toInt())
                }
                virtualPos < mdatHeaderEnd -> {
                    // Synthetic mdat box header (size + 'mdat')
                    val header = mdatBoxHeader(mdatDataSize + 8)
                    val off = (virtualPos - moovEnd).toInt()
                    UpstreamMap.Memory(header, off, (mdatHeaderEnd - virtualPos).toInt())
                }
                else -> {
                    val intoMdat = virtualPos - mdatHeaderEnd
                    if (intoMdat < mdatHead.size) {
                        val remaining = (mdatHead.size - intoMdat).toInt()
                        UpstreamMap.Memory(mdatHead, intoMdat.toInt(), remaining)
                    } else {
                        UpstreamMap.Remote(originalMdatDataStart + intoMdat)
                    }
                }
            }
        }
    }

    sealed class UpstreamMap {
        data class Memory(val bytes: ByteArray, val offset: Int, val maxLength: Int) : UpstreamMap()
        data class Remote(val originalPosition: Long) : UpstreamMap()
    }

    /**
     * Build a fast-start plan, or null if the file is already fast-start / not MP4 / fails.
     */
    fun prepare(
        url: String,
        referer: String?,
        userAgent: String = NetworkClient.USER_AGENT,
        mdatHeadBytes: Int = 1_048_576,
        moovParts: Int = 8,
    ): Plan? {
        if (!url.contains(".mp4", ignoreCase = true)) return null
        return try {
            val head = range(url, referer, userAgent, 0, 128 * 1024 - 1) ?: return null
            val total = head.totalLength ?: return null
            if (total < 1024) return null

            val boxes = parseTopLevel(head.data, allowIncomplete = true)
            val ftypBox = boxes.firstOrNull { it.type == "ftyp" } ?: return null
            // Need the full ftyp bytes
            if (ftypBox.start + ftypBox.size > head.data.size) return null
            val ftyp = head.data.copyOfRange(ftypBox.start, ftypBox.start + ftypBox.size.toInt())
            if (ftyp.size < 8) return null

            val moovEarly = boxes.firstOrNull { it.type == "moov" }
            val mdatBox = boxes.firstOrNull { it.type == "mdat" }
            // Already fast-start (moov before mdat)
            if (moovEarly != null && (mdatBox == null || moovEarly.start < mdatBox.start)) {
                return null
            }
            if (mdatBox == null || mdatBox.size < 16L) return null

            // mdat size includes header; payload follows 8-byte header (or 16 if largesize)
            val mdatHeaderSize = if (mdatBox.hdrSize > 0) mdatBox.hdrSize else 8
            val mdatDataStart = mdatBox.start.toLong() + mdatHeaderSize
            val mdatDataSize = mdatBox.size - mdatHeaderSize
            val afterMdat = mdatBox.start.toLong() + mdatBox.size
            if (afterMdat >= total) return null

            // Trailing region = moov (and maybe free/uuid — we take until EOF)
            val tailStart = afterMdat
            val tail = parallelRange(url, referer, userAgent, tailStart, total - 1, moovParts)
                ?: return null
            val tailBoxes = parseTopLevel(tail, allowIncomplete = false)
            val moovBox = tailBoxes.firstOrNull { it.type == "moov" } ?: return null
            if (moovBox.start + moovBox.size > tail.size) return null
            val moovRaw = tail.copyOfRange(moovBox.start, moovBox.start + moovBox.size.toInt())

            // Virtual: [ftyp][moov][mdat]. Original mdat payload starts at mdatDataStart.
            // delta applied to stco/co64: newAbs = oldAbs + (ftyp.size + moov.size + 8 - mdatDataStart)
            val delta = ftyp.size.toLong() + moovRaw.size.toLong() + 8L - mdatDataStart
            val moovFixed = rewriteChunkOffsets(moovRaw, delta)

            val headEnd = (mdatDataStart + mdatHeadBytes - 1).coerceAtMost(total - 1)
            val mdatHead = if (headEnd >= mdatDataStart) {
                parallelRange(url, referer, userAgent, mdatDataStart, headEnd, parts = 4)
                    ?: range(url, referer, userAgent, mdatDataStart, headEnd)?.data
                    ?: ByteArray(0)
            } else {
                ByteArray(0)
            }

            Plan(
                url = url,
                referer = referer,
                originalLength = total,
                ftyp = ftyp,
                moov = moovFixed,
                originalMdatDataStart = mdatDataStart,
                mdatDataSize = mdatDataSize,
                mdatHead = mdatHead,
            )
        } catch (_: Exception) {
            null
        }
    }

    private data class BoxInfo(
        val type: String,
        /** Offset of box start within the buffer that was parsed. */
        val start: Int,
        /** Full box size from the header (may extend past the buffer). */
        val size: Long,
        val hdrSize: Int,
    )

    private fun parseTopLevel(data: ByteArray, allowIncomplete: Boolean): List<BoxInfo> {
        val out = mutableListOf<BoxInfo>()
        var o = 0
        while (o + 8 <= data.size) {
            var size = readU32(data, o).toLong()
            val type = data.decodeToString(o + 4, o + 8)
            var hdr = 8
            if (size == 1L && o + 16 <= data.size) {
                size = readU64(data, o + 8)
                hdr = 16
            } else if (size == 0L) {
                size = (data.size - o).toLong()
            }
            if (size < hdr) break
            if (o + size > data.size && !allowIncomplete) break
            out.add(BoxInfo(type, o, size, hdr))
            // Stop advancing when the box extends past this buffer (e.g. huge mdat).
            if (o.toLong() + size > data.size) break
            o += size.toInt()
            if (out.size > 32) break
        }
        return out
    }

    private fun rewriteChunkOffsets(moov: ByteArray, delta: Long): ByteArray {
        val copy = moov.copyOf()
        fun walk(start: Int, end: Int) {
            var o = start
            while (o + 8 <= end) {
                var size = readU32(copy, o).toLong()
                val type = copy.decodeToString(o + 4, o + 8)
                var hdr = 8
                if (size == 1L && o + 16 <= end) {
                    size = readU64(copy, o + 8)
                    hdr = 16
                } else if (size == 0L) {
                    size = (end - o).toLong()
                }
                if (size < hdr || o + size > end) break
                val contentStart = o + hdr
                val contentEnd = o + size.toInt()
                when (type) {
                    "stco" -> patchStco(copy, contentStart, contentEnd, delta)
                    "co64" -> patchCo64(copy, contentStart, contentEnd, delta)
                    "moov", "trak", "mdia", "minf", "stbl", "edts", "mvex" ->
                        walk(contentStart, contentEnd)
                }
                o = contentEnd
            }
        }
        walk(0, copy.size)
        return copy
    }

    private fun patchStco(data: ByteArray, start: Int, end: Int, delta: Long) {
        if (start + 8 > end) return
        // version(1)+flags(3) + entry_count(4)
        val count = readU32(data, start + 4).toInt()
        var o = start + 8
        repeat(count) {
            if (o + 4 > end) return
            val old = readU32(data, o)
            val neu = (old + delta).coerceAtLeast(0L)
            writeU32(data, o, neu)
            o += 4
        }
    }

    private fun patchCo64(data: ByteArray, start: Int, end: Int, delta: Long) {
        if (start + 8 > end) return
        val count = readU32(data, start + 4).toInt()
        var o = start + 8
        repeat(count) {
            if (o + 8 > end) return
            val old = readU64(data, o)
            val neu = (old + delta).coerceAtLeast(0L)
            writeU64(data, o, neu)
            o += 8
        }
    }

    private fun mdatBoxHeader(boxSizeIncludingHeader: Long): ByteArray {
        // Use 32-bit size when possible
        return if (boxSizeIncludingHeader <= 0xFFFF_FFFFL) {
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(boxSizeIncludingHeader.toInt())
                .put("mdat".toByteArray(Charsets.US_ASCII))
                .array()
        } else {
            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putInt(1)
                .put("mdat".toByteArray(Charsets.US_ASCII))
                .putLong(boxSizeIncludingHeader)
                .array()
        }
    }

    private data class RangeResult(val data: ByteArray, val totalLength: Long?)

    private fun range(
        url: String,
        referer: String?,
        userAgent: String,
        start: Long,
        end: Long,
    ): RangeResult? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Range", "bytes=$start-$end")
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            .get()
            .build()
        return try {
            NetworkClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) return null
                val body = resp.body?.bytes() ?: return null
                val total = resp.header("Content-Range")
                    ?.substringAfter('/')
                    ?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()?.let { start + it }
                RangeResult(body, total)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parallelRange(
        url: String,
        referer: String?,
        userAgent: String,
        start: Long,
        end: Long,
        parts: Int,
    ): ByteArray? {
        val total = end - start + 1
        if (total <= 0) return null
        if (total < 64 * 1024 || parts <= 1) {
            return range(url, referer, userAgent, start, end)?.data
        }
        val n = parts.coerceAtMost(8).coerceAtLeast(2)
        val chunk = (total + n - 1) / n
        val pool = Executors.newFixedThreadPool(n)
        return try {
            val futures = ArrayList<Future<ByteArray?>>(n)
            for (i in 0 until n) {
                val a = start + i * chunk
                if (a > end) break
                val b = minOf(end, a + chunk - 1)
                futures.add(
                    pool.submit(
                        Callable {
                            range(url, referer, userAgent, a, b)?.data
                        },
                    ),
                )
            }
            val out = ByteArrayOutputStream(total.toInt().coerceAtLeast(0))
            for (f in futures) {
                val part = f.get(45, TimeUnit.SECONDS) ?: return null
                out.write(part)
            }
            out.toByteArray()
        } catch (_: Exception) {
            // Fallback single-stream
            range(url, referer, userAgent, start, end)?.data
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)
    }

    private fun readU64(data: ByteArray, offset: Int): Long {
        return (readU32(data, offset) shl 32) or readU32(data, offset + 4)
    }

    private fun writeU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xff).toByte()
        data[offset + 1] = ((value ushr 16) and 0xff).toByte()
        data[offset + 2] = ((value ushr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }

    private fun writeU64(data: ByteArray, offset: Int, value: Long) {
        writeU32(data, offset, value ushr 32)
        writeU32(data, offset + 4, value and 0xFFFF_FFFFL)
    }
}
