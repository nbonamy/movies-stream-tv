package fr.bonamy.movies.core.extractors

import fr.bonamy.movies.core.*

/** Input understood by Max/Vidpro/VidRock. Other extractors may use different request types. */
internal data class TmdbPlayback(val id: String, val type: MediaType = MediaType.MOVIE,
    val season: Int? = null, val episode: Int? = null) {
    init {
        require(id.isNotEmpty() && id.all(Char::isDigit))
        require(if (type == MediaType.TV) season != null && season >= 0 && episode != null && episode > 0
            else season == null && episode == null)
    }
    val path get() = "${type.apiValue}/$id" + if (type == MediaType.TV) "/$season/$episode" else ""
}
