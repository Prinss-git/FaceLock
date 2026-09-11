package com.eldroid.facelock.util

import android.animation.ValueAnimator
import android.content.Context
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.fragment.app.Fragment
import com.eldroid.facelock.R
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun Context.toast(message: String) =
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

/** Fragment is not a Context, so it needs its own overload. */
fun Fragment.toast(message: String) =
    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

/**
 * Snackbar instead of a toast wherever there is a view to anchor to: it sits
 * inside the app, respects the bottom navigation, and can carry an action.
 */
fun View.snack(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    Snackbar.make(this, message, Snackbar.LENGTH_LONG).apply {
        if (actionLabel != null && action != null) setAction(actionLabel) { action() }
    }.show()
}

fun Fragment.snack(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    view?.snack(message, actionLabel, action) ?: toast(message)
}

fun View.visible(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
}

/**
 * Shows or hides a loading skeleton, breathing gently while it is up.
 *
 * A static grey block reads as broken layout; the pulse is what tells you the
 * screen is working. The animator is held on the view's tag so it can be
 * cancelled — an animator left running on a detached view leaks it.
 */
fun View.skeleton(show: Boolean) {
    visible(show)
    val running = getTag(R.id.tag_skeleton_animator) as? ValueAnimator
    if (!show) {
        running?.cancel()
        setTag(R.id.tag_skeleton_animator, null)
        alpha = 1f
        return
    }
    if (running != null) return
    val animator = ValueAnimator.ofFloat(1f, 0.45f).apply {
        duration = 850
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { alpha = it.animatedValue as Float }
    }
    setTag(R.id.tag_skeleton_animator, animator)
    animator.start()
}

/**
 * Forces every action icon to [color].
 *
 * The toolbars sit on the near-black ink ramp in both themes, while the menu
 * tint defaults to colorOnSurface — which is dark in light mode.
 */
fun Menu.tintIcons(@ColorInt color: Int) {
    for (i in 0 until size()) {
        getItem(i).icon?.mutate()?.setTint(color)
    }
}

private val dateTimeFmt = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dayFmt = SimpleDateFormat("MMM d", Locale.getDefault())

fun Long.asDateTime(): String = dateTimeFmt.format(Date(this))

/**
 * Compact, human timestamp for list rows: "2:14 PM" today, "Yesterday 2:14 PM",
 * "Sep 8, 2:14 PM" further back. Absolute dates stay available via [asDateTime].
 */
fun Long.asRelativeDateTime(): String {
    val then = Calendar.getInstance().apply { timeInMillis = this@asRelativeDateTime }
    val now = Calendar.getInstance()
    val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val dayDelta = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)

    return when {
        sameYear && dayDelta == 0 -> "Today ${timeFmt.format(Date(this))}"
        sameYear && dayDelta == 1 -> "Yesterday ${timeFmt.format(Date(this))}"
        sameYear -> "${dayFmt.format(Date(this))}, ${timeFmt.format(Date(this))}"
        else -> asDateTime()
    }
}

/** Midnight this morning, for "today" counters. */
fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Juan Dela Cruz" -> "JD". Used for avatar placeholders. */
fun String.initials(): String {
    val parts = trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

fun String.isValidEmail(): Boolean =
    android.util.Patterns.EMAIL_ADDRESS.matcher(this.trim()).matches()
