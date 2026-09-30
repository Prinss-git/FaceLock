package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.databinding.ItemSectionHeaderBinding
import com.eldroid.facelock.presenter.common.SectionItem

/** Draws one kind of row; shared by a plain list and its sectioned version. */
interface RowBinder<T, VH : RecyclerView.ViewHolder> {
    fun create(parent: ViewGroup): VH
    fun bind(holder: VH, item: T)
}

/** Collapsible sections around any [RowBinder]'s rows. */
class SectionedAdapter<T, VH : RecyclerView.ViewHolder>(
    private val rows: RowBinder<T, VH>,
    private val onHeaderClick: (groupKey: String) -> Unit
) : ListAdapter<SectionItem<T>, RecyclerView.ViewHolder>(diff()) {

    class HeaderVH(val b: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) =
        if (getItem(position) is SectionItem.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            rows.create(parent)
        }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SectionItem.Header -> bindHeader(holder as HeaderVH, item)
            is SectionItem.Entry -> rows.bind(holder as VH, item.value)
        }
    }

    private fun bindHeader(holder: HeaderVH, item: SectionItem.Header) {
        val ctx = holder.b.root.context
        with(holder.b) {
            tvTitle.text = item.title
            tvCount.text = item.count.toString()
            ivChevron.rotation = if (item.expanded) 90f else 0f
            root.contentDescription = ctx.getString(
                if (item.expanded) R.string.cd_collapse_section else R.string.cd_expand_section,
                item.title, item.count
            )
            root.setOnClickListener { onHeaderClick(item.groupKey) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1

        fun <T> diff() = object : DiffUtil.ItemCallback<SectionItem<T>>() {
            override fun areItemsTheSame(a: SectionItem<T>, b: SectionItem<T>) = a.key == b.key
            override fun areContentsTheSame(a: SectionItem<T>, b: SectionItem<T>) = a == b
        }
    }
}
