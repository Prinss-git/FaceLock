package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.databinding.ItemLockerBuildingBinding
import com.eldroid.facelock.databinding.ItemLockerFloorBinding
import com.eldroid.facelock.databinding.ItemLockerRowBinding
import com.eldroid.facelock.presenter.lockers.LockerListItem

/**
 * Building → floor → locker list. Three row types from one flat list, so
 * collapsing a building is just a shorter list and DiffUtil animates it.
 */
class LockerGroupAdapter(
    private val onBuildingClick: (groupKey: String) -> Unit,
    private val onLockerClick: (lockerId: String) -> Unit
) : ListAdapter<LockerListItem, RecyclerView.ViewHolder>(DIFF) {

    class BuildingVH(val b: ItemLockerBuildingBinding) : RecyclerView.ViewHolder(b.root)
    class FloorVH(val b: ItemLockerFloorBinding) : RecyclerView.ViewHolder(b.root)
    class RowVH(val b: ItemLockerRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is LockerListItem.BuildingHeader -> TYPE_BUILDING
        is LockerListItem.FloorHeader -> TYPE_FLOOR
        is LockerListItem.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BUILDING -> BuildingVH(ItemLockerBuildingBinding.inflate(inflater, parent, false))
            TYPE_FLOOR -> FloorVH(ItemLockerFloorBinding.inflate(inflater, parent, false))
            else -> RowVH(ItemLockerRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LockerListItem.BuildingHeader -> bindBuilding(holder as BuildingVH, item)
            is LockerListItem.FloorHeader -> bindFloor(holder as FloorVH, item)
            is LockerListItem.Row -> bindRow(holder as RowVH, item)
        }
    }

    private fun bindBuilding(holder: BuildingVH, item: LockerListItem.BuildingHeader) {
        val ctx = holder.b.root.context
        with(holder.b) {
            val unsorted = item.name == null
            tvName.text = item.name ?: ctx.getString(R.string.building_unsorted)
            val counts = ctx.resources.getQuantityString(
                R.plurals.building_summary, item.total, item.total, item.free
            )
            tvSummary.text = item.code?.let { "$it · $counts" } ?: counts

            iconWrap.setBackgroundResource(
                if (unsorted) R.drawable.pill_warning else R.drawable.bg_icon_circle_accent
            )
            ivIcon.setImageResource(if (unsorted) R.drawable.ic_alert else R.drawable.ic_location)
            ivIcon.setColorFilter(ctx.getColor(if (unsorted) R.color.warning else R.color.accent_dark))

            ivChevron.rotation = if (item.expanded) 90f else 0f
            root.contentDescription = ctx.getString(
                if (item.expanded) R.string.cd_collapse_building else R.string.cd_expand_building,
                tvName.text
            )
            root.setOnClickListener { onBuildingClick(item.groupKey) }
        }
    }

    private fun bindFloor(holder: FloorVH, item: LockerListItem.FloorHeader) {
        holder.b.tvFloor.text = item.label
            ?: holder.b.root.context.getString(R.string.floor_unknown)
    }

    private fun bindRow(holder: RowVH, item: LockerListItem.Row) {
        val locker = item.locker
        val ctx = holder.b.root.context
        val style = LockerStatusStyle.of(locker.status)
        val color = ctx.getColor(style.tint)

        with(holder.b) {
            tvId.text = locker.id
            tvHolder.text = locker.assignedName?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.unassigned)

            tvStatus.text = LockerStatusStyle.label(locker.status)
            tvStatus.setBackgroundResource(style.pill)
            tvStatus.setTextColor(color)
            iconWrap.setBackgroundResource(style.pill)
            ivLocker.setImageResource(style.icon)
            ivLocker.setColorFilter(color)

            root.setOnClickListener { onLockerClick(locker.id) }
        }
    }

    companion object {
        private const val TYPE_BUILDING = 0
        private const val TYPE_FLOOR = 1
        private const val TYPE_ROW = 2

        val DIFF = object : DiffUtil.ItemCallback<LockerListItem>() {
            override fun areItemsTheSame(a: LockerListItem, b: LockerListItem) = a.key == b.key
            override fun areContentsTheSame(a: LockerListItem, b: LockerListItem) = a == b
        }
    }
}
