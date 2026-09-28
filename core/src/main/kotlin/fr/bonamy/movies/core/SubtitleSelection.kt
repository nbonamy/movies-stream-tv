package fr.bonamy.movies.core

/** Stable subtitle identity, independent of a player's transient group/track indices. */
sealed interface SubtitleSelection {
    data object Off : SubtitleSelection
    data class Language(val code: String) : SubtitleSelection
    data class Embedded(val language: String?, val id: String?, val label: String?, val roleFlags: Int) : SubtitleSelection
    data class Online(val subtitle: OnlineSubtitle) : SubtitleSelection

    /** Episode transitions carry only language, never the preceding episode's subtitle file. */
    fun nextEpisode(): SubtitleSelection? = when (this) {
        Off -> Off
        is Language -> this
        is Embedded -> subtitleLanguageCode(language)?.let(::Language)
        is Online -> Language(subtitle.language.code)
    }
}

fun subtitleLanguageCode(language: String?): String? = when (val code = language
    ?.lowercase(java.util.Locale.ROOT)?.substringBefore('-')?.substringBefore('_')) {
    null, "", "und" -> null
    "fre", "fra" -> "fr"
    "eng" -> "en"
    else -> code
}
