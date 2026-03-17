package com.vamanit.calendar.data.model

import java.time.ZonedDateTime

data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: ZonedDateTime,
    val endTime: ZonedDateTime,
    val location: String? = null,
    val description: String? = null,
    val source: CalendarSource,
    val colorHex: String? = null,
    val isAllDay: Boolean = false,
    val organizer: String? = null,
    val calendarName: String? = null
)

enum class CalendarSource(val label: String, val colorFallback: String) {
    GOOGLE("Google", "#4285F4"),
    MICROSOFT("Microsoft", "#0078D4")
}
