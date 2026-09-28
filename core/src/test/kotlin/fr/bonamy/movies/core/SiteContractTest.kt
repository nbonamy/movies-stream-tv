package fr.bonamy.movies.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SiteContractTest {
    @Test fun movieOnlyMenusOmitTvAndTheActiveSite() {
        val one = TestSite("one", listOf(MediaType.MOVIE, MediaType.TV))
        val two = TestSite("two", listOf(MediaType.MOVIE, MediaType.TV))
        val three = TestSite("three", listOf(MediaType.MOVIE))
        val registry = SiteRegistry(listOf(one, two, three))
        assertEquals(listOf(BrowseMenuItem.Mode(MediaType.MOVIE), BrowseMenuItem.Mode(MediaType.TV),
            BrowseMenuItem.Divider, BrowseMenuItem.Site(one.descriptor), BrowseMenuItem.Site(three.descriptor)), registry.menu(two))
        assertEquals(listOf(BrowseMenuItem.Mode(MediaType.MOVIE), BrowseMenuItem.Divider,
            BrowseMenuItem.Site(one.descriptor), BrowseMenuItem.Site(two.descriptor)), registry.menu(three))
        assertEquals(listOf(BrowseMenuItem.Mode(MediaType.MOVIE)), SiteRegistry(listOf(three)).menu(three))
    }

    @Test fun searchesAndPaginatesOnlyTheCurrentSiteAndQueryDespiteLateResponses() = runBlocking {
        val oldResponse = CompletableDeferred<CatalogPage>()
        val old = TestSite("one") { _, _ -> oldResponse.await() }
        val requests = mutableListOf<Pair<CatalogRequest, PageToken?>>()
        val query = CatalogRequest(MediaType.MOVIE, "new search")
        val token = PageToken("two", query, "opaque/next?cursor=abc")
        val second = TestSite("two") { request, after ->
            requests += request to after
            if (after == null) CatalogPage(listOf(title("two", "same/slug")), token)
            else CatalogPage(listOf(title("two", "same/slug"), title("two", "other/slug")), null)
        }
        val browser = CatalogBrowser(old, CatalogRequest(MediaType.MOVIE, "old search"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { browser.loadNext() }
        browser.invalidate() // Edit/switch while the old HTTP request is still in flight.
        browser.reset(second, query)
        assertEquals(listOf(title("two", "same/slug")), browser.loadNext())
        oldResponse.complete(CatalogPage(listOf(title("one", "same/slug")), null))
        assertNull(pending.await())
        assertEquals(listOf(title("two", "other/slug")), browser.loadNext())
        assertEquals(listOf(query to null, query to token), requests)
        assertEquals(listOf("same/slug", "other/slug"), browser.items.map { it.id })
        assertTrue(browser.items.all { it.siteId == "two" })
        assertFalse(browser.canLoadMore)
        assertNull(browser.loadNext())
    }

    @Test fun anEditInvalidatesResultsBeforeTheNextDebouncedSearchStarts() = runBlocking {
        val response = CompletableDeferred<CatalogPage>()
        val site = TestSite("one") { _, _ -> response.await() }
        val browser = CatalogBrowser(site, CatalogRequest(MediaType.MOVIE, "first"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { browser.loadNext() }
        browser.invalidate()
        response.complete(CatalogPage(listOf(title("one", "stale")), null))
        assertNull(pending.await())
        assertTrue(browser.items.isEmpty())
    }

    private fun title(site: String, id: String) = Title(id, "A title", "", "", "", "", "", siteId = site)

    private class TestSite(id: String, types: List<MediaType> = listOf(MediaType.MOVIE),
        val load: suspend (CatalogRequest, PageToken?) -> CatalogPage = { _, _ -> error("Unexpected browse") }) : StreamingSite {
        override val descriptor = SiteDescriptor(id, id, types)
        override val series = if (MediaType.TV in types) object : SeriesCatalog {
            override suspend fun seasons(show: TitleRef): List<Season> = error("Unexpected seasons")
            override suspend fun episodes(season: SeasonRef): List<Episode> = error("Unexpected episodes")
        } else null
        override suspend fun browse(request: CatalogRequest, after: PageToken?) = load(request, after)
        override suspend fun details(title: Title) = title
        override suspend fun sources(item: PlayableRef) = PlaybackOptions(listOf(PlaybackSource("own-server", "Server")), "own-server")
        override suspend fun resolve(item: PlayableRef, sourceId: String?) = error("Unexpected resolve")
    }
}
