package com.eldroid.facelock.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.databinding.ItemUserBinding
import com.eldroid.facelock.util.initials
import com.eldroid.facelock.util.visible

class UserAdapter(
    private val onToggleActive: (User) -> Unit,
    private val onChangeRole: (User) -> Unit,
    private val onDelete: (User) -> Unit,
    private val onResetPassword: (User) -> Unit,
    /** The signed-in admin, so the row for their own account can be protected. */
    private val currentUid: String? = null
) : ListAdapter<User, UserAdapter.VH>(DIFF) {

    inner class VH(val b: ItemUserBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val user = getItem(position)
        val ctx = holder.b.root.context
        val isSelf = user.uid == currentUid

        with(holder.b) {
            tvInitials.text = user.fullName.initials()
            tvName.text =
                if (isSelf) ctx.getString(R.string.name_you, user.fullName) else user.fullName
            tvEmail.text = user.email

            tvRole.text = when (user.roleEnum) {
                Role.ADMIN -> ctx.getString(R.string.role_admin)
                Role.SECURITY -> ctx.getString(R.string.role_security)
                Role.USER -> ctx.getString(R.string.role_member)
            }
            tvLocker.text = user.lockerId?.let { ctx.getString(R.string.locker_named, it) }
                ?: ctx.getString(R.string.no_locker)

            tvFace.text = ctx.getString(
                if (user.faceEnrolled) R.string.face_enrolled_short else R.string.not_enrolled
            )
            tvFace.setTextColor(
                ctx.getColor(if (user.faceEnrolled) R.color.granted else R.color.warning)
            )

            tvStatus.text =
                ctx.getString(if (user.active) R.string.active else R.string.suspended)
            tvStatus.setBackgroundResource(
                if (user.active) R.drawable.pill_granted else R.drawable.pill_denied
            )
            tvStatus.setTextColor(
                ctx.getColor(if (user.active) R.color.granted else R.color.denied)
            )

            btnToggle.text =
                ctx.getString(if (user.active) R.string.action_suspend else R.string.action_restore)
            btnToggle.setIconResource(
                if (user.active) R.drawable.ic_ban else R.drawable.ic_restore
            )

            // An admin locking or deleting their own account would strand the
            // system, so those two actions are removed on their own row.
            btnToggle.visible(!isSelf)
            btnDelete.visible(!isSelf)
            // Resetting your own password here would lock you out of the very
            // screen you would need to undo it; Profile has the safe path.
            btnReset.visible(!isSelf)

            // A pending request is the reason an admin is looking at this row.
            btnReset.setOnClickListener { onResetPassword(user) }

            btnToggle.setOnClickListener { onToggleActive(user) }
            btnRole.setOnClickListener { onChangeRole(user) }
            btnDelete.setOnClickListener { onDelete(user) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<User>() {
            override fun areItemsTheSame(a: User, b: User) = a.uid == b.uid
            override fun areContentsTheSame(a: User, b: User) = a == b
        }
    }
}
