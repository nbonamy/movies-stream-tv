package fr.bonamy.movies

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import fr.bonamy.movies.core.PlaybackSource
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.ResolvedPlayback
import fr.bonamy.movies.core.StreamingSite
import fr.bonamy.movies.core.SubtitleSelection
import fr.bonamy.movies.core.Title
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingActionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val movie = Title("continue-watching-actions-fixture", "Continue watching test", "", "", "", "", "2026", siteId = "vidbox")
    private val target = PlayableRef(movie.ref)

    @Test fun backFirstHidesControlsThenOkSavesBriefPreviewAtZero() = withActivity { activity, store ->
        main { preview(activity); field<View>(activity, "playerTopBar").visibility = View.VISIBLE }
        key(KeyEvent.KEYCODE_BACK)
        await { field<View>(activity, "playerTopBar").visibility == View.GONE }
        main { assertTrue(field<View>(activity, "playerLayer").isShown); assertNull(dialog(activity)) }
        key(KeyEvent.KEYCODE_BACK)
        await { dialog(activity)?.hasSavePrompt() == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { field<View>(activity, "playerLayer").visibility == View.GONE }
        val saved = store.bookmark(target)!!
        assertEquals(0L, saved.position)
        assertTrue(saved.queued)
        main { assertNull(field<Any?>(activity, "player")) }
    }

    @Test fun backDiscardsBriefPreviewWithoutCreatingBookmark() = withActivity { activity, store ->
        main { preview(activity) }
        key(KeyEvent.KEYCODE_BACK)
        await { dialog(activity)?.hasSavePrompt() == true }
        key(KeyEvent.KEYCODE_BACK)
        await { field<View>(activity, "playerLayer").visibility == View.GONE }
        assertNull(store.bookmark(target))
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @Test fun nativePlaybackErrorExitsWithoutSavePrompt() = withActivity { activity, store ->
        main {
            preview(activity)
            MainActivity::class.java.getDeclaredMethod("startPlayback", ResolvedPlayback::class.java,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, SubtitleSelection::class.java)
                .apply { isAccessible = true }.invoke(activity,
                    ResolvedPlayback("file:///does-not-exist/preview.mp4", emptyMap()), 0L, true, null)
        }
        await { field<ExoPlayer?>(activity, "player")?.playerError != null }
        main { field<View>(activity, "playerTopBar").visibility = View.GONE }
        key(KeyEvent.KEYCODE_BACK)
        await { field<View>(activity, "playerLayer").visibility == View.GONE }
        main { assertNull(dialog(activity)) }
        assertNull(store.bookmark(target))
    }

    @Test fun sourceResolutionErrorExitsWithoutSavePrompt() = withActivity { activity, store ->
        main {
            preview(activity)
            set(activity, "playbackSite", object : StreamingSite by AppServices.sites.sites.first() {
                override suspend fun resolve(item: PlayableRef, sourceId: String?): ResolvedPlayback =
                    error("Fixture source unavailable")
            })
            MainActivity::class.java.getDeclaredMethod("switchSource", PlaybackSource::class.java)
                .apply { isAccessible = true }.invoke(activity, PlaybackSource("fixture", "Test source"))
        }
        await { field<TextView>(activity, "playerStatus").text.startsWith("Could not use") }
        key(KeyEvent.KEYCODE_BACK)
        await { field<View>(activity, "playerLayer").visibility == View.GONE }
        main { assertNull(dialog(activity)) }
        assertNull(store.bookmark(target))
    }

    @Test fun thirtySecondsOrAnExistingBookmarkDoesNotPrompt() = withActivity { activity, store ->
        main {
            preview(activity)
            val watch = field<PlaybackWatchTime>(activity, "playbackWatchTime")
            PlaybackWatchTime::class.java.getDeclaredField("elapsed").apply { isAccessible = true }.setLong(watch, 30_000)
        }
        key(KeyEvent.KEYCODE_BACK)
        await { field<View>(activity, "playerLayer").visibility == View.GONE }
        main {
            assertNull(dialog(activity))
            store.save(movie, target, null, "", 120_000, 900_000)
            field<PlaybackWatchTime>(activity, "playbackWatchTime").reset()
            preview(activity)
        }
        key(KeyEvent.KEYCODE_BACK)
        await { field<View>(activity, "playerLayer").visibility == View.GONE }
        assertEquals(120_000L, store.position(target))
        main { assertNull(dialog(activity)) }
    }

    @Test fun longPressOffersRestartBeforeRemoveAndRetainsFocusWithoutPlaying() = withActivity { activity, store ->
        main {
            store.save(movie, target, null, "", 120_000, 900_000, subtitle = SubtitleSelection.Off)
            MainActivity::class.java.getDeclaredMethod("refreshContinueWatching", PlayableRef::class.java)
                .apply { isAccessible = true }.invoke(activity, target)
        }
        lateinit var grid: RecyclerView
        main { grid = field(activity, "resumeGrid") }
        val index = store.list().indexOfFirst { it.target == target }
        main { grid.scrollToPosition(index) }
        await { grid.findViewHolderForAdapterPosition(index)?.itemView != null }
        main {
            grid.findViewHolderForAdapterPosition(index)!!.itemView.apply { requestFocus(); performLongClick() }
        }
        await { browserDialog(activity)?.hasInput("Restart from beginning") == true }
        main {
            val actions = browserDialog(activity)!!.findViewById<LinearLayout>(R.id.dialog_actions)
            assertEquals(listOf("Restart from beginning", "Remove"), (0 until actions.childCount).map { (actions.getChildAt(it) as TextView).text.toString() })
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { browserDialog(activity)?.isShowing != true && grid.hasFocus() }
        assertEquals(0L, store.position(target))
        assertEquals(SubtitleSelection.Off, store.subtitle(target))
        main { assertEquals(View.GONE, field<View>(activity, "playerLayer").visibility) }

        val restartedIndex = store.list().indexOfFirst { it.target == target }
        await { grid.findViewHolderForAdapterPosition(restartedIndex)?.itemView != null }
        main { grid.findViewHolderForAdapterPosition(restartedIndex)!!.itemView.performLongClick() }
        await { browserDialog(activity)?.hasInput("Restart from beginning") == true }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await { browserDialog(activity)?.hasInput("Remove") == true }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        await { browserDialog(activity)?.isShowing != true }
        assertNull(store.bookmark(target))
    }

    private fun preview(activity: MainActivity) {
        set(activity, "currentMovie", movie)
        set(activity, "currentTarget", target)
        set(activity, "returnToHome", true)
        field<View>(activity, "gallery").visibility = View.GONE
        field<View>(activity, "playerLayer").visibility = View.VISIBLE
        field<View>(activity, "playerTopBar").visibility = View.GONE
        field<View>(activity, "playerView").requestFocus()
    }

    private fun dialog(activity: MainActivity) = field<Dialog?>(activity, "optionsDialog")
    private fun browserDialog(activity: MainActivity) = field<Dialog?>(activity, "browserDialog")
    private fun Dialog.hasSavePrompt() = isShowing && window?.decorView?.hasWindowFocus() == true &&
        findViewById<TextView>(R.id.dialog_title).text.toString() == "OK to save for later, Back to discard" &&
        findViewById<LinearLayout>(R.id.dialog_actions).childCount == 0
    private fun Dialog.hasInput(label: String) = isShowing && window?.decorView?.hasWindowFocus() == true &&
        (currentFocus as? TextView)?.text?.toString() == label
    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun set(activity: MainActivity, name: String, value: Any) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Timed out waiting for the remote action")
    }

    private fun withActivity(test: (MainActivity, PlaybackProgress) -> Unit) {
        val context = instrumentation.targetContext
        val stores = listOf("progress", "playback").map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val snapshots = stores.map { HashMap(it.all) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try { test(activity, PlaybackProgress(stores.first())) } finally {
            main { dialog(activity)?.dismiss(); browserDialog(activity)?.dismiss(); activity.finish() }
            instrumentation.waitForIdleSync()
            stores.zip(snapshots).forEach { (store, saved) ->
                val edit = store.edit().clear()
                saved.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Long -> edit.putLong(key, value)
                    is Int -> edit.putInt(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>)
                } }
                edit.commit()
            }
        }
    }
}
