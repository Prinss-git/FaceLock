package com.eldroid.facelock.ui.admin

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Building
import com.eldroid.facelock.data.repo.AdminActionRepository
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.databinding.ActivityBuildingsBinding
import com.eldroid.facelock.databinding.DialogBuildingFormBinding
import com.eldroid.facelock.databinding.DialogLinkOldIdsBinding
import com.eldroid.facelock.databinding.ItemLinkOldIdBinding
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.domain.usecase.MigrateLegacyLockersUseCase
import com.eldroid.facelock.domain.usecase.SuggestOldIdLinksUseCase
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.buildings.BuildingRow
import com.eldroid.facelock.presenter.buildings.BuildingsContract
import com.eldroid.facelock.presenter.buildings.BuildingsContract.FormField
import com.eldroid.facelock.presenter.buildings.BuildingsPresenter
import com.eldroid.facelock.presenter.buildings.LinkChoice
import com.eldroid.facelock.ui.adapter.BuildingAdapter
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.visible
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Building manager (admins only) — the View in MVP. Buildings are what the
 * Lockers tab groups by, and what Add locker offers in its dropdown.
 */
class BuildingsActivity : AppCompatActivity(), BuildingsContract.View {

    private lateinit var binding: ActivityBuildingsBinding
    private val holder: PresenterHolder by viewModels()
    private lateinit var presenter: BuildingsContract.Presenter
    private lateinit var adapter: BuildingAdapter

    private var formDialog: AlertDialog? = null
    private var formBinding: DialogBuildingFormBinding? = null
    private var linkDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuildingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val session = SessionManager(this)
        presenter = holder.getOrCreate {
            BuildingsPresenter(
                buildingRepo = BuildingRepository(),
                lockerRepo = LockerRepository(),
                logRepo = LogRepository(),
                trail = AdminTrail(AdminActionRepository()) { session.fullName }
            )
        }

        adapter = BuildingAdapter(
            onEdit = presenter::onEditClicked,
            onDelete = presenter::onDeleteClicked
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { presenter.onAddClicked() }
        binding.btnMigrate.setOnClickListener { presenter.onMigrateClicked() }
        binding.btnLink.setOnClickListener { presenter.onLinkClicked() }

        presenter.attachView(this)
    }

    override fun onDestroy() {
        presenter.detachView()
        formDialog?.dismiss()
        linkDialog?.dismiss()
        super.onDestroy()
    }

    // ------------------------------------------------------------ list ----

    override fun showLoading() {
        binding.progress.visible(true)
    }

    override fun showBuildings(rows: List<BuildingRow>) {
        binding.progress.visible(false)
        binding.empty.root.visible(false)
        adapter.submitList(rows)
    }

    override fun showEmpty() {
        binding.progress.visible(false)
        adapter.submitList(emptyList())
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(R.drawable.ic_location)
            tvEmptyTitle.setText(R.string.no_buildings_title)
            tvEmptyBody.setText(R.string.no_buildings_body)
            btnEmptyAction.visible(true)
            btnEmptyAction.setText(R.string.add_building)
            btnEmptyAction.setOnClickListener { presenter.onAddClicked() }
        }
    }

    override fun showLoadError(message: String) {
        binding.progress.visible(false)
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(R.drawable.ic_alert)
            tvEmptyTitle.setText(R.string.load_failed_title)
            tvEmptyBody.text = message
            btnEmptyAction.visible(false)
        }
    }

    override fun showMessage(message: String) = binding.root.snack(message)

    override fun showBusy(busy: Boolean) {
        binding.progress.visible(busy)
        binding.btnMigrate.isEnabled = !busy
        binding.fabAdd.isEnabled = !busy
    }

    // ------------------------------------------------------- migration ----

    override fun showMigrationBanner(count: Int) {
        binding.cardMigration.visible(count > 0)
        binding.tvMigrationTitle.text =
            resources.getQuantityString(R.plurals.migration_title, count, count)
    }

    override fun confirmMigration(plan: MigrateLegacyLockersUseCase.Report) {
        val lines = plan.moved.joinToString("\n") { "${it.locker.id}  →  ${it.newId}" }
        val skipped = if (plan.skipped.isEmpty()) "" else "\n\n" + getString(
            R.string.migration_will_skip, plan.skipped.size
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.migration_confirm_title)
            .setMessage(getString(R.string.migration_confirm_body) + "\n\n" + lines + skipped)
            .setPositiveButton(R.string.migration_action) { _, _ -> presenter.onMigrationConfirmed() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun showMigrationResult(result: MigrateLegacyLockersUseCase.Report) {
        val parts = mutableListOf<String>()
        if (result.moved.isNotEmpty()) {
            parts += getString(R.string.migration_moved, result.moved.size)
        }
        if (result.skipped.isNotEmpty()) {
            parts += getString(R.string.migration_skipped_header) + "\n" +
                result.skipped.joinToString("\n") { "• ${it.locker.id}: ${it.reason}" }
            parts += getString(R.string.migration_skipped_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.migration_result_title)
            .setMessage(parts.joinToString("\n\n"))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    // ---------------------------------------------------- link old IDs ----

    override fun showLinkBanner(count: Int) {
        binding.cardLink.visible(count > 0)
        binding.tvLinkTitle.text = resources.getQuantityString(R.plurals.link_title, count, count)
    }

    override fun showLinkForm(rows: List<SuggestOldIdLinksUseCase.Clues>, choices: List<LinkChoice>) {
        val form = DialogLinkOldIdsBinding.inflate(layoutInflater)
        // First entry leaves an old ID unlinked, so a wrong pick can be undone.
        val labels = listOf(getString(R.string.link_not_linked)) + choices.map { it.label }
        val picks = mutableMapOf<String, String>()

        form.btnTips.setOnClickListener {
            val open = form.tvTips.visibility != android.view.View.VISIBLE
            form.tvTips.visible(open)
            form.btnTips.setText(if (open) R.string.link_tips_hide else R.string.link_tips_show)
        }

        rows.forEach { clues ->
            val oldId = clues.oldId
            val row = ItemLinkOldIdBinding.inflate(layoutInflater, form.rows, false)
            row.til.hint = getString(R.string.link_row_hint, oldId)
            row.ac.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
            row.ac.setOnItemClickListener { _, _, position, _ ->
                if (position == 0) picks.remove(oldId) else picks[oldId] = choices[position - 1].lockerId
            }

            // What the access log remembers about this old ID, to jog the admin's memory.
            val last = clues.lastUsed?.asRelativeDateTime() ?: "—"
            val helper = mutableListOf(
                if (clues.usedBy.isEmpty()) getString(R.string.link_row_no_one, last)
                else getString(R.string.link_row_used_by, clues.usedBy.joinToString(), last)
            )
            clues.suggestion?.let { s ->
                choices.firstOrNull { it.lockerId == s.lockerId }?.let { choice ->
                    row.ac.setText(choice.label, false)
                    picks[oldId] = choice.lockerId
                    helper += getString(R.string.link_row_suggested, s.reason)
                }
            }
            row.til.helperText = helper.joinToString("\n")
            form.rows.addView(row.root)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_form_title)
            .setView(form.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        // Set the click listener after show() so a clash message does not dismiss.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                presenter.onLinksSaved(picks.toMap())
            }
        }
        dialog.setOnDismissListener { if (linkDialog === dialog) linkDialog = null }
        linkDialog = dialog
        dialog.show()
    }

    override fun closeLinkForm() {
        linkDialog?.dismiss()
    }

    // ------------------------------------------------------------ form ----

    override fun showBuildingForm(existing: Building?) {
        val form = DialogBuildingFormBinding.inflate(layoutInflater)
        if (existing != null) {
            form.etName.setText(existing.name)
            form.etCode.setText(existing.code)
            form.etFloors.setText(existing.floors.toString())
            form.swGroundFloor.isChecked = existing.groundFloor
            // The code is the document ID and every locker ID's prefix.
            form.tilCode.isEnabled = false
            form.tilCode.helperText = getString(R.string.building_code_locked)
        }
        form.etName.doAfterTextChanged { form.tilName.error = null }
        form.etCode.doAfterTextChanged { form.tilCode.error = null }
        form.etFloors.doAfterTextChanged { form.tilFloors.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_building else R.string.edit_building)
            .setView(form.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // Set the click listener after show() so a validation error does not dismiss.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                presenter.onSaveBuilding(
                    editingCode = existing?.code,
                    name = form.etName.text?.toString().orEmpty(),
                    code = form.etCode.text?.toString().orEmpty(),
                    floors = form.etFloors.text?.toString().orEmpty(),
                    groundFloor = form.swGroundFloor.isChecked
                )
            }
        }
        dialog.setOnDismissListener {
            if (formDialog === dialog) {
                formDialog = null
                formBinding = null
            }
        }
        formDialog = dialog
        formBinding = form
        dialog.show()
    }

    override fun showFormError(field: FormField, message: String) {
        val form = formBinding ?: return showMessage(message)
        when (field) {
            FormField.NAME -> form.tilName.error = message
            FormField.CODE -> form.tilCode.error = message
            FormField.FLOORS -> form.tilFloors.error = message
        }
    }

    override fun closeBuildingForm() {
        formDialog?.dismiss()
    }

    override fun confirmDelete(building: Building) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_building_title, building.name))
            .setMessage(R.string.delete_building_body)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                presenter.onDeleteConfirmed(building.code)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
