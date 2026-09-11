package com.eldroid.facelock.ui.admin

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.databinding.ActivityAdminBinding
import com.eldroid.facelock.ui.user.ProfileFragment
import com.eldroid.facelock.util.SessionManager

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
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var session: SessionManager

    private val tabs = mutableMapOf<Int, Fragment>()
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionManager(this)

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

    private fun show(itemId: Int) {
        currentTab = itemId

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
