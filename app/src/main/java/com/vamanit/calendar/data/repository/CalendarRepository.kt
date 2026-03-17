package com.vamanit.calendar.data.repository

import com.vamanit.calendar.data.model.CalendarEvent
import kotlinx.coroutines.flow.StateFlow

interface CalendarRepository {
    val events: StateFlow<List<CalendarEvent>>
    suspend fun refresh()
}
