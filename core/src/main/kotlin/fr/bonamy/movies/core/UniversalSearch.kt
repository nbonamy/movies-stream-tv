package fr.bonamy.movies.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchRow(
    val site: SiteDescriptor,
    val items: List<Title> = emptyList(),
    val loading: Boolean = false,
    val canLoadMore: Boolean = true,
    val failed: Boolean = false,
)

/** One universal query; call on the UI dispatcher. Adapters own network dispatch.
 * Each site and section keeps its own cursor, failure and cancellation state. */
class UniversalSearch(private val sites: List<StreamingSite>) {
    private class Channel(val browser: CatalogBrowser, var failed: Boolean = false, var loading: Boolean = false)
    private class Row(val site: StreamingSite, val channels: List<Channel>, var items: List<Title> = emptyList())
    private var generation = 0
    private var current = emptyList<Row>()
    private val mutableRows = MutableStateFlow<List<SearchRow>>(emptyList())
    val rows = mutableRows.asStateFlow()

    fun reset(query: String) {
        ++generation
        current = if (query.isBlank()) emptyList() else sites.map { site ->
            val sections = site.descriptor.sections.let {
                if (site.descriptor.searchScope == SearchScope.SITE) it.take(1) else it
            }
            Row(site, sections.map { Channel(CatalogBrowser(site, CatalogRequest(it.id, query.trim()))) })
        }
        publish()
    }

    /** Called immediately on edits, before starting the next debounced query. */
    fun invalidate() {
        ++generation
        current.forEach { row -> row.channels.forEach {
            it.browser.invalidate()
            it.loading = false
        } }
        publish()
    }

    suspend fun loadNext(siteId: String) = coroutineScope {
        val row = current.firstOrNull { it.site.descriptor.id == siteId } ?: return@coroutineScope
        if (row.channels.any { it.loading }) return@coroutineScope
        val ticket = generation
        // Retry only failed sections. Successful sections keep their pages and cursors.
        val retry = row.channels.any { it.failed }
        val channels = row.channels.filter { if (retry) it.failed else it.browser.canLoadMore }
        channels.forEach { it.loading = true; it.failed = false }
        publish()
        channels.forEach { channel -> launch {
            try {
                val added = channel.browser.loadNext().orEmpty()
                currentCoroutineContext().ensureActive()
                if (ticket == generation) row.items = (row.items + added).distinctBy { it.ref }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ticket == generation) channel.failed = true
            } finally {
                if (ticket == generation) {
                    channel.loading = false
                    publish()
                }
            }
        } }
    }

    private fun publish() {
        mutableRows.value = current.map { row -> SearchRow(
            site = row.site.descriptor,
            items = row.items,
            loading = row.channels.any { it.loading },
            canLoadMore = row.channels.any { it.browser.canLoadMore || it.loading },
            failed = row.channels.any { it.failed },
        ) }
    }
}
