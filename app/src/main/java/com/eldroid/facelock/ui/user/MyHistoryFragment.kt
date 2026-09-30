package com.eldroid.facelock.ui.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.databinding.FragmentMyHistoryBinding
import com.eldroid.facelock.domain.usecase.ResolveLogLockersUseCase
import com.eldroid.facelock.presenter.common.CollapseState
import com.eldroid.facelock.presenter.common.Group
import com.eldroid.facelock.presenter.common.groupByDay
import com.eldroid.facelock.ui.adapter.LogRows
import com.eldroid.facelock.ui.adapter.SectionedAdapter
import com.eldroid.facelock.util.visible
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.skeleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The member's own access history.
 *
 * One Firestore query feeds all three filters — the result set is capped at 100
 * rows, so filtering in memory is cheaper than a query round trip per chip.
 */
class MyHistoryFragment : Fragment() {

    private var _binding: FragmentMyHistoryBinding? = null
    private val binding get() = _binding!!

    private val authRepo = AuthRepository()
    private val logRepo = LogRepository()
    private val lockerRepo = LockerRepository()
    private lateinit var adapter: SectionedAdapter<AccessLog, LogRows.VH>

    private var all: List<AccessLog> = emptyList()

    /** The newest day starts open, older days closed, until tapped. */
    private val sections = CollapseState { index -> index == 0 }
    private var days: List<Group<AccessLog>> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sections.restore(
            savedInstanceState?.getStringArrayList(KEY_OPEN),
            savedInstanceState?.getStringArrayList(KEY_CLOSED)
        )
        adapter = SectionedAdapter(LogRows()) { key ->
            sections.toggle(key, days)
            applyFilter()
        }
        binding.skeleton.root.skeleton(true)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.tvHeading.setText(R.string.nav_history)
        binding.tvHeadingSub.setText(R.string.heading_sub_history)

        binding.chipGroup.setOnCheckedStateChangeListener { _, _ -> applyFilter() }

        observe()
    }

    private fun observe() {
        val uid = authRepo.currentUid ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // Lockers too, so logs show today's locker ID rather than an old one.
            combine(
                logRepo.observeLogsForUser(uid),
                lockerRepo.observeLockers(),
                ResolveLogLockersUseCase()::invoke
            )
                .catchFirestore("your access history") { showLoadError(it) }
                .collect { logs ->
                    all = logs
                    binding.skeleton.root.skeleton(false)

                    val granted = logs.count { it.granted }
                    val denied = logs.size - granted

                    binding.tvStatTotal.text = logs.size.toString()
                    binding.tvStatGranted.text = granted.toString()
                    binding.tvStatDenied.text = denied.toString()

                    binding.tvPctTotal.setText(R.string.stat_all_time)
                    binding.tvPctGranted.text = share(granted, logs.size)
                    binding.tvPctDenied.text = share(denied, logs.size)

                    binding.chipAll.text = getString(R.string.filter_all_n, logs.size)
                    binding.chipGranted.text = getString(R.string.filter_granted_n, granted)
                    binding.chipFailed.text = getString(R.string.filter_denied_n, denied)

                    applyFilter()
                }
        }
    }

    private fun share(part: Int, total: Int): String =
        if (total == 0) "—" else "%.1f%%".format(part * 100.0 / total)

    private fun applyFilter() {
        val binding = _binding ?: return
        val granted = binding.chipGranted.isChecked
        val denied = binding.chipFailed.isChecked

        val shown = when {
            granted -> all.filter { it.granted }
            denied -> all.filter { !it.granted }
            else -> all
        }
        days = groupByDay(shown) { it.timestamp }
        adapter.submitList(sections.flatten(days) { it.id })

        binding.empty.root.visible(shown.isEmpty())
        binding.empty.ivEmpty.setImageResource(
            if (denied) R.drawable.ic_shield else R.drawable.ic_history
        )
        binding.empty.tvEmptyTitle.text = getString(
            when {
                granted -> R.string.empty_granted_title
                denied -> R.string.empty_denied_title
                else -> R.string.empty_my_history_title
            }
        )
        binding.empty.tvEmptyBody.text = getString(
            when {
                granted -> R.string.empty_granted_body
                denied -> R.string.empty_denied_body
                else -> R.string.empty_my_history_body
            }
        )
    }

    /**
     * Terminal state: the listener failed and will not emit again, so say why
     * instead of leaving a spinner or an "all clear" empty list on screen.
     */
    private fun showLoadError(message: String) {
        val binding = _binding ?: return
        binding.skeleton.root.skeleton(false)
        binding.empty.root.visible(true)
        binding.empty.ivEmpty.setImageResource(R.drawable.ic_alert)
        binding.empty.tvEmptyTitle.text = getString(R.string.load_failed_title)
        binding.empty.tvEmptyBody.text = message
        binding.empty.btnEmptyAction.visible(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_OPEN, sections.openKeys())
        outState.putStringArrayList(KEY_CLOSED, sections.closedKeys())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val KEY_OPEN = "open_days"
        const val KEY_CLOSED = "closed_days"
    }
}
