package fr.bonamy.movies.core.sites.vidbox

import fr.bonamy.movies.core.*
import fr.bonamy.movies.core.extractors.*

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class VidboxSite internal constructor(
    private val client: VidboxClient,
    private val metadataOrigin: HttpUrl = "https://data.vidsrc.sh/".toHttpUrl(),
) : StreamingSite {
    constructor() : this(VidboxClient())
    override val descriptor = SiteDescriptor(ID, "Vidbox", listOf(MediaType.MOVIE, MediaType.TV))
    override val series = object : SeriesCatalog {
        override suspend fun seasons(show: TitleRef): List<Season> = runInterruptible(Dispatchers.IO) {
            requireOwned(show)
            require(show.type == MediaType.TV)
            client.seasons(show.id)
        }
        override suspend fun episodes(season: SeasonRef): List<Episode> = runInterruptible(Dispatchers.IO) {
            requireOwned(season.show)
            require(season.show.type == MediaType.TV && season.id.toIntOrNull() == season.number)
            client.episodes(season.show.id, requireNotNull(season.number))
        }
    }

    override suspend fun browse(request: CatalogRequest, after: PageToken?): CatalogPage = runInterruptible(Dispatchers.IO) {
        require(request.type in descriptor.mediaTypes)
        require(after == null || (after.siteId == ID && after.request == request)) { "Page belongs to another catalog" }
        val page = after?.value?.toInt() ?: 1
        val result = client.browse(request.type, page, request.query)
        CatalogPage(result.items, if (result.page < result.totalPages)
            PageToken(ID, request, (result.page + 1).toString()) else null)
    }

    override suspend fun details(title: Title): Title {
        requireOwned(title.ref)
        return title // Vidbox's catalog already includes its detail metadata.
    }

    override suspend fun sources(item: PlayableRef): PlaybackOptions = runInterruptible(Dispatchers.IO) {
        PlaybackOptions(client.sources(target(item)), VidboxClient.DEFAULT_SOURCE.id)
    }

    override suspend fun resolve(item: PlayableRef, sourceId: String?): ResolvedPlayback = runInterruptible(Dispatchers.IO) {
        client.resolve(target(item), sourceId ?: VidboxClient.DEFAULT_SOURCE.id)
    }

    override suspend fun subtitleContext(item: PlayableRef): SubtitleContext? = runInterruptible(Dispatchers.IO) {
        val target = target(item)
        val url = metadataOrigin.resolve("api.php")!!.newBuilder()
            .addQueryParameter("type", target.type.apiValue).addQueryParameter("tmdb", target.id)
            .apply { if (target.type == MediaType.TV) {
                addQueryParameter("season", target.season.toString())
                addQueryParameter("episode", target.episode.toString())
            } }.build()
        val body = HttpTransport(allowLoopback = metadataOrigin.host in listOf("localhost", "127.0.0.1")).text(url)
        val data = JsonParser.parseString(body).asJsonObject.getAsJsonObject("data")
        data?.get("imdb_id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?.let { SubtitleContext(it, target.season, target.episode) }
    }

    private fun target(item: PlayableRef): TmdbPlayback {
        requireOwned(item.title)
        if (item.type == MediaType.TV) require(item.episodeId == "${item.season}/${item.episode}")
        return TmdbPlayback(item.id, item.type, item.season, item.episode)
    }
    private fun requireOwned(ref: TitleRef) { require(ref.siteId == ID) { "Title belongs to another site" } }
    companion object { const val ID = "vidbox" }
}
