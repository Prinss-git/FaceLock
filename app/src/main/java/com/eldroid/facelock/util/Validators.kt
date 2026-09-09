package com.eldroid.facelock.util

import androidx.annotation.ColorRes
import com.eldroid.facelock.R

/**
 * Input rules shared by Register, Forgot password and Change password so the
 * three screens can never drift apart on what counts as acceptable.
 *
 * Everything here is pure apart from the colour ids, which keeps it testable.
 */

// ----------------------------------------------------------------- names ----

private val NAME_ALLOWED = Regex("^[\\p{L}][\\p{L} .'\\-]*$")

/**
 * Real-world names: letters plus the few separators that legitimately appear in
 * Filipino and Spanish surnames (space, hyphen, apostrophe, the dot in "Jr.").
 * Digits, symbols and emoji are rejected.
 */
fun validateName(raw: String, label: String): String? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> "$label is required"
        name.length < 2 -> "$label must be at least 2 characters"
        name.length > 40 -> "$label must be 40 characters or fewer"
        !NAME_ALLOWED.matches(name) ->
            "$label may only contain letters, spaces, hyphens and apostrophes"
        !name.any { it.isLetter() } -> "$label must contain letters"
        name.contains("  ") -> "$label has repeated spaces"
        else -> null
    }
}

// ----------------------------------------------------------------- email ----

private val EMAIL_LOCAL = Regex("^[A-Za-z0-9._%+\\-]+$")
private val EMAIL_DOMAIN = Regex("^[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

/**
 * Stricter than [isValidEmail]: the platform pattern accepts shapes such as
 * `a..b@x.co` that Firebase later rejects server-side with an opaque error.
 * Catching them here gives the user a message they can act on.
 */
fun validateEmail(raw: String): String? {
    val email = raw.trim()
    val local = email.substringBefore('@', "")
    val domain = email.substringAfter('@', "")
    return when {
        email.isEmpty() -> "Email address is required"
        email.length > 254 -> "Email address is too long"
        email.any { it.isWhitespace() } -> "Email address cannot contain spaces"
        email.count { it == '@' } != 1 -> "Enter a valid email address"
        local.isEmpty() || local.length > 64 -> "Enter a valid email address"
        !EMAIL_LOCAL.matches(local) -> "Enter a valid email address"
        local.startsWith('.') || local.endsWith('.') || local.contains("..") ->
            "Enter a valid email address"
        !EMAIL_DOMAIN.matches(domain) -> "Enter a valid email domain"
        domain.contains("..") || domain.startsWith('-') -> "Enter a valid email domain"
        !email.isValidEmail() -> "Enter a valid email address"
        else -> null
    }
}

// -------------------------------------------------------------- passwords ----

/** One line of the live checklist under the password field. */
data class PasswordRule(val label: String, val satisfied: Boolean)

enum class PasswordStrength(val label: String, @ColorRes val colorRes: Int) {
    WEAK("Weak", R.color.denied),
    FAIR("Fair", R.color.warning),
    GOOD("Good", R.color.accent_dark),
    STRONG("Strong", R.color.granted)
}

data class PasswordCheck(
    val rules: List<PasswordRule>,
    val strength: PasswordStrength,
    /** 0..100, drives the strength bar. */
    val progress: Int,
    /** First unmet requirement, or null when the password is acceptable. */
    val error: String?
)

object PasswordPolicy {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64
    private const val STRONG_LENGTH = 12

    private const val SPECIALS = "!@#$%^&*()_+-=[]{};':\",.<>/?\\|`~"

    /** Passwords that pass the character rules but are still trivially guessed. */
    private val BLOCKLIST = setOf(
        "password1!", "password@123", "passw0rd!", "qwerty123!", "welcome1!",
        "admin@123", "letmein1!", "abcd1234!", "p@ssw0rd!", "iloveyou1!",
        "facelock1!", "facelock@123", "changeme1!", "test1234!"
    )

    /**
     * Evaluates [password] against every rule.
     *
     * [email] and [names] reject passwords that merely echo the account's own
     * identity — the most common weak choice in practice.
     */
    fun check(
        password: String,
        email: String = "",
        names: List<String> = emptyList()
    ): PasswordCheck {
        val lower = password.lowercase()
        val emailLocal = email.trim().substringBefore('@', "")

        val personal = (names + emailLocal)
            .map { it.trim().lowercase() }
            .filter { it.length >= 3 }

        val rules = listOf(
            PasswordRule("At least $MIN_LENGTH characters", password.length >= MIN_LENGTH),
            PasswordRule("One uppercase letter", password.any { it.isUpperCase() }),
            PasswordRule("One lowercase letter", password.any { it.isLowerCase() }),
            PasswordRule("One number", password.any { it.isDigit() }),
            PasswordRule("One symbol (! @ # $)", password.any { it in SPECIALS }),
            PasswordRule("No spaces", password.isNotEmpty() && password.none { it.isWhitespace() })
        )

        val unmetIndex = rules.indexOfFirst { !it.satisfied }
        val error = when {
            password.isEmpty() -> "Password is required"
            password.length > MAX_LENGTH -> "Password must be $MAX_LENGTH characters or fewer"
            unmetIndex >= 0 -> when (unmetIndex) {
                0 -> "Password must be at least $MIN_LENGTH characters"
                1 -> "Add an uppercase letter"
                2 -> "Add a lowercase letter"
                3 -> "Add a number"
                4 -> "Add a symbol such as ! @ # $"
                else -> "Password cannot contain spaces"
            }
            lower in BLOCKLIST -> "That password is too common. Choose another."
            personal.any { lower.contains(it) } -> "Password cannot contain your name or email"
            hasRun(password, 4) -> "Avoid repeating a character 4 or more times"
            hasSequence(lower, 4) -> "Avoid sequences such as 1234 or abcd"
            else -> null
        }

        return PasswordCheck(
            rules = rules,
            strength = strengthOf(password, rules, error),
            progress = progressOf(password, rules, error),
            error = error
        )
    }

    /** Convenience for callers that only care whether it passes. */
    fun firstError(password: String, email: String = "", names: List<String> = emptyList()): String? =
        check(password, email, names).error

    private fun satisfiedCount(rules: List<PasswordRule>) = rules.count { it.satisfied }

    private fun strengthOf(
        password: String,
        rules: List<PasswordRule>,
        error: String?
    ): PasswordStrength = when {
        error != null ->
            if (satisfiedCount(rules) >= 4) PasswordStrength.FAIR else PasswordStrength.WEAK
        password.length >= STRONG_LENGTH && password.toSet().size >= 8 -> PasswordStrength.STRONG
        else -> PasswordStrength.GOOD
    }

    private fun progressOf(
        password: String,
        rules: List<PasswordRule>,
        error: String?
    ): Int {
        if (password.isEmpty()) return 0
        val base = satisfiedCount(rules) * 100 / rules.size
        return when {
            error != null -> (base * 6 / 10).coerceAtLeast(8)
            password.length >= STRONG_LENGTH && password.toSet().size >= 8 -> 100
            else -> 80
        }
    }

    /** "aaaa" — [min] or more of the same character back to back. */
    private fun hasRun(value: String, min: Int): Boolean {
        var run = 1
        for (i in 1 until value.length) {
            run = if (value[i] == value[i - 1]) run + 1 else 1
            if (run >= min) return true
        }
        return false
    }

    /** "1234" / "dcba" — [min] or more consecutive characters, either direction. */
    private fun hasSequence(value: String, min: Int): Boolean {
        var up = 1
        var down = 1
        for (i in 1 until value.length) {
            val delta = value[i] - value[i - 1]
            up = if (delta == 1) up + 1 else 1
            down = if (delta == -1) down + 1 else 1
            if (up >= min || down >= min) return true
        }
        return false
    }
}
