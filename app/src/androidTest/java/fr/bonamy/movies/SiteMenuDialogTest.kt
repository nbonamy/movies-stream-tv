package fr.bonamy.movies

import android.content.Intent
import android.view.KeyEvent
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import fr.bonamy.movies.core.BrowseMenuItem
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.SiteDescriptor
import fr.bonamy.movies.core.SiteSection
import org.junit.Assert.*
import org.junit.Test

class SiteMenuDialogTest {
    @Test fun remoteSkipsDividerAndSelectsAnotherSiteWithMovieOnlyMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val first = BrowseMenuItem.Site(SiteDescriptor("first", "First site", listOf(SiteSection("movie", "Movies", MediaType.MOVIE))))
        val second = BrowseMenuItem.Site(SiteDescriptor("second", "Second site", listOf(SiteSection("movie", "Movies", MediaType.MOVIE), SiteSection("tv", "TV Shows", MediaType.TV))))
        var selected: BrowseMenuItem? = null
        var dialog: android.app.Dialog? = null
        try {
            instrumentation.runOnMainSync {
                dialog = SiteMenuDialog.create(activity,
                    listOf(BrowseMenuItem.Section(SiteSection("movie", "Movies", MediaType.MOVIE)), BrowseMenuItem.Divider, first, second), SiteSection("movie", "Movies", MediaType.MOVIE)) {
                    selected = it
                    dialog?.dismiss()
                }
                dialog?.show()
            }
            fun focused(label: String) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { assertEquals(label, (dialog?.currentFocus as? TextView)?.text?.toString()) }
            }
            focused("Movies")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            focused("First site")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            focused("Second site")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            focused("First site")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            assertEquals(first, selected)
        } finally {
            instrumentation.runOnMainSync { dialog?.dismiss(); activity.finish() }
        }
    }
}
