package com.eldroid.facelock.ui.user

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.model.Locker
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.data.repo.BuildingRepository
import com.eldroid.facelock.data.repo.LockerRepository
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.data.repo.UserRepository
import com.eldroid.facelock.databinding.FragmentMyLockerBinding
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.coordinator.UserCoordinatorHost
import com.eldroid.facelock.presenter.mylocker.LockerCard
import com.eldroid.facelock.presenter.mylocker.MyLockerContract
import com.eldroid.facelock.presenter.mylocker.MyLockerPresenter
import com.eldroid.facelock.ui.adapter.LockerStatusStyle
import com.eldroid.facelock.ui.adapter.LogAdapter
import com.eldroid.facelock.util.asRelativeDateTime
import com.eldroid.facelock.util.skeleton
import com.eldroid.facelock.util.snack
import com.eldroid.facelock.util.visible
import java.util.Calendar

/** The member's home tab — the View in MVP. [MyLockerPresenter] follows their data. */
class MyLockerFragment : Fragment(), MyLockerContract.View {

    private var _binding: FragmentMyLockerBinding? = null
    private val binding get() = _binding!!

    private val holder: PresenterHolder by viewModels()
    private var presenter: MyLockerContract.Presenter? = null
    private lateinit var recentAdapter: LogAdapter

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
        binding.btnViewAll.setOnClickListener { presenter?.onViewAllClicked() }
        binding.tvGreeting.text = greeting(null)
        binding.skeleton.root.skeleton(true)

        val uid = AuthRepository().currentUid ?: return
        presenter = holder.getOrCreate {
            MyLockerPresenter(UserRepository(), LockerRepository(), LogRepository(), BuildingRepository(), uid)
        }.also { it.attachView(this) }
    }

    override fun onDestroyView() {
        presenter?.detachView()
        super.onDestroyView()
        _binding = null
    }

    // ------------------------------------------------------------ View ----

    override fun showProfile(firstName: String, faceEnrolled: Boolean) {
        val binding = _binding ?: return
        // First real data: the placeholder has done its job.
        binding.skeleton.root.skeleton(false)
        binding.tvGreeting.text = greeting(firstName)

        binding.tvFaceStatus.setText(if (faceEnrolled) R.string.enrolled else R.string.not_enrolled)
        binding.tvFaceStatus.setBackgroundResource(
            if (faceEnrolled) R.drawable.pill_granted else R.drawable.pill_warning
        )
        binding.tvFaceStatus.setTextColor(color(if (faceEnrolled) R.color.granted else R.color.warning))
        binding.tvFaceSub.setText(if (faceEnrolled) R.string.face_on_file else R.string.face_pending)
        binding.btnEnroll.setText(if (faceEnrolled) R.string.reenroll_my_face else R.string.enroll_my_face)
        // An un-enrolled account cannot open anything yet, so make it the loud state.
        binding.faceIconWrap.setBackgroundResource(
            if (faceEnrolled) R.drawable.bg_icon_circle else R.drawable.pill_warning
        )
        binding.ivFace.setColorFilter(color(if (faceEnrolled) R.color.text_secondary else R.color.warning))
    }

    override fun showNoLocker() {
        val binding = _binding ?: return
        binding.tvLockerId.text = "—"
        binding.tvLockerStatusPill.setText(R.string.no_locker_assigned)
        binding.tvLockerStatusPill.setBackgroundResource(R.drawable.pill_neutral)
        binding.tvLockerStatusPill.setTextColor(color(R.color.text_secondary))
        binding.tvLockerStatus.setText(R.string.no_locker_body)
        // The hero card already spells this out; the greeting says it short.
        binding.tvGreetingSub.setText(R.string.greeting_no_locker)
        binding.rowLocation.visible(false)
        binding.rowLastOpened.visible(false)
    }

    override fun showLocker(card: LockerCard) {
        val binding = _binding ?: return
        binding.tvLockerId.text = card.id
        val status = card.status
        if (status == null) {
            binding.tvLockerStatus.setText(R.string.locker_record_missing)
            binding.rowLocation.visible(false)
            binding.rowLastOpened.visible(false)
            return
        }

        val style = LockerStatusStyle.of(status)
        binding.tvLockerStatusPill.text = LockerStatusStyle.label(status)
        binding.tvLockerStatusPill.setBackgroundResource(style.pill)
        binding.tvLockerStatusPill.setTextColor(color(style.tint))
        binding.tvLockerStatus.text = when (status) {
            Locker.STATUS_OCCUPIED -> getString(R.string.my_locker_assigned)
            Locker.STATUS_LOCKED -> getString(R.string.my_locker_secured)
            Locker.STATUS_OFFLINE -> getString(R.string.my_locker_offline)
            Locker.STATUS_OUT_OF_SERVICE -> getString(R.string.my_locker_out_of_service)
            else -> LockerStatusStyle.label(status)
        }
        binding.tvGreetingSub.setText(
            when (status) {
                Locker.STATUS_OFFLINE -> R.string.greeting_locker_offline
                Locker.STATUS_OUT_OF_SERVICE -> R.string.greeting_locker_out_of_service
                else -> R.string.greeting_locker_ready
            }
        )
        binding.rowLocation.visible(card.place != null)
        binding.tvLockerLocation.text = card.place
        binding.rowLastOpened.visible(card.lastOpenedAt != null)
        binding.tvLastOpened.text =
            card.lastOpenedAt?.let { getString(R.string.last_opened_at, it.asRelativeDateTime()) }
    }

    override fun showRecent(logs: List<AccessLog>) {
        val binding = _binding ?: return
        recentAdapter.submitList(logs)
        binding.tvNoHistory.visible(logs.isEmpty())
        binding.recyclerRecent.visible(logs.isNotEmpty())
        binding.btnViewAll.visible(logs.isNotEmpty())
    }

    override fun showMessage(message: String) = snack(message)

    /** Lesson 1.5: through the host's coordinator, not a cast to the activity. */
    override fun openHistory() {
        (activity as? UserCoordinatorHost)?.userCoordinator?.openHistory()
    }

    private fun greeting(firstName: String?): String {
        val part = getString(
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 0..11 -> R.string.greeting_morning
                in 12..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        )
        return if (firstName.isNullOrBlank()) part else getString(R.string.greeting_named, part, firstName)
    }

    private fun color(id: Int) = requireContext().getColor(id)
}
