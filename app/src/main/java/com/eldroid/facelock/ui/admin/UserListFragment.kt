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
import com.eldroid.facelock.data.model.PasswordResetRequest
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.PasswordResetRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.DialogTempPasswordBinding
import com.eldroid.facelock.databinding.FragmentListBinding
import com.eldroid.facelock.ui.adapter.UserAdapter
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.launch

class UserListFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val userRepo = UserRepository()
    private val authRepo = AuthRepository()
    private val resetRepo = PasswordResetRepository()
    private lateinit var adapter: UserAdapter

    private var users: List<User> = emptyList()
    private var query: String = ""
    private var pendingResets: List<PasswordResetRequest> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = UserAdapter(
            onToggleActive = { user -> confirmToggleActive(user) },
            onChangeRole = { user -> showRoleDialog(user) },
            onDelete = { user -> confirmDelete(user) },
            onResetPassword = { user -> confirmReset(user) },
            currentUid = authRepo.currentUid
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

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

        binding.bannerReset.setOnClickListener { showResetQueue() }

        viewLifecycleOwner.lifecycleScope.launch {
            userRepo.observeUsers()
                .catchFirestore("the user list") { showLoadError(it) }
                .collect { list ->
                    users = list
                    binding.progress.visible(false)
                    applyFilter()
                }
        }

        // Members who asked for a reset from the sign-in screen. Written only
        // by the Cloud Function, readable here because the caller is an admin.
        viewLifecycleOwner.lifecycleScope.launch {
            resetRepo.observePendingRequests()
                .catchFirestore("password reset requests") { snack(it) }
                .collect { requests ->
                    pendingResets = requests
                    renderResetBanner()
                }
        }
    }

    private fun renderResetBanner() {
        val binding = _binding ?: return
        binding.bannerReset.visible(pendingResets.isNotEmpty())
        if (pendingResets.isNotEmpty()) {
            binding.tvBannerReset.text = resources.getQuantityString(
                R.plurals.reset_pending_banner, pendingResets.size, pendingResets.size
            )
        }
    }

    /** The queue, as a picker: choosing someone goes straight to confirming. */
    private fun showResetQueue() {
        val requests = pendingResets
        if (requests.isEmpty()) return

        val labels = requests.map { it.label }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_queue_title)
            .setItems(labels) { _, which ->
                val request = requests[which]
                val user = users.firstOrNull { it.uid == request.uid }
                confirmReset(user, request)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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

        adapter.submitList(shown)
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
            snack(
                if (active) "${user.fullName} reactivated"
                else "${user.fullName} suspended",
                actionLabel = "Undo"
            ) {
                viewLifecycleOwner.lifecycleScope.launch {
                    userRepo.setActive(user.uid, !active)
                }
            }
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
                    snack("${user.fullName} is now ${labels[which]}")
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
                    userRepo.deleteUser(user.uid)
                        .onSuccess { snack("${user.fullName} removed") }
                        .onFailure { snack(it.message ?: "Delete failed") }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // -------------------------------------------------------- password ----

    private fun confirmReset(user: User?, request: PasswordResetRequest? = null) {
        val name = user?.fullName ?: request?.label ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.reset_confirm_title, name))
            .setMessage(R.string.reset_confirm_body)
            .setPositiveButton(R.string.reset_confirm_action) { _, _ ->
                doReset(name, user?.uid, request?.id)
            }
            .setNeutralButton(
                if (request != null) R.string.reset_dismiss else R.string.action_cancel
            ) { _, _ ->
                if (request != null) dismissRequest(request)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun doReset(name: String, uid: String?, requestId: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            resetRepo.resolve(requestId = requestId, uid = uid, approve = true)
                .onSuccess { temp ->
                    if (temp != null) showTempPassword(name, temp) else snack("Reset failed.")
                }
                .onFailure { snack(it.message ?: "Reset failed.") }
        }
    }

    private fun dismissRequest(request: PasswordResetRequest) {
        viewLifecycleOwner.lifecycleScope.launch {
            resetRepo.resolve(requestId = request.id, approve = false)
                .onSuccess { snack(getString(R.string.reset_rejected)) }
                .onFailure { snack(it.message ?: "Could not dismiss it.") }
        }
    }

    /**
     * The temporary password exists only in this dialog: the backend does not
     * store it, so it cannot be shown again.
     */
    private fun showTempPassword(name: String, password: String) {
        val dialogBinding = DialogTempPasswordBinding.inflate(layoutInflater)
        dialogBinding.tvFor.text = getString(R.string.temp_password_for, name)
        dialogBinding.tvPassword.text = password
        dialogBinding.btnCopy.setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    getString(R.string.temp_password_title), password
                )
            )
            snack(getString(R.string.password_copied))
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.temp_password_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.reset_done, null)
            .setCancelable(false)
            .show()
    }

    /**
     * Terminal state: the listener failed and will not emit again, so say why
     * instead of leaving a spinner or an "all clear" empty list on screen.
     */
    private fun showLoadError(message: String) {
        val binding = _binding ?: return
        binding.progress.visible(false)
        binding.empty.root.visible(true)
        binding.empty.ivEmpty.setImageResource(R.drawable.ic_alert)
        binding.empty.tvEmptyTitle.text = getString(R.string.load_failed_title)
        binding.empty.tvEmptyBody.text = message
        binding.empty.btnEmptyAction.visible(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
