package com.eldroid.facelock.presenter.lockers

import com.eldroid.facelock.data.model.AdminActionType
import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.domain.usecase.AssignLockerUseCase
import com.eldroid.facelock.domain.usecase.GroupLockersUseCase
import com.eldroid.facelock.domain.usecase.LockerGroup
import com.eldroid.facelock.domain.usecase.NextLockerIdUseCase
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.presenter.lockers.LockersContract.AddLockerField
import com.eldroid.facelock.presenter.lockers.LockersContract.EmptyState
import com.eldroid.facelock.util.OFFLINE_ACTION_MESSAGE
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.messageForWrite
import com.eldroid.facelock.presenter.lockers.LockersContract.StatusFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LockersPresenter(
    private val lockerRepo: LockerRepository,
    private val userRepo: UserRepository,
    private val buildingRepo: BuildingRepository,
    private val trail: AdminTrail,
    override val canManage: Boolean,
    /** Transactions and unlocks must not be queued offline; checked before each. */
    private val isOnline: () -> Boolean = { true },
    private val assignLocker: AssignLockerUseCase = AssignLockerUseCase(lockerRepo),
    private val groupLockers: GroupLockersUseCase = GroupLockersUseCase(),
    private val nextLockerId: NextLockerIdUseCase = NextLockerIdUseCase()
) : CoroutinePresenter<LockersContract.View>(), LockersContract.Presenter {

    // State survives rotation because this presenter lives in a ViewModel.
    private var lockers: List<Locker> = emptyList()
    private var users: List<User> = emptyList()
    private var buildings: List<Building> = emptyList()
    private var loaded = false
    private var query = ""
    private val collapsed = mutableSetOf<String>()
    /** The folds the search replaced, restored when it is cleared. */
    private var beforeSearch: Set<String>? = null
    private var statusFilter = StatusFilter.ALL
    private var searchJob: Job? = null

    override fun onViewAttached() {
        view?.showStatusFilter(statusFilter)
        if (loaded) render() else view?.showLoading()

        scope.launch {
            combine(
                lockerRepo.observeLockers(),
                userRepo.observeUsers(),
                buildingRepo.observeBuildings()
            ) { l, u, b -> Triple(l, u, b) }
                .catchFirestore("the locker list") { view?.showLoadError(it) }
                .collect { (l, u, b) ->
                    lockers = l
                    users = u
                    buildings = b
                    loaded = true
                    render()
                }
        }
    }

    // ------------------------------------------------------------- list ----

    override fun onSearchChanged(query: String) {
        val trimmed = query.trim()
        // Wait for a pause in typing so each keystroke doesn't redraw the list.
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DELAY_MS)
            if (trimmed != this@LockersPresenter.query) narrow { this@LockersPresenter.query = trimmed }
        }
    }

    override fun onStatusFilterSelected(filter: StatusFilter) {
        if (filter != statusFilter) narrow { statusFilter = filter }
    }

    /** Search or status-filter or both: the list is showing a subset. */
    private fun narrowing() = query.isNotEmpty() || statusFilter != StatusFilter.ALL

    /**
     * Applies a change to the search or status filter. Narrowing opens every
     * building with a match (the buttons and headers can still fold them);
     * going back to the full list brings back the folds from before.
     */
    private fun narrow(change: () -> Unit) {
        val was = narrowing()
        change()
        val now = narrowing()
        if (!was && now) beforeSearch = collapsed.toSet()
        collapsed.clear()
        if (was && !now) beforeSearch?.let(collapsed::addAll)
        if (loaded) render()
    }

    override fun onBuildingToggled(groupKey: String) {
        if (!collapsed.remove(groupKey)) collapsed += groupKey
        render()
    }

    override fun onExpandAllClicked() {
        collapsed.clear()
        render()
    }

    /** Folds every building currently on screen (all of them, or a search's hits). */
    override fun onCollapseAllClicked() {
        collapsed += shownGroups().map { it.key }
        render()
    }

    /** Every building, narrowed by the search only (what the status chips count). */
    private fun searchedGroups(): List<LockerGroup> {
        val searching = query.isNotEmpty()
        return groupLockers(lockers, buildings)
            .map { group -> if (searching) group.filtered() else group }
    }

    private fun shownGroups(): List<LockerGroup> {
        val narrowing = narrowing()
        return searchedGroups()
            .map { group ->
                if (statusFilter == StatusFilter.ALL) group
                else group.copy(floors = group.floors
                    .map { f -> f.copy(lockers = f.lockers.filter(::hasStatus)) }
                    .filter { it.lockers.isNotEmpty() })
            }
            // While narrowing, only buildings with a hit are worth showing.
            .filter { !narrowing || it.lockers.isNotEmpty() }
    }

    private fun hasStatus(locker: Locker) = when (statusFilter) {
        StatusFilter.ALL -> true
        StatusFilter.FREE -> locker.isFree
        StatusFilter.OCCUPIED -> !locker.isAvailable
        StatusFilter.OUT_OF_SERVICE -> !locker.isInService
    }

    private val LockerGroup.key get() = building?.code ?: UNSORTED_KEY

    private fun render() {
        val view = view ?: return
        val groups = shownGroups()

        val searched = searchedGroups().flatMap { it.lockers }
        view.showStatusCounts(
            all = searched.size,
            free = searched.count { it.isFree },
            occupied = searched.count { !it.isAvailable },
            outOfService = searched.count { !it.isInService }
        )

        val shownCount = groups.sumOf { it.lockers.size }
        when {
            groups.isEmpty() && narrowing() -> view.showEmpty(EmptyState.NO_MATCH)
            groups.isEmpty() -> view.showEmpty(EmptyState.NO_LOCKERS)
            else -> {
                view.showLockers(groups.flatMap { it.toItems() }, shownCount)
                view.showExpandControls(
                    canExpand = groups.any { it.key in collapsed },
                    canCollapse = groups.any { it.key !in collapsed }
                )
            }
        }
    }

    private fun LockerGroup.filtered(): LockerGroup {
        val buildingHit = building?.let {
            it.name.contains(query, true) || it.code.equals(query, true)
        } == true
        if (buildingHit) return this
        return copy(floors = floors.map { f -> f.copy(lockers = f.lockers.filter(::matches)) }
            .filter { it.lockers.isNotEmpty() })
    }

    private fun matches(locker: Locker) =
        locker.id.contains(query, true) ||
            locker.label.contains(query, true) ||
            locker.location.contains(query, true) ||
            locker.assignedName?.contains(query, true) == true

    private fun LockerGroup.toItems(): List<LockerListItem> {
        val groupKey = key
        val expanded = groupKey !in collapsed
        val all = lockers
        val header = LockerListItem.BuildingHeader(
            groupKey = groupKey,
            name = building?.name,
            code = building?.code,
            total = all.size,
            free = all.count { it.isFree },
            expanded = expanded
        )
        if (!expanded) return listOf(header)
        return listOf(header) + floors.flatMap { floor ->
            val label = floor.floor?.let { building?.labelFor(it) ?: Building.floorLabel(it) }
            listOf(LockerListItem.FloorHeader(groupKey, floor.floor, label)) +
                floor.lockers.map { LockerListItem.Row(it) }
        }
    }

    // ----------------------------------------------------------- detail ----

    override fun onLockerClicked(lockerId: String) {
        val locker = find(lockerId) ?: return
        view?.showLockerDetail(LockerDetail(locker, placeOf(locker), canManage))
    }

    private fun placeOf(locker: Locker): String? {
        val building = buildings.firstOrNull { it.code == locker.building }
        val floor = locker.floor
        return when {
            building != null && floor != null -> building.locationOf(floor)
            else -> locker.location.takeIf { it.isNotBlank() }
        }
    }

    override fun onViewHistoryClicked(lockerId: String) {
        find(lockerId)?.let { view?.openLockerHistory(it) }
    }

    // ----------------------------------------------------------- assign ----

    override fun onAssignClicked(lockerId: String) {
        if (!canManage) return
        val locker = find(lockerId) ?: return
        if (!locker.isInService) {
            view?.showMessage("${locker.id} is out of service. Put it back in service first.")
            return
        }
        val holderUid = locker.assignedUid?.takeIf { it.isNotBlank() }

        // Current holder first, then members without a locker, then members
        // who would have to give one up — each group alphabetical.
        val candidates = users
            .filter(assignLocker::isEligible)
            .map { user ->
                AssignCandidate(
                    user = user,
                    isCurrentHolder = user.uid == holderUid,
                    otherLockerId = user.lockerId?.takeIf { it.isNotBlank() && it != locker.id }
                )
            }
            .sortedWith(
                compareBy<AssignCandidate>(
                    { !it.isCurrentHolder },
                    { it.otherLockerId != null },
                    { it.user.fullName.lowercase() }
                )
            )

        if (candidates.isEmpty() && holderUid == null) {
            view?.showMessage("No active member accounts to assign to.")
            return
        }
        view?.showAssignPicker(locker, candidates)
    }

    override fun onAssigneePicked(lockerId: String, uid: String?) {
        val locker = find(lockerId) ?: return
        val holder = uid?.let { id -> users.firstOrNull { it.uid == id } ?: return }
        if (offline()) return
        scope.launch {
            assignLocker(locker, holder)
                .onSuccess {
                    if (holder == null) {
                        view?.showMessage("${locker.id} unassigned")
                        trail.record(
                            AdminActionType.LOCKER_UNASSIGNED, locker.id,
                            locker.assignedName?.let { "was $it" }
                        )
                    } else {
                        view?.showMessage("${locker.id} assigned to ${holder.fullName}")
                        trail.record(AdminActionType.LOCKER_ASSIGNED, locker.id, "to ${holder.fullName}")
                    }
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Could not update ${locker.id}")) }
        }
    }

    // ------------------------------------------------- unlock / service ----

    override fun onUnlockClicked(lockerId: String) {
        if (!canManage || offline()) return
        find(lockerId)?.let { view?.confirmUnlock(it) }
    }

    override fun onUnlockConfirmed(lockerId: String) {
        if (offline()) return
        scope.launch {
            lockerRepo.requestRemoteUnlock(lockerId)
                .onSuccess {
                    view?.showMessage("Unlock command sent to $lockerId")
                    trail.record(AdminActionType.LOCKER_UNLOCK_REQUESTED, lockerId)
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Command failed")) }
        }
    }

    override fun onServiceToggleClicked(lockerId: String) {
        if (!canManage) return
        val locker = find(lockerId) ?: return
        val backInService = !locker.isInService
        if (offline()) return
        scope.launch {
            lockerRepo.setInService(lockerId, backInService)
                .onSuccess {
                    if (backInService) {
                        view?.showMessage("$lockerId is back in service")
                        trail.record(AdminActionType.LOCKER_BACK_IN_SERVICE, lockerId)
                    } else {
                        view?.showMessage("$lockerId is out of service")
                        trail.record(AdminActionType.LOCKER_OUT_OF_SERVICE, lockerId)
                    }
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Could not update $lockerId")) }
        }
    }

    override fun onRemoveClicked(lockerId: String) {
        if (!canManage) return
        find(lockerId)?.let { view?.confirmRemove(it) }
    }

    override fun onRemoveConfirmed(lockerId: String) {
        val locker = find(lockerId) ?: return
        if (offline()) return
        scope.launch {
            lockerRepo.remove(locker)
                .onSuccess {
                    view?.showMessage("${locker.id} removed")
                    trail.record(
                        AdminActionType.LOCKER_REMOVED, locker.id,
                        locker.assignedName?.let { "freed from $it" }
                    )
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Could not remove ${locker.id}")) }
        }
    }

    // -------------------------------------------------------------- add ----

    override fun onAddLockerClicked() {
        if (!canManage || offline()) return
        if (buildings.isEmpty()) view?.showNoBuildingsYet()
        else view?.showAddLockerForm(buildings)
    }

    override fun suggestLockerId(buildingCode: String): String =
        nextLockerId(buildingCode, lockers)

    override fun describeRange(buildingCode: String, startId: String, quantity: String): String? {
        val count = quantity.trim().toIntOrNull() ?: return null
        if (count < 2 || count > MAX_BULK) return null
        val ids = nextLockerId.range(buildingCode, startId.trim().uppercase(), count) ?: return null
        return "${ids.first()} … ${ids.last()}"
    }

    override fun onCreateLocker(buildingCode: String?, floor: Int?, rawId: String, quantity: String) {
        val view = view ?: return
        val building = buildings.firstOrNull { it.code == buildingCode }
        val startId = rawId.trim().uppercase()
        val count = quantity.trim().ifEmpty { "1" }.toIntOrNull()

        if (building == null) {
            return view.showAddLockerError(AddLockerField.BUILDING, "Choose a building")
        }
        if (floor == null || floor !in 1..building.floors) {
            return view.showAddLockerError(AddLockerField.FLOOR, "Choose a floor")
        }
        if (count == null || count !in 1..MAX_BULK) {
            return view.showAddLockerError(AddLockerField.QUANTITY, "Enter 1 to $MAX_BULK")
        }
        if (startId.isEmpty()) {
            return view.showAddLockerError(AddLockerField.ID, "Enter a locker ID")
        }
        if (!ID_PATTERN.matches(startId)) {
            return view.showAddLockerError(AddLockerField.ID, "Use letters, numbers and hyphens only")
        }

        val ids = if (count == 1) listOf(startId)
        else nextLockerId.range(building.code, startId, count)
            ?: return view.showAddLockerError(
                AddLockerField.ID,
                "To add several, start with an ID like ${suggestLockerId(building.code)}"
            )

        val taken = ids.filter { id -> lockers.any { it.id.equals(id, ignoreCase = true) } }
        if (taken.isNotEmpty()) {
            return view.showAddLockerError(
                AddLockerField.ID, "Already in use: ${taken.joinToString()}"
            )
        }

        val location = building.locationOf(floor)
        val newLockers = ids.map {
            Locker(id = it, label = it, building = building.code, floor = floor, location = location)
        }
        if (!isOnline()) {
            return view.showAddLockerError(AddLockerField.ID, OFFLINE_ACTION_MESSAGE)
        }
        scope.launch {
            lockerRepo.createMany(newLockers)
                .onSuccess {
                    val what = if (ids.size == 1) ids.first() else "${ids.first()} … ${ids.last()}"
                    this@LockersPresenter.view?.closeAddLockerForm()
                    this@LockersPresenter.view?.showMessage("$what added to $location")
                    trail.record(AdminActionType.LOCKERS_ADDED, what, location)
                }
                .onFailure {
                    this@LockersPresenter.view?.showAddLockerError(
                        AddLockerField.ID, messageForWrite(it, "Could not add the locker")
                    )
                }
        }
    }

    override fun onManageBuildingsClicked() {
        if (canManage) view?.openBuildingManager()
    }

    private fun find(lockerId: String) = lockers.firstOrNull { it.id == lockerId }

    /** True (and says so) when offline; an unlock queued for later could fire at any time. */
    private fun offline(): Boolean {
        if (isOnline()) return false
        view?.showMessage(OFFLINE_ACTION_MESSAGE)
        return true
    }

    private companion object {
        const val UNSORTED_KEY = "_unsorted"
        const val MAX_BULK = 50
        const val SEARCH_DELAY_MS = 250L
        val ID_PATTERN = Regex("^[A-Z0-9-]{1,24}$")
    }
}
