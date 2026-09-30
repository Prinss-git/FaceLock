package com.eldroid.facelock.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.databinding.FragmentLogsBinding
import com.eldroid.facelock.databinding.SheetLogDetailBinding
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.coordinator.LogsRequest
import com.eldroid.facelock.presenter.logs.BuildingOption
import com.eldroid.facelock.presenter.logs.LogDetail
import com.eldroid.facelock.presenter.logs.LogsContract
import com.eldroid.facelock.presenter.logs.LogsContract.EmptyKind
import com.eldroid.facelock.presenter.logs.LogsContract.Period
import com.eldroid.facelock.presenter.logs.LogsContract.ResultFilter
import com.eldroid.facelock.presenter.logs.LogsPresenter
import com.eldroid.facelock.presenter.common.SectionItem
import com.eldroid.facelock.ui.adapter.LogRows
import com.eldroid.facelock.ui.adapter.SectionedAdapter
import com.eldroid.facelock.ui.base.BaseFragment
import com.eldroid.facelock.util.asDateTime
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.visible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * System-wide access feed for admins and security staff — the View in MVP.
 * [LogsPresenter] owns every filter; this class draws and forwards taps.
 */
class AccessLogFragment : BaseFragment(), LogsContract.View {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val holder: PresenterHolder by viewModels()
    private lateinit var presenter: LogsContract.Presenter
    private lateinit var adapter: SectionedAdapter<AccessLog, LogRows.VH>
    private var detailSheet: BottomSheetDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        presenter = holder.getOrCreate {
            LogsPresenter(LogRepository(), LockerRepository(), BuildingRepository())
        }

        adapter = SectionedAdapter(
            LogRows { presenter.onLogClicked(it.id) },
            onHeaderClick = { presenter.onSectionToggled(it) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.periodGroup.setOnCheckedStateChangeListener { _, ids ->
            periodOf(ids.firstOrNull())?.let(presenter::onPeriodSelected)
        }
        binding.chipGroup.setOnCheckedStateChangeListener { _, ids ->
            presenter.onResultSelected(
                when (ids.firstOrNull()) {
                    R.id.chipGranted -> ResultFilter.GRANTED
                    R.id.chipFailed -> ResultFilter.DENIED
                    else -> ResultFilter.ALL
                }
            )
        }
        binding.etSearch.doAfterTextChanged { presenter.onSearchChanged(it?.toString().orEmpty()) }
        binding.btnBuilding.setOnClickListener { presenter.onBuildingFilterClicked() }
        binding.chipLocker.setOnCloseIconClickListener { presenter.onLockerFilterCleared() }

        presenter.attachView(this)
    }

    override fun onDestroyView() {
        presenter.detachView()
        detailSheet?.dismiss()
        detailSheet = null
        super.onDestroyView()
        _binding = null
    }

    /** Lesson 1.5: requests from other tabs arrive through the coordinator. */
    override fun onLogsRequest(request: LogsRequest): Boolean {
        if (!isAdded || _binding == null) return false
        presenter.onRequest(request)
        return true
    }

    // ------------------------------------------------------------ list ----

    override fun showLoading() {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(true)
        binding.empty.root.visible(false)
        adapter.submitList(emptyList())
    }

    override fun showLogs(items: List<SectionItem<AccessLog>>) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        binding.empty.root.visible(false)
        adapter.submitList(items)
    }

    override fun showEmpty(kind: EmptyKind) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        adapter.submitList(emptyList())
        val (icon, title, body) = when (kind) {
            EmptyKind.NOTHING_IN_PERIOD ->
                Triple(R.drawable.ic_history, R.string.empty_logs_period_title, R.string.empty_logs_period_body)
            EmptyKind.NO_MATCH ->
                Triple(R.drawable.ic_search, R.string.empty_search_title, R.string.empty_search_body)
            EmptyKind.NO_GRANTED ->
                Triple(R.drawable.ic_history, R.string.empty_granted_title, R.string.empty_granted_body)
            EmptyKind.NO_DENIED ->
                Triple(R.drawable.ic_shield, R.string.empty_denied_title, R.string.empty_denied_body)
        }
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(icon)
            tvEmptyTitle.setText(title)
            tvEmptyBody.setText(body)
            btnEmptyAction.visible(false)
        }
    }

    /**
     * Terminal state: the listener failed and will not emit again, so say why
     * instead of leaving a spinner or an "all clear" empty list on screen.
     */
    override fun showLoadError(message: String) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        adapter.submitList(emptyList())
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(R.drawable.ic_alert)
            tvEmptyTitle.text = getString(R.string.load_failed_title)
            tvEmptyBody.text = message
            btnEmptyAction.visible(false)
        }
    }

    // --------------------------------------------------------- counters ----

    override fun showStats(period: Period, total: Int, granted: Int, denied: Int) {
        val binding = _binding ?: return
        binding.tvStatToday.text = total.toString()
        binding.tvStatGranted.text = granted.toString()
        binding.tvStatDenied.text = denied.toString()
        binding.tvPctTotal.setText(periodLabel(period))
        binding.tvPctGranted.text = share(granted, total)
        binding.tvPctDenied.text = share(denied, total)
    }

    /** "85.7%" of the period's attempts, or a dash when there are none. */
    private fun share(part: Int, total: Int): String =
        if (total == 0) "—" else "%.1f%%".format(part * 100.0 / total)

    override fun showResultCounts(all: Int, granted: Int, denied: Int) {
        val binding = _binding ?: return
        binding.chipAll.text = getString(R.string.filter_all_n, all)
        binding.chipGranted.text = getString(R.string.filter_granted_n, granted)
        binding.chipFailed.text = getString(R.string.filter_denied_n, denied)
    }

    override fun showCapNote(capped: Boolean, limit: Int) {
        val binding = _binding ?: return
        binding.tvCapNote.visible(capped)
        binding.tvCapNote.text = getString(R.string.logs_capped, limit)
    }

    // --------------------------------------------------------- controls ----

    override fun showPeriod(period: Period) {
        _binding?.periodGroup?.check(
            when (period) {
                Period.TODAY -> R.id.chipToday
                Period.WEEK -> R.id.chipWeek
                Period.MONTH -> R.id.chipMonth
                Period.ALL -> R.id.chipAllTime
            }
        )
    }

    override fun showResultFilter(filter: ResultFilter) {
        _binding?.chipGroup?.check(
            when (filter) {
                ResultFilter.ALL -> R.id.chipAll
                ResultFilter.GRANTED -> R.id.chipGranted
                ResultFilter.DENIED -> R.id.chipFailed
            }
        )
    }

    override fun showLockerFilter(lockerId: String?) {
        val binding = _binding ?: return
        binding.chipLocker.visible(lockerId != null)
        binding.chipLocker.text = lockerId?.let { getString(R.string.locker_named, it) }
    }

    override fun showBuildingFilter(label: String) {
        _binding?.btnBuilding?.text = label
    }

    override fun showBuildingPicker(options: List<BuildingOption>, selectedKey: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.building)
            .setSingleChoiceItems(
                options.map { it.label }.toTypedArray(),
                options.indexOfFirst { it.key == selectedKey }
            ) { dialog, which ->
                dialog.dismiss()
                presenter.onBuildingSelected(options[which].key)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ----------------------------------------------------------- detail ----

    override fun showLogDetail(detail: LogDetail) {
        val log = detail.log
        val granted = log.granted
        val tint = requireContext().getColor(if (granted) R.color.granted else R.color.denied)
        val pill = if (granted) R.drawable.pill_granted else R.drawable.pill_denied
        val sheet = SheetLogDetailBinding.inflate(layoutInflater)

        with(sheet) {
            tvUser.text = log.userName ?: getString(R.string.unrecognized_person)
            tvResult.setText(if (granted) R.string.filter_granted else R.string.filter_denied)
            tvResult.setBackgroundResource(pill)
            tvResult.setTextColor(tint)
            iconWrap.setBackgroundResource(pill)
            ivResult.setImageResource(if (granted) R.drawable.ic_check else R.drawable.ic_alert)
            ivResult.setColorFilter(tint)

            tvLocker.text = detail.lockerLabel
            tvPlace.visible(detail.place != null)
            tvPlace.text = detail.place
            tvTime.text = log.timestamp.asDateTime()

            // For a denied attempt the score is the nearest face, not a match.
            tvMatchLabel.setText(if (granted) R.string.label_match else R.string.label_closest_match)
            tvMatchLabel.visible(log.confidence != null)
            tvMatch.visible(log.confidence != null)
            tvMatch.text = log.confidence?.let { "${(it * 100).toInt()}%" }
        }

        detailSheet?.dismiss()
        detailSheet = BottomSheetDialog(requireContext()).apply {
            setContentView(sheet.root)
            show()
        }
    }

    private fun periodOf(chipId: Int?): Period? = when (chipId) {
        R.id.chipToday -> Period.TODAY
        R.id.chipWeek -> Period.WEEK
        R.id.chipMonth -> Period.MONTH
        R.id.chipAllTime -> Period.ALL
        else -> null
    }

    private fun periodLabel(period: Period) = when (period) {
        Period.TODAY -> R.string.period_today_long
        Period.WEEK -> R.string.period_week_long
        Period.MONTH -> R.string.period_month_long
        Period.ALL -> R.string.period_all_long
    }
}
