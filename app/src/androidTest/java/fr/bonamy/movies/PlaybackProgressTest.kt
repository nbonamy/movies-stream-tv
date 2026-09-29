package fr.bonamy.movies

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.Title
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.TitleRef
import fr.bonamy.movies.core.SubtitleSelection
import fr.bonamy.movies.core.SubtitleLanguage
import fr.bonamy.movies.core.OnlineSubtitle
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackProgressTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("progress-test", Context.MODE_PRIVATE)
    private val movie = Title("55", "Title", "poster", "backdrop", "8", "Overview", "2026", siteId = "site-one")
    private val show = movie.copy(type = MediaType.TV, sectionId = "tv")

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun reopensMoviesAndEpisodesInOneRecentList() {
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
        // Stored timestamps make recency deterministic even when saves share a millisecond.
        listOf(film, first, second).forEachIndexed { index, target ->
            val data = JSONObject(preferences.getString(target.key, null)!!).put("updatedAt", index + 1)
            preferences.edit().putString(target.key, data.toString()).commit()
        }
        assertEquals(listOf(second, first, film), reopened.list().map { it.target })
        val episode = reopened.list().single { it.target == second }
        assertEquals("Second episode", episode.episodeName)
        assertEquals("still-2", episode.artwork)
        assertEquals(900_000L, episode.duration)
        assertEquals(setOf(film, first, second), reopened.list().map { it.target }.toSet())
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
        assertTrue(store.list().isEmpty())
        save(950_000)
        assertEquals(950_000L, store.position(target))
        save(950_001)
        assertTrue(store.list().isEmpty())
        save(120_000)
        save(120_000, ended = true)
        assertEquals(0L, store.position(target))
        // One malformed entry must not prevent loading the rest of the home row.
        preferences.edit().putString("broken", "{").apply()
        save(180_000)
        assertEquals(listOf(target), store.list().map { it.target })
    }

    @Test fun restoresSubtitleIdentityAndSavesChangesWhilePausedWithoutLeakingToOtherItems() {
        val target = PlayableRef(show.ref, "1/2", 1, 2)
        val store = PlaybackProgress(preferences)
        val choices = listOf(
            SubtitleSelection.Online(OnlineSubtitle("123", SubtitleLanguage.FRENCH, "Show.S01E02.WEB.srt",
                "https://dl.opensubtitles.org/download/123", "UTF-8", 50)),
            SubtitleSelection.Embedded("eng", "track-2", "English SDH", 128),
            SubtitleSelection.Off,
        )
        for (choice in choices) {
            // The same position exercises changing subtitles while paused.
            store.save(show, target, "Second", "", 120_000, 900_000, subtitle = choice)
            val reopened = PlaybackProgress(preferences)
            assertEquals(choice, reopened.subtitle(target))
            assertEquals(120_000L, reopened.position(target))
            assertNull(reopened.subtitle(PlayableRef(show.ref, "1/3", 1, 3)))
            assertNull(reopened.subtitle(target.copy(title = target.title.copy(siteId = "another-site"))))
        }
        store.remove(target)
        assertNull(PlaybackProgress(preferences).subtitle(target))
        assertEquals(0L, PlaybackProgress(preferences).position(target))
        store.save(show, target, "Second", "", 120_000, 900_000, subtitle = choices.first())
        val data = JSONObject(preferences.getString(target.key, null)!!)
        data.put("subtitle", JSONObject().put("kind", "unknown-future-kind"))
        preferences.edit().putString(target.key, data.toString()).commit()
        assertEquals(120_000L, PlaybackProgress(preferences).position(target))
        assertNull(PlaybackProgress(preferences).subtitle(target))
        store.save(show, target, "Second", "", 900_000, 900_000, ended = true, subtitle = choices.first())
        assertNull(PlaybackProgress(preferences).subtitle(target))
    }
    @Test fun keepsIdenticalIdsSeparateAcrossSitesAndMigratesLegacyVidboxOnce() {
        val legacy = JSONObject().put("id", "55").put("type", "TV").put("title", "Legacy show")
            .put("season", 2).put("episode", 3).put("episodeName", "Third").put("artwork", "still")
            .put("position", 123_000).put("duration", 900_000).put("updatedAt", 42)
        preferences.edit().putString("tv/55/2/3", legacy.toString()).putString("currentProviderId", "vidpro").commit()
        val store = PlaybackProgress(preferences)
        val migrated = PlayableRef(TitleRef("vidbox", "55", MediaType.TV), "2/3", 2, 3)
        assertEquals(123_000L, store.position(migrated))
        assertEquals(42L, store.list().single().updatedAt)
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
        assertEquals(setOf(PlayableRef(a.ref), PlayableRef(b.ref), migrated), reopened.list().map { it.target }.toSet())
        reopened.remove(PlayableRef(a.ref))
        val afterRemoval = PlaybackProgress(preferences)
        assertEquals(0L, afterRemoval.position(PlayableRef(a.ref)))
        assertEquals(setOf(PlayableRef(b.ref), migrated), afterRemoval.list().map { it.target }.toSet())
        assertEquals(360_000L, afterRemoval.position(PlayableRef(b.ref)))
        assertEquals(123_000L, reopened.position(migrated))
        SitePreferences(preferences).selectSource(a.siteId, "server-a")
        SitePreferences(preferences).selectSource(b.siteId, "server-b")
        assertEquals("server-a", SitePreferences(preferences).source(a.siteId))
        assertEquals("server-b", SitePreferences(preferences).source(b.siteId))
    }

    @Test fun sectionsKeepSelectionButShareContinueWatching() {
        val films = fr.bonamy.movies.core.SiteSection("films", "À l'affiche", MediaType.MOVIE)
        val stage = fr.bonamy.movies.core.SiteSection("stage", "Spectacles", MediaType.MOVIE)
        val site = fr.bonamy.movies.core.SiteDescriptor("custom", "Custom", listOf(films, stage))
        val settings = SitePreferences(preferences)
        settings.selectSection(site, stage)
        assertEquals(stage, SitePreferences(preferences).section(site))
        assertEquals(films, settings.section(site.copy(sections = listOf(films))))
        val store = PlaybackProgress(preferences)
        val film = movie.copy(siteId = site.id, sectionId = films.id)
        val show = film.copy(id = "stage-show", sectionId = stage.id)
        store.save(film, PlayableRef(film.ref), null, "", 120_000, 900_000)
        store.save(show, PlayableRef(show.ref), null, "", 180_000, 900_000)
        val reopened = PlaybackProgress(preferences)
        assertEquals(setOf(film, show), reopened.list().map { it.movie }.toSet())
        preferences.edit().putString(SitePreferences.key("mode", "vidbox"), "TV").commit()
        val vidbox = fr.bonamy.movies.core.sites.vidbox.VidboxSite().descriptor
        assertEquals("tv", settings.section(vidbox).id)
    }

}
