package com.eldroid.facelock.ui.user

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eldroid.facelock.R
import com.eldroid.facelock.databinding.ActivityProfileBinding

/**
 * Host for [ProfileFragment], used by the admin and security console where
 * Profile is reached from the toolbar rather than from a bottom-nav tab.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, ProfileFragment())
                .commit()
        }
    }
}
