package fr.bonamy.movies.core

import fr.bonamy.movies.core.sites.vidbox.*
import fr.bonamy.movies.core.extractors.TmdbPlayback

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CatalogTest {
    @Test
    fun vidboxSuppliesNormalizedEpisodeSubtitleMetadata() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"imdb_id":"tt9288030"}}"""))
            val site = VidboxSite(VidboxClient(), server.url("/"))
            val item = PlayableRef(TitleRef(VidboxSite.ID, "108978", MediaType.TV), "2/3", 2, 3)
            assertEquals(SubtitleContext("tt9288030", 2, 3), site.subtitleContext(item))
            assertEquals("/api.php?type=tv&tmdb=108978&season=2&episode=3", server.takeRequest().path)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                site.subtitleContext(item.copy(title = item.title.copy(siteId = "other")))
            } }
        }
    }

    @Test
    fun matchesPopularMovieAndTvPagesAndPreservesSearchAndPagination() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total_pages":750,"results":[{"id":1,"title":"A movie","poster_path":"/movie.jpg","release_date":"2025-01-01"}]}"""))
            server.enqueue(MockResponse().setBody("""{"total_pages":3,"results":[{"id":2,"name":"A show","poster_path":"/tv.jpg","first_air_date":"2024-02-01"}]}"""))
            val client = VidboxSite(VidboxClient(catalogOrigin = server.url("/")))
            val movie = client.browse(CatalogRequest(MediaType.MOVIE))
            val query = CatalogRequest(MediaType.TV, "A show")
            val tv = client.browse(query, PageToken(VidboxSite.ID, query, "2"))
            assertEquals("2", movie.next?.value)
            assertEquals("A movie", movie.items.single().title)
            assertEquals(MediaType.TV, tv.items.single().type)
            assertEquals("A show", tv.items.single().title)
            assertEquals("2024-02-01", tv.items.single().releaseDate)
            assertEquals("3", tv.next?.value)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                client.browse(query, PageToken("another-site", query, "2"))
            } }
            val first = server.takeRequest().requestUrl!!
            assertEquals("movie", first.queryParameter("type"))
            assertEquals("popularity", first.queryParameter("sort"))
            assertEquals("false", first.queryParameter("now_playing"))
            assertEquals("all", first.queryParameter("genre"))
            val second = server.takeRequest().requestUrl!!
            assertEquals("tv", second.queryParameter("type"))
            assertEquals("2", second.queryParameter("page"))
            assertEquals("A show", second.queryParameter("q"))
        }
    }

    @Test
    fun readsActualSeasonNumbersAndLoadsOnlyTheChosenSeason() {
        MockWebServer().use { server ->
            val props = """7:["$","Show",null,{"props":{"id":55,"overview":"${"A".repeat(50_000)}","seasons":[{"season_number":0,"name":"Specials","episode_count":2},{"season_number":2,"name":"Season 2","episode_count":3},{"season_number":5,"name":"Future","episode_count":0}]}}]
"""
            val chunk = Gson().toJson(listOf(1, props))
            server.enqueue(MockResponse().setBody("""<script src="/_next/static/chunks/common.fixture.js"></script><script>self.__next_f.push($chunk)</script>"""))
            server.enqueue(MockResponse().setBody("""let r="https://api.themoviedb.org/3/tv/".concat(e,"/season/").concat(t,"?language=en-US&api_key=").concat("00000000000000000000000000000000");"""))
            server.enqueue(MockResponse().setBody("""{"episodes":[{"season_number":2,"episode_number":3,"name":"Third","overview":"Plot","still_path":"/third.jpg"},{"season_number":2,"episode_number":1,"name":"First"},{"season_number":1,"episode_number":2,"name":"Wrong season"}]}"""))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val rewritten = if (request.url.host == "api.themoviedb.org") request.newBuilder()
                    .url(server.url(request.url.encodedPath + "?" + request.url.encodedQuery)).build() else request
                chain.proceed(rewritten)
            }.build()
            val client = VidboxClient(http = http, catalogOrigin = server.url("/"))
            assertEquals(listOf(0, 2), client.seasons("55").map { it.number })
            val episodes = client.episodes("55", 2)
            assertEquals(listOf(1, 3), episodes.map { it.number })
            assertEquals("https://image.tmdb.org/t/p/w500/third.jpg", episodes.last().still)
            assertEquals("/tv/55", server.takeRequest().path)
            assertEquals("/_next/static/chunks/common.fixture.js", server.takeRequest().path)
            val request = server.takeRequest().requestUrl!!
            assertEquals("/3/tv/55/season/2", request.encodedPath)
            assertEquals("en-US", request.queryParameter("language"))
            assertEquals(3, server.requestCount)
        }
    }
}
