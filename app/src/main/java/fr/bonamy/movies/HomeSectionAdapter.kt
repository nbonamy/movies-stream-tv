package fr.bonamy.movies

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Full-width home sections scroll with the catalog instead of shrinking its viewport. */
internal class HomeSectionAdapter(private val view: View, visible: Boolean = true) :
    RecyclerView.Adapter<MediaCardAdapter.Holder>() {
    var visible = visible
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
        }
    override fun getItemCount() = if (visible) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MediaCardAdapter.Holder(view.apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    })
    override fun onBindViewHolder(holder: MediaCardAdapter.Holder, position: Int) = Unit
}
