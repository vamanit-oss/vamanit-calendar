package com.vamanit.calendar.data.remote

import android.content.Context
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventAttendee
import com.vamanit.calendar.auth.GoogleAuthProvider
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.data.model.CalendarSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleCalendarDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authProvider: GoogleAuthProvider
) {
    private fun buildService(): Calendar {
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val credential = authProvider.buildCredential()
        return Calendar.Builder(transport, jsonFactory, credential)
            .setApplicationName("Vamanit Calendar")
            .build()
    }

    suspend fun fetchEvents(daysAhead: Int = 14): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val service = buildService()
        val now = ZonedDateTime.now()
        val timeMin = com.google.api.client.util.DateTime(now.toInstant().toEpochMilli())
        val timeMax = com.google.api.client.util.DateTime(
            now.plusDays(daysAhead.toLong()).toInstant().toEpochMilli()
        )

        val events = mutableListOf<CalendarEvent>()

        // Enumerate all calendars in the user's list
        val calendarList = service.calendarList().list().execute()
        calendarList.items?.forEach { cal ->
            runCatching {
                val eventsResult = service.events().list(cal.id)
                    .setTimeMin(timeMin)
                    .setTimeMax(timeMax)
                    .setSingleEvents(true)
                    .setOrderBy("startTime")
                    .setMaxResults(100)
                    .execute()
                eventsResult.items?.forEach { event ->
                    mapEvent(event, cal.id, cal.summary, cal.backgroundColor)?.let { events.add(it) }
                }
            }.onFailure { Timber.w(it, "Failed to fetch events for calendar: ${cal.id}") }
        }

        events.sortedBy { it.startTime }
    }

    private fun mapEvent(
        event: Event,
        calendarId: String?,
        calendarName: String?,
        calendarColor: String?
    ): CalendarEvent? {
        if (event.summary.isNullOrBlank()) return null
        val startMs = event.start?.dateTime?.value ?: event.start?.date?.value ?: return null
        val endMs = event.end?.dateTime?.value ?: event.end?.date?.value ?: startMs

        val zone = ZoneId.systemDefault()
        return CalendarEvent(
            id = event.id ?: return null,
            title = event.summary,
            startTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMs), zone),
            endTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(endMs), zone),
            location = event.location,
            description = event.description,
            source = CalendarSource.GOOGLE,
            colorHex = event.colorId?.let { mapGoogleColor(it) } ?: calendarColor,
            isAllDay = event.start?.date != null,
            organizer = event.organizer?.displayName,
            calendarName = calendarName,
            attendees = event.attendees
                ?.filter { it.resource != true }
                ?.mapNotNull { it.displayName?.takeIf { n -> n.isNotBlank() } ?: it.email }
                ?: emptyList(),
            calendarId = calendarId
        )
    }

    // ── Resource calendars ────────────────────────────────────────────────────

    /**
     * Returns all resource calendars (conference rooms, etc.) the signed-in user
     * has at least writer access to — those the user can book.
     *
     * Auto-generated names follow: "{Building}-{Floor}-{Name} ({Capacity})"
     * We parse this to produce the "Name @ Building" spinner label.
     */
    suspend fun fetchDelegatedResources(): List<CalendarResource> = withContext(Dispatchers.IO) {
        val service = buildService()
        val calendarList = service.calendarList().list()
            .setMinAccessRole("writer")
            .execute()
        calendarList.items
            ?.filter { it.id.contains("@resource.calendar.google.com") }
            ?.map { entry ->
                val autoName = entry.summary ?: entry.id
                // Pattern: "Building Name-Floor-Resource Name (Capacity)"
                val match = Regex("""^(.+?)-\d+-(.+?)(?:\s*\(\d+\))?$""").find(autoName)
                val building = match?.groupValues?.getOrNull(1)?.trim()
                val name     = match?.groupValues?.getOrNull(2)?.trim() ?: autoName
                CalendarResource(
                    calendarId   = entry.id,
                    displayName  = name,
                    buildingName = building
                )
            }
            ?: emptyList()
    }

    /** Returns the display name of the primary (personal) calendar, e.g. "Ramkaran Rudravaram". */
    suspend fun fetchUserDisplayName(): String = withContext(Dispatchers.IO) {
        runCatching {
            buildService().calendars().get("primary").execute().summary ?: "My Calendar"
        }.getOrDefault("My Calendar")
    }

    /**
     * Books (or clears) a resource room on a Google Calendar event.
     *
     * Pass [resourceCalendarId] = null to remove any existing room booking.
     * Requires the `calendar.events` write scope to be granted.
     */
    suspend fun patchEventRoom(
        calendarId: String,
        eventId: String,
        resourceCalendarId: String?
    ) = withContext(Dispatchers.IO) {
        val service = buildService()
        // Fetch current event attendees (non-resource people) so we don't wipe them
        val current = service.events().get(calendarId, eventId).execute()
        val people  = current.attendees?.filter { it.resource != true } ?: emptyList()

        val newAttendees: List<EventAttendee> = if (resourceCalendarId != null) {
            people + listOf(EventAttendee().setEmail(resourceCalendarId).setResource(true))
        } else {
            people
        }

        val patch = Event().apply { attendees = newAttendees }
        service.events().patch(calendarId, eventId, patch)
            .setSendUpdates("none")
            .execute()
        Timber.d("Patched event $eventId: room → $resourceCalendarId")
    }

    private fun mapGoogleColor(colorId: String): String? = when (colorId) {
        "1" -> "#AC725E"; "2" -> "#D06B64"; "3" -> "#F83A22"
        "4" -> "#FA573C"; "5" -> "#FF7537"; "6" -> "#FFAD46"
        "7" -> "#42D692"; "8" -> "#16A765"; "9" -> "#7BD148"
        "10" -> "#B3DC6C"; "11" -> "#FBE983"; "12" -> "#FAD165"
        "13" -> "#92E1C0"; "14" -> "#9FE1E7"; "15" -> "#9FC6E7"
        "16" -> "#4986E7"; "17" -> "#9A9CFF"; "18" -> "#B99AFF"
        "19" -> "#C2C2C2"; "20" -> "#CABDBF"; "21" -> "#CCA6AC"
        "22" -> "#F691B2"; "23" -> "#CD74E6"; "24" -> "#A47AE2"
        else -> null
    }
}
