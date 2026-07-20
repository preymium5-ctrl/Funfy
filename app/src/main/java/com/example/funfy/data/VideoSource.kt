package com.example.funfy.data

/** Provider used by a selectable source or regional feed. */
enum class SourceProvider(val label: String) {
    XVIDEOS("XVideos"),
    EPORNER("Eporner"),
    /** Direct scrapers (preview + play + download via page stream extract). */
    LEGACY("Direct sites"),
}

/** Regions shown in the source picker. */
enum class SourceRegion(val label: String) {
    JAV("Japan / JAV"),
    PHILIPPINES("Philippines"),
    INDONESIA("Indonesia"),
    MYANMAR("Myanmar"),
    VIETNAM("Vietnam"),
    THAILAND("Thailand"),
    HENTAI("Hentai"),
}

/**
 * A content source shown by Settings and search.
 *
 * Global providers (XVideos / Eporner / …) plus real regional sites. Keyword-only
 * "Region - XVideos/Eporner" feeds were removed — each region lists direct sites only.
 */
enum class VideoSource(
    val id: String,
    val label: String,
    val baseUrl: String,
    val hostHints: List<String>,
    val provider: SourceProvider,
    /** Fixed provider query for regional feeds. */
    val keyword: String? = null,
    val region: SourceRegion? = null,
    val isSelectable: Boolean = true,
) {
    // ── Global providers (proven play + download) ──────────────────────────
    XVIDEOS(
        id = "xvideos",
        label = "XVideos",
        baseUrl = "https://www.xvideos.com",
        hostHints = listOf("xvideos.com", "xvideos-cdn.com"),
        provider = SourceProvider.XVIDEOS,
    ),
    EPORNER(
        id = "eporner",
        label = "Eporner",
        baseUrl = "https://www.eporner.com",
        hostHints = listOf("eporner.com"),
        provider = SourceProvider.EPORNER,
    ),
    PORNHUB(
        id = "pornhub",
        label = "Pornhub",
        baseUrl = "https://www.pornhub.com",
        hostHints = listOf("pornhub.com", "phncdn.com"),
        provider = SourceProvider.LEGACY,
    ),
    REDTUBE(
        id = "redtube",
        label = "RedTube",
        baseUrl = "https://www.redtube.com",
        hostHints = listOf("redtube.com", "rdtcdn.com"),
        provider = SourceProvider.LEGACY,
    ),
    HQPORNER(
        id = "hqporner",
        label = "HQPorner",
        baseUrl = "https://hqporner.com",
        hostHints = listOf("hqporner.com", "mydaddy.cc", "bigcdn.cc"),
        provider = SourceProvider.LEGACY,
    ),
    TNAFLIX(
        id = "tnaflix",
        label = "TNAFlix",
        baseUrl = "https://www.tnaflix.com",
        hostHints = listOf("tnaflix.com"),
        provider = SourceProvider.LEGACY,
    ),
    PORNTREX(
        id = "porntrex",
        label = "Porntrex",
        baseUrl = "https://www.porntrex.com",
        hostHints = listOf("porntrex.com", "cdntrex.com"),
        provider = SourceProvider.LEGACY,
    ),
    SEXVID(
        id = "sexvid",
        label = "Sexvid",
        baseUrl = "https://www.sexvid.xxx",
        hostHints = listOf("sexvid.xxx"),
        provider = SourceProvider.LEGACY,
    ),
    ANALDIN(
        id = "analdin",
        label = "Analdin",
        baseUrl = "https://www.analdin.com",
        hostHints = listOf("analdin.com"),
        provider = SourceProvider.LEGACY,
    ),
    XXXTIME(
        id = "xxxtime",
        label = "XxxTime",
        baseUrl = "https://xxxtime.video",
        hostHints = listOf("xxxtime.video", "siska.tv", "siska.video"),
        provider = SourceProvider.LEGACY,
    ),

    // ── Japan / JAV — 5 real sites ────────────────────────────────────────
    MISSAV(
        id = "missav",
        label = "MissAV",
        baseUrl = "https://missav.ws",
        hostHints = listOf("missav.ws", "missav.com", "missav.ai", "surrit.com", "fourhoi.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.JAV,
    ),
    JAVFREE(
        id = "javfree",
        label = "JavFree",
        baseUrl = "https://javfree.me",
        hostHints = listOf("javfree.me"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.JAV,
    ),
    JAVTSUNAMI(
        id = "javtsunami",
        label = "JavTsunami",
        baseUrl = "https://javtsunami.com",
        hostHints = listOf(
            "javtsunami.com",
            "imagerls.com",
            "turbovidhls.com",
            "hicherri.com",
            "vide0.net",
        ),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.JAV,
    ),
    ONETWOAV(
        id = "123av",
        label = "123AV",
        baseUrl = "https://123av.com",
        hostHints = listOf("123av.com", "123av.me", "javplayer.cc"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.JAV,
    ),
    JAVSEEN(
        id = "javseen",
        label = "JavSeen",
        baseUrl = "https://javseenz.tv",
        hostHints = listOf("javseenz.tv", "javseen.tv", "pics.javseenz.tv"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.JAV,
    ),

    // ── Philippines — real sites only ─────────────────────────────────────
    PINAYOT(
        id = "pinayot",
        label = "PinayOT",
        baseUrl = "https://pinayot.com",
        hostHints = listOf("pinayot.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.PHILIPPINES,
    ),
    PINAYFLIX(
        id = "pinayflix",
        label = "PinayFlix",
        baseUrl = "https://pinayflix.uk",
        hostHints = listOf(
            "pinayflix.uk",
            "pinayflix.com",
            "flixtream.top",
            "goostream.net",
            "corecache.goostream.net",
        ),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.PHILIPPINES,
    ),
    PORNKAI(
        id = "pornkai",
        label = "PornKai",
        baseUrl = "https://pornkai.com",
        // Keep only PornKai hosts so XVideos global URLs still resolve to XVIDEOS.
        hostHints = listOf("pornkai.com", "thumb-cdn77.others-cdn.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.PHILIPPINES,
    ),
    PINAYPORNSITE(
        id = "pinaypornsite",
        label = "PinayPornSite",
        baseUrl = "https://www.pinaypornsite.com",
        hostHints = listOf("pinaypornsite.com", "thebesthosterv.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.PHILIPPINES,
    ),
    /** Removed from picker — kept for migration of old prefs only. */
    PINAYVIRAL(
        id = "pinayviral",
        label = "PinayViral",
        baseUrl = "https://www.pinayviral.org",
        hostHints = listOf("pinayviral.org"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.PHILIPPINES,
        isSelectable = false,
    ),

    // ── Indonesia — real sites only ───────────────────────────────────────
    INDO18(
        id = "indo18",
        label = "Indo18",
        baseUrl = "https://www.indo18.com",
        hostHints = listOf("indo18.com", "jomblo.org", "playmogo.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.INDONESIA,
    ),
    BOKEPBOX(
        id = "bokepbox",
        label = "BokepBox",
        baseUrl = "https://bokepbox.co",
        hostHints = listOf("bokepbox.co", "bokepindo.blog"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.INDONESIA,
    ),
    BOKEPINDOHOT(
        id = "bokepindohot",
        label = "BokepIndoHot",
        baseUrl = "https://bokepindohot.net",
        hostHints = listOf("bokepindohot.net"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.INDONESIA,
    ),
    BEBASINDO(
        id = "bebasindo",
        label = "BebasIndo",
        baseUrl = "https://bebasindo.top",
        hostHints = listOf("bebasindo.top", "cdn.bebasindo.top"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.INDONESIA,
    ),
    NONTONBOKEP(
        id = "nontonbokep",
        label = "NontonBokep",
        baseUrl = "https://nontonbokep.top",
        hostHints = listOf("nontonbokep.top", "200cdn.top", "303in.top"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.INDONESIA,
    ),

    // ── Myanmar — real sites only (mmporns / drkogyi removed) ─────────────
    BUUMAL(
        id = "buumal",
        label = "Buumal",
        baseUrl = "https://www.buumal.com",
        hostHints = listOf("buumal.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.MYANMAR,
    ),
    MMHDHUB(
        id = "mmhdhub",
        label = "MMHDHub",
        baseUrl = "https://mmhdhub.com",
        hostHints = listOf("mmhdhub.com", "mmhd-cdn.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.MYANMAR,
    ),
    BABEXTUBE(
        id = "babextube",
        label = "BabeXTube",
        baseUrl = "https://babextube.com",
        hostHints = listOf("babextube.com", "sub.babextube.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.MYANMAR,
    ),

    // ── Vietnam — real sites ───────────────────────────────────────────────
    VLXX(
        id = "vlxx",
        label = "VLXX",
        baseUrl = "https://vlxx.moi",
        hostHints = listOf(
            "vlxx.moi",
            "vlxx.sex",
            "vlimg.com",
            "cdn.vlimg.com",
            "vlstream.net",
            "vlplayer.com",
            "qooglevideo.com",
            "cdn.vlcontent.com",
        ),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.VIETNAM,
    ),
    SEXHAY24H(
        id = "sexhay24h",
        label = "SexHay24h",
        // sexhay24h.net permanently redirects here; direct host is faster/stable.
        baseUrl = "https://sexdeptv.com",
        hostHints = listOf(
            "sexdeptv.com",
            "sexhay24h.net",
            "phimsexhay.co",
            "javcg.xyz",
            "newfeedcdn.site",
        ),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.VIETNAM,
    ),
    QUATVN(
        id = "quatvn",
        label = "QuatVn",
        baseUrl = "https://quatvn.asia",
        hostHints = listOf("quatvn.asia", "quatvn.stream", "stream.quatvn.asia"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.VIETNAM,
    ),
    /** Removed from picker — slow / flaky CDN; kept for migration of old prefs only. */
    SHENNANA(
        id = "shennana",
        label = "ShenNana",
        baseUrl = "https://shennana.com",
        hostHints = listOf("shennana.com", "sn-cdn.goodhub.xyz", "stream.goodhub.xyz", "playhydrax.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.VIETNAM,
        isSelectable = false,
    ),

    // ── Thailand — direct sites with thumbs + multi-quality streams ─────────
    THAIPORNTV(
        id = "thaiporntv",
        label = "ThaiPornTV",
        baseUrl = "https://www.thaiporntv.com",
        hostHints = listOf("thaiporntv.com", "web.techvids.top", "techvids.top"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.THAILAND,
    ),
    /** KVS tube — Thai tag home, multi-quality get_file → CDN HLS (fast/stable). */
    OKXXX(
        id = "okxxx",
        label = "OK.xxx",
        baseUrl = "https://ok.xxx",
        hostHints = listOf(
            "ok.xxx",
            "static.ok.xxx",
            "privatehost.com",
            "cdn.privatehost.com",
        ),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.THAILAND,
    ),
    IXXX(
        id = "ixxx",
        label = "iXXX",
        baseUrl = "https://www.ixxx.com",
        hostHints = listOf("ixxx.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.THAILAND,
    ),

    // ── Hentai ─────────────────────────────────────────────────────────────
    HANIME(
        id = "hanime",
        label = "Hanime",
        baseUrl = "https://hanime.tv",
        hostHints = listOf("hanime.tv", "hanime-cdn.com", "freeanimehentai.net"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.HENTAI,
    ),
    HENTAIMAMA(
        id = "hentaimama",
        label = "HentaiMama",
        baseUrl = "https://hentaimama.io",
        hostHints = listOf(
            "hentaimama.io",
            "gdvid.info",
            "javprovider.com",
            "na-01.javprovider.com",
        ),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.HENTAI,
    ),
    HENTAI4K(
        id = "hentai4k",
        label = "Hentai4K",
        baseUrl = "https://hentai4k.com",
        hostHints = listOf("hentai4k.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.HENTAI,
    ),
    RULE34VIDEO(
        id = "rule34video",
        label = "Rule34 Video",
        baseUrl = "https://rule34video.com",
        hostHints = listOf("rule34video.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.HENTAI,
    ),
    HENTAIGASM(
        id = "hentaigasm",
        label = "Hentaigasm",
        baseUrl = "https://hentaigasm.com",
        hostHints = listOf("hentaigasm.com", "hgasm1.com", "hgasm3.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.HENTAI,
    ),
    HENTAICITY(
        id = "hentaicity",
        label = "HentaiCity",
        baseUrl = "https://www.hentaicity.com",
        hostHints = listOf("hentaicity.com", "cdn1.hentaicity.com", "hls.hentaicity.com"),
        provider = SourceProvider.LEGACY,
        region = SourceRegion.HENTAI,
    );

    val isRegional: Boolean get() = region != null

    companion object {
        val DEFAULT = XVIDEOS

        /** All user-selectable sources (providers + regional + brought-back sites). */
        val selectable: List<VideoSource> = entries.filter(VideoSource::isSelectable)

        val regionalCatalog: List<VideoSource> = entries.filter { it.isSelectable && it.isRegional }

        val regionalCatalogByRegion: Map<SourceRegion, List<VideoSource>> =
            SourceRegion.entries.associateWith { region ->
                regionalCatalog.filter { it.region == region }
            }

        fun fromId(id: String?): VideoSource {
            val migrated = when (id?.lowercase()) {
                "xnxx", "xhamster", "xhamster2" -> DEFAULT
                "bokepindo", "bokepindo.blog" -> BOKEPBOX
                "pinayflixhd", "pinayflixhd.com" -> PORNKAI
                "thaipornxxx", "pornthai", "thai", "thai_eporner" -> THAIPORNTV
                "vietnam", "viet_eporner" -> QUATVN
                // Removed / renamed sources
                "jav_xvideos", "jav_eporner", "javguru", "jav.guru" -> MISSAV
                "javff", "javff.com" -> JAVTSUNAMI
                "hentaihaven", "hentaihaven.xxx", "hentaihaven.com" -> HENTAIMAMA
                "ph_xvideos", "ph_eporner" -> PINAYOT
                "pinayviral", "pinayviral.org" -> PINAYFLIX
                "indonesia", "indo_eporner" -> INDO18
                "myanmar", "myanmar_eporner", "mmporns", "drkogyi" -> BUUMAL
                "shennana", "shennana.com" -> QUATVN
                "ok.xxx", "okxxx.com" -> OKXXX
                else -> entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
            }
            return migrated?.takeIf(VideoSource::isSelectable) ?: DEFAULT
        }

        fun fromUrl(url: String): VideoSource? {
            val lower = url.lowercase()
            return entries
                .asSequence()
                .filter { it.keyword == null }
                .sortedByDescending { source -> source.hostHints.maxOfOrNull(String::length) ?: 0 }
                .firstOrNull { source -> source.hostHints.any(lower::contains) }
                ?: if (lower.contains("xvideos.com")) XVIDEOS else null
        }
    }
}
