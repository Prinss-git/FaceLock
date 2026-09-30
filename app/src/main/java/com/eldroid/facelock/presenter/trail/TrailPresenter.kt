package com.eldroid.facelock.presenter.trail

import com.eldroid.facelock.data.model.AdminAction
import com.eldroid.facelock.data.repo.AdminActionRepository
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.presenter.common.CollapseState
import com.eldroid.facelock.presenter.common.Group
import com.eldroid.facelock.presenter.common.groupByDay
import com.eldroid.facelock.presenter.logs.LogsContract.Period
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.startOfToday
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TrailPresenter(
    private val repo: AdminActionRepository
) : CoroutinePresenter<TrailContract.View>(), TrailContract.Presenter {

    private var period = Period.WEEK
    private var actions: List<AdminAction>? = null
    private var job: Job? = null

    /** The newest day starts open, older days closed, until tapped. */
    private val sections = CollapseState { index -> index == 0 }
    private var days: List<Group<AdminAction>> = emptyList()

    override fun onViewAttached() {
        view?.showPeriod(period)
        actions?.let(::render) ?: view?.showLoading()
        subscribe()
    }

    override fun onPeriodSelected(period: Period) {
        if (period == this.period) return
        this.period = period
        actions = null
        view?.showLoading()
        if (view != null) subscribe()
    }

    private fun subscribe() {
        job?.cancel()
        val since = period.days?.let { startOfToday() - (it - 1) * DAY_MS } ?: 0L
        job = scope.launch {
            repo.observeSince(since, LIMIT)
                .catchFirestore("the activity trail") { view?.showLoadError(it) }
                .collect {
                    actions = it
                    render(it)
                }
        }
    }

    override fun onSectionToggled(groupKey: String) {
        sections.toggle(groupKey, days)
        actions?.let(::render)
    }

    private fun render(actions: List<AdminAction>) {
        days = groupByDay(actions) { it.timestamp }
        if (actions.isEmpty()) view?.showEmpty()
        else view?.showActions(sections.flatten(days) { it.id })
    }

    private companion object {
        const val LIMIT = 300L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
