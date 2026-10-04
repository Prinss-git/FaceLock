package com.eldroid.facelock.domain.usecase

import com.eldroid.facelock.data.model.Locker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A board that stops heartbeating never writes "I'm gone", so OFFLINE is worked
 * out here from [Locker.lastSeenAt] rather than stored. The copy is for display
 * only and is never written back.
 *
 * A locker whose board has never checked in keeps its stored status: before the
 * hardware exists, every locker would otherwise read as offline.
 */
fun Locker.withLiveStatus(now: Long): Locker {
    val seen = lastSeenAt ?: return this
    val gone = isInService && now - seen > Locker.DEVICE_TIMEOUT_MS
    return if (gone) copy(status = Locker.STATUS_OFFLINE) else this
}

/**
 * Current time, re-emitted every [periodMs]. A locker document does not change
 * when its board dies, so the screens need their own tick to notice.
 */
fun clock(periodMs: Long = 30_000L): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}
