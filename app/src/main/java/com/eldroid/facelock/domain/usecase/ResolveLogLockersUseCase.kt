package com.eldroid.facelock.domain.usecase

import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.model.Locker

/**
 * Rewrites each log's locker to the locker's current ID.
 *
 * Logs can't be edited (firestore.rules), so ones written before a locker was
 * re-created under a new ID still name the old one (L-011). A locker records
 * that old ID as `formerId`, so the log is mapped to it here — once, on load —
 * and every screen, filter and search after that sees only current IDs.
 *
 * A log whose locker can't be identified is returned unchanged.
 */
class ResolveLogLockersUseCase {

    operator fun invoke(logs: List<AccessLog>, lockers: List<Locker>): List<AccessLog> {
        val index = index(lockers)
        return logs.map { log ->
            val current = index[log.lockerId]?.id
            if (current == null || current == log.lockerId) log else log.copy(lockerId = current)
        }
    }

    private fun index(lockers: List<Locker>): Map<String, Locker> = buildMap {
        // Former IDs first, so a current ID always wins a clash.
        lockers.forEach { locker -> locker.formerId?.let { put(it, locker) } }
        lockers.forEach { put(it.id, it) }
    }
}
