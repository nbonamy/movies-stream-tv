package fr.bonamy.movies.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class UniversalSearchTest {
    @Test fun searchesAllSectionsButCallsSiteWideSearchOnlyOnceAndKeepsSiteIdentity() = runBlocking {
        val requests = mutableListOf<Pair<String, CatalogRequest>>()
        val sections = Site("sections", SearchScope.SECTION) { request, _ ->
            requests += "sections" to request
            CatalogPage(listOf(title("sections", request.sectionId, "same-id")), null)
        }
        val unified = Site("unified", SearchScope.SITE) { request, _ ->
            requests += "unified" to request
            CatalogPage(listOf(title("unified", "movie", "same-id"), title("unified", "tv", "same-id")), null)
        }
        val search = UniversalSearch(listOf(sections, unified))
        search.reset(" query ")
        search.loadNext("sections")
        search.loadNext("unified")
        assertEquals(setOf("movie", "tv"), requests.filter { it.first == "sections" }.map { it.second.sectionId }.toSet())
        assertEquals(1, requests.count { it.first == "unified" })
        assertTrue(requests.all { it.second.query == "query" })
        assertEquals(4, search.rows.value.flatMap { it.items }.map { it.ref }.distinct().size)
        assertTrue(search.rows.value.all { !it.canLoadMore && !it.loading && !it.failed })
    }

    @Test fun slowOrFailedSitesDoNotHoldOtherResultsAndRetryKeepsSuccessfulSectionCursor() = runBlocking {
        val slow = CompletableDeferred<CatalogPage>()
        val requests = mutableListOf<Pair<CatalogRequest, PageToken?>>()
        var tvFails = true
        val split = Site("split", SearchScope.SECTION) { request, after ->
            requests += request to after
            if (request.sectionId == "tv" && tvFails) error("Unavailable")
            val item = title("split", request.sectionId, if (after == null) "one" else "two")
            CatalogPage(listOf(item), if (request.sectionId == "movie" && after == null)
                PageToken("split", request, "opaque-next") else null)
        }
        val search = UniversalSearch(listOf(Site("slow", SearchScope.SITE) { _, _ -> slow.await() }, split))
        search.reset("find")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { search.loadNext("slow") }
        search.loadNext("split")
        assertTrue(search.rows.value[0].loading)
        assertEquals("one", search.rows.value[1].items.single().id)
        assertTrue(search.rows.value[1].failed)
        tvFails = false
        search.loadNext("split")
        assertEquals(1, requests.count { it.first.sectionId == "movie" })
        assertEquals(2, search.rows.value[1].items.size)
        assertFalse(search.rows.value[1].failed)
        search.loadNext("split")
        assertEquals("opaque-next", requests.last().second?.value)
        assertEquals(listOf("one", "one", "two"), search.rows.value[1].items.map { it.id })
        assertFalse(search.rows.value[1].canLoadMore)
        slow.complete(CatalogPage(emptyList(), null))
        pending.await()
        assertTrue(search.rows.value[0].items.isEmpty())
        assertFalse(search.rows.value[0].loading)
    }

    @Test fun editingCancelsOldResultsEvenWhenAnAdapterCompletesAfterCancellation() = runBlocking {
        val old = CompletableDeferred<CatalogPage>()
        val site = Site("site", SearchScope.SITE) { request, _ ->
            if (request.query == "old") withContext(NonCancellable) { old.await() }
            else CatalogPage(listOf(title("site", "movie", "new")), null)
        }
        val search = UniversalSearch(listOf(site))
        search.reset("old")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { search.loadNext("site") }
        search.invalidate()
        pending.cancel()
        search.reset("new")
        search.loadNext("site")
        old.complete(CatalogPage(listOf(title("site", "movie", "old")), null))
        pending.join()
        assertEquals(listOf("new"), search.rows.value.single().items.map { it.id })
        assertFalse(search.rows.value.single().loading)
        search.reset(" ")
        assertTrue(search.rows.value.isEmpty())
    }

    @Test fun sameTitleInTwoSiteSectionsAppearsOnce() = runBlocking {
        val delegate = Site("site", SearchScope.SECTION) { request, _ ->
            CatalogPage(listOf(title("site", "movie", "shared").copy(sectionId = request.sectionId)), null)
        }
        val site = object : StreamingSite by delegate {
            override val descriptor = delegate.descriptor.copy(sections = listOf(
                SiteSection("latest", "Latest", MediaType.MOVIE),
                SiteSection("classics", "Classics", MediaType.MOVIE)))
        }
        val search = UniversalSearch(listOf(site))
        search.reset("find")
        search.loadNext("site")
        assertEquals(listOf("shared"), search.rows.value.single().items.map { it.id })
        assertFalse(search.rows.value.single().canLoadMore)
    }

    private fun title(site: String, section: String, id: String) = Title(id, id, "", "", "", "", "",
        type = if (section == "tv") MediaType.TV else MediaType.MOVIE, siteId = site, sectionId = section)

    private class Site(id: String, scope: SearchScope,
        private val browse: suspend (CatalogRequest, PageToken?) -> CatalogPage) : StreamingSite {
        override val descriptor = SiteDescriptor(id, id, listOf(SiteSection("movie", "Movies", MediaType.MOVIE),
            SiteSection("tv", "TV Shows", MediaType.TV)), scope)
        override val series: SeriesCatalog? = null
        override suspend fun browse(request: CatalogRequest, after: PageToken?) = browse.invoke(request, after)
        override suspend fun details(title: Title) = title
        override suspend fun sources(item: PlayableRef): PlaybackOptions = error("Not used")
        override suspend fun resolve(item: PlayableRef, sourceId: String?): ResolvedPlayback = error("Not used")
    }
}
