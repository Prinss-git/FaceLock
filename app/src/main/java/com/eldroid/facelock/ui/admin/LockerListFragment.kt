package com.eldroid.facelock.ui.admin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.repo.AdminActionRepository
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.DialogAddLockerBinding
import com.eldroid.facelock.databinding.DialogAssignLockerBinding
import com.eldroid.facelock.databinding.FragmentLockersBinding
import com.eldroid.facelock.databinding.SheetLockerDetailBinding
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.lockers.AssignCandidate
import com.eldroid.facelock.presenter.lockers.LockerDetail
import com.eldroid.facelock.presenter.lockers.LockerListItem
import com.eldroid.facelock.presenter.lockers.LockersContract
import com.eldroid.facelock.presenter.lockers.LockersContract.AddLockerField
import com.eldroid.facelock.presenter.lockers.LockersContract.EmptyState
import com.eldroid.facelock.presenter.lockers.LockersContract.StatusFilter
import com.eldroid.facelock.presenter.lockers.LockersPresenter
import com.eldroid.facelock.ui.adapter.AssignCandidateAdapter
import com.eldroid.facelock.ui.adapter.LockerGroupAdapter
import com.eldroid.facelock.ui.adapter.LockerStatusStyle
import com.eldroid.facelock.ui.base.BaseFragment
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.NetworkMonitor
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.visible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Lockers tab — the View in MVP. It draws what [LockersPresenter] hands it and
 * forwards every tap; grouping, filtering and all decisions live there.
 */
class LockerListFragment : BaseFragment(), LockersContract.View {

    private var _binding: FragmentLockersBinding? = null
    private val binding get() = _binding!!

    private val holder: PresenterHolder by viewModels()
    private lateinit var presenter: LockersContract.Presenter
    private lateinit var adapter: LockerGroupAdapter

    // Dialogs are owned by the view; the presenter only says what to show.
    private var detailSheet: BottomSheetDialog? = null
    private var addLockerDialog: AlertDialog? = null
    private var addLockerBinding: DialogAddLockerBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLockersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val session = SessionManager(requireContext())
        val isAdmin = session.role == Role.ADMIN
        presenter = holder.getOrCreate {
            LockersPresenter(
                lockerRepo = LockerRepository(),
                userRepo = UserRepository(),
                buildingRepo = BuildingRepository(),
                trail = AdminTrail(AdminActionRepository()) { session.fullName },
                canManage = isAdmin,
                isOnline = NetworkMonitor(requireContext())::isOnline
            )
        }

        adapter = LockerGroupAdapter(
            onBuildingClick = presenter::onBuildingToggled,
            onLockerClick = presenter::onLockerClicked
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.tvHeadingSub.setText(
            if (presenter.canManage) R.string.heading_sub_admin else R.string.heading_sub_security
        )
        binding.btnBuildings.visible(presenter.canManage)
        binding.btnBuildings.setOnClickListener { presenter.onManageBuildingsClicked() }
        binding.fabAdd.visible(presenter.canManage)
        binding.fabAdd.setOnClickListener { presenter.onAddLockerClicked() }
        binding.etSearch.doAfterTextChanged { presenter.onSearchChanged(it?.toString().orEmpty()) }
        binding.btnExpandAll.setOnClickListener { presenter.onExpandAllClicked() }
        binding.btnCollapseAll.setOnClickListener { presenter.onCollapseAllClicked() }
        binding.statusGroup.setOnCheckedStateChangeListener { _, ids ->
            presenter.onStatusFilterSelected(
                when (ids.firstOrNull()) {
                    R.id.chipStatusFree -> StatusFilter.FREE
                    R.id.chipStatusOccupied -> StatusFilter.OCCUPIED
                    R.id.chipStatusOut -> StatusFilter.OUT_OF_SERVICE
                    else -> StatusFilter.ALL
                }
            )
        }

        // The FAB would otherwise sit on top of the last row while scrolling.
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6) binding.fabAdd.shrink() else if (dy < -6) binding.fabAdd.extend()
            }
        })

        presenter.attachView(this)
    }

    override fun onDestroyView() {
        presenter.detachView()
        detailSheet?.dismiss()
        addLockerDialog?.dismiss()
        detailSheet = null
        addLockerDialog = null
        addLockerBinding = null
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------ list ----

    override fun showLoading() {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(true)
        binding.empty.root.visible(false)
    }

    override fun showLockers(items: List<LockerListItem>, lockerCount: Int) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        binding.empty.root.visible(false)
        binding.recycler.visible(true)
        adapter.submitList(items)
        binding.tvCount.text =
            resources.getQuantityString(R.plurals.locker_count, lockerCount, lockerCount)
    }

    override fun showExpandControls(canExpand: Boolean, canCollapse: Boolean) {
        val binding = _binding ?: return
        // Both stay visible so the row does not jump; the one that would do nothing is dimmed.
        binding.btnExpandAll.visible(true)
        binding.btnCollapseAll.visible(true)
        binding.btnExpandAll.isEnabled = canExpand
        binding.btnCollapseAll.isEnabled = canCollapse
    }

    override fun showStatusCounts(all: Int, free: Int, occupied: Int, outOfService: Int) {
        val binding = _binding ?: return
        binding.chipStatusAll.text = getString(R.string.status_all_n, all)
        binding.chipStatusFree.text = getString(R.string.status_free_n, free)
        binding.chipStatusOccupied.text = getString(R.string.status_occupied_n, occupied)
        binding.chipStatusOut.text = getString(R.string.status_out_n, outOfService)
    }

    override fun showStatusFilter(filter: StatusFilter) {
        _binding?.statusGroup?.check(
            when (filter) {
                StatusFilter.ALL -> R.id.chipStatusAll
                StatusFilter.FREE -> R.id.chipStatusFree
                StatusFilter.OCCUPIED -> R.id.chipStatusOccupied
                StatusFilter.OUT_OF_SERVICE -> R.id.chipStatusOut
            }
        )
    }

    private fun hideExpandControls() {
        val binding = _binding ?: return
        binding.btnExpandAll.visible(false)
        binding.btnCollapseAll.visible(false)
    }

    override fun showEmpty(state: EmptyState) {
        val binding = _binding ?: return
        val searching = state == EmptyState.NO_MATCH
        binding.skeleton.root.skeleton(false)
        adapter.submitList(emptyList())
        binding.tvCount.text = resources.getQuantityString(R.plurals.locker_count, 0, 0)
        hideExpandControls()

        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(if (searching) R.drawable.ic_search else R.drawable.ic_locker)
            tvEmptyTitle.setText(
                if (searching) R.string.empty_search_title else R.string.empty_lockers_title
            )
            tvEmptyBody.setText(
                when {
                    searching -> R.string.empty_search_body
                    presenter.canManage -> R.string.empty_lockers_body_buildings
                    else -> R.string.empty_lockers_body_staff
                }
            )
            btnEmptyAction.visible(!searching && presenter.canManage)
            btnEmptyAction.setText(R.string.buildings)
            btnEmptyAction.setOnClickListener { presenter.onManageBuildingsClicked() }
        }
    }

    /**
     * Terminal state: the listener failed and will not emit again, so say why
     * instead of leaving a spinner or an "all clear" empty list on screen.
     */
    override fun showLoadError(message: String) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        hideExpandControls()
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(R.drawable.ic_alert)
            tvEmptyTitle.text = getString(R.string.load_failed_title)
            tvEmptyBody.text = message
            btnEmptyAction.visible(false)
        }
    }

    override fun showMessage(message: String) = snack(message)

    // ---------------------------------------------------------- detail ----

    override fun showLockerDetail(detail: LockerDetail) {
        val locker = detail.locker
        val sheetBinding = SheetLockerDetailBinding.inflate(layoutInflater)
        val style = LockerStatusStyle.of(locker.status)
        val color = requireContext().getColor(style.tint)

        with(sheetBinding) {
            tvId.text = locker.id
            tvPlace.text = detail.place ?: getString(R.string.no_location)
            tvStatus.text = LockerStatusStyle.label(locker.status)
            tvStatus.setBackgroundResource(style.pill)
            tvStatus.setTextColor(color)
            iconWrap.setBackgroundResource(style.pill)
            ivLocker.setImageResource(style.icon)
            ivLocker.setColorFilter(color)

            tvHolder.text = locker.assignedName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.unassigned)
            tvLastOpened.visible(locker.lastOpenedAt != null)
            locker.lastOpenedAt?.let {
                tvLastOpened.text = getString(R.string.last_opened, it.asRelativeDateTime())
            }

            actionRow.visible(detail.canManage)
            btnAssign.setText(
                if (locker.isAvailable) R.string.action_assign else R.string.action_reassign
            )
            btnAssign.isEnabled = detail.canAssign
            // Unlocking an offline or retired unit would silently do nothing.
            btnUnlock.isEnabled = detail.canUnlock

            manageRow.visible(detail.canManage)
            btnService.setText(
                if (locker.isInService) R.string.action_out_of_service else R.string.action_back_in_service
            )
            btnService.isEnabled = detail.canToggleService
            tvServiceHint.visible(detail.canManage && !detail.canToggleService)

            // Each action closes the sheet first; the presenter decides what follows.
            fun on(button: View, action: (String) -> Unit) = button.setOnClickListener {
                detailSheet?.dismiss()
                action(locker.id)
            }
            on(btnAssign, presenter::onAssignClicked)
            on(btnUnlock, presenter::onUnlockClicked)
            on(btnHistory, presenter::onViewHistoryClicked)
            on(btnService, presenter::onServiceToggleClicked)
            on(btnRemove, presenter::onRemoveClicked)
        }

        detailSheet?.dismiss()
        detailSheet = BottomSheetDialog(requireContext()).apply {
            setContentView(sheetBinding.root)
            show()
        }
    }

    override fun showAssignPicker(locker: Locker, candidates: List<AssignCandidate>) {
        val body = DialogAssignLockerBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.assign_locker, locker.id))
            .setView(body.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        val pickerAdapter = AssignCandidateAdapter { user ->
            dialog.dismiss()
            presenter.onAssigneePicked(locker.id, user.uid)
        }
        body.rvCandidates.layoutManager = LinearLayoutManager(requireContext())
        body.rvCandidates.adapter = pickerAdapter
        pickerAdapter.submitList(candidates)

        val hasHolder = !locker.isAvailable
        body.rowUnassign.visible(hasHolder)
        body.dividerUnassign.visible(hasHolder && candidates.isNotEmpty())
        body.tvUnassignBody.text = locker.assignedName?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.assign_unassign_body, it) }
            ?: getString(R.string.assign_unassign_body_generic)
        body.rowUnassign.setOnClickListener {
            dialog.dismiss()
            presenter.onAssigneePicked(locker.id, null)
        }

        // A search box only earns its space once the list is long.
        body.tilSearch.visible(candidates.size > SEARCH_THRESHOLD)
        body.etSearch.doAfterTextChanged { text ->
            val query = text?.toString()?.trim().orEmpty()
            val shown = if (query.isEmpty()) candidates else candidates.filter {
                it.user.fullName.contains(query, ignoreCase = true) ||
                    it.user.email.contains(query, ignoreCase = true)
            }
            pickerAdapter.submitList(shown)
            body.tvNoMatch.text = getString(R.string.assign_no_match, query)
            body.tvNoMatch.visible(shown.isEmpty())
        }

        dialog.show()
    }

    override fun confirmRemove(locker: Locker) {
        val holder = locker.assignedName?.takeIf { it.isNotBlank() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.remove_locker_title, locker.id))
            .setMessage(
                if (holder != null) getString(R.string.remove_locker_body_held, holder)
                else getString(R.string.remove_locker_body)
            )
            .setPositiveButton(R.string.action_remove_short) { _, _ ->
                presenter.onRemoveConfirmed(locker.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun openLockerHistory(locker: Locker) {
        coordinator?.showLockerHistory(locker.id)
    }

    override fun confirmUnlock(locker: Locker) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remote_unlock)
            .setMessage(
                getString(
                    if (locker.hasDevice) R.string.remote_unlock_body else R.string.remote_unlock_body_no_device,
                    locker.id
                )
            )
            .setPositiveButton(R.string.action_unlock) { _, _ ->
                presenter.onUnlockConfirmed(locker.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------- add locker ----

    override fun showAddLockerForm(buildings: List<Building>) {
        val form = DialogAddLockerBinding.inflate(layoutInflater)
        var selectedBuilding: Building? = null
        var selectedFloor: Int? = null
        // Only overwrite the ID while it still holds our own suggestion.
        var lastSuggestion = ""

        // "Filled in from the building…" for one locker; the ID range for several.
        fun refreshIdHelp() {
            val range = selectedBuilding?.let {
                presenter.describeRange(
                    it.code,
                    form.etId.text?.toString().orEmpty(),
                    form.etQuantity.text?.toString().orEmpty()
                )
            }
            form.tvIdHelp.text = range?.let { getString(R.string.bulk_range_preview, it) }
                ?: getString(R.string.locker_id_auto_help)
        }

        form.acBuilding.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                buildings.map { "${it.name} (${it.code})" }
            )
        )
        fun selectBuilding(position: Int) {
            val building = buildings[position]
            selectedBuilding = building
            form.tilBuilding.error = null

            // Each building names its own floors: GF, 2F… or 1F, 2F…
            val floors = (1..building.floors).toList()
            form.acFloor.setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    floors.map(building::labelFor)
                )
            )
            // Keep the chosen floor only if the new building has it too.
            val keep = selectedFloor?.takeIf { it <= building.floors }
            selectedFloor = keep ?: if (building.floors == 1) 1 else null
            form.acFloor.setText(selectedFloor?.let(building::labelFor).orEmpty(), false)

            val suggestion = presenter.suggestLockerId(building.code)
            val current = form.etId.text?.toString().orEmpty()
            if (current.isEmpty() || current == lastSuggestion) form.etId.setText(suggestion)
            lastSuggestion = suggestion
            refreshIdHelp()
        }
        form.etQuantity.setText(1.toString())
        form.acBuilding.setOnItemClickListener { _, _, position, _ -> selectBuilding(position) }
        form.acFloor.setOnItemClickListener { _, _, position, _ ->
            selectedFloor = position + 1
            form.tilFloor.error = null
        }
        form.etId.doAfterTextChanged {
            form.tilId.error = null
            refreshIdHelp()
        }
        form.etQuantity.doAfterTextChanged {
            form.tilQuantity.error = null
            refreshIdHelp()
        }

        // One building: nothing to choose, so preselect it.
        if (buildings.size == 1) {
            form.acBuilding.setText(form.acBuilding.adapter.getItem(0).toString(), false)
            selectBuilding(0)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_locker)
            .setView(form.root)
            .setPositiveButton(R.string.action_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // Set the click listener after show() so a validation error does not dismiss.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                presenter.onCreateLocker(
                    selectedBuilding?.code,
                    selectedFloor,
                    form.etId.text?.toString().orEmpty(),
                    form.etQuantity.text?.toString().orEmpty()
                )
            }
        }
        dialog.setOnDismissListener {
            if (addLockerDialog === dialog) {
                addLockerDialog = null
                addLockerBinding = null
            }
        }

        addLockerDialog = dialog
        addLockerBinding = form
        dialog.show()
    }

    override fun showAddLockerError(field: AddLockerField, message: String) {
        val form = addLockerBinding ?: return snack(message)
        when (field) {
            AddLockerField.BUILDING -> form.tilBuilding.error = message
            AddLockerField.FLOOR -> form.tilFloor.error = message
            AddLockerField.ID -> form.tilId.error = message
            AddLockerField.QUANTITY -> form.tilQuantity.error = message
        }
    }

    override fun closeAddLockerForm() {
        addLockerDialog?.dismiss()
    }

    override fun showNoBuildingsYet() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.no_buildings_title)
            .setMessage(R.string.no_buildings_body)
            .setPositiveButton(R.string.buildings) { _, _ -> presenter.onManageBuildingsClicked() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun openBuildingManager() {
        startActivity(Intent(requireContext(), BuildingsActivity::class.java))
    }

    private companion object {
        /** Members in the assign picker before a search box is shown. */
        const val SEARCH_THRESHOLD = 6
    }
}
