package com.eldroid.facelock.domain.usecase

import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.model.Locker

/**
 * Guesses which current locker each unlinked old ID became, from what the
 * sort into buildings carried over: it copied the old locker's holder and
 * `lastOpenedAt` onto the new one.
 *
 * - `lastOpenedAt` equal to the old ID's latest granted log is a strong clue.
 * - A holder who was granted access at the old ID is a weaker one (holders
 *   may have changed since).
 *
 * Only unambiguous matches are suggested; everything else is left for the
 * admin, who reviews every suggestion before saving.
 */
class SuggestOldIdLinksUseCase {

    data class Clues(
        val oldId: String,
        /** People granted access at this old ID, most frequent first. */
        val usedBy: List<String>,
        val lastUsed: Long?,
        val suggestion: Suggestion?
    )

    data class Suggestion(val lockerId: String, val reason: String)

    operator fun invoke(
        oldIds: List<String>,
        logs: List<AccessLog>,
        candidates: List<Locker>
    ): List<Clues> {
        val byOldId = logs.filter { it.lockerId in oldIds }.groupBy { it.lockerId }

        // Every (old ID, locker) pair with any evidence, scored.
        val pairs = oldIds.flatMap { oldId ->
            val granted = byOldId[oldId].orEmpty().filter { it.granted }
            val lastGranted = granted.maxOfOrNull { it.timestamp }
            val grantedUids = granted.mapNotNull { it.uid }.toSet()
            candidates.mapNotNull { locker ->
                val sameTime = lastGranted != null && locker.lastOpenedAt == lastGranted
                val sameHolder = locker.assignedUid != null && locker.assignedUid in grantedUids
                val score = (if (sameTime) 3 else 0) + (if (sameHolder) 2 else 0)
                if (score == 0) null else Pair(oldId, locker) to Scored(score, reason(sameTime, sameHolder, locker))
            }
        }.toMap()

        val chosen = mutableMapOf<String, Suggestion>()
        val taken = mutableSetOf<String>()
        // Strongest evidence first; at each level accept only one-to-one matches.
        pairs.values.map { it.score }.distinct().sortedDescending().forEach { level ->
            val open = pairs.filter { (key, s) ->
                s.score == level && key.first !in chosen && key.second.id !in taken
            }
            open.forEach { (key, s) ->
                val (oldId, locker) = key
                val oneLocker = open.keys.count { it.first == oldId } == 1
                val oneOldId = open.keys.count { it.second.id == locker.id } == 1
                if (oneLocker && oneOldId) {
                    chosen[oldId] = Suggestion(locker.id, s.reason)
                    taken += locker.id
                }
            }
        }

        return oldIds.map { oldId ->
            val entries = byOldId[oldId].orEmpty()
            val names = entries.filter { it.granted }.mapNotNull { it.userName?.takeIf(String::isNotBlank) }
            Clues(
                oldId = oldId,
                usedBy = names.groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.map { it.key }.take(MAX_NAMES),
                lastUsed = entries.maxOfOrNull { it.timestamp },
                suggestion = chosen[oldId]
            )
        }
    }

    private data class Scored(val score: Int, val reason: String)

    private fun reason(sameTime: Boolean, sameHolder: Boolean, locker: Locker): String = when {
        sameTime && sameHolder -> "same last opening and same holder"
        sameTime -> "last opened at the same moment"
        else -> "held by ${locker.assignedName ?: "the same person"}"
    }

    private companion object {
        const val MAX_NAMES = 3
    }
}
