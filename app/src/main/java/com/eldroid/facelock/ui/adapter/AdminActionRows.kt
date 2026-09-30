package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AdminAction
import com.eldroid.facelock.databinding.ItemAdminActionBinding
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.initials
import com.eldroid.facelock.util.visible

/** One activity-trail row. */
class AdminActionRows : RowBinder<AdminAction, AdminActionRows.VH> {

    class VH(val b: ItemAdminActionBinding) : RecyclerView.ViewHolder(b.root)

    override fun create(parent: ViewGroup) = VH(
        ItemAdminActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun bind(holder: VH, item: AdminAction) {
        val action = item
        with(holder.b) {
            tvInitials.text = action.actorName.initials()
            // An unknown action name (from a newer app version) still reads sensibly.
            val label = action.type?.label ?: action.action
            tvWhat.text = root.context.getString(R.string.dot_pair, label, action.target)
            tvDetails.visible(!action.details.isNullOrBlank())
            tvDetails.text = action.details
            tvWho.text = root.context.getString(
                R.string.dot_pair, action.actorName, action.timestamp.asRelativeDateTime()
            )
        }
    }
}
