package fr.bonamy.movies

import fr.bonamy.movies.core.SiteRegistry
import fr.bonamy.movies.core.sites.vidbox.VidboxSite

/** Register real site adapters here; screens depend only on StreamingSite. */
internal object AppServices {
    val sites = SiteRegistry(listOf(VidboxSite()))
}
