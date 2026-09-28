package fr.bonamy.movies.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpisodeNavigationTest {
    private val show = TitleRef("site", "show/slug", MediaType.TV)
    private fun episode(id: String, season: Int? = null, number: Int? = null) =
        Episode(PlayableRef(show, id, season, number), id, "", "")

    @Test fun advancesWithinSeasonAcrossEmptySeasonsAndStopsAtSeriesEnd() = runBlocking {
        val first = episode("pilot", 1, 1)
        val last = episode("finale", 1, 4) // Listing order, not episode-number arithmetic.
        val nextSeason = episode("return", 3, 1)
        val catalog = Listing(listOf(1 to listOf(first, last), 2 to emptyList(), 3 to listOf(nextSeason)))
        assertEquals(last, catalog.nextEpisode(first.target))
        assertEquals(nextSeason, catalog.nextEpisode(last.target))
        catalog.requests.clear()
        assertNull(catalog.nextEpisode(nextSeason.target))
        assertEquals(listOf("season-2"), catalog.requests) // No earlier-season requests on resume.
    }

    @Test fun navigatesOpaqueEpisodeAndSeasonIdsWithoutNumberMetadata() = runBlocking {
        val first = episode("part/alpha")
        val next = episode("part/beta")
        val catalog = Listing(listOf(null to listOf(first), null to listOf(next)))
        assertEquals(next, catalog.nextEpisode(first.target))
        assertNull(catalog.nextEpisode(next.target))
    }

    @Test fun missingEpisodesAndLookupFailuresAreNotReportedAsSeriesEnd() {
        val catalog = Listing(listOf(1 to listOf(episode("known", 1, 1))))
        assertThrows(IOException::class.java) {
            runBlocking { catalog.nextEpisode(episode("missing", 1, 2).target) }
        }
        for (failure in listOf(IOException("offline"), CancellationException("left player"))) {
            val unavailable = object : SeriesCatalog {
                override suspend fun seasons(show: TitleRef): List<Season> = throw failure
                override suspend fun episodes(season: SeasonRef): List<Episode> = error("Unexpected lookup")
            }
            val thrown = assertThrows(failure.javaClass) {
                runBlocking { unavailable.nextEpisode(episode("current").target) }
            }
            assertSame(failure, thrown)
        }
    }

    private inner class Listing(private val listing: List<Pair<Int?, List<Episode>>>) : SeriesCatalog {
        val requests = mutableListOf<String>()
        override suspend fun seasons(show: TitleRef) = listing.mapIndexed { index, (number, episodes) ->
            Season(SeasonRef(show, "season-$index", number), "Season", episodes.size)
        }
        override suspend fun episodes(season: SeasonRef): List<Episode> {
            requests += season.id
            return listing[season.id.removePrefix("season-").toInt()].second
        }
    }
}
