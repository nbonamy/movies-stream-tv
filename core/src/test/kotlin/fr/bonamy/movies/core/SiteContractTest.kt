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
        assertEquals(listOf(BrowseMenuItem.Section(SiteSection("movie", "Movies", MediaType.MOVIE)), BrowseMenuItem.Section(SiteSection("tv", "TV Shows", MediaType.TV)),
            BrowseMenuItem.Divider, BrowseMenuItem.Site(one.descriptor), BrowseMenuItem.Site(three.descriptor)), registry.menu(two))
        assertEquals(listOf(BrowseMenuItem.Section(SiteSection("movie", "Movies", MediaType.MOVIE)), BrowseMenuItem.Divider,
            BrowseMenuItem.Site(one.descriptor), BrowseMenuItem.Site(two.descriptor)), registry.menu(three))
        assertEquals(listOf(BrowseMenuItem.Section(SiteSection("movie", "Movies", MediaType.MOVIE))), SiteRegistry(listOf(three)).menu(three))
    }

    @Test fun searchesAndPaginatesOnlyTheCurrentSiteAndQueryDespiteLateResponses() = runBlocking {
        val oldResponse = CompletableDeferred<CatalogPage>()
        val old = TestSite("one") { _, _ -> oldResponse.await() }
        val requests = mutableListOf<Pair<CatalogRequest, PageToken?>>()
        val query = CatalogRequest("movie", "new search")
        val token = PageToken("two", query, "opaque/next?cursor=abc")
        val second = TestSite("two") { request, after ->
            requests += request to after
            if (after == null) CatalogPage(listOf(title("two", "same/slug")), token)
            else CatalogPage(listOf(title("two", "same/slug"), title("two", "other/slug")), null)
        }
        val browser = CatalogBrowser(old, CatalogRequest("movie", "old search"))
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

    @Test fun arbitrarySectionsOfTheSameMediaTypeOwnTheirLabelsAndRejectCrossSectionResults() = runBlocking<Unit> {
        val site = object : StreamingSite by TestSite("custom") {
            override val descriptor = SiteDescriptor("custom", "Custom", listOf(
                SiteSection("films", "À l'affiche", MediaType.MOVIE),
                SiteSection("stage", "Spectacles", MediaType.MOVIE),
                SiteSection("classics", "Les classiques", MediaType.MOVIE)))
            override suspend fun browse(request: CatalogRequest, after: PageToken?) =
                CatalogPage(listOf(title("custom", "42").copy(sectionId = "films")), null)
        }
        val menu = SiteRegistry(listOf(site)).menu(site)
        assertEquals(listOf("À l'affiche", "Spectacles", "Les classiques"),
            menu.map { (it as BrowseMenuItem.Section).section.title })
        val browser = CatalogBrowser(site, CatalogRequest("stage"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { browser.loadNext() } }
        browser.reset(site, CatalogRequest("films"))
        assertEquals("42", browser.loadNext()!!.single().id)
        browser.reset(site, CatalogRequest("stage", "search"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { browser.loadNext() } }
        val unified = object : StreamingSite by site {
            override val descriptor = site.descriptor.copy(searchScope = SearchScope.SITE)
        }
        browser.reset(unified, CatalogRequest("stage", "search"))
        assertEquals("films", browser.loadNext()!!.single().sectionId)
        browser.reset(unified, CatalogRequest("stage"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { browser.loadNext() } }
    }

    @Test fun anEditInvalidatesResultsBeforeTheNextDebouncedSearchStarts() = runBlocking {
        val response = CompletableDeferred<CatalogPage>()
        val site = TestSite("one") { _, _ -> response.await() }
        val browser = CatalogBrowser(site, CatalogRequest("movie", "first"))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { browser.loadNext() }
        browser.invalidate()
        response.complete(CatalogPage(listOf(title("one", "stale")), null))
        assertNull(pending.await())
        assertTrue(browser.items.isEmpty())
    }

    private fun title(site: String, id: String) = Title(id, "A title", "", "", "", "", "", siteId = site)

    private class TestSite(id: String, types: List<MediaType> = listOf(MediaType.MOVIE),
        val load: suspend (CatalogRequest, PageToken?) -> CatalogPage = { _, _ -> error("Unexpected browse") }) : StreamingSite {
        override val descriptor = SiteDescriptor(id, id, types.map { SiteSection(it.apiValue, it.label, it) })
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
