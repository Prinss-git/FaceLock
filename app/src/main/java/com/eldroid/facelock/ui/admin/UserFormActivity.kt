package com.eldroid.facelock.ui.admin

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Role
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityUserFormBinding
import com.eldroid.facelock.util.PasswordPolicy
import com.eldroid.facelock.util.authMessage
import com.eldroid.facelock.util.render
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.validateEmail
import com.eldroid.facelock.util.validateName
import com.eldroid.facelock.util.visible
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Admin-side account creation. Lets an admin provision an account with any
 * role, unlike public self-registration which is always USER.
 *
 * The password rules are exactly the ones the member would face on the sign-up
 * screen — an admin-issued account should not be allowed a weaker password.
 */
class UserFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserFormBinding
    private val authRepo = AuthRepository()

    private val roles = Role.values()
    private var submitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.passwordStrength.root.visible(false)

        setUpRoleDropdown()

        binding.btnGenerate.setOnClickListener { generatePassword() }
        binding.btnSave.setOnClickListener { save() }

        binding.etPassword.doAfterTextChanged {
            renderStrength()
            if (submitted) revalidate()
        }
        listOf(binding.etFirstName, binding.etLastName, binding.etEmail).forEach { field ->
            field.doAfterTextChanged { if (submitted) revalidate() }
        }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
    }

    private fun setUpRoleDropdown() {
        val labels = roles.map {
            when (it) {
                Role.ADMIN -> getString(R.string.role_admin)
                Role.SECURITY -> getString(R.string.role_security)
                Role.USER -> getString(R.string.role_member)
            }
        }
        binding.spinnerRole.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        binding.spinnerRole.setText(labels[roles.indexOf(Role.USER)], false)
        binding.tvRoleHint.setText(R.string.role_hint_user)

        binding.spinnerRole.setOnItemClickListener { _, _, position, _ ->
            // Spell out what the chosen role can actually do — "SECURITY" alone
            // does not tell an admin whether it can unlock a locker.
            binding.tvRoleHint.setText(
                when (roles[position]) {
                    Role.ADMIN -> R.string.role_hint_admin
                    Role.SECURITY -> R.string.role_hint_security
                    Role.USER -> R.string.role_hint_user
                }
            )
        }
    }

    private fun selectedRole(): Role {
        val text = binding.spinnerRole.text.toString()
        return when (text) {
            getString(R.string.role_admin) -> Role.ADMIN
            getString(R.string.role_security) -> Role.SECURITY
            else -> Role.USER
        }
    }

    // --------------------------------------------------------- validation ----

    private val first get() = binding.etFirstName.text.toString().trim()
    private val last get() = binding.etLastName.text.toString().trim()
    private val email get() = binding.etEmail.text.toString().trim()
    private val pass get() = binding.etPassword.text.toString()

    private fun revalidate(): Boolean {
        val errors = linkedMapOf<TextInputLayout, String?>(
            binding.tilFirstName to validateName(first, getString(R.string.first_name)),
            binding.tilLastName to validateName(last, getString(R.string.last_name)),
            binding.tilEmail to validateEmail(email),
            binding.tilPassword to PasswordPolicy.firstError(
                password = pass,
                email = email,
                names = listOf(first, last)
            )
        )
        errors.forEach { (layout, message) ->
            layout.error = message
            layout.isErrorEnabled = message != null
        }
        return errors.values.all { it == null }
    }

    private fun renderStrength() {
        binding.passwordStrength.render(
            PasswordPolicy.check(pass, email, listOf(first, last)),
            pass
        )
    }

    /** Saves the admin from inventing a password that passes the policy. */
    private fun generatePassword() {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#$%^&*?-_"
        val all = upper + lower + digits + symbols
        val rnd = SecureRandom()

        // Guarantee one character of each class, then fill and shuffle so the
        // position of each class is not predictable. Retry on the rare draw
        // that trips the run/sequence rules rather than handing over a
        // password the form would then reject.
        repeat(MAX_GENERATE_ATTEMPTS) {
            val chars = mutableListOf(
                upper[rnd.nextInt(upper.length)],
                lower[rnd.nextInt(lower.length)],
                digits[rnd.nextInt(digits.length)],
                symbols[rnd.nextInt(symbols.length)]
            )
            while (chars.size < GENERATED_LENGTH) chars += all[rnd.nextInt(all.length)]
            chars.shuffle()

            val generated = chars.joinToString("")
            if (PasswordPolicy.firstError(generated, email, listOf(first, last)) == null) {
                binding.etPassword.setText(generated)
                binding.etPassword.setSelection(generated.length)
                renderStrength()
                toast(getString(R.string.password_generated))
                return
            }
        }
        toast(getString(R.string.password_generate_failed))
    }

    // ------------------------------------------------------------- submit ----

    private fun save() {
        submitted = true
        if (!revalidate()) return

        val role = selectedRole()
        setLoading(true)

        lifecycleScope.launch {
            authRepo.register(first, last, email, pass, role)
                .onSuccess {
                    toast("$first $last created")
                    finish()
                }
                .onFailure { error ->
                    setLoading(false)
                    val message = error.authMessage("Could not create the account.")
                    if (message.contains("already exists", ignoreCase = true)) {
                        binding.tilEmail.error = message
                    } else {
                        toast(message)
                    }
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visible(loading)
        binding.btnSave.isEnabled = !loading
        binding.btnSave.text =
            if (loading) getString(R.string.creating) else getString(R.string.create_user)
    }

    private companion object {
        const val GENERATED_LENGTH = 14
        const val MAX_GENERATE_ATTEMPTS = 20
    }
}
