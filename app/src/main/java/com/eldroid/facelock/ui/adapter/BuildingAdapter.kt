package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.databinding.ItemBuildingBinding
import com.eldroid.facelock.presenter.buildings.BuildingRow

class BuildingAdapter(
    private val onEdit: (code: String) -> Unit,
    private val onDelete: (code: String) -> Unit
) : ListAdapter<BuildingRow, BuildingAdapter.VH>(DIFF) {

    inner class VH(val b: ItemBuildingBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemBuildingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val building = row.building
        val res = holder.b.root.resources

        with(holder.b) {
            tvCode.text = building.code
            tvName.text = building.name
            // "3 floors (GF–3F) · 12 lockers · 4 free"
            val floors = res.getQuantityString(R.plurals.floor_count, building.floors, building.floors) +
                " (${building.labelFor(1)}–${building.labelFor(building.floors)})"
                    .takeIf { building.floors > 1 }.orEmpty()
            tvDetails.text = listOf(
                floors,
                res.getQuantityString(
                    R.plurals.building_summary, row.lockerCount, row.lockerCount, row.freeCount
                )
            ).joinToString(" · ")

            btnEdit.setOnClickListener { onEdit(building.code) }
            btnDelete.setOnClickListener { onDelete(building.code) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<BuildingRow>() {
            override fun areItemsTheSame(a: BuildingRow, b: BuildingRow) =
                a.building.code == b.building.code
            override fun areContentsTheSame(a: BuildingRow, b: BuildingRow) = a == b
        }
    }
}
