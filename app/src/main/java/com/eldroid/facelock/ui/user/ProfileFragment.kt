package com.eldroid.facelock.ui.user

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.BuildConfig
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.FragmentProfileBinding
import com.eldroid.facelock.ui.auth.ChangePasswordActivity
import com.eldroid.facelock.ui.auth.LoginActivity
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.asDateTime
import com.eldroid.facelock.util.catchFirestore
import com.eldroid.facelock.util.initials
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.startOfToday
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Account overview, shared by both sides of the app: members open it as a
 * bottom-nav tab, admins and security staff as [ProfileActivity] from the
 * toolbar. Members see their locker and enrollment state; staff get a live
 * system-wide summary instead.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val authRepo = AuthRepository()
    private val userRepo = UserRepository()
    private val lockerRepo = LockerRepository()
    private val logRepo = LogRepository()

    /** render() runs on every snapshot, so guard against stacking listeners. */
    private var statsStarted = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnEnroll.setOnClickListener {
            startActivity(Intent(requireContext(), FaceEnrollActivity::class.java))
        }
        binding.btnChangePassword.setOnClickListener {
            startActivity(Intent(requireContext(), ChangePasswordActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        binding.tvVersion.text = "FaceLock v${BuildConfig.VERSION_NAME}"

        val uid = authRepo.currentUid ?: run { requireActivity().finish(); return }
        viewLifecycleOwner.lifecycleScope.launch {
            userRepo.observeUser(uid)
                .filterNotNull()
                .catchFirestore("your profile") { snack(it) }
                .collect { render(it) }
        }
    }

    private fun render(user: User) {
        val staff = user.roleEnum != Role.USER

        binding.tvInitials.text = user.fullName.initials()
        binding.tvName.text = user.fullName
        binding.tvEmail.text = user.email
        binding.tvRoleBadge.text = when (user.roleEnum) {
            Role.ADMIN -> "Administrator"
            Role.SECURITY -> "Security"
            Role.USER -> "Member"
        }

        // Locker and face template are member concepts. For staff the whole
        // Account section goes, rather than leaving a labelled card wrapped
        // around a single date.
        binding.accountSection.visible(!staff)
        binding.rowLocker.visible(!staff)
        binding.dividerLocker.visible(!staff)
        binding.rowFace.visible(!staff)
        binding.dividerFace.visible(!staff)
        binding.btnEnroll.visible(!staff)

        binding.adminSection.visible(staff)
        if (staff && !statsStarted) {
            statsStarted = true
            observeStats()
        }

        if (!staff) {
            binding.tvLocker.text =
                user.lockerId ?: getString(R.string.no_locker_assigned)

            val enrolled = user.faceEnrolled
            binding.tvFaceStatus.text =
                getString(if (enrolled) R.string.enrolled else R.string.not_enrolled)
            binding.tvFaceStatus.setBackgroundResource(
                if (enrolled) R.drawable.pill_granted else R.drawable.pill_warning
            )
            binding.tvFaceStatus.setTextColor(
                requireContext().getColor(if (enrolled) R.color.granted else R.color.warning)
            )
            binding.tvFaceSub.text =
                getString(if (enrolled) R.string.face_on_file else R.string.face_pending)
            binding.btnEnroll.text =
                getString(if (enrolled) R.string.reenroll_my_face else R.string.enroll_my_face)
        }

        // "Member since" is the wrong noun for an administrator.
        binding.tvJoined.text = user.createdAt.asDateTime()
        binding.tvJoinedHeader.text = getString(
            if (staff) R.string.account_created_on else R.string.member_since_on,
            user.createdAt.asDateTime()
        )
    }

    /** Live system counters shown to admins and security staff. */
    private fun observeStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                userRepo.observeUsers(),
                lockerRepo.observeLockers(),
                logRepo.observeAllLogs()
            ) { users, lockers, logs -> Triple(users, lockers, logs) }
                .catchFirestore("the system summary") { snack(it) }
                .collect { (users, lockers, logs) ->
                    val binding = _binding ?: return@collect
                    binding.tvStatUsers.text = users.size.toString()
                    binding.tvStatLockers.text = lockers.size.toString()
                    binding.tvStatAssigned.text = lockers.count { !it.isAvailable }.toString()

                    val since = startOfToday()
                    binding.tvStatDenied.text = logs.count {
                        it.result == AccessLog.RESULT_DENIED && it.timestamp >= since
                    }.toString()
                }
        }
    }


    private fun confirmLogout() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_sign_out)
            .setMessage("You'll need your password to sign back in.")
            .setPositiveButton(R.string.action_sign_out) { _, _ ->
                authRepo.logout()
                SessionManager(requireContext()).clear()
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                requireActivity().finishAffinity()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
