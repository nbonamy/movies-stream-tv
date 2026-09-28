package fr.bonamy.movies.core

import fr.bonamy.movies.core.sites.kopoti.KopotiSite
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class KopotiSiteTest {
    @Test fun categoriesKeepTheirOwnPaginationAndSearchScansPastNonmatchingPages() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val site = KopotiSite(OkHttpClient(), server.url("/folder/home/kopoti/"))
            server.enqueue(json("Other", "10", true))
            val films = site.browse(CatalogRequest("films"))
            assertEquals("29", server.takeRequest().requestUrl!!.queryParameter("catid"))
            assertEquals("films", films.items.single().sectionId)
            server.enqueue(json("Unrelated", "11", true))
            server.enqueue(json("Inès &amp; friends (2026)", "12", false))
            val shows = site.browse(CatalogRequest("spectacles", "ines"))
            assertEquals("Inès & friends", shows.items.single().title)
            assertEquals("2026", shows.items.single().releaseDate)
            assertEquals("spectacles", shows.items.single().sectionId)
            assertEquals(MediaType.MOVIE, shows.items.single().type)
            assertNull(shows.next)
            assertEquals("3", server.takeRequest().requestUrl!!.queryParameter("catid"))
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                site.browse(CatalogRequest("spectacles"), films.next)
            } }
            server.enqueue(json("Next", "13", false))
            assertEquals("13", site.browse(CatalogRequest("films"), films.next).items.single().id)
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
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
    private fun json(title: String, id: String, more: Boolean) = MockResponse().setBody("""
        {"films":[{"id":"99","title":"$title","poster":"https://images.example/poster.jpg",
        "link":"/folder/b/kopoti/$id"}],"hasMore":$more}
    """)
}
