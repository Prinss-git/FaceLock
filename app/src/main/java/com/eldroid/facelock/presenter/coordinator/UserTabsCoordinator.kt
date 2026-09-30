package com.eldroid.facelock.presenter.coordinator

/**
 * Tab-to-tab navigation on the member side (lesson 1.5). My Locker asks for
 * the History tab through this instead of casting its activity.
 */
interface UserTabsCoordinator {
    fun openHistory()
}

/** An Activity that owns the member-side coordinator. */
interface UserCoordinatorHost {
    val userCoordinator: UserTabsCoordinator
}

class AppUserTabsCoordinator(private val openHistoryTab: () -> Unit) : UserTabsCoordinator {
    override fun openHistory() = openHistoryTab()
}
