package fr.bonamy.movies.core

/** Sections are site-owned navigation; media type describes how their titles play. */
data class SiteSection(val id: String, val title: String, val mediaType: MediaType) {
    init { require(id.isNotBlank() && title.isNotBlank()) }
}

data class SiteDescriptor(val id: String, val name: String, val sections: List<SiteSection>) {
    init { require(id.isNotBlank() && sections.isNotEmpty() && sections.map { it.id }.distinct().size == sections.size) }
    fun section(id: String): SiteSection = sections.first { it.id == id }
}

data class PlaybackOptions(val sources: List<PlaybackSource>, val defaultSourceId: String) {
    init { require(sources.any { it.id == defaultSourceId } && sources.map { it.id }.distinct().size == sources.size) }
    fun preferred(id: String?): PlaybackSource = sources.firstOrNull { it.id == id }
        ?: sources.first { it.id == defaultSourceId }
}

/** Site implementations own all provider identifiers, URL shapes, parsing and defaults.
 * Suspending operations are main-safe and must propagate cancellation. */
interface StreamingSite {
    val descriptor: SiteDescriptor
    val series: SeriesCatalog?
    suspend fun browse(request: CatalogRequest, after: PageToken? = null): CatalogPage
    suspend fun details(title: Title): Title
    suspend fun sources(item: PlayableRef): PlaybackOptions
    suspend fun resolve(item: PlayableRef, sourceId: String? = null): ResolvedPlayback
    suspend fun subtitleContext(item: PlayableRef): SubtitleContext? = null
}

interface SeriesCatalog {
    suspend fun seasons(show: TitleRef): List<Season>
    suspend fun episodes(season: SeasonRef): List<Episode>
}

/** Extractor request types belong to the playback host, never to the Android UI. */
internal fun interface StreamExtractor<in Request> {
    fun resolve(request: Request): ResolvedPlayback
}

sealed interface BrowseMenuItem {
    data class Section(val section: SiteSection) : BrowseMenuItem
    data object Divider : BrowseMenuItem
    data class Site(val descriptor: SiteDescriptor) : BrowseMenuItem
}

class SiteRegistry(val sites: List<StreamingSite>) {
    init {
        require(sites.isNotEmpty() && sites.map { it.descriptor.id }.distinct().size == sites.size)
        require(sites.all { it.descriptor.sections.any { section -> section.mediaType == MediaType.TV } == (it.series != null) })
    }
    fun get(id: String): StreamingSite = sites.first { it.descriptor.id == id }
    fun initial(id: String?): StreamingSite = sites.firstOrNull { it.descriptor.id == id } ?: sites.first()
    fun menu(site: StreamingSite): List<BrowseMenuItem> = buildList {
        addAll(site.descriptor.sections.map(BrowseMenuItem::Section))
        val others = sites.filter { it.descriptor.id != site.descriptor.id }
        if (others.isNotEmpty()) {
            add(BrowseMenuItem.Divider)
            addAll(others.map { BrowseMenuItem.Site(it.descriptor) })
        }
    }
}

/** Captured tickets reject late results even if the same title is opened again. */
class RequestSession {
    private var generation = 0L
    fun next(): Long = ++generation
    fun isCurrent(ticket: Long) = ticket == generation
    fun invalidate() { ++generation }
}
