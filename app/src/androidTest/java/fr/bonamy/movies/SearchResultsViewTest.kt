package fr.bonamy.movies

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.SearchRow
import fr.bonamy.movies.core.SiteDescriptor
import fr.bonamy.movies.core.SiteSection
import fr.bonamy.movies.core.Title
import org.junit.Assert.*
import org.junit.Test

class SearchResultsViewTest {
    @Test fun remoteScrollsHorizontallyRestoresEachRowAndOpensTheOwningSiteAfterAppend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        fun row(id: String, count: Int) = SearchRow(
            SiteDescriptor(id, id, listOf(SiteSection("movie", "Movies", MediaType.MOVIE))),
            (0 until count).map { Title("$it", "$id $it", "", "", "", "", "", siteId = id) },
            canLoadMore = true)
        var selected: Title? = null
        val requested = mutableListOf<String>()
        lateinit var view: SearchResultsView
        try {
            instrumentation.runOnMainSync {
                view = SearchResultsView(activity, { _, _ -> }, { title, _ -> selected = title }, { requested += it })
                activity.setContentView(view)
                view.submit(listOf(row("first", 0).copy(loading = true), row("second", 0).copy(loading = true)))
                view.focusResults()
            }
            fun focused(prefix: String) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var actual: String? = null
                var settled = false
                fun idle(node: View): Boolean =
                    (node !is RecyclerView || (node.scrollState == RecyclerView.SCROLL_STATE_IDLE && !node.isComputingLayout && !node.isLayoutRequested)) &&
                        (node !is ViewGroup || (0 until node.childCount).all { idle(node.getChildAt(it)) })
                do {
                    instrumentation.runOnMainSync {
                        actual = activity.currentFocus?.contentDescription?.toString()
                        settled = view.hasWindowFocus() && idle(view)
                    }
                    if (settled && actual?.startsWith(prefix) == true) return
                    SystemClock.sleep(30)
                } while (SystemClock.uptimeMillis() < deadline)
                var tree = ""
                instrumentation.runOnMainSync {
                    fun inspect(node: View, depth: Int) {
                        tree += "\n${" ".repeat(depth)}${node.javaClass.simpleName} ${node.width}x${node.height} focus=${node.hasFocus()} desc=${node.contentDescription}"
                        if (node is androidx.leanback.widget.BaseGridView) tree += " selected=${node.selectedPosition} count=${node.adapter?.itemCount}"
                        if (node is ViewGroup && depth < 3) (0 until node.childCount).forEach { inspect(node.getChildAt(it), depth + 1) }
                    }
                    inspect(view, 0)
                }
                fail("Expected focus $prefix, got $actual $tree")
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { view.submit(listOf(row("first", 12), row("second", 12))) }
            focused("first 0 ·")
            repeat(8) { index ->
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                focused("first ${index + 1} ·")
            }
            assertTrue(requested.contains("first"))
            instrumentation.runOnMainSync { view.submit(listOf(row("first", 20), row("second", 12))) }
            focused("first 8 ·")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            focused("second ")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            focused("first 8 ·")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            assertEquals("first", selected?.siteId)
            assertEquals("8", selected?.id)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
