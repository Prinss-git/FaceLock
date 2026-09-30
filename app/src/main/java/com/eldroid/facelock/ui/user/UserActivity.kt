package com.eldroid.facelock.ui.user

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.forEach
import androidx.fragment.app.Fragment
import com.eldroid.facelock.R
import com.eldroid.facelock.databinding.ActivityUserBinding
import com.eldroid.facelock.util.SessionManager

/**
 * Shell for the member side: My Locker, History and Profile as bottom-nav tabs.
 *
 * Each tab is created once and then shown or hidden, so switching tabs keeps
 * scroll position and does not restart the Firestore listeners underneath.
 */
class UserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserBinding
    private lateinit var session: SessionManager

    private val tabs = mutableMapOf<Int, Fragment>()
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    /** Called by MyLockerFragment's "View all" shortcut. */
    fun openHistoryTab() {
        binding.bottomNav.selectedItemId = R.id.nav_history
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
