package fr.bonamy.movies.core.sites.cinejoy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.bonamy.movies.core.*
import fr.bonamy.movies.core.extractors.WingExtractor
import fr.bonamy.movies.core.extractors.WingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

class CinejoySite internal constructor(
    private val network: HttpTransport,
    private val apiKey: () -> String,
    private val metadataOrigin: HttpUrl = "https://api.themoviedb.org/3/".toHttpUrl(),
) : StreamingSite {
    private constructor(network: HttpTransport) : this(network, CinejoyConfig(network)::key)
    constructor() : this(HttpTransport())
    override val descriptor = SiteDescriptor(ID, "Cinejoy", listOf(
        SiteSection("movie", "Movies", MediaType.MOVIE), SiteSection("tv", "TV Shows", MediaType.TV)), SearchScope.SITE)
    private val extractor = WingExtractor(network)
    override val series = object : SeriesCatalog {
        override suspend fun seasons(show: TitleRef): List<Season> = io {
            owned(show); require(show.type == MediaType.TV)
            get("tv/${show.id}").getAsJsonArray("seasons")?.mapNotNull { element ->
                val row = element.asJsonObject
                val number = row.get("season_number")?.asInt ?: return@mapNotNull null
                if (number < 1 || row.get("episode_count")?.asInt == 0 || !aired(row.text("air_date"))) return@mapNotNull null
                Season(SeasonRef(show, row.string("id"), number), row.text("name"), row.get("episode_count")?.asInt ?: 0)
            }?.sortedBy { it.number } ?: throw IOException("Cinejoy seasons unavailable")
        }
        override suspend fun episodes(season: SeasonRef): List<Episode> = io {
            owned(season.show); require(season.show.type == MediaType.TV && season.number != null && season.number > 0)
            val data = get("tv/${season.show.id}/season/${season.number}")
            require(data.string("id") == season.id) { "Season identity changed" }
            episodesFrom(data, season.show, season.number)
        }
    }

    override suspend fun browse(request: CatalogRequest, after: PageToken?): CatalogPage = io {
        val section = descriptor.section(request.sectionId)
        require(after == null || after.siteId == ID && after.request == request) { "Page belongs to another catalog" }
        val page = after?.value?.toInt() ?: 1
        require(page in 1..500)
        val query = request.query?.trim()?.takeIf { it.isNotEmpty() }
        val data = get(if (query == null) "discover/${section.mediaType.apiValue}" else "search/multi", buildMap {
            put("page", page.toString()); put("include_adult", "false"); put("language", "en-US")
            if (query == null) put("sort_by", "popularity.desc") else put("query", query)
        })
        require(data.get("page")?.asInt == page) { "Unexpected catalog page" }
        val rows = data.getAsJsonArray("results") ?: throw IOException("Cinejoy catalog unavailable")
        val items = rows.mapNotNull { element ->
            val row = element.asJsonObject
            val type = if (query == null) section.mediaType else when (row.text("media_type")) {
                "movie" -> MediaType.MOVIE; "tv" -> MediaType.TV; else -> return@mapNotNull null
            }
            if (row.get("adult")?.asBoolean == true || row.text("poster_path").isBlank()) return@mapNotNull null
            title(row, type)
        }.distinctBy { it.ref }
        val total = data.get("total_pages")?.asInt ?: throw IOException("Cinejoy pagination unavailable")
        CatalogPage(items, if (page < minOf(total, 500)) PageToken(ID, request, (page + 1).toString()) else null)
    }

    override suspend fun details(title: Title): Title = io {
        owned(title.ref)
        title(get("${title.type.apiValue}/${title.id}"), title.type)
    }
    override suspend fun sources(item: PlayableRef): PlaybackOptions = io { owned(item.title); extractor.sources() }
    override suspend fun resolve(item: PlayableRef, sourceId: String?): ResolvedPlayback = io {
        owned(item.title)
        if (item.type == MediaType.TV) {
            require(item.season != null && item.season > 0 && item.episode != null && item.episode > 0)
            val episodes = episodesFrom(get("tv/${item.id}/season/${item.season}"), item.title, item.season)
            if (episodes.none { it.target == item }) throw IOException("Episode identity is unavailable or has changed")
        }
        val metadata = get("${item.type.apiValue}/${item.id}", mapOf("append_to_response" to "external_ids"))
        val name = if (item.type == MediaType.MOVIE) metadata.text("title") else metadata.text("name")
        val date = metadata.text(if (item.type == MediaType.MOVIE) "release_date" else "first_air_date")
        extractor.resolve(WingRequest(item.id, item.type, name, date.take(4), imdb(metadata), item.season, item.episode,
            sourceId ?: extractor.sources().defaultSourceId))
    }
    override suspend fun subtitleContext(item: PlayableRef): SubtitleContext? = io {
        owned(item.title)
        imdb(get("${item.type.apiValue}/${item.id}", mapOf("append_to_response" to "external_ids")))
            ?.let { SubtitleContext(it, item.season, item.episode) }
    }

    private fun get(path: String, parameters: Map<String, String> = emptyMap()): JsonObject {
        val url = metadataOrigin.resolve(path)!!.newBuilder().addQueryParameter("api_key", apiKey())
            .apply { parameters.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return JsonParser.parseString(network.text(url, "https://cinejoy.pk/")).asJsonObject
    }
    private fun title(row: JsonObject, type: MediaType) = Title(row.string("id"),
        row.text(if (type == MediaType.MOVIE) "title" else "name"), image(row.text("poster_path"), "w500"),
        image(row.text("backdrop_path"), "w1280"), row.text("vote_average"), row.text("overview"),
        row.text(if (type == MediaType.MOVIE) "release_date" else "first_air_date"), type, ID, type.apiValue)
    private fun episodesFrom(data: JsonObject, show: TitleRef, season: Int): List<Episode> {
        if (data.get("season_number")?.asInt != season) throw IOException("Unexpected season")
        return data.getAsJsonArray("episodes")?.mapNotNull { entry ->
            val row = entry.asJsonObject
            val number = row.get("episode_number")?.asInt ?: return@mapNotNull null
            if (number < 1 || !aired(row.text("air_date"))) return@mapNotNull null
            require(row.get("season_number")?.asInt == season) { "Unexpected episode season" }
            Episode(PlayableRef(show, row.string("id"), season, number), row.text("name"), row.text("overview"),
                image(row.text("still_path"), "w780"))
        }?.sortedBy { it.number } ?: throw IOException("Cinejoy episodes unavailable")
    }
    private fun owned(ref: TitleRef) { require(ref.siteId == ID && ref.id.matches(Regex("[0-9]+"))) { "Invalid Cinejoy title" } }
    private fun imdb(row: JsonObject) = (row.getAsJsonObject("external_ids")?.text("imdb_id")?.takeIf { it.isNotBlank() }
        ?: row.text("imdb_id")).takeIf { it.matches(Regex("tt[0-9]+")) }
    private fun image(path: String, size: String) = if (path.startsWith("/") && !path.startsWith("//")) "https://image.tmdb.org/t/p/$size$path" else ""
    private fun aired(date: String) = date.isNotBlank() && runCatching { !LocalDate.parse(date).isAfter(LocalDate.now(ZoneOffset.UTC)) }.getOrDefault(false)
    private fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }
    companion object { const val ID = "cinejoy" }
}
