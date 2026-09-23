package com.eldroid.facelock.ui.admin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.databinding.FragmentLogsBinding
import com.eldroid.facelock.ui.adapter.LogAdapter
import com.eldroid.facelock.util.startOfToday
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.launch

/**
 * System-wide access feed for admins and security staff.
 *
 * The counters describe *today*, while the list shows the full retained window,
 * so a quiet day still reads as quiet rather than as an empty screen.
 */
class AccessLogFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val logRepo = LogRepository()
    private lateinit var adapter: LogAdapter

    private var all: List<AccessLog> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = LogAdapter()
        binding.skeleton.root.skeleton(true)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.tvHeading.setText(R.string.nav_logs)
        binding.tvHeadingSub.setText(R.string.heading_sub_logs)

        binding.chipGroup.setOnCheckedStateChangeListener { _, _ -> applyFilter() }

        // One listener covers every chip: the filters are a view over the same
        // capped result set, not three separate queries.
        viewLifecycleOwner.lifecycleScope.launch {
            logRepo.observeAllLogs()
                .catchFirestore("the access log") { showLoadError(it) }
                .collect { logs ->
                    all = logs
                    binding.skeleton.root.skeleton(false)

                    val since = startOfToday()
                    val today = logs.filter { it.timestamp >= since }
                    val granted = today.count { it.granted }
                    val denied = today.size - granted

                    binding.tvStatToday.text = today.size.toString()
                    binding.tvStatGranted.text = granted.toString()
                    binding.tvStatDenied.text = denied.toString()

                    binding.tvPctTotal.setText(R.string.stat_total_events)
                    binding.tvPctGranted.text = share(granted, today.size)
                    binding.tvPctDenied.text = share(denied, today.size)

                    // Counts on the chips turn a filter into information.
                    binding.chipAll.text = getString(R.string.filter_all_n, logs.size)
                    binding.chipGranted.text =
                        getString(R.string.filter_granted_n, logs.count { it.granted })
                    binding.chipFailed.text =
                        getString(R.string.filter_denied_n, logs.count { !it.granted })

                    applyFilter()
                }
        }
    }

    /** "85.4%" of the day's traffic, or a dash when there is none yet. */
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
        adapter.submitList(shown)

        binding.empty.root.visible(shown.isEmpty())
        binding.empty.ivEmpty.setImageResource(
            if (denied) R.drawable.ic_shield else R.drawable.ic_history
        )
        binding.empty.tvEmptyTitle.text = getString(
            when {
                granted -> R.string.empty_granted_title
                denied -> R.string.empty_denied_title
                else -> R.string.empty_logs_title
            }
        )
        binding.empty.tvEmptyBody.text = getString(
            when {
                granted -> R.string.empty_granted_body
                denied -> R.string.empty_denied_body
                else -> R.string.empty_logs_body
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
