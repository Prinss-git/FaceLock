package com.eldroid.facelock.ui.admin

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AdminAction
import com.eldroid.facelock.data.repo.AdminActionRepository
import com.eldroid.facelock.databinding.ActivityTrailBinding
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.logs.LogsContract.Period
import com.eldroid.facelock.presenter.trail.TrailContract
import com.eldroid.facelock.presenter.trail.TrailPresenter
import com.eldroid.facelock.presenter.common.SectionItem
import com.eldroid.facelock.ui.adapter.AdminActionRows
import com.eldroid.facelock.ui.adapter.SectionedAdapter
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.visible

/** Activity trail (admins only) — the View in MVP. */
class ActivityTrailActivity : AppCompatActivity(), TrailContract.View {

    private lateinit var binding: ActivityTrailBinding
    private val holder: PresenterHolder by viewModels()
    private lateinit var presenter: TrailContract.Presenter
    private val adapter = SectionedAdapter(AdminActionRows()) { presenter.onSectionToggled(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        presenter = holder.getOrCreate { TrailPresenter(AdminActionRepository()) }
        binding.periodGroup.setOnCheckedStateChangeListener { _, ids ->
            when (ids.firstOrNull()) {
                R.id.chipToday -> Period.TODAY
                R.id.chipWeek -> Period.WEEK
                R.id.chipMonth -> Period.MONTH
                R.id.chipAllTime -> Period.ALL
                else -> null
            }?.let(presenter::onPeriodSelected)
        }
        presenter.attachView(this)
    }

    override fun onDestroy() {
        presenter.detachView()
        super.onDestroy()
    }

    override fun showLoading() {
        binding.skeleton.root.skeleton(true)
        binding.empty.root.visible(false)
        adapter.submitList(emptyList())
    }

    override fun showActions(items: List<SectionItem<AdminAction>>) {
        binding.skeleton.root.skeleton(false)
        binding.empty.root.visible(false)
        adapter.submitList(items)
    }

    override fun showEmpty() {
        binding.skeleton.root.skeleton(false)
        adapter.submitList(emptyList())
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(R.drawable.ic_shield)
            tvEmptyTitle.setText(R.string.trail_empty_title)
            tvEmptyBody.setText(R.string.trail_empty_body)
            btnEmptyAction.visible(false)
        }
    }

    override fun showLoadError(message: String) {
        binding.skeleton.root.skeleton(false)
        with(binding.empty) {
            root.visible(true)
            ivEmpty.setImageResource(R.drawable.ic_alert)
            tvEmptyTitle.setText(R.string.load_failed_title)
            tvEmptyBody.text = message
            btnEmptyAction.visible(false)
        }
    }

    override fun showPeriod(period: Period) {
        binding.periodGroup.check(
            when (period) {
                Period.TODAY -> R.id.chipToday
                Period.WEEK -> R.id.chipWeek
                Period.MONTH -> R.id.chipMonth
                Period.ALL -> R.id.chipAllTime
            }
        )
    }
}
