package com.eldroid.facelock.presenter.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A flat list of collapsible sections: a header per group, then its rows
 * while it is open. Collapsing is just a shorter list, so DiffUtil animates it
 * — the same approach as the Lockers tab's buildings.
 */
sealed interface SectionItem<out T> {
    val key: String

    data class Header(
        val groupKey: String,
        val title: String,
        val count: Int,
        val expanded: Boolean
    ) : SectionItem<Nothing> {
        override val key get() = "h:$groupKey"
    }

    data class Entry<T>(val id: String, val value: T) : SectionItem<T> {
        override val key get() = "e:$id"
    }
}

data class Group<T>(val key: String, val title: String, val items: List<T>)

/**
 * Which sections are open. Sections follow [openByDefault] until the person
 * taps them; after that their choice sticks. Kept by the presenter (or the
 * screen), so it survives new data arriving.
 */
class CollapseState(private val openByDefault: (index: Int) -> Boolean = { true }) {

    private val overrides = mutableMapOf<String, Boolean>()

    fun isOpen(key: String, index: Int): Boolean = overrides[key] ?: openByDefault(index)

    fun toggle(key: String, index: Int) {
        overrides[key] = !isOpen(key, index)
    }

    /** For saving across rotation on screens without a presenter. */
    fun openKeys(): ArrayList<String> = ArrayList(overrides.filterValues { it }.keys)
    fun closedKeys(): ArrayList<String> = ArrayList(overrides.filterValues { !it }.keys)

    fun restore(open: List<String>?, closed: List<String>?) {
        open?.forEach { overrides[it] = true }
        closed?.forEach { overrides[it] = false }
    }

    /**
     * Headers and rows for [groups]. [forceOpen] shows every row (while
     * searching or filtering, so a match is never hidden in a closed section).
     */
    fun <T> flatten(
        groups: List<Group<T>>,
        forceOpen: Boolean = false,
        idOf: (T) -> String
    ): List<SectionItem<T>> = buildList {
        groups.forEachIndexed { index, group ->
            val open = forceOpen || isOpen(group.key, index)
            add(SectionItem.Header(group.key, group.title, group.items.size, open))
            if (open) group.items.forEach { add(SectionItem.Entry(idOf(it), it)) }
        }
    }

    /** Toggles by key, finding the section's position in [groups]. */
    fun <T> toggle(key: String, groups: List<Group<T>>) {
        val index = groups.indexOfFirst { it.key == key }
        if (index >= 0) toggle(key, index)
    }
}

/** Newest-first items into "Today", "Yesterday", "Mon, Sep 28", … sections. */
fun <T> groupByDay(items: List<T>, timeOf: (T) -> Long): List<Group<T>> {
    val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = keyFmt.format(Date())
    val yesterday = keyFmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val sameYearFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    val otherYearFmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())

    return items.groupBy { keyFmt.format(Date(timeOf(it))) }
        .entries
        .sortedByDescending { it.key }
        .map { (key, dayItems) ->
            val date = Date(timeOf(dayItems.first()))
            val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
            val title = when (key) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> (if (year == thisYear) sameYearFmt else otherYearFmt).format(date)
            }
            Group(key, title, dayItems)
        }
}
