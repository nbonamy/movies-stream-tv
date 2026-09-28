package fr.bonamy.movies.core

import fr.bonamy.movies.core.sites.kopoti.KopotiSite
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class KopotiSiteTest {
    @Test fun categoriesKeepTheirOwnPaginationWhileSearchReturnsAllCategories() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val site = KopotiSite(OkHttpClient(), server.url("/folder/home/kopoti/"))
            server.enqueue(json("Other", "10", true))
            val films = site.browse(CatalogRequest("films"))
            assertEquals("29", server.takeRequest().requestUrl!!.queryParameter("catid"))
            assertEquals("films", films.items.single().sectionId)
            server.enqueue(json("Foresti (2026)", "11", true))
            val query = CatalogRequest("films", "foresti")
            val first = site.browse(query)
            assertEquals("films", first.items.single().sectionId)
            server.enqueue(json("Florence Foresti &amp; friends (2026)", "12", false, "Spectacle"))
            val shows = site.browse(query, first.next)
            assertEquals("Florence Foresti & friends", shows.items.single().title)
            assertEquals("2026", shows.items.single().releaseDate)
            assertEquals("spectacles", shows.items.single().sectionId)
            assertEquals(MediaType.MOVIE, shows.items.single().type)
            assertNull(shows.next)
            val search = server.takeRequest().requestUrl!!
            assertEquals("/folder/api_search.php", search.encodedPath)
            assertEquals("foresti", search.queryParameter("searchword"))
            assertNull(search.queryParameter("catid"))
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                site.browse(CatalogRequest("spectacles"), films.next)
            } }
            server.enqueue(json("Next", "13", false))
            assertEquals("13", site.browse(CatalogRequest("films"), films.next).items.single().id)
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
        }
    }

    @Test fun filmSearchFindsOlderTitlesOutsideTheFeaturedCategory() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.requestUrl!!.encodedPath == "/folder/api_search.php")
                        json("Tout le bleu du ciel (2025)", "591572817", false)
                    else MockResponse().setBody("""{"films":[],"hasMore":false}""")
            }
            val site = KopotiSite(OkHttpClient(), server.url("/folder/home/kopoti/"))
            val results = site.browse(CatalogRequest("films", "tout le bleu"))
            assertEquals("Tout le bleu du ciel", results.items.single().title)
            assertEquals("films", results.items.single().sectionId)
            assertEquals("tout le bleu", server.takeRequest().requestUrl!!.queryParameter("searchword"))
        }
    }

    @Test fun detailsAndFreshPlayerResolutionPreserveSectionAndPlaybackHeaders() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.url.host == "sharecloudy.com") request.newBuilder()
                    .url(server.url(request.url.encodedPath)).build() else request)
            }.build()
            val site = KopotiSite(http, server.url("/folder/home/kopoti/"))
            val title = Title("42", "A show", "", "", "", "", "", siteId = "kopoti", sectionId = "spectacles")
            fun detail() = MockResponse().setBody("""<div class="film-detail-synopsis">A &amp; B</div>
                <div class="film-player"><iframe src="https://sharecloudy.com/iframe/abc123"></iframe></div>""")
            server.enqueue(detail())
            assertEquals(title.copy(overview = "A & B"), site.details(title))
            server.enqueue(detail())
            val options = site.sources(PlayableRef(title.ref))
            server.enqueue(detail())
            server.enqueue(MockResponse().setBody("""<script>jwplayer("player").setup({playlist:[{sources:[{
                file:"https://cdn.example/stream.m3u8?token=fresh",label:"HD"}]}]});</script>"""))
            val playback = site.resolve(PlayableRef(title.ref), options.defaultSourceId)
            assertEquals("https://cdn.example/stream.m3u8?token=fresh", playback.streamUrl)
            assertEquals(MediaFormat.HLS, playback.format)
            assertEquals("https://sharecloudy.com/iframe/abc123", playback.requestHeaders["Referer"])
            assertEquals("https://sharecloudy.com", playback.requestHeaders["Origin"])
            assertNull(playback.subtitleContext)
            assertEquals(4, server.requestCount)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { site.resolve(PlayableRef(title.ref), "unknown") } }
            server.enqueue(MockResponse().setBody("""<div class="film-player"><iframe src="https://unknown.example/embed"></iframe></div>"""))
            assertThrows(java.io.IOException::class.java) { runBlocking { site.sources(PlayableRef(title.ref)) } }
        }
    }
    @Test fun retainsProviderCookieAcrossSelfRedirectsAndLaterRequests() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.getHeader("Cookie") != "g=true") {
                        MockResponse().setResponseCode(302).setHeader("Location", request.path!!)
                            .setHeader("Set-Cookie", "g=true; Path=/; Max-Age=31536000")
                    } else MockResponse().setBody("""<div class="film-detail-synopsis">Cookie accepted</div>
                        <div class="film-player"><iframe src="https://sharecloudy.com/iframe/cookiecheck"></iframe></div>""")
            }
            val site = KopotiSite(OkHttpClient(), server.url("/folder/home/kopoti/"))
            val title = Title("42", "Toy story", "", "", "", "", "", siteId = "kopoti", sectionId = "films")
            assertEquals("Cookie accepted", site.details(title).overview)
            assertEquals("sharecloudy", site.sources(PlayableRef(title.ref)).defaultSourceId)
            assertEquals(3, server.requestCount)
        }
    }

    private fun json(title: String, id: String, more: Boolean, category: String = "Drame") = MockResponse().setBody("""
        {"films":[{"id":"99","title":"$title","poster":"https://images.example/poster.jpg",
        "link":"/folder/b/kopoti/$id","cat":"$category"}],"hasMore":$more}
    """)
}
