package fr.bonamy.movies.core

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared ordered navigation; a site can override nextEpisode for its own next-link API. */
internal suspend fun findNextEpisode(catalog: SeriesCatalog, current: PlayableRef): Episode? {
    require(current.type == MediaType.TV)
    val seasons = catalog.seasons(current.title)
    // Number metadata avoids fetching earlier seasons when resuming a known episode.
    val start = current.season?.let { number -> seasons.indexOfFirst { it.number == number } }
        ?.takeIf { it >= 0 } ?: 0
    var found = false
    for (season in seasons.drop(start)) {
        currentCoroutineContext().ensureActive()
        require(season.ref.show == current.title) { "Season belongs to another show" }
        val episodes = catalog.episodes(season.ref)
        currentCoroutineContext().ensureActive()
        require(episodes.all { it.target.title == current.title }) { "Episode belongs to another show" }
        for (episode in episodes) {
            if (episode.target.key == current.key) found = true
            else if (found) return episode
        }
    }
    if (!found) throw IOException("The current episode is no longer listed")
    return null
}
