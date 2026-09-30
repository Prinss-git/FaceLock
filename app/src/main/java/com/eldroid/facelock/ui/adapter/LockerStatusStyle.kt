package com.eldroid.facelock.ui.adapter

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.eldroid.facelock.R
import com.eldroid.facelock.data.model.Locker

/** Pill, colour and icon for a locker status, shared by the list and the detail sheet. */
data class LockerStatusStyle(
    @DrawableRes val pill: Int,
    @ColorRes val tint: Int,
    @DrawableRes val icon: Int
) {
    companion object {
        fun of(status: String) = when (status) {
            Locker.STATUS_OCCUPIED ->
                LockerStatusStyle(R.drawable.pill_accent, R.color.accent_dark, R.drawable.ic_lock)
            Locker.STATUS_OFFLINE ->
                LockerStatusStyle(R.drawable.pill_denied, R.color.denied, R.drawable.ic_wifi_off)
            Locker.STATUS_LOCKED ->
                LockerStatusStyle(R.drawable.pill_neutral, R.color.text_secondary, R.drawable.ic_lock)
            Locker.STATUS_OUT_OF_SERVICE ->
                LockerStatusStyle(R.drawable.pill_neutral, R.color.text_tertiary, R.drawable.ic_ban)
            else ->
                LockerStatusStyle(R.drawable.pill_granted, R.color.granted, R.drawable.ic_lock_open)
        }

        /** "OUT_OF_SERVICE" → "Out of service". */
        fun label(status: String) =
            status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
