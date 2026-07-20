package com.example.funfy.data

/**
 * Tag / category chip for homepage filtering.
 * Paths mirror https://www.xvideos.com/ main category nav (`/c/...`).
 */
data class ContentTag(
    val label: String,
    /** XVideos category path, e.g. `/c/Amateur-65`. Null = keyword search only. */
    val categoryPath: String? = null,
    /** Keyword used on non-XVideos sources (and as search fallback). */
    val keyword: String = label,
)

/**
 * Official XVideos homepage categories (from site nav).
 * @see https://www.xvideos.com/
 */
object XvideosTags {
    val ALL: List<ContentTag> = listOf(
        ContentTag("AI", "/c/AI-239", "AI"),
        ContentTag("Amateur", "/c/Amateur-65", "amateur"),
        ContentTag("Anal", "/c/Anal-12", "anal"),
        ContentTag("Arab", "/c/Arab-159", "arab"),
        ContentTag("Asian", "/c/Asian_Woman-32", "asian"),
        ContentTag("ASMR", "/c/ASMR-229", "asmr"),
        ContentTag("Ass", "/c/Ass-14", "ass"),
        ContentTag("BBW", "/c/bbw-51", "bbw"),
        ContentTag("Bi", "/c/Bi_Sexual-62", "bisexual"),
        ContentTag("Big Ass", "/c/Big_Ass-24", "big ass"),
        ContentTag("Big Cock", "/c/Big_Cock-34", "big cock"),
        ContentTag("Big Tits", "/c/Big_Tits-23", "big tits"),
        ContentTag("Black", "/c/Black_Woman-30", "black"),
        ContentTag("Blonde", "/c/Blonde-20", "blonde"),
        ContentTag("Blowjob", "/c/Blowjob-15", "blowjob"),
        ContentTag("Brunette", "/c/Brunette-25", "brunette"),
        ContentTag("Cam Porn", "/c/Cam_Porn-58", "cam"),
        ContentTag("Creampie", "/c/Creampie-40", "creampie"),
        ContentTag("Cuckold/Hotwife", "/c/Cuckold-237", "cuckold"),
        ContentTag("Cumshot", "/c/Cumshot-18", "cumshot"),
        ContentTag("Femdom", "/c/Femdom-235", "femdom"),
        ContentTag("Fisting", "/c/Fisting-165", "fisting"),
        ContentTag("Fucked Up Family", "/c/Fucked_Up_Family-81", "step family"),
        ContentTag("Gangbang", "/c/Gangbang-69", "gangbang"),
        ContentTag("Gapes", "/c/Gapes-167", "gape"),
        ContentTag("Indian", "/c/Indian-89", "indian"),
        ContentTag("Interracial", "/c/Interracial-27", "interracial"),
        ContentTag("Latina", "/c/Latina-16", "latina"),
        ContentTag("Lesbian", "/c/Lesbian-26", "lesbian"),
        ContentTag("Lingerie", "/c/Lingerie-83", "lingerie"),
        ContentTag("Mature", "/c/Mature-38", "mature"),
        ContentTag("Milf", "/c/Milf-19", "milf"),
        ContentTag("Oiled", "/c/Oiled-22", "oiled"),
        ContentTag("Redhead", "/c/Redhead-31", "redhead"),
        ContentTag("Solo", "/c/Solo_and_Masturbation-33", "solo masturbation"),
        ContentTag("Squirting", "/c/Squirting-56", "squirting"),
        ContentTag("Stockings", "/c/Stockings-28", "stockings"),
        ContentTag("Teen", "/c/Teen-13", "teen"),
    )

    fun fromLabel(label: String?): ContentTag? {
        if (label.isNullOrBlank()) return null
        return ALL.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: ContentTag(label = label, categoryPath = null, keyword = label)
    }
}
