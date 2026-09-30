package com.eldroid.facelock.presenter.logs

import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.domain.usecase.ResolveLogLockersUseCase
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.presenter.common.CollapseState
import com.eldroid.facelock.presenter.common.Group
import com.eldroid.facelock.presenter.common.groupByDay
import com.eldroid.facelock.presenter.coordinator.LogsRequest
import com.eldroid.facelock.presenter.logs.LogsContract.EmptyKind
import com.eldroid.facelock.presenter.logs.LogsContract.Period
import com.eldroid.facelock.presenter.logs.LogsContract.ResultFilter
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.startOfToday
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One period feeds everything on screen: the counters, the result chips and
 * the list are all computed from the same loaded set, so they can never
 * disagree the way "today" counters over an all-time list did.
 */
class LogsPresenter(
    private val logRepo: LogRepository,
    private val lockerRepo: LockerRepository,
    private val buildingRepo: BuildingRepository,
    private val resolveLockers: ResolveLogLockersUseCase = ResolveLogLockersUseCase()
) : CoroutinePresenter<LogsContract.View>(), LogsContract.Presenter {

    private var period = Period.WEEK
    private var result = ResultFilter.ALL
    private var query = ""
    private var buildingKey = ALL_KEY
    private var lockerFilter: String? = null

    /** As stored; may still name old locker IDs. */
    private var rawLogs: List<AccessLog> = emptyList()
    /** [rawLogs] with every locker ID made current. Everything below uses this. */
    private var logs: List<AccessLog> = emptyList()
    private var lockers: List<Locker> = emptyList()
    private var buildings: List<Building> = emptyList()
    private var lockersById: Map<String, Locker> = emptyMap()
    private var logsLoaded = false
    private var logsJob: Job? = null

    /** The newest day starts open, older days closed, until tapped. */
    private val sections = CollapseState { index -> index == 0 }
    private var days: List<Group<AccessLog>> = emptyList()

    override fun onViewAttached() {
        val view = view ?: return
        view.showPeriod(period)
        view.showResultFilter(result)
        view.showLockerFilter(lockerFilter)
        view.showBuildingFilter(buildingLabel())
        if (logsLoaded) render() else view.showLoading()

        scope.launch {
            combine(lockerRepo.observeLockers(), buildingRepo.observeBuildings()) { l, b -> l to b }
                .catchFirestore("lockers") { /* logs still show; names just stay unresolved */ }
                .collect { (l, b) ->
                    lockers = l
                    buildings = b
                    lockersById = l.associateBy { it.id }
                    // A newly linked old ID changes how existing logs resolve.
                    logs = resolveLockers(rawLogs, lockers)
                    if (logsLoaded) render()
                }
        }
        subscribeLogs()
    }

    /** Re-queries the server when the period changes; everything else is local. */
    private fun subscribeLogs() {
        logsJob?.cancel()
        logsJob = scope.launch {
            logRepo.observeSince(sinceOf(period), LIMIT)
                .catchFirestore("the access log") { view?.showLoadError(it) }
                .collect {
                    rawLogs = it
                    logs = resolveLockers(it, lockers)
                    logsLoaded = true
                    render()
                }
        }
    }

    private fun sinceOf(period: Period): Long {
        val days = period.days ?: return 0L
        return startOfToday() - (days - 1) * DAY_MS
    }

    // ---------------------------------------------------------- filters ----

    override fun onPeriodSelected(period: Period) {
        if (period == this.period) return
        this.period = period
        logsLoaded = false
        view?.showLoading()
        if (view != null) subscribeLogs()
    }

    override fun onResultSelected(filter: ResultFilter) {
        if (filter == result) return
        result = filter
        render()
    }

    override fun onSearchChanged(query: String) {
        val trimmed = query.trim()
        if (trimmed == this.query) return
        this.query = trimmed
        render()
    }

    override fun onBuildingFilterClicked() {
        val options = listOf(BuildingOption(ALL_KEY, "All buildings")) +
            buildings.map { BuildingOption(it.code, "${it.name} (${it.code})") } +
            BuildingOption(OTHER_KEY, "Not in a building")
        view?.showBuildingPicker(options, buildingKey)
    }

    override fun onBuildingSelected(key: String) {
        buildingKey = key
        view?.showBuildingFilter(buildingLabel())
        render()
    }

    override fun onLockerFilterCleared() {
        lockerFilter = null
        view?.showLockerFilter(null)
        render()
    }

    override fun onRequest(request: LogsRequest) {
        when (request) {
            is LogsRequest.ForLocker -> {
                // A locker's history: its whole record, whatever else was set.
                lockerFilter = request.lockerId
                result = ResultFilter.ALL
                query = ""
                buildingKey = ALL_KEY
                view?.showLockerFilter(lockerFilter)
                view?.showResultFilter(result)
                view?.showBuildingFilter(buildingLabel())
                if (period != Period.ALL) {
                    period = Period.ALL
                    view?.showPeriod(period)
                    logsLoaded = false
                    view?.showLoading()
                    if (view != null) subscribeLogs()
                    return
                }
            }
            LogsRequest.DeniedOnly -> {
                result = ResultFilter.DENIED
                view?.showResultFilter(result)
            }
        }
        render()
    }

    private fun buildingLabel(): String = when (buildingKey) {
        ALL_KEY -> "All buildings"
        OTHER_KEY -> "No building"
        else -> buildings.firstOrNull { it.code == buildingKey }?.name ?: buildingKey
    }

    // ----------------------------------------------------------- render ----

    private fun render() {
        val view = view ?: return
        if (!logsLoaded) return

        // Everything except the result filter: that is what the chips count.
        val scoped = logs.filter(::inScope)
        val granted = scoped.count { it.granted }
        val denied = scoped.size - granted
        view.showStats(period, scoped.size, granted, denied)
        view.showResultCounts(scoped.size, granted, denied)
        view.showCapNote(logs.size >= LIMIT, LIMIT.toInt())

        val shown = when (result) {
            ResultFilter.ALL -> scoped
            ResultFilter.GRANTED -> scoped.filter { it.granted }
            ResultFilter.DENIED -> scoped.filterNot { it.granted }
        }
        days = groupByDay(shown) { it.timestamp }
        when {
            // A search or a locker's history shows every match, whatever was closed.
            shown.isNotEmpty() -> view.showLogs(
                sections.flatten(days, forceOpen = query.isNotEmpty() || lockerFilter != null) { it.id }
            )
            logs.isEmpty() -> view.showEmpty(EmptyKind.NOTHING_IN_PERIOD)
            scoped.isEmpty() -> view.showEmpty(EmptyKind.NO_MATCH)
            result == ResultFilter.GRANTED -> view.showEmpty(EmptyKind.NO_GRANTED)
            else -> view.showEmpty(EmptyKind.NO_DENIED)
        }
    }

    /** Logs are already resolved, so every check here is on current IDs only. */
    private fun inScope(log: AccessLog): Boolean {
        if (lockerFilter != null && log.lockerId != lockerFilter) return false

        val locker = lockersById[log.lockerId]
        val building = buildings.firstOrNull { it.code == locker?.building }
        when (buildingKey) {
            ALL_KEY -> Unit
            OTHER_KEY -> if (building != null) return false
            else -> if (building?.code != buildingKey) return false
        }
        if (query.isNotEmpty()) {
            val hit = log.userName?.contains(query, true) == true ||
                log.lockerId.contains(query, true) ||
                building?.name?.contains(query, true) == true ||
                building?.code?.equals(query, true) == true
            if (!hit) return false
        }
        return true
    }

    // ----------------------------------------------------------- detail ----

    override fun onSectionToggled(groupKey: String) {
        sections.toggle(groupKey, days)
        render()
    }

    override fun onLogClicked(logId: String) {
        val log = logs.firstOrNull { it.id == logId } ?: return
        val locker = lockersById[log.lockerId]
        val building = buildings.firstOrNull { it.code == locker?.building }
        val floor = locker?.floor
        val place = when {
            building != null && floor != null -> building.locationOf(floor)
            else -> locker?.location?.takeIf { it.isNotBlank() }
        }
        view?.showLogDetail(LogDetail(log, log.lockerId, place))
    }

    private companion object {
        const val ALL_KEY = "*"
        const val OTHER_KEY = "?"
        const val LIMIT = 500L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
