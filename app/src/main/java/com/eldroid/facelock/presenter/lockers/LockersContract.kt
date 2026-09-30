package com.eldroid.facelock.presenter.lockers

import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.presenter.base.BasePresenter

/** Lockers tab: buildings → floors → lockers, with assign, unlock and add. */
interface LockersContract {

    interface View {
        fun showLoading()
        fun showLockers(items: List<LockerListItem>, lockerCount: Int)
        /** Enables Expand all / Collapse all: each only when it would change something. */
        fun showExpandControls(canExpand: Boolean, canCollapse: Boolean)
        /** Chip counts, over whatever the search currently matches. */
        fun showStatusCounts(all: Int, free: Int, occupied: Int, outOfService: Int)
        /** Keeps the chips in step after rotation. */
        fun showStatusFilter(filter: StatusFilter)
        fun showEmpty(state: EmptyState)
        fun showLoadError(message: String)
        fun showMessage(message: String)

        fun showLockerDetail(detail: LockerDetail)
        fun showAssignPicker(locker: Locker, candidates: List<AssignCandidate>)
        fun confirmUnlock(locker: Locker)
        fun confirmRemove(locker: Locker)
        /** Hand-off to the Logs tab (lesson 1.5: through the coordinator). */
        fun openLockerHistory(locker: Locker)

        fun showAddLockerForm(buildings: List<Building>)
        fun showAddLockerError(field: AddLockerField, message: String)
        fun closeAddLockerForm()
        fun showNoBuildingsYet()
        fun openBuildingManager()
    }

    interface Presenter : BasePresenter<View> {
        /** Admins manage; security staff only browse. */
        val canManage: Boolean

        fun onSearchChanged(query: String)
        fun onBuildingToggled(groupKey: String)
        fun onStatusFilterSelected(filter: StatusFilter)
        fun onExpandAllClicked()
        fun onCollapseAllClicked()
        fun onLockerClicked(lockerId: String)

        fun onAssignClicked(lockerId: String)
        fun onAssigneePicked(lockerId: String, uid: String?)
        fun onUnlockClicked(lockerId: String)
        fun onUnlockConfirmed(lockerId: String)
        fun onRemoveClicked(lockerId: String)
        fun onRemoveConfirmed(lockerId: String)
        fun onServiceToggleClicked(lockerId: String)
        fun onViewHistoryClicked(lockerId: String)

        fun onAddLockerClicked()
        fun suggestLockerId(buildingCode: String): String
        /** "M-014 … M-023", or null when the start ID or quantity can't form a range. */
        fun describeRange(buildingCode: String, startId: String, quantity: String): String?
        fun onCreateLocker(buildingCode: String?, floor: Int?, rawId: String, quantity: String)
        fun onManageBuildingsClicked()
    }

    enum class EmptyState { NO_LOCKERS, NO_MATCH }

    enum class StatusFilter { ALL, FREE, OCCUPIED, OUT_OF_SERVICE }

    enum class AddLockerField { BUILDING, FLOOR, ID, QUANTITY }
}

/** Flat rows for the grouped list. [key] is stable across updates. */
sealed class LockerListItem {
    abstract val key: String

    /** [name] null is the "Unsorted" group of lockers without a building. */
    data class BuildingHeader(
        val groupKey: String,
        val name: String?,
        val code: String?,
        val total: Int,
        val free: Int,
        val expanded: Boolean
    ) : LockerListItem() {
        override val key get() = "b:$groupKey"
    }

    /** [label] is "GF", "2F"… as the building names it; null when no floor is set. */
    data class FloorHeader(
        val groupKey: String,
        val floor: Int?,
        val label: String?
    ) : LockerListItem() {
        override val key get() = "f:$groupKey:$floor"
    }

    data class Row(val locker: Locker) : LockerListItem() {
        override val key get() = "l:${locker.id}"
    }
}

data class LockerDetail(
    val locker: Locker,
    /** "Main Building · 1F", or the stored location text for older lockers. */
    val place: String?,
    val canManage: Boolean
) {
    val canAssign: Boolean get() = canManage && locker.isInService
    val canUnlock: Boolean
        get() = canManage && locker.isInService && locker.status != Locker.STATUS_OFFLINE
    /** Only an unassigned locker may be retired; a retired one can always come back. */
    val canToggleService: Boolean get() = canManage && (locker.isAvailable || !locker.isInService)
}

/** One member in the assign picker. */
data class AssignCandidate(
    val user: User,
    val isCurrentHolder: Boolean,
    /** Another locker this member already holds, if any. */
    val otherLockerId: String?
)
