package com.vamanit.calendar.data.repository

import com.vamanit.calendar.data.model.CalendarEvent
import kotlinx.coroutines.flow.StateFlow

interface CalendarRepository {
    val events: StateFlow<List<CalendarEvent>>

    /** Full refresh — fetches all events from all sources. */
    suspend fun refresh()

    /**
     * Delta-check refresh — asks Graph if anything changed since the last sync.
     * Triggers a full [refresh] only when changes are detected. No-ops otherwise.
     * Returns true if a full refresh was triggered.
     */
    suspend fun refreshIfChanged(): Boolean
}
