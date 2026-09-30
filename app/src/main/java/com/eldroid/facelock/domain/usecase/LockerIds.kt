package com.eldroid.facelock.domain.usecase

import com.eldroid.facelock.data.model.Locker

/** Locker IDs are `CODE-NNN`: the building code, then a 3-digit number. */
object LockerIds {

    fun format(code: String, number: Int): String = "%s-%03d".format(code, number)

    /** The number part of an ID in [code]'s series, or null for any other ID. */
    fun numberIn(code: String, id: String): Int? =
        Regex("^${Regex.escape(code)}-(\\d+)$").find(id)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Sorts L-2 before L-10: text prefix first, then the trailing number as a
     * number, which a plain string sort gets wrong.
     */
    val naturalOrder: Comparator<Locker> = compareBy<Locker>(
        { it.id.substringBeforeLast('-') },
        { it.id.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE },
        { it.id }
    )
}

/** Next free ID in a building's series: one past the highest number in use. */
class NextLockerIdUseCase {
    operator fun invoke(code: String, lockers: List<Locker>): String {
        val highest = lockers.mapNotNull { LockerIds.numberIn(code, it.id) }.maxOrNull() ?: 0
        return LockerIds.format(code, highest + 1)
    }

    /**
     * [count] consecutive IDs starting at [startId], e.g. M-014 × 3 →
     * M-014, M-015, M-016. Null when [startId] is not in [code]'s CODE-NNN
     * series, since there is then no number to count up from.
     */
    fun range(code: String, startId: String, count: Int): List<String>? {
        val start = LockerIds.numberIn(code, startId) ?: return null
        return (start until start + count).map { LockerIds.format(code, it) }
    }
}
