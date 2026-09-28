package fr.bonamy.movies

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.Movie
import fr.bonamy.movies.core.PlaybackTarget
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackProgressTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("progress-test", Context.MODE_PRIVATE)
    private val movie = Movie("55", "Title", "poster", "backdrop", "8", "Overview", "2026")
    private val show = movie.copy(type = MediaType.TV)

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun reopensMovieAndEpisodeProgressWithSeparateHomeLists() {
        val store = PlaybackProgress(preferences)
        val film = PlaybackTarget("55")
        val first = PlaybackTarget("55", MediaType.TV, 1, 2)
        val second = PlaybackTarget("55", MediaType.TV, 2, 2)
        store.save(movie, film, null, "poster", 120_000, 900_000)
        store.save(show, first, "First episode", "still-1", 240_000, 900_000)
        store.save(show, second, "Second episode", "still-2", 360_000, 900_000)

        val reopened = PlaybackProgress(preferences)
        assertEquals(120_000L, reopened.position(film))
        assertEquals(240_000L, reopened.position(first))
        assertEquals(360_000L, reopened.position(second))
        assertEquals(0L, reopened.position(PlaybackTarget("55", MediaType.TV, 2, 3)))
        assertEquals(listOf(movie), reopened.list(MediaType.MOVIE).map { it.movie })
        val episode = reopened.list(MediaType.TV).single { it.target == second }
        assertEquals("Second episode", episode.episodeName)
        assertEquals("still-2", episode.artwork)
        assertEquals(900_000L, episode.duration)
        assertEquals(setOf(first, second), reopened.list(MediaType.TV).map { it.target }.toSet())
    }

    @Test fun retainsProgressOnFailedLoadsAndRemovesUnstartedOrCompletedItems() {
        val store = PlaybackProgress(preferences)
        val target = PlaybackTarget("55")
        fun save(position: Long, duration: Long = 1_000_000, ended: Boolean = false) =
            store.save(movie, target, null, "poster", position, duration, ended)
        save(120_000)
        save(0, -1) // No duration while a stream is preparing or unavailable.
        save(-1)
        assertEquals(120_000L, store.position(target))
        save(30_000)
        assertTrue(store.list(MediaType.MOVIE).isEmpty())
        save(950_000)
        assertEquals(950_000L, store.position(target))
        save(950_001)
        assertTrue(store.list(MediaType.MOVIE).isEmpty())
        save(120_000)
        save(120_000, ended = true)
        assertEquals(0L, store.position(target))
        // One malformed entry must not prevent loading the rest of the home row.
        preferences.edit().putString("broken", "{").apply()
        save(180_000)
        assertEquals(listOf(target), store.list(MediaType.MOVIE).map { it.target })
    }
}
