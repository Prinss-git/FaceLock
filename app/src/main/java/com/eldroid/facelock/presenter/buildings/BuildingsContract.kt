package com.eldroid.facelock.presenter.buildings

import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.presenter.base.BasePresenter

/** Building manager: add, edit and delete the buildings lockers are grouped under. */
interface BuildingsContract {

    interface View {
        fun showLoading()
        fun showBuildings(rows: List<BuildingRow>)
        fun showEmpty()
        fun showLoadError(message: String)
        fun showMessage(message: String)

        /** [existing] null means a new building. */
        fun showBuildingForm(existing: Building?)
        fun showFormError(field: FormField, message: String)
        fun closeBuildingForm()
        fun confirmDelete(building: Building)
    }

    interface Presenter : BasePresenter<View> {
        fun onAddClicked()
        fun onEditClicked(code: String)
        fun onSaveBuilding(
            editingCode: String?,
            name: String,
            code: String,
            floors: String,
            groundFloor: Boolean
        )
        fun onDeleteClicked(code: String)
        fun onDeleteConfirmed(code: String)
    }

    enum class FormField { NAME, CODE, FLOORS }
}

data class BuildingRow(
    val building: Building,
    val lockerCount: Int,
    val freeCount: Int
)
