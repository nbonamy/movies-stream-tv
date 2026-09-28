package fr.bonamy.movies.core.sites.vidbox

import fr.bonamy.movies.core.*
import fr.bonamy.movies.core.extractors.*

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale

internal class VidboxClient(
    private val http: OkHttpClient = HttpTransport.defaultClient(),
    private val catalogOrigin: HttpUrl = "https://vidbox.vc/".toHttpUrl(),
    private val playerOrigin: HttpUrl = "https://vixsrc.to/".toHttpUrl(),
    private val alternativeOrigin: HttpUrl = "https://vidrock.net/".toHttpUrl(),
    private val maxOrigin: HttpUrl = "https://ythd.org/".toHttpUrl(),
) {
    private val network = HttpTransport(http, listOf(catalogOrigin, playerOrigin, alternativeOrigin, maxOrigin)
        .any { it.host in listOf("localhost", "127.0.0.1") })
    private val max = MaxExtractor(network, maxOrigin)
    private val vidpro = VidproExtractor(network, playerOrigin)
    private val rock = VidRockExtractor(network, alternativeOrigin)

    fun browse(type: MediaType, page: Int = 1, query: String? = null): VidboxPage =
        discover(type, page, query?.trim()?.takeIf(String::isNotEmpty))

    private val series by lazy { VidboxSeriesCatalog(network, catalogOrigin) }
    fun seasons(id: String): List<Season> = series.seasons(id)
    fun episodes(id: String, season: Int): List<Episode> = series.episodes(id, season)

    fun sources(target: TmdbPlayback): List<PlaybackSource> {
        val alternatives = runCatching { rock.alternativeEntries(target) }.getOrDefault(emptyMap())
        return buildList {
            addAll(BUILT_IN_SOURCES)
            alternatives.forEach { (name, item) ->
                if (item.get("url")?.takeUnless { it.isJsonNull }?.asString?.isNotBlank() == true &&
                    item.get("type")?.takeUnless { it.isJsonNull }?.asString == "hls") {
                    add(PlaybackSource("vidrock:$name", "VidRock • $name"))
                }
            }
        }
    }

    fun resolve(target: TmdbPlayback, sourceId: String = DEFAULT_SOURCE.id): ResolvedPlayback {
        return when (sourceId) {
            DEFAULT_SOURCE.id -> max.resolve(target)
            VIDPRO_SOURCE.id -> vidpro.resolve(target)
            else -> rock.resolve(target to sourceId)
        }
    }

    private fun discover(type: MediaType, page: Int, query: String?): VidboxPage {
        require(page in 1..500)
        val url = catalogOrigin.resolve("api/search/discover")!!.newBuilder()
            .addQueryParameter("type", type.apiValue)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "popularity")
            .addQueryParameter("now_playing", "false")
            .addQueryParameter("trending", "false")
            .apply {
                listOf("country", "genre", "rating", "watch_provider", "year")
                    .forEach { addQueryParameter(it, "all") }
                if (query != null) addQueryParameter("q", query)
            }.build()
        val response = JsonParser.parseString(network.text(url, referer = catalogOrigin.toString())).asJsonObject
        val results = response.getAsJsonArray("results") ?: return VidboxPage(emptyList(), page, 1)
        val totalPages = (response.get("total_pages")?.asInt ?: 1).coerceIn(1, 500)
        val movies = results.mapNotNull { element ->
            val item = element.asJsonObject
            val id = item.get("id")?.asString.orEmpty()
            val title = item.get(if (type == MediaType.TV) "name" else "title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val posterPath = item.get("poster_path")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (id.isEmpty() || !id.all(Char::isDigit) || title.isBlank() || !posterPath.startsWith("/")) return@mapNotNull null
            Title(
                id = id,
                title = title,
                poster = "https://image.tmdb.org/t/p/w500$posterPath",
                backdrop = item.imageUrl("backdrop_path", "original"),
                rating = item.get("vote_average")?.takeUnless { it.isJsonNull }?.asDouble
                    ?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                overview = item.get("overview")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                releaseDate = item.get(if (type == MediaType.TV) "first_air_date" else "release_date")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                type = type,
                siteId = VidboxSite.ID,
            )
        }.distinctBy(Title::id)
        return VidboxPage(movies, page, totalPages)
    }

    private fun JsonObject.imageUrl(name: String, size: String): String {
        val path = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        return if (path.startsWith("/")) "https://image.tmdb.org/t/p/$size$path" else ""
    }

    companion object {
        val DEFAULT_SOURCE = PlaybackSource("max", "Max")
        val VIDPRO_SOURCE = PlaybackSource("vidpro", "Vidpro")
        val BUILT_IN_SOURCES = listOf(DEFAULT_SOURCE, VIDPRO_SOURCE)
    }
}

internal data class VidboxPage(val items: List<Title>, val page: Int, val totalPages: Int)
