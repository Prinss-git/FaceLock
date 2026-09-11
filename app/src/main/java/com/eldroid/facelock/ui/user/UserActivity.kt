package com.eldroid.facelock.ui.user

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.eldroid.facelock.R
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityUserBinding
import com.eldroid.facelock.ui.auth.LoginActivity
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.tintIcons

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

        setSupportActionBar(binding.toolbar)

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
        supportActionBar?.title = when (itemId) {
            R.id.nav_history -> getString(R.string.nav_history)
            R.id.nav_profile -> getString(R.string.action_profile)
            else -> getString(R.string.nav_my_locker)
        }
        supportActionBar?.subtitle =
            if (itemId == R.id.nav_locker) session.fullName.ifBlank { null } else null

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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_member_toolbar, menu)
        menu.tintIcons(getColor(R.color.text_primary))
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_logout) {
            AlertDialog.Builder(this)
                .setTitle(R.string.action_sign_out)
                .setMessage("You'll need your password to sign back in.")
                .setPositiveButton(R.string.action_sign_out) { _, _ ->
                    AuthRepository().logout()
                    session.clear()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finishAffinity()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {
        const val STATE_TAB = "selected_tab"
    }
}
