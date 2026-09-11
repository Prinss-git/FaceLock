package com.eldroid.facelock.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.DialogTextInputBinding
import com.eldroid.facelock.databinding.FragmentListBinding
import com.eldroid.facelock.ui.adapter.LockerAdapter
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LockerListFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val lockerRepo = LockerRepository()
    private val userRepo = UserRepository()
    private lateinit var adapter: LockerAdapter

    private var users: List<User> = emptyList()
    private var lockers: List<Locker> = emptyList()
    private var query: String = ""
    private var isAdmin = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        isAdmin = SessionManager(requireContext()).role == Role.ADMIN

        adapter = LockerAdapter(
            onAssign = { locker -> showAssignDialog(locker) },
            onUnlock = { locker -> confirmRemoteUnlock(locker) },
            showActions = isAdmin
        )
        binding.skeleton.root.skeleton(true)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.tilSearch.hint = getString(R.string.search_lockers)
        binding.etSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            applyFilter()
        }

        binding.fabAdd.visible(isAdmin)
        binding.fabAdd.text = getString(R.string.add_locker)
        binding.fabAdd.setOnClickListener { showCreateLockerDialog() }
        binding.empty.btnEmptyAction.setOnClickListener { showCreateLockerDialog() }

        // The FAB would otherwise sit on top of the last row while scrolling.
        binding.recycler.addOnScrollListener(
            object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int
                ) {
                    if (dy > 6) binding.fabAdd.shrink() else if (dy < -6) binding.fabAdd.extend()
                }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                lockerRepo.observeLockers(),
                userRepo.observeUsers()
            ) { lockerList, userList -> lockerList to userList }
                .catchFirestore("the locker list") { showLoadError(it) }
                .collect { (lockerList, userList) ->
                    users = userList
                    lockers = lockerList
                    binding.skeleton.root.skeleton(false)
                    applyFilter()
                }
        }
    }

    private fun applyFilter() {
        val binding = _binding ?: return
        val shown =
            if (query.isBlank()) lockers
            else lockers.filter {
                it.id.contains(query, true) ||
                    it.label.contains(query, true) ||
                    it.location.contains(query, true) ||
                    it.assignedName?.contains(query, true) == true
            }

        adapter.submitList(shown)
        binding.tvCount.text = resources.getQuantityString(
            R.plurals.locker_count, shown.size, shown.size
        )

        val searching = query.isNotBlank()
        binding.empty.root.visible(shown.isEmpty())
        binding.empty.ivEmpty.setImageResource(
            if (searching) R.drawable.ic_search else R.drawable.ic_locker
        )
        binding.empty.tvEmptyTitle.text = getString(
            if (searching) R.string.empty_search_title else R.string.empty_lockers_title
        )
        binding.empty.tvEmptyBody.text = getString(
            when {
                searching -> R.string.empty_search_body
                isAdmin -> R.string.empty_lockers_body_admin
                else -> R.string.empty_lockers_body_staff
            }
        )
        binding.empty.btnEmptyAction.visible(!searching && isAdmin && lockers.isEmpty())
        binding.empty.btnEmptyAction.text = getString(R.string.add_locker)
    }

    // ------------------------------------------------------------ actions ----

    private fun showCreateLockerDialog() {
        val dialogBinding = DialogTextInputBinding.inflate(layoutInflater)
        dialogBinding.til.hint = getString(R.string.locker_id)
        dialogBinding.tvHelp.text = getString(R.string.locker_id_help)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_locker)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // Set the click listener after show() so a bad ID does not dismiss.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = dialogBinding.etInput.text.toString().trim().uppercase()
                when {
                    id.isBlank() ->
                        dialogBinding.til.error = getString(R.string.locker_id_required)
                    lockers.any { it.id.equals(id, true) } ->
                        dialogBinding.til.error = getString(R.string.locker_id_taken)
                    else -> {
                        dialogBinding.til.error = null
                        createLocker(id)
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun createLocker(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            lockerRepo.createLocker(Locker(id = id, label = id, location = ""))
                .onSuccess { snack("Locker $id added") }
                .onFailure { snack(it.message ?: "Failed to add locker") }
        }
    }

    private fun showAssignDialog(locker: Locker) {
        val candidates = users.filter { it.active && it.roleEnum == Role.USER }
        if (candidates.isEmpty()) {
            snack(getString(R.string.no_assignable_users))
            return
        }

        val labels = (listOf(getString(R.string.unassign)) +
            candidates.map { "${it.fullName}  ·  ${it.email}" }).toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.assign_locker, locker.label.ifBlank { locker.id }))
            .setItems(labels) { _, which ->
                val selected = if (which == 0) null else candidates[which - 1]
                assign(locker, selected)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun assign(locker: Locker, selected: User?) {
        viewLifecycleOwner.lifecycleScope.launch {
            lockerRepo.assign(locker.id, selected?.uid, selected?.fullName)
            // Keep the user document in sync with the locker document.
            locker.assignedUid?.let { userRepo.assignLocker(it, null) }
            selected?.let { userRepo.assignLocker(it.uid, locker.id) }
            snack(
                if (selected == null) "${locker.label} unassigned"
                else "${locker.label} assigned to ${selected.fullName}"
            )
        }
    }

    private fun confirmRemoteUnlock(locker: Locker) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remote_unlock)
            .setMessage(
                getString(R.string.remote_unlock_body, locker.label.ifBlank { locker.id })
            )
            .setPositiveButton(R.string.action_unlock) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    lockerRepo.requestRemoteUnlock(locker.id)
                        .onSuccess { snack("Unlock command sent to ${locker.label}") }
                        .onFailure { snack(it.message ?: "Command failed") }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
