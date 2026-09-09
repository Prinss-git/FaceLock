package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.databinding.ItemLogBinding
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.visible

class LogAdapter : ListAdapter<AccessLog, LogAdapter.VH>(DIFF) {

    inner class VH(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val log = getItem(position)
        val ctx = holder.b.root.context
        val granted = log.granted

        val tint = ctx.getColor(if (granted) R.color.granted else R.color.denied)
        val pill = if (granted) R.drawable.pill_granted else R.drawable.pill_denied

        with(holder.b) {
            tvUser.text = log.userName ?: ctx.getString(R.string.unrecognized_person)
            tvLocker.text = ctx.getString(R.string.locker_named, log.lockerId)
            tvTime.text = log.timestamp.asRelativeDateTime()

            tvResult.text = ctx.getString(
                if (granted) R.string.filter_granted else R.string.filter_denied
            )
            tvResult.setBackgroundResource(pill)
            tvResult.setTextColor(tint)

            // The icon and the colour rail carry the result before the label is read.
            ivResult.setImageResource(if (granted) R.drawable.ic_check else R.drawable.ic_alert)
            ivResult.setColorFilter(tint)
            iconWrap.setBackgroundResource(pill)
            viewIndicator.setBackgroundColor(tint)

            log.confidence?.let {
                tvConfidence.visible(true)
                tvConfidence.text = ctx.getString(R.string.match_percent, (it * 100).toInt())
            } ?: tvConfidence.visible(false)

            // Screen readers get the whole row as one sentence.
            root.contentDescription = buildString {
                append(tvUser.text).append(", ")
                append(tvResult.text).append(", ")
                append(tvLocker.text).append(", ")
                append(tvTime.text)
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AccessLog>() {
            override fun areItemsTheSame(a: AccessLog, b: AccessLog) = a.id == b.id
            override fun areContentsTheSame(a: AccessLog, b: AccessLog) = a == b
        }
    }
}
