package fr.bonamy.movies.core.sites.movies123

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.bonamy.movies.core.*
import fr.bonamy.movies.core.extractors.PloyanExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/** Season slugs are opaque references; their numeric suffix is NOT the playback ID. */
class Movies123Site internal constructor(http: OkHttpClient, private val home: HttpUrl) : StreamingSite {
    constructor() : this(HttpTransport.defaultClient(), "https://ww8.123moviesfree.net/".toHttpUrl())
    override val descriptor = SiteDescriptor(ID, "123Movies", listOf(
        SiteSection("movie", "Movies", MediaType.MOVIE), SiteSection("tv", "TV Shows", MediaType.TV),
    ), SearchScope.SITE)
    private val network = HttpTransport(http, home.host in listOf("localhost", "127.0.0.1"))
    private val extractor = PloyanExtractor(network)
    override val series: SeriesCatalog = object : SeriesCatalog {
        override suspend fun seasons(show: TitleRef) = io { seasonList(show) }
        override suspend fun episodes(season: SeasonRef) = io { episodeList(season) }
    }

    override suspend fun browse(request: CatalogRequest, after: PageToken?): CatalogPage = io {
        descriptor.section(request.sectionId)
        require(after == null || after.siteId == ID && after.request == request) { "Page belongs to another catalog" }
        val query = request.query?.trim()?.takeIf(String::isNotEmpty)
        if (query != null) {
            val offset = after?.value?.toInt() ?: 0
            require(offset >= 0)
            val result = search(query, offset)
            CatalogPage(result.rows.mapNotNull(::searchTitle).distinctBy { it.ref },
                if (result.more) PageToken(ID, request, (offset + result.rows.size).toString()) else null)
        } else {
            val path = if (request.sectionId == "movie") "/movies/" else "/tv-series/"
            val url = owned(after?.value ?: path)
            require(url.encodedPath.startsWith(path) && url.query == null) { "Invalid catalog page" }
            val page = page(url)
            val items = page.select(".list-movie a.poster[href]").mapNotNull { card ->
                val link = owned(card.attr("href"))
                val slug = link.pathSegments.filter(String::isNotEmpty).lastOrNull() ?: return@mapNotNull null
                val type = if (link.encodedPath.startsWith("/season/")) MediaType.TV else MediaType.MOVIE
                require(type.apiValue == request.sectionId)
                title(slug, card.selectFirst("h2")?.text() ?: card.selectFirst("img")?.attr("alt").orEmpty(), type,
                    poster = card.selectFirst("img")?.absUrl("data-src").orEmpty())
            }.distinctBy { it.ref }
            if (items.isEmpty()) throw IOException("123Movies returned no catalog cards")
            val next = page.selectFirst("a[aria-label=Next]")?.attr("href")?.takeIf(String::isNotBlank)?.let { href ->
                val nextUrl = owned(href)
                require(nextUrl.encodedPath.startsWith(path) && nextUrl.query == null && nextUrl != url)
                PageToken(ID, request, nextUrl.encodedPath)
            }
            CatalogPage(items, next)
        }
    }

    override suspend fun details(title: Title): Title = io {
        requireOwned(title.ref)
        val slug = if (title.type == MediaType.TV) seasonList(title.ref).firstOrNull()?.ref?.id
            ?: throw IOException("No seasons found") else title.id
        val page = detail(slug, title.type)
        fun field(label: String) = page.select("p").firstOrNull { it.selectFirst("strong")?.text() == "$label:" }
            ?.text()?.substringAfter(':')?.trim().orEmpty()
        title.copy(
            overview = page.selectFirst("h1 + .fst-italic")?.text().orEmpty(),
            backdrop = page.selectFirst("#play-now img")?.absUrl("data-src").orEmpty().ifBlank { title.backdrop },
            rating = field("IMDb").substringBefore('/'),
            releaseDate = field("Release"),
        )
    }

    override suspend fun sources(item: PlayableRef): PlaybackOptions = io {
        val page = playbackPage(item)
        if (page.selectFirst("button#srv-1") == null) throw IOException("Server 1 is unavailable")
        PlaybackOptions(listOf(SOURCE), SOURCE.id)
    }

    override suspend fun resolve(item: PlayableRef, sourceId: String?): ResolvedPlayback = io {
        require(sourceId == null || sourceId == SOURCE.id) { "Unsupported 123Movies source" }
        val page = playbackPage(item)
        val mid = page.selectFirst("#mid")?.attr("data-mid")?.takeIf { it.matches(NUMBER) }
            ?: throw IOException("Missing 123Movies playback identity")
        extractor.resolve(mid, item.episode ?: 1)
    }

    private fun playbackPage(item: PlayableRef): Document {
        requireOwned(item.title)
        if (item.type == MediaType.MOVIE) return detail(item.id, item.type)
        val parts = item.episodeId!!.split('/')
        require(parts.size == 2 && parts[1].toIntOrNull() == item.episode && (item.episode ?: 0) > 0)
        val info = seasonSlug(parts[0])
        require(info.first == item.id && info.second == item.season) { "Episode belongs to another show" }
        val page = detail(parts[0], MediaType.TV)
        if (page.selectFirst("#eps-list button#ep-${item.episode}") == null) throw IOException("Episode unavailable")
        return page
    }

    private fun seasonList(show: TitleRef): List<Season> {
        requireOwned(show)
        require(show.type == MediaType.TV)
        val seasons = linkedMapOf<String, Season>()
        var offset = 0
        // Search is broad; require the exact slug stem and reject ambiguous season numbers.
        repeat(20) {
            val found = search(show.id.replace('-', ' '), offset)
            found.rows.filter { it.get("d")?.asString == "s" }.forEach { row ->
                val slug = row.string("s")
                val info = runCatching { seasonSlug(slug) }.getOrNull() ?: return@forEach
                if (info.first == show.id) {
                    val number = row.get("n")?.asInt ?: info.second
                    if (number != info.second) throw IOException("Conflicting season identity")
                    seasons[slug] = Season(SeasonRef(show, slug, number), "Season $number", row.get("e")?.asInt ?: 0)
                }
            }
            if (!found.more) {
                val result = seasons.values.sortedBy { it.number }
                if (result.groupBy { it.number }.any { it.value.size > 1 }) throw IOException("Ambiguous seasons for this show")
                if (result.isEmpty()) throw IOException("No seasons found for this show")
                return result
            }
            offset += found.rows.size
        }
        throw IOException("Too many season search results")
    }

    private fun episodeList(season: SeasonRef): List<Episode> {
        requireOwned(season.show)
        val info = seasonSlug(season.id)
        require(season.show.type == MediaType.TV && info.first == season.show.id && info.second == season.number)
        val page = detail(season.id, MediaType.TV)
        val image = page.selectFirst("#play-now img")?.absUrl("data-src").orEmpty()
        return page.select("#eps-list button.episode[id]").mapNotNull { button ->
            val number = button.id().removePrefix("ep-").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            Episode(PlayableRef(season.show, "${season.id}/$number", season.number, number),
                "Episode $number", "", image)
        }.distinctBy { it.target }.sortedBy { it.number }.also {
            if (it.isEmpty()) throw IOException("No episodes listed for this season")
        }
    }

    private data class SearchPage(val rows: List<JsonObject>, val more: Boolean)
    private fun search(query: String, offset: Int): SearchPage {
        val url = home.resolve("searching")!!.newBuilder().addQueryParameter("q", query)
            .addQueryParameter("limit", "40").addQueryParameter("offset", offset.toString()).build()
        val data = JsonParser.parseString(network.text(url, home.toString())).asJsonObject
        val rows = data.getAsJsonArray("data")?.map { it.asJsonObject } ?: throw IOException("Search unavailable")
        val total = data.getAsJsonObject("meta")?.get("total_items")?.asInt ?: throw IOException("Search pagination unavailable")
        return SearchPage(rows, rows.isNotEmpty() && offset + rows.size < total)
    }

    private fun searchTitle(row: JsonObject): Title? {
        val type = when (row.get("d")?.asString) { "m" -> MediaType.MOVIE; "s" -> MediaType.TV; else -> return null }
        return title(row.string("s"), row.string("t"), type, row.get("y")?.asString.orEmpty())
    }

    private fun title(slug: String, name: String, type: MediaType, year: String = "", poster: String = ""): Title {
        require(slug.matches(SLUG))
        val id = if (type == MediaType.TV) seasonSlug(slug).first else slug
        return Title(id, if (type == MediaType.TV) name.replace(SEASON_NAME, "").trim() else name,
            poster.ifBlank { "$IMAGES/thumb/w_200/h_300/$slug.jpg" },
            "$IMAGES/cover/w_1200/h_500/$slug.jpg", "", "", year, type, ID)
    }

    private fun seasonSlug(slug: String): Pair<String, Int> {
        val match = SEASON_SLUG.matchEntire(slug) ?: throw IOException("Unrecognized season reference")
        return match.groupValues[1] to match.groupValues[2].toInt()
    }
    private fun requireOwned(ref: TitleRef) {
        require(ref.siteId == ID && ref.id.matches(SLUG)) { "Invalid 123Movies title" }
    }
    private fun detail(slug: String, type: MediaType): Document {
        require(slug.matches(SLUG))
        val page = page(home.resolve("${if (type == MediaType.TV) "season" else "movie"}/$slug/")!!)
        if (page.selectFirst("#mid") == null) throw IOException("123Movies title unavailable")
        return page
    }
    private fun owned(path: String): HttpUrl {
        val url = home.resolve(path) ?: throw IOException("Invalid 123Movies URL")
        require(url.scheme == home.scheme && url.host == home.host && url.port == home.port && url.fragment == null)
        return url
    }
    private fun page(url: HttpUrl) = Jsoup.parse(network.text(url, home.toString()), url.toString())
    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }

    companion object {
        const val ID = "123movies"
        private val SOURCE = PlaybackSource("1", "Server 1")
        private val SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val NUMBER = Regex("[0-9]+")
        private val SEASON_SLUG = Regex("(.+)-season-([0-9]+)-[0-9]+")
        private val SEASON_NAME = Regex("\\s*-\\s*Season\\s+[0-9]+$", RegexOption.IGNORE_CASE)
        private const val IMAGES = "https://img.icdn.my.id"
    }
}
