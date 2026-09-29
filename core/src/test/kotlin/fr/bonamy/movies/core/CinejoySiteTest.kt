package fr.bonamy.movies.core

import fr.bonamy.movies.core.sites.cinejoy.CinejoySite
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CinejoySiteTest {
    @Test fun `site-wide search preserves media identity and continuation through filtered pages`() = runBlocking {
        MockWebServer().use { server ->
            val site = CinejoySite(HttpTransport(allowLoopback = true), { "fixture" }, server.url("/3/"))
            server.enqueue(MockResponse().setBody("""{"page":1,"total_pages":3,"results":[{"id":4,"media_type":"person"}]}"""))
            server.enqueue(MockResponse().setBody("""{"page":2,"total_pages":3,"results":[
              {"id":348,"media_type":"movie","title":"Alien","poster_path":"/movie.jpg"},
              {"id":12,"media_type":"tv","name":"Alien TV","poster_path":"/tv.jpg"},
              {"id":13,"media_type":"movie","title":"Filtered","poster_path":"/x.jpg","adult":true}]}"""))
            val request = CatalogRequest("movie", "alien")
            val first = site.browse(request)
            assertTrue(first.items.isEmpty()); assertNotNull(first.next)
            val second = site.browse(request, first.next)
            assertEquals(listOf("movie", "tv"), second.items.map { it.sectionId })
            assertEquals(listOf(MediaType.MOVIE, MediaType.TV), second.items.map { it.type })
            assertTrue(second.items.all { it.siteId == "cinejoy" })
            assertEquals("3", second.next?.value)
            val sent = server.takeRequest().requestUrl!!
            assertEquals("/3/search/multi", sent.encodedPath)
            assertEquals("alien", sent.queryParameter("query"))
            assertEquals("false", sent.queryParameter("include_adult"))
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("page"))
            try { site.browse(CatalogRequest("tv", "alien"), first.next); fail("Foreign cursor accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `series keeps opaque episode identities and refuses mismatched seasons`() = runBlocking {
        MockWebServer().use { server ->
            val site = CinejoySite(HttpTransport(allowLoopback = true), { "fixture" }, server.url("/3/"))
            val show = TitleRef("cinejoy", "108978", MediaType.TV)
            server.enqueue(MockResponse().setBody("""{"seasons":[
              {"id":22,"season_number":2,"episode_count":2,"name":"Season 2","air_date":"2023-01-01"},
              {"id":99,"season_number":3,"episode_count":2,"name":"Future","air_date":"2999-01-01"},
              {"id":11,"season_number":1,"episode_count":2,"name":"Season 1","air_date":"2022-01-01"}]}"""))
            val seasons = site.series.seasons(show)
            assertEquals(listOf(1, 2), seasons.map { it.number })
            server.enqueue(MockResponse().setBody("""{"id":22,"season_number":2,"episodes":[
              {"id":4901211,"season_number":2,"episode_number":3,"name":"Three","air_date":"2023-01-01"},
              {"id":4901210,"season_number":2,"episode_number":2,"name":"Two","air_date":"2023-01-01"},
              {"id":4999999,"season_number":2,"episode_number":4,"name":"Future","air_date":"2999-01-01"}]}"""))
            val episodes = site.series.episodes(seasons[1].ref)
            assertEquals(listOf("4901210", "4901211"), episodes.map { it.target.episodeId })
            assertEquals(listOf(2, 3), episodes.map { it.number })
            assertTrue(episodes.all { it.target.title == show && it.season == 2 })
            server.enqueue(MockResponse().setBody("""{"id":999,"season_number":2,"episodes":[]}"""))
            try { site.series.episodes(seasons[1].ref); fail("Wrong season accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
