package com.eldroid.facelock.ui.user

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.FragmentMyLockerBinding
import com.eldroid.facelock.ui.adapter.LogAdapter
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The member's home tab: locker state, face enrollment state, and a short
 * preview of their own recent access.
 */
class MyLockerFragment : Fragment() {

    private var _binding: FragmentMyLockerBinding? = null
    private val binding get() = _binding!!

    private val authRepo = AuthRepository()
    private val userRepo = UserRepository()
    private val lockerRepo = LockerRepository()
    private val logRepo = LogRepository()

    private lateinit var recentAdapter: LogAdapter

    /** Cancelled and restarted whenever the assigned locker changes. */
    private var lockerJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyLockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recentAdapter = LogAdapter()
        binding.recyclerRecent.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecent.adapter = recentAdapter
        binding.recyclerRecent.isNestedScrollingEnabled = false

        binding.btnEnroll.setOnClickListener {
            startActivity(Intent(requireContext(), FaceEnrollActivity::class.java))
        }
        binding.btnViewAll.setOnClickListener {
            (activity as? UserActivity)?.openHistoryTab()
        }

        binding.tvGreeting.text = greeting()
        binding.skeleton.root.skeleton(true)

        observeProfile()
        observeRecent()
    }

    private fun greeting(): String {
        val name = SessionManager(requireContext()).fullName.split(" ").firstOrNull().orEmpty()
        val part = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        return if (name.isBlank()) part else "$part, $name"
    }

    private fun observeProfile() {
        val uid = authRepo.currentUid ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            userRepo.observeUser(uid)
                .filterNotNull()
                .catchFirestore("your profile") {
                    binding.skeleton.root.skeleton(false)
                    snack(it)
                }
                .collect { render(it) }
        }
    }

    private fun render(user: User) {
        // First real data: the placeholder has done its job.
        binding.skeleton.root.skeleton(false)
        SessionManager(requireContext()).lockerId = user.lockerId
        renderFace(user.faceEnrolled)

        val lockerId = user.lockerId
        if (lockerId.isNullOrBlank()) {
            lockerJob?.cancel()
            binding.tvLockerId.text = "—"
            binding.tvLockerStatusPill.text = getString(R.string.no_locker_assigned)
            binding.tvLockerStatusPill.setBackgroundResource(R.drawable.pill_neutral)
            binding.tvLockerStatusPill.setTextColor(color(R.color.text_secondary))
            binding.tvLockerStatus.text = getString(R.string.no_locker_body)
            binding.tvGreetingSub.text = getString(R.string.no_locker_body)
            binding.rowLocation.visible(false)
            binding.rowLastOpened.visible(false)
        } else {
            observeLocker(lockerId)
        }
    }

    private fun renderFace(enrolled: Boolean) {
        binding.tvFaceStatus.text =
            getString(if (enrolled) R.string.enrolled else R.string.not_enrolled)
        binding.tvFaceStatus.setBackgroundResource(
            if (enrolled) R.drawable.pill_granted else R.drawable.pill_warning
        )
        binding.tvFaceStatus.setTextColor(color(if (enrolled) R.color.granted else R.color.warning))
        binding.tvFaceSub.text =
            getString(if (enrolled) R.string.face_on_file else R.string.face_pending)
        binding.btnEnroll.text =
            getString(if (enrolled) R.string.reenroll_my_face else R.string.enroll_my_face)

        // An un-enrolled account cannot open anything yet, so make it the loud state.
        binding.faceIconWrap.setBackgroundResource(
            if (enrolled) R.drawable.bg_icon_circle else R.drawable.pill_warning
        )
        binding.ivFace.setColorFilter(
            color(if (enrolled) R.color.text_secondary else R.color.warning)
        )
    }

    private fun observeLocker(lockerId: String) {
        lockerJob?.cancel()
        lockerJob = viewLifecycleOwner.lifecycleScope.launch {
            lockerRepo.observeLocker(lockerId)
                .catchFirestore("your locker") { snack(it) }
                .collect { locker ->
                    if (locker == null) {
                        binding.tvLockerId.text = lockerId
                        binding.tvLockerStatus.text = "Locker record not found."
                        binding.rowLocation.visible(false)
                        binding.rowLastOpened.visible(false)
                        return@collect
                    }

                    binding.tvLockerId.text = locker.label.ifBlank { locker.id }
                    binding.tvLockerStatus.text = when (locker.status) {
                        Locker.STATUS_OCCUPIED -> "Assigned to you"
                        Locker.STATUS_LOCKED -> "Secured"
                        Locker.STATUS_OFFLINE -> "Unit offline — contact your administrator"
                        else -> locker.status
                    }

                    val offline = locker.status == Locker.STATUS_OFFLINE
                    binding.tvLockerStatusPill.text =
                        if (offline) "Offline" else locker.status.lowercase().replaceFirstChar {
                            it.uppercase()
                        }
                    binding.tvLockerStatusPill.setBackgroundResource(
                        if (offline) R.drawable.pill_denied else R.drawable.pill_accent
                    )
                    binding.tvLockerStatusPill.setTextColor(
                        color(if (offline) R.color.denied else R.color.accent_dark)
                    )
                    binding.tvGreetingSub.text =
                        if (offline) "Your locker unit is offline."
                        else "Your locker is secured and ready."

                    binding.rowLocation.visible(locker.location.isNotBlank())
                    binding.tvLockerLocation.text = locker.location

                    locker.lastOpenedAt?.let {
                        binding.rowLastOpened.visible(true)
                        binding.tvLastOpened.text = "Last opened ${it.asRelativeDateTime()}"
                    } ?: binding.rowLastOpened.visible(false)
                }
        }
    }

    /** Only the three most recent entries — the History tab has the rest. */
    private fun observeRecent() {
        val uid = authRepo.currentUid ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            logRepo.observeLogsForUser(uid)
                .catchFirestore("your access history") { snack(it) }
                .collect { logs ->
                    recentAdapter.submitList(logs.take(3))
                    binding.tvNoHistory.visible(logs.isEmpty())
                    binding.recyclerRecent.visible(logs.isNotEmpty())
                    binding.btnViewAll.visible(logs.isNotEmpty())
                }
        }
    }

    private fun color(id: Int) = requireContext().getColor(id)

    override fun onDestroyView() {
        super.onDestroyView()
        lockerJob?.cancel()
        _binding = null
    }
}
