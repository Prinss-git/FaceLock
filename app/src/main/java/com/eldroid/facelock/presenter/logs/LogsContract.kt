package com.eldroid.facelock.presenter.logs

import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.presenter.base.BasePresenter
import com.eldroid.facelock.presenter.common.SectionItem
import com.eldroid.facelock.presenter.coordinator.LogsRequest

/** Access log for admins and security: one period drives counters and list. */
interface LogsContract {

    interface View {
        fun showLoading()
        /** Day sections, newest first; closed days carry only their header. */
        fun showLogs(items: List<SectionItem<AccessLog>>)
        fun showEmpty(kind: EmptyKind)
        fun showLoadError(message: String)

        /** Counters for everything in the period that passes search/building/locker. */
        fun showStats(period: Period, total: Int, granted: Int, denied: Int)
        fun showResultCounts(all: Int, granted: Int, denied: Int)
        /** True when the period holds more attempts than were loaded. */
        fun showCapNote(capped: Boolean, limit: Int)

        // Keep controls in step when the presenter changes a filter itself
        // (a coordinator request, or state restored after rotation).
        fun showPeriod(period: Period)
        fun showResultFilter(filter: ResultFilter)
        fun showLockerFilter(lockerId: String?)
        fun showBuildingFilter(label: String)

        fun showBuildingPicker(options: List<BuildingOption>, selectedKey: String)
        fun showLogDetail(detail: LogDetail)
    }

    interface Presenter : BasePresenter<View> {
        fun onPeriodSelected(period: Period)
        fun onResultSelected(filter: ResultFilter)
        fun onSearchChanged(query: String)
        fun onBuildingFilterClicked()
        fun onBuildingSelected(key: String)
        fun onLockerFilterCleared()
        fun onLogClicked(logId: String)
        /** A day header was tapped. */
        fun onSectionToggled(groupKey: String)
        /** From another tab, via the coordinator (lesson 1.5). */
        fun onRequest(request: LogsRequest)
    }

    enum class Period(val days: Int?) {
        TODAY(1), WEEK(7), MONTH(30), ALL(null)
    }

    enum class ResultFilter { ALL, GRANTED, DENIED }

    enum class EmptyKind { NOTHING_IN_PERIOD, NO_MATCH, NO_GRANTED, NO_DENIED }
}

data class BuildingOption(val key: String, val label: String)

data class LogDetail(
    val log: AccessLog,
    /** The locker's current ID. */
    val lockerLabel: String,
    /** "Main Building · 1F" when the locker is known. */
    val place: String?
)
