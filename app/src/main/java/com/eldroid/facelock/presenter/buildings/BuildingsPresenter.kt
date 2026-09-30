package com.eldroid.facelock.presenter.buildings

import com.eldroid.facelock.data.model.AdminActionType
import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.presenter.base.CoroutinePresenter
import com.eldroid.facelock.presenter.buildings.BuildingsContract.FormField
import com.eldroid.facelock.util.OFFLINE_ACTION_MESSAGE
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.messageForWrite
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BuildingsPresenter(
    private val buildingRepo: BuildingRepository,
    private val lockerRepo: LockerRepository,
    private val trail: AdminTrail,
    private val isOnline: () -> Boolean = { true }
) : CoroutinePresenter<BuildingsContract.View>(), BuildingsContract.Presenter {

    private var buildings: List<Building> = emptyList()
    private var lockers: List<Locker> = emptyList()
    private var loaded = false

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
    }

    private fun render() {
        val view = view ?: return
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
        // Adding checks the code is free inside a transaction, which needs the server.
        if (editingCode == null && !isOnline()) {
            return view.showFormError(FormField.CODE, OFFLINE_ACTION_MESSAGE)
        }
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
                        messageForWrite(it, "Could not save the building")
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
        if (offline()) return
        scope.launch {
            buildingRepo.deleteBuilding(code)
                .onSuccess {
                    view?.showMessage("$name deleted")
                    trail.record(AdminActionType.BUILDING_DELETED, "$name ($code)")
                }
                .onFailure { view?.showMessage(messageForWrite(it, "Could not delete the building")) }
        }
    }

    /** True (and says so) when an action that needs the server can't run. */
    private fun offline(): Boolean {
        if (isOnline()) return false
        view?.showMessage(OFFLINE_ACTION_MESSAGE)
        return true
    }

    private companion object {
        const val MAX_NAME = 40
    }
}
