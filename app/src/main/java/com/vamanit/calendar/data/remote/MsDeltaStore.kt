package com.vamanit.calendar.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Microsoft Graph calendarView delta-link across sessions.
 *
 * The delta-link encodes where the last sync left off. Passing it back
 * to Graph returns ONLY the events that changed since then — far more
 * efficient than a full calendarView fetch every polling cycle.
 *
 * If [daysAhead] ever changes the old baseline is automatically cleared
 * so a fresh seed is performed with the new window on the next poll.
 */
@Singleton
class MsDeltaStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("ms_delta_store", Context.MODE_PRIVATE)

    /** The `@odata.deltaLink` URL from the most recent completed sync. */
    var deltaLink: String?
        get()      = prefs.getString(KEY_DELTA_LINK, null)
        set(value) = prefs.edit().putString(KEY_DELTA_LINK, value).apply()

    /**
     * Returns the stored delta link only if it was seeded with [daysAhead].
     * If the window changed, clears the stale link and returns null so the
     * caller re-seeds with the correct window.
     */
    fun deltaLinkForWindow(daysAhead: Int): String? {
        val storedDays = prefs.getInt(KEY_DAYS_AHEAD, -1)
        if (storedDays != daysAhead) {
            clear()
            return null
        }
        return deltaLink
    }

    /** Saves the delta link together with the window it was seeded for. */
    fun saveDeltaLink(link: String, daysAhead: Int) {
        prefs.edit()
            .putString(KEY_DELTA_LINK, link)
            .putInt(KEY_DAYS_AHEAD, daysAhead)
            .apply()
    }

    fun clear() = prefs.edit()
        .remove(KEY_DELTA_LINK)
        .remove(KEY_DAYS_AHEAD)
        .apply()

    companion object {
        private const val KEY_DELTA_LINK = "delta_link"
        private const val KEY_DAYS_AHEAD = "days_ahead"
    }
}
