package com.eldroid.facelock.ui.auth

import android.os.Bundle
import android.os.CountDownTimer
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityForgotPasswordBinding
import com.eldroid.facelock.util.authMessage
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.validateEmail
import com.eldroid.facelock.util.visible
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.launch

/**
 * Sends a Firebase password reset link.
 *
 * Deliberately does not tell the caller whether the address is registered: an
 * unknown email produces the same "check your email" screen as a known one, so
 * the form cannot be used to enumerate accounts. Genuine failures (offline,
 * rate limited) are still reported.
 */
class ForgotPasswordActivity : AppCompatActivity() {

    companion object {
        /** Optional email to prefill, passed from the sign-in screen. */
        const val EXTRA_EMAIL = "extra_email"
        private const val RESEND_COOLDOWN_MS = 60_000L
    }

    private lateinit var binding: ActivityForgotPasswordBinding
    private val authRepo = AuthRepository()
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
            authRepo.sendPasswordReset(email)
                .onSuccess { showSentState(email) }
                .onFailure { failure ->
                    setLoading(false)
                    // Unknown address: behave exactly as if it had worked.
                    if (failure is FirebaseAuthInvalidUserException) {
                        showSentState(email)
                    } else {
                        toast(failure.authMessage("Could not send the reset email."))
                    }
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
        binding.tvSentTo.text =
            "If an account exists for $email, a password reset link is on its way."
        startResendCooldown()
    }

    /** Firebase rate-limits reset emails; a visible countdown beats a silent failure. */
    private fun startResendCooldown() {
        cooldown?.cancel()
        binding.btnResend.isEnabled = false
        cooldown = object : CountDownTimer(RESEND_COOLDOWN_MS, 1_000L) {
            override fun onTick(remaining: Long) {
                binding.btnResend.text = "Resend link in ${remaining / 1000}s"
            }

            override fun onFinish() {
                binding.btnResend.isEnabled = true
                binding.btnResend.text = "Resend link"
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visible(loading)
        binding.btnSend.isEnabled = !loading
        binding.btnSend.text = if (loading) "Sending…" else "Send reset link"
    }
}
