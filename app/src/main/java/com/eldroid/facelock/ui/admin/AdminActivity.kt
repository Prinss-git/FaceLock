package com.eldroid.facelock.ui.admin

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.forEach
import androidx.fragment.app.Fragment
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.AccessLog
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.repo.LogRepository
import com.eldroid.facelock.databinding.ActivityAdminBinding
import com.eldroid.facelock.presenter.alerts.DeniedAlertsContract
import com.eldroid.facelock.presenter.alerts.DeniedAlertsPresenter
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.presenter.coordinator.AdminFragmentCoordinator
import com.eldroid.facelock.presenter.coordinator.AppAdminCoordinator
import com.eldroid.facelock.presenter.coordinator.CoordinatorHost
import com.eldroid.facelock.ui.user.ProfileFragment
import com.eldroid.facelock.util.SessionManager
import com.google.android.material.snackbar.Snackbar

/**
 * Admin and security console.
 *
 * Same shape as the member shell: no app bar, bottom-nav tabs, and each tab
 * carrying its own heading in the content. Profile is a tab here too, so Sign
 * out lives in exactly one place for every role.
 *
 * Tabs are kept alive and swapped with show/hide rather than replaced, so
 * moving between them does not drop scroll position or restart the Firestore
 * listeners each fragment holds.
 */
class AdminActivity : AppCompatActivity(), CoordinatorHost, DeniedAlertsContract.View {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var session: SessionManager

    private val tabs = mutableMapOf<Int, Fragment>()
    private var currentTab = 0

    /** Lesson 1.5: the tabs talk to each other only through this. */
    override val coordinator: AdminFragmentCoordinator by lazy {
        AppAdminCoordinator(openLogsTab = { binding.bottomNav.selectedItemId = R.id.nav_logs })
    }

    private val holder: PresenterHolder by viewModels()
    private lateinit var alerts: DeniedAlertsContract.Presenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

        alerts = holder.getOrCreate {
            DeniedAlertsPresenter(
                logRepo = LogRepository(),
                readSeenAt = { session.deniedSeenAt },
                writeSeenAt = { session.deniedSeenAt = it }
            )
        }
        alerts.attachView(this)

        // After process death the FragmentManager restores the tab fragments on
        // its own; re-adopt them, or show() would add a second copy on top.
        binding.bottomNav.menu.forEach { item ->
            supportFragmentManager.findFragmentByTag(item.itemId.toString())
                ?.let { tabs[item.itemId] = it }
        }

        // Security role is read-only: it sees logs and lockers, not user admin.
        val security = session.role == Role.SECURITY
        if (security) {
            binding.bottomNav.menu.findItem(R.id.nav_users).isVisible = false
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = savedInstanceState?.getInt(STATE_TAB)?.takeIf { it != 0 }
            ?: if (security) R.id.nav_logs else R.id.nav_lockers
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onDestroy() {
        alerts.detachView()
        super.onDestroy()
    }

    // ------------------------------------------------------------ alerts ----

    override fun showUnseenCount(count: Int) {
        // Arriving while Logs is on screen: it is being seen right now.
        if (count > 0 && currentTab == R.id.nav_logs) {
            alerts.onLogsOpened()
            return
        }
        if (count > 0) {
            binding.bottomNav.getOrCreateBadge(R.id.nav_logs).apply {
                number = count
                isVisible = true
            }
        } else {
            binding.bottomNav.removeBadge(R.id.nav_logs)
        }
    }

    override fun announceDenied(log: AccessLog) {
        if (currentTab == R.id.nav_logs) return
        Snackbar.make(
            binding.root,
            getString(R.string.alert_denied_at, log.lockerId),
            Snackbar.LENGTH_LONG
        )
            .setAnchorView(binding.bottomNav)
            .setAction(R.string.action_view) { coordinator.showDeniedLogs() }
            .show()
    }

    private fun show(itemId: Int) {
        currentTab = itemId
        if (itemId == R.id.nav_logs) alerts.onLogsOpened()

        val tx = supportFragmentManager.beginTransaction()
        tabs.values.forEach { tx.hide(it) }

        val existing = tabs[itemId]
        if (existing == null) {
            val fragment = when (itemId) {
                R.id.nav_users -> UserListFragment()
                R.id.nav_logs -> AccessLogFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> LockerListFragment()
            }
            tabs[itemId] = fragment
            tx.add(R.id.container, fragment, itemId.toString())
        } else {
            tx.show(existing)
        }
        tx.commit()
    }

    private companion object {
        const val STATE_TAB = "selected_tab"
    }
}
