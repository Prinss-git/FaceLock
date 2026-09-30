package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.databinding.ItemAssignCandidateBinding
import com.eldroid.facelock.presenter.lockers.AssignCandidate
import com.eldroid.facelock.util.initials
import com.eldroid.facelock.util.visible

/**
 * Member picker rows for the "Assign locker" dialog.
 *
 * Each row carries enough context to choose well: the current holder is
 * tagged, and so is anyone who already holds a different locker, because
 * picking them moves them off it.
 */
class AssignCandidateAdapter(
    private val onPick: (User) -> Unit
) : ListAdapter<AssignCandidate, AssignCandidateAdapter.VH>(DIFF) {

    inner class VH(val b: ItemAssignCandidateBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemAssignCandidateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val candidate = getItem(position)
        val user = candidate.user
        val ctx = holder.b.root.context

        with(holder.b) {
            tvInitials.text = user.fullName.initials()
            tvName.text = user.fullName
            tvEmail.text = user.email

            when {
                candidate.isCurrentHolder -> {
                    tvTag.text = ctx.getString(R.string.assign_tag_current)
                    tvTag.setBackgroundResource(R.drawable.pill_granted)
                    tvTag.setTextColor(ctx.getColor(R.color.granted))
                }
                candidate.otherLockerId != null -> {
                    tvTag.text = ctx.getString(R.string.assign_tag_has, candidate.otherLockerId)
                    tvTag.setBackgroundResource(R.drawable.pill_warning)
                    tvTag.setTextColor(ctx.getColor(R.color.warning))
                }
            }
            tvTag.visible(candidate.isCurrentHolder || candidate.otherLockerId != null)

            // Re-picking the current holder would be a no-op write.
            root.isEnabled = !candidate.isCurrentHolder
            root.setOnClickListener { onPick(user) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AssignCandidate>() {
            override fun areItemsTheSame(a: AssignCandidate, b: AssignCandidate) =
                a.user.uid == b.user.uid
            override fun areContentsTheSame(a: AssignCandidate, b: AssignCandidate) = a == b
        }
    }
}
