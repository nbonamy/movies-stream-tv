package fr.bonamy.movies

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import fr.bonamy.movies.core.SearchRow
import fr.bonamy.movies.core.Title

/** Native TV rows: each site retains its horizontal position when moving up/down. */
internal class SearchResultsView(
    context: Context,
    private val image: (String, ImageView) -> Unit,
    private val select: (Title, View) -> Unit,
    private val loadMore: (String) -> Unit,
    private val longPress: ((Title, View) -> Unit)? = null,
) : VerticalGridView(context) {
    private var rows = emptyList<SearchRow>()
    private var focusWhenReady = false
    private val rowAdapter = object : RecyclerView.Adapter<RowHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = rows.size
        override fun getItemId(position: Int) = position.toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder()
        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])
    }

    init {
        id = View.generateViewId()
        setNumColumns(1)
        windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
        windowAlignmentOffsetPercent = 0f
        itemAlignmentOffsetPercent = 0f
        saveChildrenPolicy = BaseGridView.SAVE_ALL_CHILD
        clipToPadding = false
        clipChildren = false
        itemAnimator = null
        setPadding(0, 0, 0, dp(32))
        adapter = rowAdapter
    }

    fun submit(value: List<SearchRow>) {
        val previous = rows
        rows = value
        if (previous.map { it.site.id } != value.map { it.site.id }) rowAdapter.notifyDataSetChanged()
        else value.indices.filter { previous[it] != value[it] }.forEach { rowAdapter.notifyItemChanged(it, true) }
        focusReadyResults()
    }

    fun focusResults() {
        focusWhenReady = true
        requestFocus()
        focusReadyResults()
    }

    fun cancelPendingFocus() { focusWhenReady = false }

    private fun focusReadyResults() {
        if (!focusWhenReady) return
        val first = rows.indexOfFirst { it.items.isNotEmpty() }
        if (first < 0) return
        post {
            if (focusWhenReady) {
                focusWhenReady = false
                selectedPosition = first
                requestFocus()
            }
        }
    }

    private inner class RowHolder : RecyclerView.ViewHolder(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2)
        clipChildren = false
        clipToPadding = false
    }) {
        private val panel = itemView as LinearLayout
        private val heading = LayoutInflater.from(context).inflate(R.layout.section_header, panel, false)
        private val grid = HorizontalGridView(context).apply {
            id = R.id.search_site_titles
            setNumRows(1)
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            setHasFixedSize(false)
            setHorizontalSpacing(dp(14))
            windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
            windowAlignmentOffsetPercent = 0f
            itemAlignmentOffsetPercent = 0f
            clipChildren = false
            clipToPadding = false
            itemAnimator = null
            setPadding(0, 0, 0, dp(4))
        }
        private var row: SearchRow? = null
        private var boundItems = emptyList<Title>()
        private val cards = MediaCardAdapter(image = image, select = { index, view ->
            row?.items?.getOrNull(index)?.let { select(it, view) }
        }, longPress = longPress?.let { action -> { index, view ->
            row?.items?.getOrNull(index)?.let { action(it, view) }
        } }, focus = { index ->
            row?.let { if (index >= it.items.size - 4 && it.canLoadMore && !it.loading && !it.failed) loadMore(it.site.id) }
        })
        private val action = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = ResourcesCompat.getFont(context, R.font.theme)
            setTextColor(Color.LTGRAY)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.browser_card_background)
            foreground = context.getDrawable(R.drawable.browser_card_focus_foreground)
            setOnClickListener { row?.let { if (!it.loading && (it.canLoadMore || it.failed)) loadMore(it.site.id) } }
        }
        private val actionAdapter = object : RecyclerView.Adapter<MediaCardAdapter.Holder>() {
            var visible = true
                set(value) {
                    if (field == value) return
                    field = value
                    if (value) notifyItemInserted(0) else notifyItemRemoved(0)
                }
            override fun getItemCount() = if (visible) 1 else 0
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MediaCardAdapter.Holder(action.apply {
                layoutParams = RecyclerView.LayoutParams(dp(200), dp(92)).apply {
                    setMargins(dp(7), dp(10), dp(7), dp(10))
                }
            })
            override fun onBindViewHolder(holder: MediaCardAdapter.Holder, position: Int) = Unit
        }

        init {
            panel.addView(heading)
            panel.addView(grid, LinearLayout.LayoutParams(-1, -2))
            grid.adapter = ConcatAdapter(cards, actionAdapter)
        }

        fun bind(value: SearchRow) {
            val actionHadFocus = action.hasFocus()
            val wasEmpty = boundItems.isEmpty()
            val firstAdded = boundItems.size
            row = value
            heading.findViewById<TextView>(R.id.title).text = value.site.name
            if (value.items.take(boundItems.size) != boundItems) {
                cards.clear()
                boundItems = emptyList()
                grid.selectedPosition = 0
            }
            cards.append(value.items.drop(boundItems.size).map {
                MediaCard(it.ref, "${it.title} · ${if (it.type == fr.bonamy.movies.core.MediaType.TV) "TV" else "Movie"}", it.poster)
            })
            boundItems = value.items
            action.text = when {
                value.loading -> if (value.items.isEmpty()) "Searching…" else "Loading more…"
                value.failed -> if (value.items.isEmpty()) "Search unavailable\nSelect to retry" else "Some results unavailable\nSelect to retry"
                value.canLoadMore -> "Load more"
                else -> "No results"
            }
            action.contentDescription = "${value.site.name}: ${action.text}"
            actionAdapter.visible = value.items.isEmpty() || value.loading || value.failed || value.canLoadMore
            // Inserting before the loading/retry tile moves its adapter position.
            // Start new rows at the first title, never anchored to that trailing tile.
            if (wasEmpty && value.items.isNotEmpty()) grid.post {
                grid.selectedPosition = 0
                if (grid.hasFocus()) grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
            else if (actionHadFocus && value.items.size > firstAdded) grid.post { grid.selectedPosition = firstAdded }
            else if (actionHadFocus && value.items.isNotEmpty() && !actionAdapter.visible) {
                grid.selectedPosition = value.items.lastIndex
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
