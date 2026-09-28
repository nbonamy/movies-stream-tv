package fr.bonamy.movies

import android.graphics.Color
import android.graphics.Outline
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView

internal data class MediaCard(val id: Any, val title: String, val image: String, val progress: Int? = null)

/** MediaStation cards with recycling for long movie catalogs and TV seasons. */
internal class MediaCardAdapter(
    private val landscape: Boolean = false,
    private val image: (String, ImageView) -> Unit,
    private val select: (Int, View) -> Unit,
    private val focus: (Int) -> Unit = {},
) : RecyclerView.Adapter<MediaCardAdapter.Holder>() {
    private val items = mutableListOf<MediaCard>()
    private val stableIds = mutableMapOf<Any, Long>()
    init { setHasStableIds(true) }
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = stableIds.getOrPut(items[position].id) { stableIds.size.toLong() }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }
    fun append(cards: List<MediaCard>) {
        val start = items.size
        items.addAll(cards)
        notifyItemRangeInserted(start, cards.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = LayoutInflater.from(parent.context).inflate(R.layout.item_media_item_video, parent, false)
        val density = parent.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        card.layoutParams = RecyclerView.LayoutParams(
            if (landscape) dp(200) else parent.resources.getDimensionPixelSize(R.dimen.card_video_width),
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(7), dp(10), dp(7), dp(10)) }
        card.isFocusable = true
        card.isClickable = true
        card.foreground = parent.context.getDrawable(R.drawable.browser_card_focus_foreground)
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
            }
        }
        card.findViewById<ImageView>(R.id.thumbnail).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (landscape) layoutParams = layoutParams.apply { width = dp(200); height = dp(113) }
        }
        card.findViewById<TextView>(R.id.title).apply {
            setTextColor(Color.argb(208, 255, 255, 255))
            if (landscape) textSize = 13f
            else setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val holder = Holder(card)
        card.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { select(it, card) }
        }
        card.setOnFocusChangeListener { _, focused ->
            if (focused) holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(focus)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.itemView.contentDescription = item.title
        holder.itemView.findViewById<TextView>(R.id.title).text = item.title
        holder.itemView.findViewById<ProgressBar>(R.id.progress).apply {
            visibility = if (item.progress == null) View.GONE else View.VISIBLE
            progress = item.progress ?: 0
        }
        holder.itemView.findViewById<ImageView>(R.id.thumbnail).apply {
            setImageDrawable(null)
            image(item.image, this)
        }
    }
}
