package com.eldroid.facelock.ui.auth

import android.os.Bundle
import android.os.CountDownTimer
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.R
import com.eldroid.facelock.data.repo.PasswordResetRepository
import com.eldroid.facelock.databinding.ActivityForgotPasswordBinding
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.validateEmail
import com.eldroid.facelock.util.visible
import kotlinx.coroutines.launch

/**
 * Raises a password reset request for an administrator to approve.
 *
 * There is no email in this flow. Firebase's client SDK cannot set a password
 * for a user who is not signed in, so the reset happens in a Cloud Function
 * and an admin hands the temporary password over in person — which suits a
 * locker system whose administrator is already on site.
 *
 * Deliberately does not tell the caller whether the address is registered: an
 * unknown email produces the same confirmation as a known one, so the form
 * cannot be used to enumerate accounts.
 */
class ForgotPasswordActivity : AppCompatActivity() {

    companion object {
        /** Optional email to prefill, passed from the sign-in screen. */
        const val EXTRA_EMAIL = "extra_email"
        private const val RESEND_COOLDOWN_MS = 60_000L
    }

    private lateinit var binding: ActivityForgotPasswordBinding
    private val resetRepo = PasswordResetRepository()
    private var cooldown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        intent.getStringExtra(EXTRA_EMAIL)?.takeIf { it.isNotBlank() }?.let {
            binding.etEmail.setText(it)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSend.setOnClickListener { attemptSend() }
        binding.btnBackToLogin.setOnClickListener { finish() }
        binding.btnResend.setOnClickListener { attemptSend() }

        binding.etEmail.doAfterTextChanged { binding.tilEmail.error = null }
        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptSend()
                true
            } else false
        }
    }

    override fun onDestroy() {
        cooldown?.cancel()
        super.onDestroy()
    }

    private fun attemptSend() {
        val email = binding.etEmail.text.toString().trim()

        val error = validateEmail(email)
        if (error != null) {
            binding.tilEmail.error = error
            showRequestState()
            return
        }
        binding.tilEmail.error = null

        setLoading(true)
        lifecycleScope.launch {
            resetRepo.requestReset(email)
                .onSuccess { showSentState(email) }
                .onFailure { failure ->
                    setLoading(false)
                    // The backend swallows "no such account" itself, so anything
                    // that reaches here is a real transport or deploy problem.
                    toast(failure.message ?: getString(R.string.reset_request_failed))
                }
        }
    }

    private fun showRequestState() {
        binding.groupRequest.visible(true)
        binding.groupSent.visible(false)
    }

    private fun showSentState(email: String) {
        setLoading(false)
        binding.groupRequest.visible(false)
        binding.groupSent.visible(true)
        binding.tvSentTo.text = getString(R.string.reset_requested_body, email)
        startResendCooldown()
    }

    /** Stops repeated taps piling identical requests onto the admin's queue. */
    private fun startResendCooldown() {
        cooldown?.cancel()
        binding.btnResend.isEnabled = false
        cooldown = object : CountDownTimer(RESEND_COOLDOWN_MS, 1_000L) {
            override fun onTick(remaining: Long) {
                binding.btnResend.text =
                    getString(R.string.reset_resend_in, remaining / 1000)
            }

            override fun onFinish() {
                binding.btnResend.isEnabled = true
                binding.btnResend.setText(R.string.reset_resend)
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visible(loading)
        binding.btnSend.isEnabled = !loading
        binding.btnSend.setText(
            if (loading) R.string.reset_sending else R.string.request_reset
        )
    }
}
