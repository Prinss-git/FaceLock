package com.eldroid.facelock.domain.usecase

import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.repo.LockerRepository

/**
 * One-time move of lockers created before buildings existed (IDs like L-001,
 * a free-text location such as "Main Building · 1F") into the CODE-NNN scheme.
 *
 * A locker is only moved when its location names exactly one known building
 * and a floor that building has. Anything ambiguous is reported and left
 * untouched — guessing would put a real locker in the wrong place.
 */
class MigrateLegacyLockersUseCase(private val lockerRepo: LockerRepository) {

    data class Move(val locker: Locker, val building: Building, val floor: Int, val newId: String)
    data class Skip(val locker: Locker, val reason: String)
    data class Report(val moved: List<Move>, val skipped: List<Skip>)

    fun needsMigration(lockers: List<Locker>): List<Locker> =
        lockers.filter { it.building.isNullOrBlank() }

    /** Works out every move without writing anything. */
    fun plan(lockers: List<Locker>, buildings: List<Building>): Report {
        val moves = mutableListOf<Move>()
        val skips = mutableListOf<Skip>()
        // Numbers already taken per building, including ones handed out below.
        val highest = buildings.associate { b ->
            b.code to (lockers.mapNotNull { LockerIds.numberIn(b.code, it.id) }.maxOrNull() ?: 0)
        }.toMutableMap()

        needsMigration(lockers).sortedWith(LockerIds.naturalOrder).forEach { locker ->
            val building = matchBuilding(locker.location, buildings)
            // Read the floor from what is left once the building name is gone,
            // so a name like "Building 2" is not mistaken for floor 2.
            val rest = building?.let {
                locker.location.replace(it.name.trim(), "", ignoreCase = true)
            }.orEmpty()
            val saysGround = GROUND.containsMatchIn(rest)
            val floor = if (saysGround) 1 else parseFloor(rest)
            when {
                locker.location.isBlank() ->
                    skips += Skip(locker, "no location recorded")
                building == null ->
                    skips += Skip(locker, "no building matches “${locker.location}”")
                saysGround && !building.groundFloor ->
                    skips += Skip(
                        locker,
                        "${building.name} has no GF; turn on “First floor is called GF” for it"
                    )
                floor == null ->
                    skips += Skip(locker, "no floor in “${locker.location}”")
                floor !in 1..building.floors ->
                    skips += Skip(locker, "${building.name} has no floor ${building.labelFor(floor)}")
                else -> {
                    val next = highest.getValue(building.code) + 1
                    highest[building.code] = next
                    moves += Move(locker, building, floor, LockerIds.format(building.code, next))
                }
            }
        }
        return Report(moves, skips)
    }

    /** Applies [report] one locker at a time; a failed move becomes a skip. */
    suspend operator fun invoke(report: Report): Report {
        val done = mutableListOf<Move>()
        val skipped = report.skipped.toMutableList()
        report.moved.forEach { move ->
            lockerRepo.migrate(
                old = move.locker,
                newId = move.newId,
                buildingCode = move.building.code,
                floor = move.floor,
                location = move.building.locationOf(move.floor)
            )
                .onSuccess { done += move }
                .onFailure { skipped += Skip(move.locker, it.message ?: "write failed") }
        }
        return Report(done, skipped)
    }

    /** The longest building name found in the text, so "Main" never beats "Main Building". */
    private fun matchBuilding(location: String, buildings: List<Building>): Building? {
        val matches = buildings.filter {
            it.name.isNotBlank() && location.contains(it.name.trim(), ignoreCase = true)
        }
        val longest = matches.maxOfOrNull { it.name.trim().length } ?: return null
        return matches.singleOrNull { it.name.trim().length == longest }
    }

    /** "1F", "2nd floor", "Floor 3" → the first number in the text. */
    private fun parseFloor(location: String): Int? =
        Regex("(\\d+)").find(location)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        /** "GF", "G/F", "Ground", "ground floor" — floor 1 of a GF-named building. */
        val GROUND = Regex("\\bG/?F\\b|\\bground\\b", RegexOption.IGNORE_CASE)
    }
}
