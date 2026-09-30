package com.eldroid.facelock.presenter.trail

import com.eldroid.facelock.data.model.AdminAction
import com.eldroid.facelock.presenter.base.BasePresenter
import com.eldroid.facelock.presenter.common.SectionItem
import com.eldroid.facelock.presenter.logs.LogsContract.Period

/** Read-only list of admin changes, newest first, in day sections. */
interface TrailContract {

    interface View {
        fun showLoading()
        fun showActions(items: List<SectionItem<AdminAction>>)
        fun showEmpty()
        fun showLoadError(message: String)
        fun showPeriod(period: Period)
    }

    interface Presenter : BasePresenter<View> {
        fun onPeriodSelected(period: Period)
        /** A day header was tapped. */
        fun onSectionToggled(groupKey: String)
    }
}
