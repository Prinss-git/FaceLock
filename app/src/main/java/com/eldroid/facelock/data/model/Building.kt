package com.eldroid.facelock.data.model

import com.google.firebase.firestore.DocumentId

/**
 * A building that holds lockers. The document ID is the short [code]
 * ("M", "ENG"), which also prefixes its locker IDs: M-001, M-002…
 *
 * Floors are stored as numbers 1..[floors]. [groundFloor] only changes how
 * floor 1 is named: "GF" (then 2F, 3F…) instead of "1F".
 */
data class Building(
    @DocumentId val code: String = "",
    val name: String = "",
    val floors: Int = 1,
    val groundFloor: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** "GF", "2F"… as this building names its floors. */
    fun labelFor(floor: Int): String =
        if (groundFloor && floor == 1) GROUND_LABEL else floorLabel(floor)

    /** "Main Building · 2F" — also stored on each locker as its `location`. */
    fun locationOf(floor: Int): String = "$name · ${labelFor(floor)}"

    companion object {
        /** Plain "3F" naming, for lockers whose building is unknown. */
        fun floorLabel(floor: Int): String = "${floor}F"

        const val GROUND_LABEL = "GF"

        /** 1–3 capital letters. */
        val CODE_PATTERN = Regex("^[A-Z]{1,3}$")

        const val MAX_FLOORS = 50
    }
}
