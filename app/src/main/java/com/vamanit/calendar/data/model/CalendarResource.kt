package com.vamanit.calendar.data.model

/**
 * A Google Calendar resource (conference room, etc.) that the signed-in user
 * has at least writer access to — i.e., they can book it.
 */
data class CalendarResource(
    val calendarId: String,
    val displayName: String,
    val buildingName: String? = null
) {
    /**
     * Spinner label: "Trivikram @ RCV Innovations HQ"
     * Falls back to just the display name when no building is known.
     */
    val spinnerLabel: String
        get() = if (!buildingName.isNullOrBlank()) "$displayName @ $buildingName"
                else displayName
}
