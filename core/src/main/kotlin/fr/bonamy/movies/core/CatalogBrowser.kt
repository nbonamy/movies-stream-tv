package fr.bonamy.movies.core

/** One catalog/search session. Call from the UI dispatcher; adapters own network dispatch. */
class CatalogBrowser(site: StreamingSite, request: CatalogRequest) {
    private class State(val site: StreamingSite, val request: CatalogRequest,
        var items: List<Title> = emptyList(), var next: PageToken? = null,
        var loaded: Boolean = false, var loading: Boolean = false)
    private var state = State(site, request)
    val items: List<Title> get() = state.items
    val canLoadMore: Boolean get() = !state.loading && (!state.loaded || state.next != null)

    fun reset(site: StreamingSite, request: CatalogRequest) {
        site.descriptor.section(request.sectionId)
        state = State(site, request)
    }

    /** Invalidate in-flight work immediately on edit, before the debounce expires. */
    fun invalidate() {
        state = State(state.site, state.request, state.items, state.next, state.loaded)
    }

    suspend fun loadNext(): List<Title>? {
        if (!canLoadMore) return null
        val current = state
        current.loading = true
        try {
            val page = current.site.browse(current.request, current.next)
            if (state !== current) return null
            require(page.items.all { it.siteId == current.site.descriptor.id && it.sectionId == current.request.sectionId && it.type == current.site.descriptor.section(current.request.sectionId).mediaType })
            require(page.next == null || (page.next.siteId == current.site.descriptor.id && page.next.request == current.request))
            require(page.next == null || page.next != current.next) { "Site repeated its page token" }
            val known = current.items.map { it.ref }.toSet()
            val added = page.items.distinctBy { it.ref }.filter { it.ref !in known }
            current.items = current.items + added
            current.next = page.next
            current.loaded = true
            return added
        } finally {
            if (state === current) current.loading = false
        }
    }
}
