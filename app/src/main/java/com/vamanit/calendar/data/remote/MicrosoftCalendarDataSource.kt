package com.vamanit.calendar.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.data.model.CalendarSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftCalendarDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        private val ISO_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val PARSE_FMT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd'T'HH:mm:ss[.SSSSSSS][.SSS][.SS][.S]"
        )
        private const val EVENT_SELECT =
            "id,subject,start,end,location,bodyPreview,organizer,isAllDay,attendees"
    }

    /** Lightweight calendar descriptor used internally. */
    private data class MsCalendarInfo(
        val id: String,
        val name: String,
        /** true = user owns this calendar (personal/primary); false = delegated/managed */
        val isOwned: Boolean
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches events from ALL calendars the signed-in user has access to,
     * iterating per-calendar so each event can be tagged with its [CalendarEvent.calendarId].
     *
     * Personal / owned calendar events → calendarId = null (shown in "My Calendar" view).
     * Delegated / managed calendar events → calendarId = calendar GUID (filterable by resource).
     */
    suspend fun fetchEvents(token: String, daysAhead: Int = 14): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            val calendars = fetchAllCalendars(token)
            val allEvents = mutableListOf<CalendarEvent>()
            calendars.forEach { cal ->
                runCatching {
                    val tagId = if (cal.isOwned) null else cal.id
                    allEvents.addAll(
                        fetchCalendarViewForCalendar(token, cal.id, cal.name, tagId, daysAhead)
                    )
                }.onFailure { Timber.w(it, "Failed to fetch MS events for calendar: ${cal.id}") }
            }
            // Deduplicate (recurring events can appear in multiple views) then sort
            allEvents.distinctBy { it.id }.sortedBy { it.startTime }
        }

    /** Returns the signed-in Microsoft user's display name (e.g. "Ramkaran Rudravaram"). */
    suspend fun fetchUserDisplayName(token: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val json = graphGet(token, "$GRAPH_BASE/me?\$select=displayName") ?: return@runCatching null
            json.get("displayName")?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "My Calendar"
    }

    /**
     * Returns Microsoft calendars that were delegated / shared to the signed-in user —
     * i.e. calendars that appear in /me/calendars whose owner is NOT the signed-in user.
     *
     * Uses only v1.0-safe properties (isSharedWithMe is beta-only → 400 on v1.0).
     * `canEdit` is intentionally not required: read-only delegates should also appear.
     */
    suspend fun fetchDelegatedCalendars(token: String): List<CalendarResource> =
        withContext(Dispatchers.IO) {
            // Best-effort email fetch; empty string means we can't exclude by owner comparison
            val userEmail = runCatching { fetchUserEmail(token) }.getOrNull() ?: ""
            Timber.d("MS fetchDelegatedCalendars: userEmail='$userEmail'")
            val url = "$GRAPH_BASE/me/calendars" +
                "?\$select=id,name,owner,canEdit,isDefaultCalendar&\$top=50"
            val json  = graphGet(token, url) ?: return@withContext emptyList()
            val value = json.getAsJsonArray("value") ?: return@withContext emptyList()
            Timber.d("MS fetchDelegatedCalendars: ${value.size()} total calendars")
            value.mapNotNull { elem ->
                val cal        = elem.asJsonObject
                val id         = cal.get("id")?.asString                   ?: return@mapNotNull null
                val name       = cal.get("name")?.asString                 ?: return@mapNotNull null
                val isDefault  = cal.get("isDefaultCalendar")?.asBoolean   ?: false
                val canEdit    = cal.get("canEdit")?.asBoolean              ?: false
                val ownerEmail = cal.getAsJsonObject("owner")?.get("address")?.asString ?: ""
                Timber.d("  '$name' isDefault=$isDefault canEdit=$canEdit owner='$ownerEmail'")
                // Skip calendars the user owns (default calendar or email match)
                val isOwnedByUser = isDefault ||
                    (userEmail.isNotBlank() && ownerEmail.equals(userEmail, ignoreCase = true))
                if (isOwnedByUser) {
                    Timber.d("  → skipped (owned)")
                    return@mapNotNull null
                }
                val parts = name.split(" - ", limit = 2)
                val (building, displayName) =
                    if (parts.size == 2) parts[0].trim() to parts[1].trim()
                    else null to name
                Timber.d("  → delegated resource: '$displayName' @ '$building'")
                CalendarResource(calendarId = id, displayName = displayName, buildingName = building)
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns the signed-in user's primary email address. */
    private suspend fun fetchUserEmail(token: String): String? {
        val json = graphGet(token, "$GRAPH_BASE/me?\$select=mail,userPrincipalName")
            ?: return null
        return json.get("mail")?.asString?.takeIf { it.isNotBlank() }
            ?: json.get("userPrincipalName")?.asString
    }

    /**
     * Returns all calendars in the user's calendar list, with an [MsCalendarInfo.isOwned] flag
     * so callers can distinguish personal from delegated calendars.
     */
    private suspend fun fetchAllCalendars(token: String): List<MsCalendarInfo> {
        // Fetch user email to distinguish owned vs delegated calendars.
        // If it fails use empty string — unknown ownership defaults to isOwned=true so
        // events are never silently hidden.
        val userEmail = runCatching { fetchUserEmail(token) }.getOrNull() ?: ""
        val url = "$GRAPH_BASE/me/calendars" +
            "?\$select=id,name,isDefaultCalendar,owner,canEdit&\$top=50"
        val json = graphGet(token, url) ?: return emptyList()
        val value = json.getAsJsonArray("value") ?: return emptyList()
        return value.mapNotNull { elem ->
            val cal        = elem.asJsonObject
            val id         = cal.get("id")?.asString              ?: return@mapNotNull null
            val name       = cal.get("name")?.asString            ?: return@mapNotNull null
            val isDefault  = cal.get("isDefaultCalendar")?.asBoolean ?: false
            val ownerEmail = cal.getAsJsonObject("owner")?.get("address")?.asString ?: ""
            // Owned if: default calendar, owner matches user, or owner unknown (blank)
            val isOwned = isDefault ||
                ownerEmail.isBlank() ||
                ownerEmail.equals(userEmail, ignoreCase = true)
            MsCalendarInfo(id, name, isOwned)
        }
    }

    /** Fetches events from a single calendar's calendarView endpoint. */
    private suspend fun fetchCalendarViewForCalendar(
        token: String,
        calendarId: String,
        calendarName: String,
        tagCalendarId: String?,
        daysAhead: Int
    ): List<CalendarEvent> {
        val now           = ZonedDateTime.now()
        val startDateTime = now.format(ISO_FMT)
        val endDateTime   = now.plusDays(daysAhead.toLong()).format(ISO_FMT)
        val url = "$GRAPH_BASE/me/calendars/$calendarId/calendarView" +
            "?startDateTime=$startDateTime" +
            "&endDateTime=$endDateTime" +
            "&\$top=100" +
            "&\$select=$EVENT_SELECT" +
            "&\$orderby=start/dateTime"
        val json  = graphGet(token, url) ?: return emptyList()
        val value = json.getAsJsonArray("value") ?: return emptyList()
        return value.mapNotNull { mapEvent(it.asJsonObject, tagCalendarId, calendarName) }
    }

    /** Executes a GET request against the Graph API and returns the parsed JSON body. */
    private fun graphGet(token: String, url: String): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Prefer", "outlook.timezone=\"${ZoneId.systemDefault().id}\"")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Graph API error ${response.code} for $url")
                    return null
                }
                gson.fromJson(response.body?.string() ?: return null, JsonObject::class.java)
            }
        }.getOrNull()
    }

    private fun mapEvent(
        obj: JsonObject,
        calendarId: String?,
        calendarName: String
    ): CalendarEvent? {
        val id    = obj.get("id")?.asString      ?: return null
        val title = obj.get("subject")?.asString ?: return null
        val startObj = obj.getAsJsonObject("start") ?: return null
        val endObj   = obj.getAsJsonObject("end")   ?: return null
        val deviceZone = ZoneId.systemDefault()
        val apiTz = runCatching {
            ZoneId.of(startObj.get("timeZone")?.asString ?: "")
        }.getOrDefault(deviceZone)
        val start = parseGraphDateTime(startObj.get("dateTime")?.asString ?: return null, apiTz)
            ?.withZoneSameInstant(deviceZone) ?: return null
        val end = parseGraphDateTime(endObj.get("dateTime")?.asString ?: return null, apiTz)
            ?.withZoneSameInstant(deviceZone) ?: start.plusHours(1)
        return CalendarEvent(
            id          = id,
            title       = title,
            startTime   = start,
            endTime     = end,
            location    = obj.getAsJsonObject("location")
                ?.get("displayName")?.asString?.takeIf { it.isNotBlank() },
            description = obj.get("bodyPreview")?.asString,
            source      = CalendarSource.MICROSOFT,
            colorHex    = null,
            isAllDay    = obj.get("isAllDay")?.asBoolean ?: false,
            organizer   = obj.getAsJsonObject("organizer")
                ?.getAsJsonObject("emailAddress")?.get("name")?.asString,
            calendarName = calendarName,
            attendees   = obj.getAsJsonArray("attendees")
                ?.mapNotNull { it.asJsonObject.getAsJsonObject("emailAddress")?.let { ea ->
                    ea.get("name")?.asString?.takeIf { n -> n.isNotBlank() }
                        ?: ea.get("address")?.asString
                } } ?: emptyList(),
            calendarId  = calendarId
        )
    }

    private fun parseGraphDateTime(dt: String, tz: ZoneId): ZonedDateTime? = runCatching {
        ZonedDateTime.parse(dt, PARSE_FMT.withZone(tz))
    }.getOrNull()
}
