package com.eldroid.facelock.domain.usecase

import android.util.Log
import com.eldroid.facelock.data.model.AdminAction
import com.eldroid.facelock.data.model.AdminActionType
import com.eldroid.facelock.data.repo.AdminActionRepository
import com.eldroid.facelock.data.repo.FirebaseRefs

/**
 * Records admin changes in the activity trail.
 *
 * Called after a change has succeeded, and never waits for the write. It is
 * best effort on purpose: if the trail write fails, the change itself stands
 * and the failure is only logged, because undoing a real assignment over a
 * missing audit line would be worse.
 */
class AdminTrail(
    private val repo: AdminActionRepository,
    private val actorName: () -> String
) {
    fun record(type: AdminActionType, target: String, details: String? = null) {
        val uid = FirebaseRefs.auth.currentUser?.uid ?: return
        repo.add(
            AdminAction(
                actorUid = uid,
                actorName = actorName().ifBlank { FirebaseRefs.auth.currentUser?.email.orEmpty() },
                action = type.name,
                target = target,
                details = details
            )
        ) { Log.w("FaceLock/trail", "could not record ${type.name} $target", it) }
    }
}
