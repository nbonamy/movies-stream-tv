package fr.bonamy.movies.core

data class Title(
    val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val rating: String,
    val overview: String,
    val releaseDate: String,
    val type: MediaType = MediaType.MOVIE,
    val siteId: String,
    val sectionId: String = type.apiValue,
) {
    val ref get() = TitleRef(siteId, id, type)
}

data class ResolvedPlayback(
    val streamUrl: String,
    val requestHeaders: Map<String, String>,
    val subtitleContext: SubtitleContext? = null,
    val format: MediaFormat = MediaFormat.HLS,
)

enum class MediaFormat(val mimeType: String) { HLS("application/x-mpegURL"), MP4("video/mp4") }

data class PlaybackSource(
    val id: String,
    val label: String,
)

enum class MediaType(val apiValue: String, val label: String) {
    MOVIE("movie", "Movies"), TV("tv", "TV Shows")
}

data class TitleRef(val siteId: String, val id: String, val type: MediaType) {
    init { require(siteId.isNotBlank() && id.isNotBlank()) }
}

data class PlayableRef(val title: TitleRef, val episodeId: String? = null,
    val season: Int? = null, val episode: Int? = null) {
    init {
        require(if (title.type == MediaType.TV) !episodeId.isNullOrBlank() else episodeId == null && season == null && episode == null)
    }
    val id get() = title.id
    val siteId get() = title.siteId
    val type get() = title.type
    // JSON avoids delimiter collisions when a provider uses slugs or paths as IDs.
    val key: String get() = com.google.gson.Gson().toJson(listOf("v2", siteId, type.name, id, episodeId))
}

data class CatalogRequest(val sectionId: String, val query: String? = null)
data class PageToken(val siteId: String, val request: CatalogRequest, val value: String)
data class CatalogPage(val items: List<Title>, val next: PageToken?)
data class SeasonRef(val show: TitleRef, val id: String, val number: Int? = null)
data class Season(val ref: SeasonRef, val name: String, val episodeCount: Int) {
    val number get() = ref.number
}
data class Episode(val target: PlayableRef, val name: String, val overview: String, val still: String) {
    val number get() = target.episode
    val season get() = target.season
}
