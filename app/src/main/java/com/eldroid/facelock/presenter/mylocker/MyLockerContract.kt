package com.eldroid.facelock.presenter.mylocker

import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.presenter.base.BasePresenter

/** The member's home tab: their face status, their locker, and recent attempts. */
interface MyLockerContract {

    interface View {
        fun showProfile(firstName: String, faceEnrolled: Boolean)
        fun showNoLocker()
        fun showLocker(card: LockerCard)
        /** The newest few attempts; empty shows "no history yet". */
        fun showRecent(logs: List<AccessLog>)
        fun showMessage(message: String)
        fun openHistory()
    }

    interface Presenter : BasePresenter<View> {
        fun onViewAllClicked()
    }
}

data class LockerCard(
    val id: String,
    /** Null when the locker record is gone (removed after assignment). */
    val status: String?,
    /** "Main Building · 1F", or null when unknown. */
    val place: String?,
    val lastOpenedAt: Long?
)
