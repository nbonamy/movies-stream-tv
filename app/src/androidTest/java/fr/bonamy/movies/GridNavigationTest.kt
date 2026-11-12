package fr.bonamy.movies

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class GridNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun catalogWrapsPastFullWidthHeadersAndScrollsInBothDirections() = checkWrapping(7, true)
    @Test fun episodeGridWrapsAndStopsAtItsFirstAndLastCards() = checkWrapping(4, false)

    private fun checkWrapping(columns: Int, header: Boolean) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var grid: RecyclerView
        val offset = if (header) 1 else 0
        val count = columns * 4 + 2 // A partial final row.
        try {
            main {
                val cards = MediaCardAdapter(landscape = !header, image = { _, _ -> }, select = { _, _ -> })
                cards.append((0 until count).map { MediaCard(it, "Card $it", "") })
                grid = RecyclerView(activity).apply {
                    layoutManager = GridLayoutManager(activity, columns).apply {
                        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(position: Int) = if (position < offset) columns else 1
                        }
                    }
                    itemAnimator = null
                    adapter = if (header) ConcatAdapter(HomeSectionAdapter(TextView(activity).apply {
                        text = "Catalog"
                    }), cards) else cards
                }
                activity.setContentView(grid, android.view.ViewGroup.LayoutParams(-1,
                    (260 * activity.resources.displayMetrics.density).toInt()))
                grid.scrollToPosition(offset + columns - 1)
            }
            await { grid.findViewHolderForAdapterPosition(offset + columns - 1)?.itemView != null }
            main { grid.findViewHolderForAdapterPosition(offset + columns - 1)!!.itemView.requestFocus() }
            // Cross several row boundaries, including rows that need scrolling.
            for (position in columns until count) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await { focusedPosition(grid) == offset + position }
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            main { assertEquals(offset + count - 1, focusedPosition(grid)) }
            for (position in count - 2 downTo 0) {
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                await { focusedPosition(grid) == offset + position }
            }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            main { assertEquals(offset, focusedPosition(grid)) }
        } finally {
            main { activity.finish() }
        }
    }

    private fun focusedPosition(grid: RecyclerView): Int =
        grid.findFocus()?.let { grid.findContainingViewHolder(it)?.absoluteAdapterPosition } ?: RecyclerView.NO_POSITION
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
        fail("Timed out waiting for grid focus")
    }
}
