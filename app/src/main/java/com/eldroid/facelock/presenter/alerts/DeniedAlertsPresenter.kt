package com.eldroid.facelock.presenter.alerts

import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.presenter.base.BasePresenter
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.util.catchFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * In-app alerts for denied attempts, for admins and security staff.
 *
 * Push notifications need Cloud Functions (Blaze plan), so this listens only
 * while the app is open: a badge counts denied attempts since the staff member
 * last opened Logs, and a denied attempt that arrives live is announced.
 */
interface DeniedAlertsContract {
    interface View {
        fun showUnseenCount(count: Int)
        fun announceDenied(log: AccessLog)
    }

    interface Presenter : BasePresenter<View> {
        /** The Logs tab was opened: everything up to now counts as seen. */
        fun onLogsOpened()
    }
}

class DeniedAlertsPresenter(
    private val logRepo: LogRepository,
    private val readSeenAt: () -> Long,
    private val writeSeenAt: (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) : CoroutinePresenter<DeniedAlertsContract.View>(), DeniedAlertsContract.Presenter {

    private var job: Job? = null
    /** Only attempts after this moment are announced; older ones just count. */
    private var liveSince = 0L
    private val announced = mutableSetOf<String>()

    override fun onViewAttached() {
        liveSince = clock()
        // First run on this device: start counting from now, not from history.
        if (readSeenAt() == 0L) writeSeenAt(liveSince)
        listen()
    }

    override fun onLogsOpened() {
        writeSeenAt(clock())
        view?.showUnseenCount(0)
        if (view != null) listen()
    }

    private fun listen() {
        job?.cancel()
        val seenAt = readSeenAt()
        job = scope.launch {
            // Only the timestamp is filtered on the server; checking the result
            // here needs no composite index.
            logRepo.observeSince(seenAt + 1, LIMIT)
                .catchFirestore("denied-attempt alerts") { /* stay quiet; Logs shows errors */ }
                .collect { logs ->
                    val denied = logs.filter { !it.granted && it.timestamp > seenAt }
                    view?.showUnseenCount(denied.size)
                    denied.firstOrNull { it.timestamp >= liveSince && announced.add(it.id) }
                        ?.let { view?.announceDenied(it) }
                }
        }
    }

    private companion object {
        const val LIMIT = 100L
    }
}
