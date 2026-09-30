package com.eldroid.facelock.presenter.buildings

import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.model.AdminActionType
import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.domain.usecase.LockerIds
import com.eldroid.facelock.domain.usecase.MigrateLegacyLockersUseCase
import com.eldroid.facelock.domain.usecase.ResolveLogLockersUseCase
import com.eldroid.facelock.domain.usecase.SuggestOldIdLinksUseCase
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.presenter.buildings.BuildingsContract.FormField
import com.eldroid.facelock.util.catchFirestore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BuildingsPresenter(
    private val buildingRepo: BuildingRepository,
    private val lockerRepo: LockerRepository,
    private val logRepo: LogRepository,
    private val trail: AdminTrail,
    private val migrate: MigrateLegacyLockersUseCase = MigrateLegacyLockersUseCase(lockerRepo),
    private val resolveLockers: ResolveLogLockersUseCase = ResolveLogLockersUseCase(),
    private val suggestLinks: SuggestOldIdLinksUseCase = SuggestOldIdLinksUseCase()
) : CoroutinePresenter<BuildingsContract.View>(), BuildingsContract.Presenter {

    private var buildings: List<Building> = emptyList()
    private var lockers: List<Locker> = emptyList()
    private var logs: List<AccessLog> = emptyList()
    private var loaded = false
    private var pendingMigration: MigrateLegacyLockersUseCase.Report? = null

    override fun onViewAttached() {
        if (loaded) render() else view?.showLoading()

        scope.launch {
            combine(buildingRepo.observeBuildings(), lockerRepo.observeLockers()) { b, l -> b to l }
                .catchFirestore("buildings") { view?.showLoadError(it) }
                .collect { (b, l) ->
                    buildings = b
                    lockers = l
                    loaded = true
                    render()
                }
        }
        // Separately, so a problem reading logs never hides the buildings.
        scope.launch {
            logRepo.observeSince(0L, LOG_SCAN_LIMIT)
                .catchFirestore("the access log") { /* the link banner just stays hidden */ }
                .collect {
                    logs = it
                    if (loaded) render()
                }
        }
    }

    /** Old IDs in the log that no locker answers to, current or former. */
    private fun unlinkedIds() = resolveLockers.unknownIds(logs, lockers)

    private fun render() {
        val view = view ?: return
        view.showMigrationBanner(migrate.needsMigration(lockers).size)
        view.showLinkBanner(unlinkedIds().size)
        if (buildings.isEmpty()) return view.showEmpty()

        view.showBuildings(buildings.map { building ->
            val inside = lockersIn(building.code)
            BuildingRow(building, inside.size, inside.count { it.isFree })
        })
    }

    private fun lockersIn(code: String) = lockers.filter { it.building == code }

    // ------------------------------------------------------ add / edit ----

    override fun onAddClicked() {
        view?.showBuildingForm(null)
    }

    override fun onEditClicked(code: String) {
        buildings.firstOrNull { it.code == code }?.let { view?.showBuildingForm(it) }
    }

    override fun onSaveBuilding(
        editingCode: String?,
        name: String,
        code: String,
        floors: String,
        groundFloor: Boolean
    ) {
        val view = view ?: return
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        val cleanCode = (editingCode ?: code).trim().uppercase()
        val floorCount = floors.trim().toIntOrNull()
        val others = buildings.filter { it.code != editingCode }
        // A building cannot shrink below a floor that still has lockers on it.
        val highestUsedFloor = editingCode?.let { lockersIn(it).mapNotNull { l -> l.floor }.maxOrNull() }

        when {
            cleanName.isEmpty() ->
                return view.showFormError(FormField.NAME, "Enter the building name")
            cleanName.length > MAX_NAME ->
                return view.showFormError(FormField.NAME, "Keep it under $MAX_NAME characters")
            others.any { it.name.equals(cleanName, ignoreCase = true) } ->
                return view.showFormError(FormField.NAME, "Another building already has this name")
            editingCode == null && !Building.CODE_PATTERN.matches(cleanCode) ->
                return view.showFormError(FormField.CODE, "Use 1–3 letters, e.g. M or ENG")
            editingCode == null && others.any { it.code == cleanCode } ->
                return view.showFormError(FormField.CODE, "Code $cleanCode is already in use")
            floorCount == null || floorCount !in 1..Building.MAX_FLOORS ->
                return view.showFormError(
                    FormField.FLOORS, "Enter a number from 1 to ${Building.MAX_FLOORS}"
                )
            highestUsedFloor != null && floorCount < highestUsedFloor ->
                return view.showFormError(
                    FormField.FLOORS,
                    "Lockers stand on floor $highestUsedFloor; keep at least $highestUsedFloor floors"
                )
        }

        val saved = Building(
            code = cleanCode,
            name = cleanName,
            floors = floorCount!!,
            groundFloor = groundFloor
        )
        scope.launch {
            val result = if (editingCode == null) {
                buildingRepo.createBuilding(saved)
            } else {
                val existing = buildings.first { it.code == editingCode }
                buildingRepo.updateBuilding(
                    existing.copy(name = saved.name, floors = saved.floors, groundFloor = groundFloor),
                    lockersIn(editingCode)
                )
            }
            result
                .onSuccess {
                    this@BuildingsPresenter.view?.closeBuildingForm()
                    this@BuildingsPresenter.view?.showMessage(
                        if (editingCode == null) "$cleanName added" else "$cleanName updated"
                    )
                    val details = "${saved.floors} floor(s)" + if (groundFloor) ", GF first" else ""
                    trail.record(
                        if (editingCode == null) AdminActionType.BUILDING_ADDED
                        else AdminActionType.BUILDING_EDITED,
                        "$cleanName ($cleanCode)",
                        details
                    )
                }
                .onFailure {
                    this@BuildingsPresenter.view?.showFormError(
                        if (editingCode == null) FormField.CODE else FormField.NAME,
                        it.message ?: "Could not save the building"
                    )
                }
        }
    }

    // ---------------------------------------------------------- delete ----

    override fun onDeleteClicked(code: String) {
        val building = buildings.firstOrNull { it.code == code } ?: return
        val count = lockersIn(code).size
        if (count > 0) {
            view?.showMessage("${building.name} still has $count locker(s). Move or remove them first.")
        } else {
            view?.confirmDelete(building)
        }
    }

    override fun onDeleteConfirmed(code: String) {
        val name = buildings.firstOrNull { it.code == code }?.name ?: code
        scope.launch {
            buildingRepo.deleteBuilding(code)
                .onSuccess {
                    view?.showMessage("$name deleted")
                    trail.record(AdminActionType.BUILDING_DELETED, "$name ($code)")
                }
                .onFailure { view?.showMessage(it.message ?: "Could not delete the building") }
        }
    }

    // ------------------------------------------------------- migration ----

    override fun onMigrateClicked() {
        if (migrate.needsMigration(lockers).isEmpty()) {
            view?.showMessage("Every locker is already in a building.")
            return
        }
        val plan = migrate.plan(lockers, buildings)
        if (plan.moved.isEmpty()) {
            // Nothing can be matched yet; the report says why for each locker.
            view?.showMigrationResult(plan)
        } else {
            pendingMigration = plan
            view?.confirmMigration(plan)
        }
    }

    override fun onMigrationConfirmed() {
        val plan = pendingMigration ?: return
        pendingMigration = null
        view?.showBusy(true)
        scope.launch {
            val result = migrate(plan)
            view?.showBusy(false)
            view?.showMigrationResult(result)
            if (result.moved.isNotEmpty()) {
                trail.record(
                    AdminActionType.LOCKERS_MIGRATED,
                    "${result.moved.size} locker(s)",
                    result.moved.joinToString { "${it.locker.id}→${it.newId}" }
                )
            }
        }
    }

    // --------------------------------------------------- link old IDs ----

    override fun onLinkClicked() {
        val oldIds = unlinkedIds()
        if (oldIds.isEmpty()) {
            view?.showMessage("Every locker in the access log is already linked.")
            return
        }
        // Only lockers that have no old ID yet; place and holder for recognition.
        val candidates = lockers
            .filter { it.formerId.isNullOrBlank() }
            .sortedWith(LockerIds.naturalOrder)
        val choices = candidates.map { locker ->
            val building = buildings.firstOrNull { it.code == locker.building }
            val floor = locker.floor
            val place = if (building != null && floor != null) building.locationOf(floor)
            else locker.location
            LinkChoice(
                locker.id,
                listOfNotNull(locker.id, place, locker.assignedName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            )
        }
        if (choices.isEmpty()) {
            view?.showMessage("Every locker already has an old ID linked.")
            return
        }
        view?.showLinkForm(suggestLinks(oldIds, logs, candidates), choices)
    }

    override fun onLinksSaved(links: Map<String, String>) {
        if (links.isEmpty()) {
            view?.closeLinkForm()
            return
        }
        // One old ID per locker: two old IDs can't both become the same locker.
        val clash = links.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (clash.isNotEmpty()) {
            view?.showMessage("${clash.joinToString()} was chosen more than once. Pick each locker once.")
            return
        }
        val byLocker = links.entries.associate { (oldId, lockerId) -> lockerId to oldId }
        scope.launch {
            lockerRepo.setFormerIds(byLocker)
                .onSuccess {
                    view?.closeLinkForm()
                    view?.showMessage("${links.size} old ID(s) linked. The logs now show current IDs.")
                    trail.record(
                        AdminActionType.LOCKERS_LINKED,
                        "${links.size} locker(s)",
                        links.entries.joinToString { (old, new) -> "$old→$new" }
                    )
                }
                .onFailure { view?.showMessage(it.message ?: "Could not save the links") }
        }
    }

    private companion object {
        const val MAX_NAME = 40
        /** Enough to cover the access log a test deployment builds up. */
        const val LOG_SCAN_LIMIT = 500L
    }
}
