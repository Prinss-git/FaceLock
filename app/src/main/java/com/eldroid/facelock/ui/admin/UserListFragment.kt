package com.eldroid.facelock.ui.admin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.model.AdminActionType
import com.eldroid.facelock.data.repo.AdminActionRepository
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.databinding.FragmentListBinding
import com.eldroid.facelock.presenter.common.CollapseState
import com.eldroid.facelock.presenter.common.Group
import com.eldroid.facelock.ui.adapter.SectionedAdapter
import com.eldroid.facelock.ui.adapter.UserRows
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.launch

class UserListFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val userRepo = UserRepository()
    private val trail by lazy {
        AdminTrail(AdminActionRepository()) { SessionManager(requireContext()).fullName }
    }
    private val authRepo = AuthRepository()
    private lateinit var adapter: SectionedAdapter<User, UserRows.VH>

    private var users: List<User> = emptyList()
    private var query: String = ""

    /** Role sections, all open until tapped. */
    private val sections = CollapseState()
    private var roleGroups: List<Group<User>> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sections.restore(
            savedInstanceState?.getStringArrayList(KEY_OPEN),
            savedInstanceState?.getStringArrayList(KEY_CLOSED)
        )
        val rows = UserRows(
            onToggleActive = { user -> confirmToggleActive(user) },
            onChangeRole = { user -> showRoleDialog(user) },
            onDelete = { user -> confirmDelete(user) },
            currentUid = authRepo.currentUid
        )
        adapter = SectionedAdapter(rows) { key ->
            sections.toggle(key, roleGroups)
            applyFilter()
        }
        binding.skeleton.root.skeleton(true)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.tvHeading.setText(R.string.nav_users)
        binding.tvHeadingSub.setText(R.string.heading_sub_users)
        binding.tilSearch.hint = getString(R.string.search_users)
        binding.etSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            applyFilter()
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

        viewLifecycleOwner.lifecycleScope.launch {
            userRepo.observeUsers()
                .catchFirestore("the user list") { showLoadError(it) }
                .collect { list ->
                    users = list
                    binding.skeleton.root.skeleton(false)
                    applyFilter()
                }
        }
    }

    private fun openUserForm() {
        startActivity(Intent(requireContext(), UserFormActivity::class.java))
    }

    private fun applyFilter() {
        val binding = _binding ?: return
        val shown =
            if (query.isBlank()) users
            else users.filter {
                it.fullName.contains(query, true) ||
                    it.email.contains(query, true) ||
                    it.role.contains(query, true) ||
                    it.lockerId?.contains(query, true) == true
            }

        // Staff first: they are few and the ones an admin looks for most.
        roleGroups = listOf(
            Role.ADMIN to R.string.users_group_admins,
            Role.SECURITY to R.string.users_group_security,
            Role.USER to R.string.users_group_members
        ).mapNotNull { (role, title) ->
            shown.filter { it.roleEnum == role }.takeIf { it.isNotEmpty() }
                ?.let { Group(role.name, getString(title), it) }
        }
        adapter.submitList(sections.flatten(roleGroups, forceOpen = query.isNotBlank()) { it.uid })
        binding.tvCount.text =
            resources.getQuantityString(R.plurals.user_count, shown.size, shown.size)

        val searching = query.isNotBlank()
        binding.empty.root.visible(shown.isEmpty())
        binding.empty.ivEmpty.setImageResource(
            if (searching) R.drawable.ic_search else R.drawable.ic_people
        )
        binding.empty.tvEmptyTitle.text = getString(
            if (searching) R.string.empty_search_title else R.string.empty_users_title
        )
        binding.empty.tvEmptyBody.text = getString(
            if (searching) R.string.empty_search_body else R.string.empty_users_body
        )
        binding.empty.btnEmptyAction.visible(!searching && users.isEmpty())
        binding.empty.btnEmptyAction.text = getString(R.string.add_user)
    }

    // ------------------------------------------------------------ actions ----

    private fun confirmToggleActive(user: User) {
        val nowActive = !user.active
        if (nowActive) {
            setActive(user, true)
            return
        }
        // Suspending is the one that locks someone out, so it gets a confirm.
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.suspend_title, user.fullName))
            .setMessage(R.string.suspend_body)
            .setPositiveButton(R.string.action_suspend) { _, _ -> setActive(user, false) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun setActive(user: User, active: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            userRepo.setActive(user.uid, active)
                .onSuccess {
                    trail.record(
                        if (active) AdminActionType.USER_RESTORED else AdminActionType.USER_SUSPENDED,
                        user.fullName
                    )
                    snack(
                        if (active) "${user.fullName} reactivated"
                        else "${user.fullName} suspended",
                        actionLabel = "Undo"
                    ) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            userRepo.setActive(user.uid, !active)
                                .onSuccess {
                                    trail.record(
                                        if (active) AdminActionType.USER_SUSPENDED
                                        else AdminActionType.USER_RESTORED,
                                        user.fullName, "undo"
                                    )
                                }
                                .onFailure { snack(it.message ?: "Undo failed") }
                        }
                    }
                }
                .onFailure { snack(it.message ?: "Could not update ${user.fullName}") }
        }
    }

    private fun showRoleDialog(user: User) {
        val roles = Role.values()
        val labels = roles.map {
            when (it) {
                Role.ADMIN -> getString(R.string.role_admin)
                Role.SECURITY -> getString(R.string.role_security)
                Role.USER -> getString(R.string.role_member)
            }
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.set_role_for, user.fullName))
            .setSingleChoiceItems(labels, roles.indexOf(user.roleEnum)) { dialog, which ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepo.updateUser(user.uid, mapOf("role" to roles[which].name))
                        .onSuccess {
                            snack("${user.fullName} is now ${labels[which]}")
                            trail.record(
                                AdminActionType.USER_ROLE_CHANGED, user.fullName,
                                "${user.roleEnum.name} → ${roles[which].name}"
                            )
                        }
                        .onFailure { snack(it.message ?: "Could not change the role") }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(user: User) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remove_title, user.fullName))
            .setMessage(R.string.remove_body)
            .setPositiveButton(R.string.action_remove_short) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepo.deleteUser(user)
                        .onSuccess {
                            snack("${user.fullName} removed")
                            trail.record(
                                AdminActionType.USER_DELETED, user.fullName,
                                user.lockerId?.let { "freed $it" }
                            )
                        }
                        .onFailure { snack(it.message ?: "Delete failed") }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Terminal state: the listener failed and will not emit again, so say why
     * instead of leaving a spinner or an "all clear" empty list on screen.
     */
    private fun showLoadError(message: String) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        binding.empty.root.visible(true)
        binding.empty.ivEmpty.setImageResource(R.drawable.ic_alert)
        binding.empty.tvEmptyTitle.text = getString(R.string.load_failed_title)
        binding.empty.tvEmptyBody.text = message
        binding.empty.btnEmptyAction.visible(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_OPEN, sections.openKeys())
        outState.putStringArrayList(KEY_CLOSED, sections.closedKeys())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val KEY_OPEN = "open_roles"
        const val KEY_CLOSED = "closed_roles"
    }
}
