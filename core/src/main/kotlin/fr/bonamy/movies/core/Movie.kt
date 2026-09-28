package fr.bonamy.movies.core

data class Movie(
    val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val rating: String,
    val overview: String,
    val releaseDate: String,
    val type: MediaType = MediaType.MOVIE,
)

data class ResolvedMovie(
    val playlistUrl: String,
    val requestHeaders: Map<String, String>,
    val subtitleContext: SubtitleContext? = null,
)

data class MovieSource(
    val id: String,
    val label: String,
)

enum class MediaType(val apiValue: String, val label: String) {
    MOVIE("movie", "Movies"), TV("tv", "TV Shows")
}

data class CatalogPage(val items: List<Movie>, val page: Int, val totalPages: Int)
data class Season(val number: Int, val name: String, val episodeCount: Int)
data class Episode(val number: Int, val season: Int, val name: String, val overview: String, val still: String)

data class PlaybackTarget(val id: String, val type: MediaType = MediaType.MOVIE,
                          val season: Int? = null, val episode: Int? = null) {
    init {
        require(id.isNotEmpty() && id.all(Char::isDigit))
        require(if (type == MediaType.TV) season != null && season >= 0 && episode != null && episode > 0
            else season == null && episode == null)
    }
    val path: String get() = "${type.apiValue}/$id" + if (type == MediaType.TV) "/$season/$episode" else ""
}
