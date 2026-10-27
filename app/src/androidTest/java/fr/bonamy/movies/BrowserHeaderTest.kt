package fr.bonamy.movies

import android.content.Intent
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class BrowserHeaderTest {
    @Test fun remoteHighlightsMenuTitleAndSearchLabelTogetherWithTheirIcons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var menu: View
        lateinit var search: View
        lateinit var title: TextView
        lateinit var searchLabel: TextView
        lateinit var menuIcon: ImageView
        lateinit var searchIcon: ImageView
        lateinit var content: Button
        var menuClicks = 0
        var searchClicks = 0
        try {
            instrumentation.runOnMainSync {
                val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                val header = activity.layoutInflater.inflate(R.layout.browser_header, panel, false)
                panel.addView(header)
                title = header.findViewById<TextView>(R.id.breadcrumb).apply { text = "Movies / Popular" }
                menu = header.findViewById(R.id.menu)
                search = header.findViewById<View>(R.id.search).apply { visibility = View.VISIBLE }
                searchLabel = header.findViewById(R.id.search_label)
                menuIcon = header.findViewById(R.id.menu_icon)
                searchIcon = header.findViewById(R.id.search_icon)
                menu.setOnClickListener { menuClicks++ }
                search.setOnClickListener { searchClicks++ }
                content = Button(activity).apply { text = "Catalog" }
                panel.addView(content)
                activity.setContentView(panel)
                menu.requestFocus()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(menu.hasFocus())
                assertEquals("The title must highlight with the menu", Color.WHITE, title.currentTextColor)
                assertEquals(Color.WHITE, menuIcon.imageTintList?.getColorForState(menuIcon.drawableState, 0))
                assertEquals(0, Color.alpha(searchLabel.currentTextColor))
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(search.hasFocus())
                assertNotEquals(Color.WHITE, title.currentTextColor)
                assertEquals(Color.WHITE, searchLabel.currentTextColor)
                assertEquals(Color.WHITE, searchIcon.imageTintList?.getColorForState(searchIcon.drawableState, 0))
                assertTrue("Search label is left of the icon", searchLabel.right < searchIcon.left)
                assertEquals(1, menuClicks)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(1, searchClicks)
                assertTrue(menu.hasFocus())
                assertEquals(0, Color.alpha(searchLabel.currentTextColor))
                content.requestFocus()
                assertNotEquals(Color.WHITE, title.currentTextColor)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
