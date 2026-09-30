package com.eldroid.facelock.domain.usecase

import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker

/** Lockers of one building, split by floor. [building] is null for "Unsorted". */
data class LockerGroup(
    val building: Building?,
    val floors: List<FloorGroup>
) {
    val lockers: List<Locker> get() = floors.flatMap { it.lockers }
}

/** [floor] is null when a locker has no floor recorded. */
data class FloorGroup(val floor: Int?, val lockers: List<Locker>)

/**
 * Building → floor → locker grouping for the Lockers tab.
 *
 * Buildings come in name order, floors ascending, lockers in natural ID order.
 * Lockers whose building is missing (made before buildings existed, or whose
 * building was since deleted) are gathered in a trailing "Unsorted" group so
 * nothing silently disappears from the list. Empty buildings are kept: an admin
 * who just added one expects to see it.
 */
class GroupLockersUseCase {

    operator fun invoke(lockers: List<Locker>, buildings: List<Building>): List<LockerGroup> {
        val known = buildings.associateBy { it.code }
        val byBuilding = lockers.groupBy { it.building?.takeIf(known::containsKey) }

        val grouped = buildings.sortedBy { it.name.lowercase() }.map { building ->
            LockerGroup(building, floorsOf(byBuilding[building.code].orEmpty()))
        }
        val unsorted = byBuilding[null].orEmpty()
        return if (unsorted.isEmpty()) grouped
        else grouped + LockerGroup(null, floorsOf(unsorted))
    }

    private fun floorsOf(lockers: List<Locker>): List<FloorGroup> =
        lockers.groupBy { it.floor }
            .toSortedMap(nullsLast(naturalOrder<Int>()))
            .map { (floor, onFloor) -> FloorGroup(floor, onFloor.sortedWith(LockerIds.naturalOrder)) }
}
