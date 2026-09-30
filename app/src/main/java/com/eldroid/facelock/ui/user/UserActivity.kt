package com.eldroid.facelock.ui.user

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.forEach
import androidx.fragment.app.Fragment
import com.eldroid.facelock.R
import com.eldroid.facelock.databinding.ActivityUserBinding
import com.eldroid.facelock.presenter.coordinator.AppUserTabsCoordinator
import com.eldroid.facelock.presenter.coordinator.UserCoordinatorHost
import com.eldroid.facelock.presenter.coordinator.UserTabsCoordinator
import com.eldroid.facelock.ui.SessionGuard
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.bindOfflineBanner
import com.google.android.material.snackbar.Snackbar

/**
 * Shell for the member side: My Locker, History and Profile as bottom-nav tabs.
 *
 * Each tab is created once and then shown or hidden, so switching tabs keeps
 * scroll position and does not restart the Firestore listeners underneath.
 */
class UserActivity : AppCompatActivity(), UserCoordinatorHost {

    private lateinit var binding: ActivityUserBinding
    private lateinit var session: SessionManager

    private val tabs = mutableMapOf<Int, Fragment>()
    private var currentTab = 0
    private lateinit var guard: SessionGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)
        guard = SessionGuard(this).also { it.attach() }
        bindOfflineBanner(binding.offline.root)
        if (savedInstanceState == null && intent.getBooleanExtra(SessionGuard.EXTRA_ROLE_CHANGED, false)) {
            Snackbar.make(binding.root, R.string.role_changed_message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.bottomNav).show()
        }

        // After process death the FragmentManager restores the tab fragments on
        // its own; re-adopt them, or show() would add a second copy on top.
        binding.bottomNav.menu.forEach { item ->
            supportFragmentManager.findFragmentByTag(item.itemId.toString())
                ?.let { tabs[item.itemId] = it }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        binding.bottomNav.selectedItemId =
            savedInstanceState?.getInt(STATE_TAB)?.takeIf { it != 0 } ?: R.id.nav_locker
    }

    override fun onDestroy() {
        guard.detach()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    /** Lesson 1.5: My Locker's "View all" reaches the History tab through this. */
    override val userCoordinator: UserTabsCoordinator by lazy {
        AppUserTabsCoordinator(openHistoryTab = { binding.bottomNav.selectedItemId = R.id.nav_history })
    }

    private fun show(itemId: Int) {
        currentTab = itemId

        val tx = supportFragmentManager.beginTransaction()
        tabs.values.forEach { tx.hide(it) }

        val existing = tabs[itemId]
        if (existing == null) {
            val fragment = when (itemId) {
                R.id.nav_history -> MyHistoryFragment()
                R.id.nav_profile -> ProfileFragment()
                else -> MyLockerFragment()
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
