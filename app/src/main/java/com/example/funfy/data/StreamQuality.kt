package com.example.funfy.data

/** Canonical height rank for a quality label (0 = unknown/embed). */
fun streamQualityRank(label: String): Int {
    val l = label.lowercase().trim()
    Regex("""\b(2160|1440|1080|720|480|360|240|144|250)\s*p\b""").find(l)
        ?.groupValues?.get(1)?.toIntOrNull()?.let { return if (it == 250) 240 else it }
    return when {
        "2160" in l || "4k" in l -> 2160
        "1440" in l -> 1440
        "1080" in l -> 1080
        "720" in l -> 720
        "480" in l -> 480
        "360" in l -> 360
        Regex("""\b240\s*p\b""").containsMatchIn(l) ||
            Regex("""\b250\s*p\b""").containsMatchIn(l) -> 240
        Regex("""\b144\s*p\b""").containsMatchIn(l) -> 144
        "auto" in l || "hls" in l -> 1
        "embed" in l -> 0
        l == "high" || l == "hd" -> 720
        l == "medium" || l == "med" -> 480
        l == "low" || l == "sd" -> 360
        l == "mp4" -> 500
        else -> 100
    }
}

/**
 * Normalize labels to the public ladder: 144p / 240p / 360p / 480p / 720p / 1080p / Auto.
 */
fun normalizeStreamQualityLabel(label: String, url: String = ""): String {
    if (Regex("""^\s*(2160|1440|1080|720|480|360|240|144)\s*p\s*$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(label)
    ) {
        val d = label.filter { it.isDigit() }
        return if (d.isNotEmpty()) "${d}p" else label
    }
    if (label.contains("auto", true) || label.contains("hls", true)) return "Auto"
    val fromUrl = if (url.isNotBlank()) NetworkClient.guessQualityLabel(url, "") else ""
    val seed = when {
        fromUrl.isNotBlank() && streamQualityRank(label) <= 500 -> fromUrl
        fromUrl.isNotBlank() && !label.contains(Regex("""\d{3,4}\s*p""", RegexOption.IGNORE_CASE)) ->
            fromUrl
        else -> label
    }
    return when (val r = streamQualityRank(seed)) {
        2160 -> "2160p"
        1440 -> "1440p"
        1080 -> "1080p"
        720 -> "720p"
        480 -> "480p"
        360 -> "360p"
        240 -> "240p"
        144 -> "144p"
        1 -> "Auto"
        0 -> "Embed"
        else -> {
            if (fromUrl.isNotBlank()) fromUrl
            else if (Regex("""\d{3,4}\s*p""", RegexOption.IGNORE_CASE).containsMatchIn(label)) {
                label.replace(Regex("""\s+"""), "")
            } else {
                label.ifBlank { "Video" }
            }
        }
    }
}

/**
 * Playable quality list for picker / download: one option per ladder tier when possible.
 * Sorted highest → lowest (menu order).
 */
fun availableStreamQualities(streams: List<StreamOption>): List<StreamOption> {
    return streams
        .filter { !it.label.equals("Embed", ignoreCase = true) }
        .map { opt -> opt.copy(label = normalizeStreamQualityLabel(opt.label, opt.url)) }
        .distinctBy { it.url }
        .groupBy { rank ->
            val r = streamQualityRank(rank.label)
            if (r >= 144) r else rank.url
        }
        .values
        .map { group ->
            group.maxWithOrNull(
                compareBy<StreamOption> { it.sizeBytes ?: -1L }
                    .thenByDescending { streamQualityRank(it.label) },
            )!!
        }
        .sortedByDescending { streamQualityRank(it.label) }
}

/**
 * Default playback: prefer **360p**, then **480p**, then lower, then mid/high.
 * Avoid starting on 720p/1080p unless that is all the site offers.
 */
fun pickDefaultStream(streams: List<StreamOption>): StreamOption? {
    val expanded = expandMultiQualityStreams(streams)
    val options = availableStreamQualities(expanded)
    if (options.isEmpty()) {
        return expanded.firstOrNull { !it.label.equals("Embed", true) }
            ?: expanded.firstOrNull()
            ?: streams.firstOrNull()
    }
    // Preference order for first play (mobile / weak networks).
    val prefer = listOf(360, 480, 240, 144, 720, 1080, 1440, 2160, 1)
    for (tier in prefer) {
        options.firstOrNull { streamQualityRank(it.label) == tier }?.let { return it }
    }
    // Closest to 360–480 without going above 720 when possible.
    val underHd = options.filter {
        val r = streamQualityRank(it.label)
        r in 144..720
    }
    if (underHd.isNotEmpty()) {
        return underHd.minByOrNull { kotlin.math.abs(streamQualityRank(it.label) - 360) }
    }
    return options.lastOrNull() // lowest remaining
}

/**
 * Expand multi-bitrate masters (xHamster / Beeg-style) into discrete quality URLs
 * so the picker and default logic can start on 360p/480p instead of ABR-at-1080.
 */
fun expandMultiQualityStreams(streams: List<StreamOption>): List<StreamOption> {
    if (streams.isEmpty()) return streams
    val out = linkedMapOf<String, StreamOption>()
    fun put(opt: StreamOption) {
        val label = normalizeStreamQualityLabel(opt.label, opt.url)
        val key = "${streamQualityRank(label)}|${opt.url}"
        out.putIfAbsent(key, opt.copy(label = label))
    }
    for (opt in streams) {
        val u = opt.url
        val lower = u.lowercase()
        val multiExpanded = expandMultiTemplateUrl(u)
        if (multiExpanded.isNotEmpty()) {
            multiExpanded.forEach(::put)
            continue
        }
        // Progressive ladder: …/480p.h264.mp4, …/720p.h264.mp4 — keep as-is with correct label.
        if (lower.contains("p.h264.mp4") || Regex("""/\d{3,4}p\.(?:h264|av1|mp4)""").containsMatchIn(lower)) {
            put(opt.copy(label = NetworkClient.guessQualityLabel(u, opt.label)))
            continue
        }
        put(opt)
    }
    return out.values
        .distinctBy { it.url }
        .sortedByDescending { streamQualityRank(it.label) }
}

/**
 * xHamster multi masters look like:
 * `…/multi=256x144:144p:,…,1920x1080:1080p:/path/_TPL_.av1.mp4.m3u8`
 * Replace `_TPL_` with each tier so playback can pick a fixed low quality.
 */
private fun expandMultiTemplateUrl(url: String): List<StreamOption> {
    if (!url.contains("_TPL_", ignoreCase = true) && !url.contains("multi=", ignoreCase = true)) {
        return emptyList()
    }
    if (!url.contains(".m3u8", ignoreCase = true)) return emptyList()
    val tiers = mutableListOf<Pair<String, Int>>()
    // Capture labels listed after multi=
    val multiSeg = Regex(
        """multi=([^/]+)""",
        RegexOption.IGNORE_CASE,
    ).find(url)?.groupValues?.get(1).orEmpty()
    if (multiSeg.isNotBlank()) {
        Regex("""(\d{3,4})p""").findAll(multiSeg).forEach { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@forEach
            tiers += "${h}p" to h
        }
    }
    if (tiers.isEmpty()) {
        // Fallback common ladder
        tiers += listOf("240p" to 240, "360p" to 360, "480p" to 480, "720p" to 720, "1080p" to 1080)
    }
    if (!url.contains("_TPL_", ignoreCase = true)) {
        // Master without per-variant template — keep as Auto only.
        return emptyList()
    }
    return tiers
        .distinctBy { it.second }
        .sortedBy { it.second }
        .map { (label, _) ->
            // Prefer h264 progressive-style variant name used by xhcdn when possible.
            val tpl = if (url.contains(".av1.", true)) {
                label // e.g. 480p inside av1 template
            } else {
                label
            }
            StreamOption(label, url.replace("_TPL_", tpl, ignoreCase = true))
        }
}

/** Cap for adaptive HLS when the user has not picked a higher fixed quality. */
const val DEFAULT_MAX_PLAYBACK_HEIGHT = 480
