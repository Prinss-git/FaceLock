package com.eldroid.facelock.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.User
import com.eldroid.facelock.data.repo.AuthRepository
import com.eldroid.facelock.databinding.ActivityRegisterBinding
import com.eldroid.facelock.presenter.auth.RegisterContract
import com.eldroid.facelock.presenter.auth.RegisterContract.FieldType
import com.eldroid.facelock.presenter.auth.RegisterForm
import com.eldroid.facelock.presenter.auth.RegisterPresenter
import com.eldroid.facelock.presenter.base.PresenterHolder
import com.eldroid.facelock.ui.user.FaceEnrollActivity
import com.eldroid.facelock.util.PasswordPolicy
import com.eldroid.facelock.util.SessionManager
import com.eldroid.facelock.util.render
import com.eldroid.facelock.util.toast
import com.eldroid.facelock.util.visible
import com.google.android.material.textfield.TextInputLayout

/**
 * Self-registration — the View in MVP. Always creates a plain USER account;
 * admin and security roles are granted only by an existing admin.
 */
class RegisterActivity : AppCompatActivity(), RegisterContract.View {

    private lateinit var binding: ActivityRegisterBinding
    private val holder: PresenterHolder by viewModels()
    private lateinit var presenter: RegisterContract.Presenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        presenter = holder.getOrCreate { RegisterPresenter(AuthRepository()) }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRegister.setOnClickListener { presenter.handleRegisterClick() }

        binding.passwordStrength.root.visible(false)

        // Live feedback: the strength meter always, field errors once submitted.
        binding.etPassword.doAfterTextChanged {
            renderStrength()
            presenter.handleInputChanged(FieldType.PASSWORD)
        }
        mapOf(
            binding.etFirstName to FieldType.FIRST_NAME,
            binding.etLastName to FieldType.LAST_NAME,
            binding.etEmail to FieldType.EMAIL,
            binding.etConfirm to FieldType.CONFIRM
        ).forEach { (field, type) ->
            field.doAfterTextChanged { presenter.handleInputChanged(type) }
        }

        binding.etConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                presenter.handleRegisterClick()
                true
            } else false
        }

        presenter.attachView(this)
    }

    override fun onDestroy() {
        presenter.detachView()
        super.onDestroy()
    }

    // ------------------------------------------------------------ View ----

    override fun getForm() = RegisterForm(
        first = binding.etFirstName.text.toString(),
        last = binding.etLastName.text.toString(),
        email = binding.etEmail.text.toString(),
        password = binding.etPassword.text.toString(),
        confirm = binding.etConfirm.text.toString()
    )

    override fun showValidationErrors(errors: Map<FieldType, String?>) {
        errors.forEach { (field, message) ->
            val layout = layoutOf(field)
            layout.error = message
            layout.isErrorEnabled = message != null
        }
    }

    override fun focusField(field: FieldType) {
        layoutOf(field).editText?.requestFocus()
    }

    override fun showProgress(loading: Boolean) {
        binding.progress.visible(loading)
        binding.btnRegister.isEnabled = !loading
        binding.btnRegister.setText(if (loading) R.string.creating_account else R.string.create_account)
    }

    override fun showMessage(message: String) = toast(message)

    override fun navigateToEnroll(user: User) {
        SessionManager(this).apply {
            role = user.roleEnum
            fullName = user.fullName
        }
        toast(getString(R.string.account_created_enroll))
        startActivity(Intent(this, FaceEnrollActivity::class.java))
        finishAffinity()
    }

    /** The strength meter is pure presentation, so it stays in the View. */
    private fun renderStrength() {
        val form = getForm()
        val check = PasswordPolicy.check(
            password = form.password,
            email = form.email.trim(),
            names = listOf(form.first.trim(), form.last.trim())
        )
        binding.passwordStrength.render(check, form.password)
    }

    private fun layoutOf(field: FieldType): TextInputLayout = when (field) {
        FieldType.FIRST_NAME -> binding.tilFirstName
        FieldType.LAST_NAME -> binding.tilLastName
        FieldType.EMAIL -> binding.tilEmail
        FieldType.PASSWORD -> binding.tilPassword
        FieldType.CONFIRM -> binding.tilConfirm
    }
}
