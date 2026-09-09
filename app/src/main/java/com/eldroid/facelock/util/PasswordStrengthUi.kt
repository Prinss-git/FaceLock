package com.eldroid.facelock.util

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.eldroid.facelock.R
import com.eldroid.facelock.databinding.ViewPasswordStrengthBinding

/**
 * Paints a [PasswordCheck] onto the shared strength view: a coloured bar, a
 * one-word verdict and a tick/dot checklist that updates on every keystroke.
 */
fun ViewPasswordStrengthBinding.render(check: PasswordCheck, password: String) {
    val ctx = root.context

    root.visible(password.isNotEmpty())
    if (password.isEmpty()) return

    val strengthColor = ContextCompat.getColor(ctx, check.strength.colorRes)
    barStrength.setIndicatorColor(strengthColor)
    barStrength.setProgressCompat(check.progress, true)

    tvStrengthLabel.text = check.strength.label
    tvStrengthLabel.setTextColor(strengthColor)

    val met = ContextCompat.getColor(ctx, R.color.granted)
    val pending = ContextCompat.getColor(ctx, R.color.text_tertiary)

    val text = SpannableStringBuilder()
    check.rules.forEachIndexed { index, rule ->
        if (index > 0) text.append("\n")
        val start = text.length
        text.append(if (rule.satisfied) "✓  " else "○  ").append(rule.label)
        text.setSpan(
            ForegroundColorSpan(if (rule.satisfied) met else pending),
            start,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    tvRequirements.text = text
}
