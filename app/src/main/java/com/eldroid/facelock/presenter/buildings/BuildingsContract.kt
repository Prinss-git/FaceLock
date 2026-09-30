package com.eldroid.facelock.presenter.buildings

import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.domain.usecase.MigrateLegacyLockersUseCase
import com.eldroid.facelock.domain.usecase.SuggestOldIdLinksUseCase
import com.eldroid.facelock.presenter.base.BasePresenter

/** Building manager: add, edit and delete buildings; sort old lockers into them. */
interface BuildingsContract {

    interface View {
        fun showLoading()
        fun showBuildings(rows: List<BuildingRow>)
        fun showEmpty()
        fun showLoadError(message: String)
        fun showMessage(message: String)
        fun showBusy(busy: Boolean)

        /** [count] lockers still have no building; 0 hides the banner. */
        fun showMigrationBanner(count: Int)
        fun confirmMigration(plan: MigrateLegacyLockersUseCase.Report)
        fun showMigrationResult(result: MigrateLegacyLockersUseCase.Report)

        /** [count] old locker IDs in the access log point at no locker; 0 hides it. */
        fun showLinkBanner(count: Int)
        /** One row per old ID, with its clues; each can be matched to one of [choices]. */
        fun showLinkForm(rows: List<SuggestOldIdLinksUseCase.Clues>, choices: List<LinkChoice>)
        fun closeLinkForm()

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
        fun onMigrateClicked()
        fun onMigrationConfirmed()
        fun onLinkClicked()
        /** Old ID → chosen current locker ID; unmatched old IDs are simply left out. */
        fun onLinksSaved(links: Map<String, String>)
    }

    enum class FormField { NAME, CODE, FLOORS }
}

/** A current locker an old ID can be linked to: "M-001 · Main Building · 1F · Ana Cruz". */
data class LinkChoice(val lockerId: String, val label: String)

data class BuildingRow(
    val building: Building,
    val lockerCount: Int,
    val freeCount: Int
)
