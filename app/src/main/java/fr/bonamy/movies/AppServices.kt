package fr.bonamy.movies

import fr.bonamy.movies.core.SiteRegistry
import fr.bonamy.movies.core.sites.kopoti.KopotiSite
import fr.bonamy.movies.core.sites.vidbox.VidboxSite
import fr.bonamy.movies.core.sites.movies123.Movies123Site

/** Register real site adapters here; screens depend only on StreamingSite. */
internal object AppServices {
    val sites = SiteRegistry(listOf(VidboxSite(), KopotiSite(), Movies123Site()))
}
