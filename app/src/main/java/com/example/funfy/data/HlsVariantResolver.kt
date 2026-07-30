package com.example.funfy.data

import android.util.Log

/**
 * Resolves single HLS **master** playlists (xHamster/Beeg-style `Auto` streams) into
 * discrete per-resolution variant URLs.
 *
 * Why: those sources expose one `.m3u8` master labelled "Auto". Handed to ExoPlayer's
 * adaptive selection it opens on the fattest (1080p) rendition — slow start + buffering.
 * By fetching the master here and playing a **fixed media playlist** for the chosen tier,
 * the player deterministically starts on 480p with no ABR ramp-up.
 */
object HlsVariantResolver {

    /**
     * Expand any HLS master in [streams] into discrete variant options.
     * Falls back to the original list on any network/parse failure.
     */
    private const val TAG = "HlsVariantResolver"

    fun expand(streams: List<StreamOption>, referer: String): List<StreamOption> {
        if (streams.isEmpty()) return streams
        Log.i(TAG, "expand() input=${streams.map { "${it.label}|${it.url.take(80)}" }}")
        val out = linkedMapOf<String, StreamOption>()
        for (opt in streams) {
            val url = opt.url
            val looksLikeMaster = url.contains(".m3u8", ignoreCase = true) &&
                (
                    opt.label.equals("Auto", ignoreCase = true) ||
                        opt.label.contains("HLS", ignoreCase = true) ||
                        streamQualityRank(opt.label) <= 1
                    )
            Log.i(TAG, "opt label=${opt.label} looksLikeMaster=$looksLikeMaster url=${url.take(100)}")
            if (!looksLikeMaster) {
                out.putIfAbsent(url, opt)
                continue
            }
            val variants = try {
                parseMaster(url, referer)
            } catch (e: Exception) {
                Log.w(TAG, "parseMaster failed: ${e.message}")
                emptyList()
            }
            Log.i(TAG, "parseMaster returned ${variants.size} variants: ${variants.map { it.label }}")
            if (variants.size >= 2) {
                // Keep discrete variants; also keep the master as an explicit "Auto" choice.
                for (v in variants) out.putIfAbsent(v.url, v)
                out.putIfAbsent(url, opt.copy(label = "Auto"))
            } else {
                out.putIfAbsent(url, opt)
            }
        }
        val result = out.values.sortedByDescending { streamQualityRank(it.label) }
        Log.i(TAG, "expand() output=${result.map { it.label }}")
        return result
    }


    private data class Variant(val url: String, val height: Int, val bandwidth: Int)

    private fun parseMaster(masterUrl: String, referer: String): List<StreamOption> {
        val body = NetworkClient.get(masterUrl, referer)
        if (!body.contains("#EXTM3U")) return emptyList()
        if (!body.contains("#EXT-X-STREAM-INF")) return emptyList() // media playlist, not a master

        val lines = body.lines()
        val found = mutableListOf<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(line)
                val height = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val bandwidth = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                // Next non-comment, non-blank line is the variant URI.
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    val abs = when {
                        uri.startsWith("http") -> uri
                        uri.startsWith("//") -> "https:$uri"
                        else -> resolveRelative(masterUrl, uri)
                    }
                    found.add(Variant(abs, height, bandwidth))
                }
                i = j + 1
            } else {
                i++
            }
        }
        if (found.isEmpty()) return emptyList()

        // Prefer RESOLUTION when present; otherwise infer a ladder from bandwidth order.
        val labeled = if (found.all { it.height <= 0 }) {
            val ladder = listOf(240, 360, 480, 720, 1080, 1440, 2160)
            found.sortedBy { it.bandwidth }
                .mapIndexed { idx, v ->
                    StreamOption("${ladder.getOrElse(idx) { 1080 }}p", v.url)
                }
        } else {
            found.sortedBy { if (it.height > 0) it.height else it.bandwidth }
                .map { v -> StreamOption(labelForHeight(v.height), v.url) }
        }
        return labeled.distinctBy { it.label }
    }

    private fun labelForHeight(h: Int): String = when {
        h >= 2000 -> "2160p"
        h >= 1400 -> "1440p"
        h >= 1000 -> "1080p"
        h >= 700 -> "720p"
        h >= 450 -> "480p"
        h >= 300 -> "360p"
        h > 0 -> "${h}p"
        else -> "Auto"
    }

    private fun resolveRelative(base: String, relative: String): String = try {
        java.net.URI(base).resolve(relative).toString()
    } catch (_: Exception) {
        val slash = base.lastIndexOf('/')
        if (slash > 0) base.substring(0, slash + 1) + relative else relative
    }
}
