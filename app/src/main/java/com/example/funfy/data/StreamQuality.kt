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
 * Default playback: prefer **480p**, then **360p**, then lower, then mid/high.
 * Avoid starting on 720p/1080p unless that is all the site offers.
 */
fun pickDefaultStream(streams: List<StreamOption>): StreamOption? {
    val options = availableStreamQualities(streams)
    if (options.isEmpty()) {
        return streams.firstOrNull { !it.label.equals("Embed", true) }
            ?: streams.firstOrNull()
    }
    // Preference order for first play (mobile-friendly).
    val prefer = listOf(480, 360, 240, 144, 720, 1080, 1440, 2160, 1)
    for (tier in prefer) {
        options.firstOrNull { streamQualityRank(it.label) == tier }?.let { return it }
    }
    // Closest to 480 without going above 720 if possible
    val underHd = options.filter {
        val r = streamQualityRank(it.label)
        r in 144..720
    }
    if (underHd.isNotEmpty()) {
        return underHd.minByOrNull { kotlin.math.abs(streamQualityRank(it.label) - 480) }
    }
    return options.lastOrNull() // lowest remaining
}
