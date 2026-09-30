package com.eldroid.facelock.ui.admin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AdminActionRepository
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.FragmentListBinding
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.common.SectionItem
import com.eldroid.facelock.presenter.users.UsersContract
import com.eldroid.facelock.presenter.users.UsersContract.EmptyKind
import com.eldroid.facelock.presenter.users.UsersContract.Filter
import com.eldroid.facelock.presenter.users.UsersPresenter
import com.eldroid.facelock.ui.adapter.SectionedAdapter
import com.eldroid.facelock.ui.adapter.UserRows
import com.eldroid.facelock.util.NetworkMonitor
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.visible

/** Users tab (admins only) — the View in MVP. [UsersPresenter] owns the list and actions. */
class UserListFragment : Fragment(), UsersContract.View {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val holder: PresenterHolder by viewModels()
    private lateinit var presenter: UsersContract.Presenter
    private lateinit var adapter: SectionedAdapter<User, UserRows.VH>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val session = SessionManager(requireContext())
        // The presenter outlives this fragment (rotation), so its lambdas use the
        // application context, never the fragment.
        val app = requireContext().applicationContext
        presenter = holder.getOrCreate {
            UsersPresenter(
                userRepo = UserRepository(),
                trail = AdminTrail(AdminActionRepository()) { session.fullName },
                sectionTitle = { app.getString(sectionTitleOf(it)) },
                roleName = { app.getString(roleNameOf(it)) },
                isOnline = NetworkMonitor(requireContext())::isOnline
            )
        }

        val rows = UserRows(
            onToggleActive = { presenter.onToggleActiveClicked(it.uid) },
            onChangeRole = { presenter.onChangeRoleClicked(it.uid) },
            onDelete = { presenter.onDeleteClicked(it.uid) },
            currentUid = AuthRepository().currentUid
        )
        adapter = SectionedAdapter(rows) { presenter.onSectionToggled(it) }
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.tvHeading.setText(R.string.nav_users)
        binding.tvHeadingSub.setText(R.string.heading_sub_users)
        binding.tilSearch.hint = getString(R.string.search_users)
        binding.etSearch.doAfterTextChanged { presenter.onSearchChanged(it?.toString().orEmpty()) }
        binding.filterGroup.setOnCheckedStateChangeListener { _, ids ->
            presenter.onFilterSelected(
                when (ids.firstOrNull()) {
                    R.id.chipSuspended -> Filter.SUSPENDED
                    R.id.chipNoFace -> Filter.NO_FACE
                    R.id.chipNoLocker -> Filter.NO_LOCKER
                    else -> Filter.ALL
                }
            )
        }

        binding.fabAdd.visible(true)
        binding.fabAdd.text = getString(R.string.add_user)
        binding.fabAdd.setOnClickListener { openUserForm() }
        binding.empty.btnEmptyAction.setOnClickListener { openUserForm() }

        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6) binding.fabAdd.shrink() else if (dy < -6) binding.fabAdd.extend()
            }
        })

        presenter.attachView(this)
    }

    override fun onDestroyView() {
        presenter.detachView()
        super.onDestroyView()
        _binding = null
    }

    private fun openUserForm() {
        startActivity(Intent(requireContext(), UserFormActivity::class.java))
    }

    // ------------------------------------------------------------ list ----

    override fun showLoading() {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(true)
        binding.empty.root.visible(false)
    }

    override fun showUsers(items: List<SectionItem<User>>, count: Int) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        binding.empty.root.visible(false)
        adapter.submitList(items)
        binding.tvCount.text = resources.getQuantityString(R.plurals.user_count, count, count)
    }

    override fun showEmpty(kind: EmptyKind) {
        val binding = _binding ?: return
        val noMatch = kind == EmptyKind.NO_MATCH
        binding.skeleton.root.skeleton(false)
        adapter.submitList(emptyList())
        binding.tvCount.text = resources.getQuantityString(R.plurals.user_count, 0, 0)
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(if (noMatch) R.drawable.ic_search else R.drawable.ic_people)
            tvEmptyTitle.setText(if (noMatch) R.string.empty_search_title else R.string.empty_users_title)
            tvEmptyBody.setText(if (noMatch) R.string.empty_search_body else R.string.empty_users_body)
            btnEmptyAction.visible(!noMatch)
            btnEmptyAction.setText(R.string.add_user)
        }
    }

    /**
     * Terminal state: the listener failed and will not emit again, so say why
     * instead of leaving a spinner or an "all clear" empty list on screen.
     */
    override fun showLoadError(message: String) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(R.drawable.ic_alert)
            tvEmptyTitle.text = getString(R.string.load_failed_title)
            tvEmptyBody.text = message
            btnEmptyAction.visible(false)
        }
    }

    override fun showMessage(message: String) = snack(message)

    override fun showUndo(message: String) =
        snack(message, actionLabel = getString(R.string.action_undo)) { presenter.onUndoClicked() }

    // --------------------------------------------------------- filters ----

    override fun showFilterCounts(all: Int, suspended: Int, noFace: Int, noLocker: Int) {
        val binding = _binding ?: return
        binding.chipUsersAll.text = getString(R.string.status_all_n, all)
        binding.chipSuspended.text = getString(R.string.users_filter_suspended_n, suspended)
        binding.chipNoFace.text = getString(R.string.users_filter_no_face_n, noFace)
        binding.chipNoLocker.text = getString(R.string.users_filter_no_locker_n, noLocker)
    }

    override fun showFilter(filter: Filter) {
        _binding?.filterGroup?.check(
            when (filter) {
                Filter.ALL -> R.id.chipUsersAll
                Filter.SUSPENDED -> R.id.chipSuspended
                Filter.NO_FACE -> R.id.chipNoFace
                Filter.NO_LOCKER -> R.id.chipNoLocker
            }
        )
    }

    // --------------------------------------------------------- dialogs ----

    override fun confirmSuspend(user: User) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.suspend_title, user.fullName))
            .setMessage(R.string.suspend_body)
            .setPositiveButton(R.string.action_suspend) { _, _ -> presenter.onSuspendConfirmed(user.uid) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun showRolePicker(user: User) {
        val roles = Role.values()
        val labels = roles.map { getString(roleNameOf(it)) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.set_role_for, user.fullName))
            .setSingleChoiceItems(labels, roles.indexOf(user.roleEnum)) { dialog, which ->
                dialog.dismiss()
                presenter.onRolePicked(user.uid, roles[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun confirmDelete(user: User) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remove_title, user.fullName))
            .setMessage(R.string.remove_body)
            .setPositiveButton(R.string.action_remove_short) { _, _ ->
                presenter.onDeleteConfirmed(user.uid)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

}

private fun roleNameOf(role: Role) = when (role) {
    Role.ADMIN -> R.string.role_admin
    Role.SECURITY -> R.string.role_security
    Role.USER -> R.string.role_member
}

private fun sectionTitleOf(role: Role) = when (role) {
    Role.ADMIN -> R.string.users_group_admins
    Role.SECURITY -> R.string.users_group_security
    Role.USER -> R.string.users_group_members
}
