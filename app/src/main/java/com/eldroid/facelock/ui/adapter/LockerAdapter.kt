package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.databinding.ItemLockerBinding
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.visible

class LockerAdapter(
    private val onAssign: (Locker) -> Unit,
    private val onUnlock: (Locker) -> Unit,
    private val showActions: Boolean
) : ListAdapter<Locker, LockerAdapter.VH>(DIFF) {

    inner class VH(val b: ItemLockerBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemLockerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val locker = getItem(position)
        val ctx = holder.b.root.context

        with(holder.b) {
            tvLabel.text = locker.label.ifBlank { locker.id }
            tvLocation.text = locker.location.ifBlank { ctx.getString(R.string.no_location) }

            tvAssigned.text = locker.assignedName?.let {
                ctx.getString(R.string.assigned_to, it)
            } ?: ctx.getString(R.string.unassigned)

            val (pill, tint, icon) = when (locker.status) {
                Locker.STATUS_OCCUPIED ->
                    Triple(R.drawable.pill_accent, R.color.accent_dark, R.drawable.ic_lock)
                Locker.STATUS_OFFLINE ->
                    Triple(R.drawable.pill_denied, R.color.denied, R.drawable.ic_wifi_off)
                Locker.STATUS_LOCKED ->
                    Triple(R.drawable.pill_neutral, R.color.text_secondary, R.drawable.ic_lock)
                else ->
                    Triple(R.drawable.pill_granted, R.color.granted, R.drawable.ic_lock_open)
            }
            val color = ctx.getColor(tint)

            tvStatus.text = locker.status.lowercase().replaceFirstChar { it.uppercase() }
            tvStatus.setBackgroundResource(pill)
            tvStatus.setTextColor(color)

            // Leading badge mirrors the status, so the list scans by colour.
            iconWrap.setBackgroundResource(pill)
            ivLocker.setImageResource(icon)
            ivLocker.setColorFilter(color)

            locker.lastOpenedAt?.let {
                rowLastOpened.visible(true)
                tvLastOpened.text = ctx.getString(R.string.last_opened, it.asRelativeDateTime())
            } ?: rowLastOpened.visible(false)

            actionRow.visible(showActions)
            btnAssign.text = ctx.getString(
                if (locker.isAvailable) R.string.action_assign else R.string.action_reassign
            )
            btnAssign.setOnClickListener { onAssign(locker) }

            // Unlocking an offline unit would silently do nothing.
            val online = locker.status != Locker.STATUS_OFFLINE
            btnUnlock.isEnabled = online
            btnUnlock.alpha = if (online) 1f else 0.45f
            btnUnlock.setOnClickListener { onUnlock(locker) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Locker>() {
            override fun areItemsTheSame(a: Locker, b: Locker) = a.id == b.id
            override fun areContentsTheSame(a: Locker, b: Locker) = a == b
        }
    }
}
