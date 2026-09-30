package com.eldroid.facelock.presenter.coordinator

/**
 * Fragment-to-fragment messaging for the admin/security tabs (Module 2,
 * lesson 1.5). Fragments never reference each other: Lockers asks the
 * coordinator for a locker's history, and the coordinator switches tabs and
 * hands the request to whichever fragment listens for it.
 *
 *   [Lockers fragment] → coordinator → [Logs fragment]
 */
interface AdminFragmentCoordinator {
    fun showLockerHistory(lockerId: String)
    fun showDeniedLogs()
    fun registerListener(listener: CoordinatorListener)
    fun unregisterListener(listener: CoordinatorListener)
}

/** Implemented by fragments that react to coordinator requests. */
interface CoordinatorListener {
    /** Return true once handled, so the request is not delivered twice. */
    fun onLogsRequest(request: LogsRequest): Boolean = false
}

sealed class LogsRequest {
    data class ForLocker(val lockerId: String) : LogsRequest()
    object DeniedOnly : LogsRequest()
}

/** An Activity that owns the coordinator its fragments talk through. */
interface CoordinatorHost {
    val coordinator: AdminFragmentCoordinator
}

/**
 * [openLogsTab] switches the bottom navigation to Logs.
 *
 * The request is kept until a listener handles it: the Logs fragment may not
 * exist yet the first time, and is only created — and registered — by the tab
 * switch itself.
 */
class AppAdminCoordinator(
    private val openLogsTab: () -> Unit
) : AdminFragmentCoordinator {

    private val listeners = linkedSetOf<CoordinatorListener>()
    private var pending: LogsRequest? = null

    override fun showLockerHistory(lockerId: String) = request(LogsRequest.ForLocker(lockerId))

    override fun showDeniedLogs() = request(LogsRequest.DeniedOnly)

    private fun request(request: LogsRequest) {
        pending = request
        openLogsTab()
        deliver()
    }

    override fun registerListener(listener: CoordinatorListener) {
        listeners += listener
        deliver()
    }

    override fun unregisterListener(listener: CoordinatorListener) {
        listeners -= listener
    }

    private fun deliver() {
        val request = pending ?: return
        // Copy: a listener may unregister itself while handling.
        if (listeners.toList().any { it.onLogsRequest(request) }) pending = null
    }
}
