package com.eldroid.facelock.ui.admin

import android.os.Bundle
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
import com.eldroid.facelock.databinding.ActivityBuildingsBinding
import com.eldroid.facelock.databinding.DialogBuildingFormBinding
import com.eldroid.facelock.domain.usecase.AdminTrail
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.buildings.BuildingRow
import com.eldroid.facelock.presenter.buildings.BuildingsContract
import com.eldroid.facelock.presenter.buildings.BuildingsContract.FormField
import com.eldroid.facelock.presenter.buildings.BuildingsPresenter
import com.eldroid.facelock.ui.adapter.BuildingAdapter
import com.eldroid.facelock.util.NetworkMonitor
import com.eldroid.facelock.util.SessionManager
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
                trail = AdminTrail(AdminActionRepository()) { session.fullName },
                isOnline = NetworkMonitor(this)::isOnline
            )
        }

        adapter = BuildingAdapter(
            onEdit = presenter::onEditClicked,
            onDelete = presenter::onDeleteClicked
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { presenter.onAddClicked() }

        presenter.attachView(this)
    }

    override fun onDestroy() {
        presenter.detachView()
        formDialog?.dismiss()
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
