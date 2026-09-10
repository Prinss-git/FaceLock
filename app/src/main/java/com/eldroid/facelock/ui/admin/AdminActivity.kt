package com.eldroid.facelock.ui.admin

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityAdminBinding
import com.eldroid.facelock.ui.auth.LoginActivity
import com.eldroid.facelock.ui.user.ProfileActivity
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.tintIcons

/**
 * Admin and security console.
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

        setSupportActionBar(binding.toolbar)

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
        supportActionBar?.title = when (itemId) {
            R.id.nav_users -> getString(R.string.nav_users)
            R.id.nav_logs -> getString(R.string.nav_logs)
            else -> getString(R.string.nav_lockers)
        }
        supportActionBar?.subtitle =
            if (session.role == Role.SECURITY) "Security console" else "Admin dashboard"

        val tx = supportFragmentManager.beginTransaction()
        tabs.values.forEach { tx.hide(it) }

        val existing = tabs[itemId]
        if (existing == null) {
            val fragment = when (itemId) {
                R.id.nav_users -> UserListFragment()
                R.id.nav_logs -> AccessLogFragment()
                else -> LockerListFragment()
            }
            tabs[itemId] = fragment
            tx.add(R.id.container, fragment, itemId.toString())
        } else {
            tx.show(existing)
        }
        tx.commit()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logout, menu)
        menu.tintIcons(getColor(R.color.white))
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_profile) {
            startActivity(Intent(this, ProfileActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_device_test) {
            startActivity(Intent(this, DeviceTestActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_logout) {
            AlertDialog.Builder(this)
                .setTitle(R.string.action_sign_out)
                .setMessage("You'll need your password to sign back in.")
                .setPositiveButton(R.string.action_sign_out) { _, _ -> logout() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun logout() {
        AuthRepository().logout()
        session.clear()
        startActivity(Intent(this, LoginActivity::class.java))
        finishAffinity()
    }

    private companion object {
        const val STATE_TAB = "selected_tab"
    }
}
