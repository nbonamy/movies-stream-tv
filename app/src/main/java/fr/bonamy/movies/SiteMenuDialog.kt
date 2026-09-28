package fr.bonamy.movies

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.bonamy.movies.core.BrowseMenuItem
import fr.bonamy.movies.core.SiteSection

/** MediaStation action rows, with a real, nonfocusable separator between modes and sites. */
internal object SiteMenuDialog {
    fun create(context: Context, items: List<BrowseMenuItem>, selected: SiteSection,
        choose: (BrowseMenuItem) -> Unit): Dialog {
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var initial: View? = null
        val actions = mutableListOf<View>()
        val spacing = context.resources.getDimensionPixelSize(R.dimen.tv_dialog_action_spacing)
        items.forEach { item ->
            if (item == BrowseMenuItem.Divider) {
                panel.addView(View(context).apply { setBackgroundColor(0x40ffffff) },
                    LinearLayout.LayoutParams(-1, 1).apply { setMargins(0, spacing * 2, 0, spacing * 2) })
            } else {
                val row = LayoutInflater.from(context).inflate(R.layout.item_tv_dialog_action, panel, false) as TextView
                row.id = View.generateViewId()
                row.text = when (item) {
                    is BrowseMenuItem.Section -> item.section.title
                    is BrowseMenuItem.Site -> item.descriptor.name
                    BrowseMenuItem.Divider -> error("Divider is not an action")
                }
                row.isSelected = item is BrowseMenuItem.Section && item.section == selected
                if (row.isSelected) initial = row
                row.setOnClickListener { choose(item) }
                panel.addView(row, (row.layoutParams as LinearLayout.LayoutParams).apply { bottomMargin = spacing })
                actions += row
            }
        }
        actions.forEachIndexed { index, row ->
            row.nextFocusUpId = actions[(index - 1).coerceAtLeast(0)].id
            row.nextFocusDownId = actions[(index + 1).coerceAtMost(actions.lastIndex)].id
        }
        val scroll = ScrollView(context).apply {
            addView(panel)
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -2)
        }
        val dialog = DialogUtils.getDialogBuilder(context, "Browse").setView(scroll).create()
        // Keep DialogUtils's show/animation handler; select the active mode after it runs.
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.post {
                    val maxHeight = context.resources.getDimensionPixelSize(R.dimen.tv_dialog_actions_max_height)
                    if (scroll.height > maxHeight) scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
                    (initial ?: actions.firstOrNull())?.requestFocus()
                }
            }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        return dialog
    }
}
