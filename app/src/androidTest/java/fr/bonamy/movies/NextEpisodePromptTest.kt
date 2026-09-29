package fr.bonamy.movies

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import fr.bonamy.movies.core.Episode
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.TitleRef
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class NextEpisodePromptTest {
    private val next = Episode(PlayableRef(TitleRef("site", "show", MediaType.TV), "2/1", 2, 1), "Return", "", "")

    @Test fun focusesAtThirtySecondsAllowsDismissalAndAdvancesWithRemoteOk() = withPrompt { prompt, button, playback, selected ->
        main { prompt.start { next } }
        assertEquals(next, runBlocking { withContext(Dispatchers.Main) { prompt.nextEpisode() } })
        main {
            for (position in listOf(0L, 69_999L, 100_000L)) {
                prompt.update(position, 100_000, true)
                assertEquals(View.GONE, button.visibility)
            }
            prompt.update(70_000, -1, true)
            assertEquals(View.GONE, button.visibility)
            prompt.update(70_000, 100_000, false) // An open player menu retains its focus.
            assertEquals(View.GONE, button.visibility)
            prompt.update(70_000, 100_000, true)
            assertTrue(button.hasFocus())
            playback.requestFocus()
            prompt.update(71_000, 100_000, true)
            assertTrue(playback.hasFocus()) // Do not steal focus every tick.
            assertTrue(prompt.dismiss())
            prompt.update(72_000, 100_000, true)
            assertEquals(View.GONE, button.visibility)
            prompt.update(10_000, 100_000, true) // Seeking back rearms the prompt.
            prompt.update(75_000, 100_000, true)
            assertTrue(button.hasFocus())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        main {
            assertEquals(listOf(next), selected)
            assertEquals(View.GONE, button.visibility)
            prompt.update(80_000, 100_000, true)
            assertEquals(View.GONE, button.visibility)
            prompt.stop()
            prompt.update(80_000, 100_000, true)
            assertEquals(View.GONE, button.visibility)
        }
    }

    @Test fun sharesLookupWithAutoplayRetriesFailuresAndDiscardsCancelledEpisodes() = withPrompt { prompt, button, _, _ ->
        var requests = 0
        main { prompt.start { requests++; if (requests == 1) throw IOException("offline") else next } }
        assertThrows(IOException::class.java) { runBlocking { withContext(Dispatchers.Main) { prompt.nextEpisode() } } }
        assertEquals(next, runBlocking { withContext(Dispatchers.Main) { prompt.nextEpisode() } })
        assertEquals(next, runBlocking { withContext(Dispatchers.Main) { prompt.nextEpisode() } })
        assertEquals(2, requests)

        val pending = CompletableDeferred<Episode?>()
        var cancelled = false
        main {
            prompt.start { try { pending.await() } finally { cancelled = true } }
            prompt.stop()
            prompt.start { null } // Final episode: no prompt, even after an older lookup finishes.
        }
        assertNull(runBlocking { withContext(Dispatchers.Main) { prompt.nextEpisode() } })
        pending.complete(next)
        main {
            assertTrue(cancelled)
            prompt.update(80_000, 100_000, true)
            assertEquals(View.GONE, button.visibility)
        }
    }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun withPrompt(test: (NextEpisodePrompt, Button, Button, MutableList<Episode>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val selected = mutableListOf<Episode>()
        lateinit var button: Button
        lateinit var playback: Button
        lateinit var prompt: NextEpisodePrompt
        try {
            main {
                button = Button(activity).apply { text = "Next episode" }
                playback = Button(activity).apply { text = "Playback" }
                activity.setContentView(LinearLayout(activity).apply { addView(playback); addView(button) })
                prompt = NextEpisodePrompt(button, playback, scope) { selected += it }
                playback.requestFocus()
            }
            instrumentation.waitForIdleSync()
            test(prompt, button, playback, selected)
        } finally {
            main { scope.cancel(); activity.finish() }
        }
    }
}
