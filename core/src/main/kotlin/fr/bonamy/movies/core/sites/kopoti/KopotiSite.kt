package fr.bonamy.movies.core.sites.kopoti

import com.google.gson.JsonParser
import fr.bonamy.movies.core.*
import fr.bonamy.movies.core.extractors.ShareCloudyExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

class KopotiSite internal constructor(
    http: OkHttpClient,
    private val home: HttpUrl,
) : StreamingSite {
    constructor() : this(HttpTransport.defaultClient(), "https://kopoti.com/dumq33m3aa/home/kopoti/".toHttpUrl())
    override val descriptor = SiteDescriptor(ID, "Kopoti", listOf(
        SiteSection("films", "À l'affiche", MediaType.MOVIE),
        SiteSection("spectacles", "Spectacles", MediaType.MOVIE),
    ), SearchScope.SITE)
    override val series: SeriesCatalog? = null
    private val folder = home.pathSegments.first()
    private val prefix = "/$folder/b/kopoti/"
    private val transport = HttpTransport(http.newBuilder().cookieJar(KopotiCookies()).build(), allowLoopback = home.host in listOf("localhost", "127.0.0.1"))
    private val extractor = ShareCloudyExtractor(transport)

    override suspend fun browse(request: CatalogRequest, after: PageToken?): CatalogPage = runInterruptible(Dispatchers.IO) {
        descriptor.section(request.sectionId)
        require(after == null || after.siteId == ID && after.request == request) { "Page belongs to another catalog" }
        val offset = after?.value?.toInt() ?: 0
        require(offset >= 0)
        val query = request.query?.trim()?.takeIf { it.isNotEmpty() }
        // Kopoti's website search covers all categories, independent of the home section.
        val endpoint = if (query == null) "api_category.php" else "api_search.php"
        val url = home.resolve("/$folder/$endpoint")!!.newBuilder()
            .apply {
                if (query == null) addQueryParameter("catid", if (request.sectionId == "films") "29" else "3")
                else addQueryParameter("searchword", query)
            }
            .addQueryParameter("offset", offset.toString()).addQueryParameter("limit", "20")
            .addQueryParameter("folder", folder).addQueryParameter("pr", "kopoti").build()
        val data = JsonParser.parseString(transport.text(url, home.toString())).asJsonObject
        val films = data.getAsJsonArray("films") ?: throw IOException("Kopoti catalog unavailable")
        val items = films.map { entry ->
            val film = entry.asJsonObject
            val link = ownedUrl(film.string("link"))
            val rawTitle = Jsoup.parseBodyFragment(film.string("title")).text()
            val year = YEAR.find(rawTitle)
            val poster = film.get("poster")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            Title(link.encodedPath.removePrefix(prefix), rawTitle.replace(YEAR, "").trim(), poster, poster,
                "", "", year?.groupValues?.get(1).orEmpty(), MediaType.MOVIE, ID,
                if (query == null) request.sectionId
                else if (film.string("cat").equals("Spectacle", ignoreCase = true)) "spectacles" else "films")
        }
        val more = data.get("hasMore")?.asBoolean == true && films.size() > 0
        CatalogPage(items,
            if (more) PageToken(ID, request, (offset + films.size()).toString()) else null)
    }

    override suspend fun details(title: Title): Title = runInterruptible(Dispatchers.IO) {
        requireOwned(title.ref)
        val page = page(title.id)
        title.copy(overview = page.selectFirst(".film-detail-synopsis")?.text().orEmpty())
    }

    override suspend fun sources(item: PlayableRef): PlaybackOptions = runInterruptible(Dispatchers.IO) {
        requireOwned(item.title)
        player(page(item.id)) // Do not advertise an unsupported host as playable.
        PlaybackOptions(listOf(SOURCE), SOURCE.id)
    }

    override suspend fun resolve(item: PlayableRef, sourceId: String?): ResolvedPlayback = runInterruptible(Dispatchers.IO) {
        requireOwned(item.title)
        require(sourceId == null || sourceId == SOURCE.id) { "Unknown Kopoti source" }
        extractor.resolve(player(page(item.id)))
    }

    private fun requireOwned(ref: TitleRef) {
        require(ref.siteId == ID && ref.type == MediaType.MOVIE) { "Title belongs to another site or media type" }
    }
    private fun ownedUrl(path: String): HttpUrl {
        val url = home.resolve(path) ?: throw IOException("Invalid Kopoti title URL")
        require(url.scheme == home.scheme && url.host == home.host && url.port == home.port &&
            url.encodedPath.startsWith(prefix) && url.encodedPath.removePrefix(prefix).matches(Regex("[0-9]+")))
        return url
    }
    private fun page(id: String): Document {
        require(id.matches(Regex("[0-9]+")))
        val url = ownedUrl(prefix + id)
        return Jsoup.parse(transport.text(url, home.toString()), url.toString())
    }
    private fun player(page: Document): HttpUrl {
        val url = page.selectFirst(".film-player iframe[src]")?.absUrl("src")?.toHttpUrl()
            ?: throw IOException("Kopoti player unavailable")
        if (!extractor.supports(url)) throw IOException("Unsupported Kopoti playback host")
        return url
    }

    companion object {
        const val ID = "kopoti"
        private val YEAR = Regex("\\s*\\((\\d{4})\\)")
        private val SOURCE = PlaybackSource("sharecloudy", "ShareCloudy")
    }
}

/** Provider session only; cookies are never persisted or shared with another site. */
private class KopotiCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.removeAll { old -> old.expiresAt <= now || received.any {
            it.name == old.name && it.domain == old.domain && it.path == old.path
        } }
        cookies.addAll(received.filter { it.expiresAt > now })
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}
