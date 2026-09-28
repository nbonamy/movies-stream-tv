package fr.bonamy.movies

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.Title
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.TitleRef
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackProgressTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("progress-test", Context.MODE_PRIVATE)
    private val movie = Title("55", "Title", "poster", "backdrop", "8", "Overview", "2026", siteId = "site-one")
    private val show = movie.copy(type = MediaType.TV)

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun reopensMovieAndEpisodeProgressWithSeparateHomeLists() {
        val store = PlaybackProgress(preferences)
        val film = PlayableRef(movie.ref)
        val first = PlayableRef(show.ref, "season-one/second", 1, 2)
        val second = PlayableRef(show.ref, "season-two/second", 2, 2)
        store.save(movie, film, null, "poster", 120_000, 900_000)
        store.save(show, first, "First episode", "still-1", 240_000, 900_000)
        store.save(show, second, "Second episode", "still-2", 360_000, 900_000)

        val reopened = PlaybackProgress(preferences)
        assertEquals(120_000L, reopened.position(film))
        assertEquals(240_000L, reopened.position(first))
        assertEquals(360_000L, reopened.position(second))
        assertEquals(0L, reopened.position(PlayableRef(show.ref, "season-two/third", 2, 3)))
        assertEquals(listOf(movie), reopened.list(movie.siteId, MediaType.MOVIE).map { it.movie })
        val episode = reopened.list(movie.siteId, MediaType.TV).single { it.target == second }
        assertEquals("Second episode", episode.episodeName)
        assertEquals("still-2", episode.artwork)
        assertEquals(900_000L, episode.duration)
        assertEquals(setOf(first, second), reopened.list(movie.siteId, MediaType.TV).map { it.target }.toSet())
    }

    @Test fun retainsProgressOnFailedLoadsAndRemovesUnstartedOrCompletedItems() {
        val store = PlaybackProgress(preferences)
        val target = PlayableRef(movie.ref)
        fun save(position: Long, duration: Long = 1_000_000, ended: Boolean = false) =
            store.save(movie, target, null, "poster", position, duration, ended)
        save(120_000)
        save(0, -1) // No duration while a stream is preparing or unavailable.
        save(-1)
        assertEquals(120_000L, store.position(target))
        save(30_000)
        assertTrue(store.list(movie.siteId, MediaType.MOVIE).isEmpty())
        save(950_000)
        assertEquals(950_000L, store.position(target))
        save(950_001)
        assertTrue(store.list(movie.siteId, MediaType.MOVIE).isEmpty())
        save(120_000)
        save(120_000, ended = true)
        assertEquals(0L, store.position(target))
        // One malformed entry must not prevent loading the rest of the home row.
        preferences.edit().putString("broken", "{").apply()
        save(180_000)
        assertEquals(listOf(target), store.list(movie.siteId, MediaType.MOVIE).map { it.target })
    }
    @Test fun keepsIdenticalIdsSeparateAcrossSitesAndMigratesLegacyVidboxOnce() {
        val legacy = JSONObject().put("id", "55").put("type", "TV").put("title", "Legacy show")
            .put("season", 2).put("episode", 3).put("episodeName", "Third").put("artwork", "still")
            .put("position", 123_000).put("duration", 900_000).put("updatedAt", 42)
        preferences.edit().putString("tv/55/2/3", legacy.toString()).putString("currentProviderId", "vidpro").commit()
        val store = PlaybackProgress(preferences)
        val migrated = PlayableRef(TitleRef("vidbox", "55", MediaType.TV), "2/3", 2, 3)
        assertEquals(123_000L, store.position(migrated))
        assertEquals(42L, store.list("vidbox", MediaType.TV).single().updatedAt)
        assertFalse(preferences.contains("tv/55/2/3"))
        assertEquals("vidpro", SitePreferences(preferences).source("vidbox"))
        assertNull(SitePreferences(preferences).source(movie.siteId))

        val a = movie.copy(id = "same/slug")
        val b = a.copy(siteId = "site-two")
        store.save(a, PlayableRef(a.ref), null, a.poster, 180_000, 900_000)
        store.save(b, PlayableRef(b.ref), null, b.poster, 360_000, 900_000)
        val reopened = PlaybackProgress(preferences)
        assertEquals(180_000L, reopened.position(PlayableRef(a.ref)))
        assertEquals(360_000L, reopened.position(PlayableRef(b.ref)))
        assertEquals(listOf(a), reopened.list(a.siteId, MediaType.MOVIE).map { it.movie })
        assertEquals(listOf(b), reopened.list(b.siteId, MediaType.MOVIE).map { it.movie })
        assertEquals(123_000L, reopened.position(migrated))
        SitePreferences(preferences).selectSource(a.siteId, "server-a")
        SitePreferences(preferences).selectSource(b.siteId, "server-b")
        assertEquals("server-a", SitePreferences(preferences).source(a.siteId))
        assertEquals("server-b", SitePreferences(preferences).source(b.siteId))
    }

}
