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

    /**
     * Lightweight calendar descriptor used internally.
     *
     * @param id      GUID for calendars in /me/calendars; email address for delegate rooms.
     * @param name    Display name.
     * @param isOwned true = user's own calendar → events tagged calendarId=null.
     * @param isRoom  true = Exchange room mailbox → access via /users/{email}/calendarView.
     */
    private data class MsCalendarInfo(
        val id: String,
        val name: String,
        val isOwned: Boolean,
        val isRoom: Boolean = false
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches events from ALL calendars the signed-in user has access to:
     *  - Personal calendars from /me/calendars
     *  - Delegate room calendars discovered via /me/findRooms
     *
     * Owned calendar events → calendarId = null.
     * Room / delegated calendar events → calendarId = GUID or room email (filterable).
     */
    suspend fun fetchEvents(token: String, daysAhead: Int = 14): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            val calendars = fetchAllCalendars(token)
            val allEvents = mutableListOf<CalendarEvent>()
            calendars.forEach { cal ->
                runCatching {
                    val tagId = if (cal.isOwned) null else cal.id
                    allEvents.addAll(fetchCalendarView(token, cal, tagId, daysAhead))
                }.onFailure { Timber.w(it, "Failed to fetch MS events for calendar: ${cal.id}") }
            }
            allEvents.distinctBy { it.id }.sortedBy { it.startTime }
        }

    /** Returns the signed-in Microsoft user's display name. */
    suspend fun fetchUserDisplayName(token: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val json = graphGet(token, "$GRAPH_BASE/me?\$select=displayName") ?: return@runCatching null
            json.get("displayName")?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "My Calendar"
    }

    /**
     * Returns delegate room calendars from /me/findRooms as spinner entries.
     * Each entry's [CalendarResource.calendarId] is the room email address, which
     * is also used to tag events fetched from that room so filtering works.
     *
     * Requires User.ReadBasic.All scope. Returns empty list gracefully if unavailable.
     */
    suspend fun fetchDelegatedCalendars(token: String): List<CalendarResource> =
        withContext(Dispatchers.IO) {
            val rooms = fetchRooms(token)
            Timber.d("MS fetchDelegatedCalendars: ${rooms.size} delegate rooms")
            rooms.map { room ->
                Timber.d("  room: '${room.name}' → ${room.id}")
                CalendarResource(
                    calendarId   = room.id,    // room email address
                    displayName  = room.name,
                    buildingName = null
                )
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns all calendars the user can access:
     *  - /me/calendars (personal + shared calendars added to the mailbox)
     *  - /me/findRooms (Exchange room mailboxes where user is a delegate)
     */
    private suspend fun fetchAllCalendars(token: String): List<MsCalendarInfo> {
        val userEmail = runCatching { fetchUserEmail(token) }.getOrNull() ?: ""

        // Personal calendars
        val personal = runCatching {
            val url = "$GRAPH_BASE/me/calendars?\$select=id,name,isDefaultCalendar,owner,canEdit&\$top=50"
            val json = graphGet(token, url) ?: return@runCatching emptyList()
            val value = json.getAsJsonArray("value") ?: return@runCatching emptyList()
            value.mapNotNull { elem ->
                val cal        = elem.asJsonObject
                val id         = cal.get("id")?.asString                  ?: return@mapNotNull null
                val name       = cal.get("name")?.asString                ?: return@mapNotNull null
                val isDefault  = cal.get("isDefaultCalendar")?.asBoolean  ?: false
                val ownerEmail = cal.getAsJsonObject("owner")?.get("address")?.asString ?: ""
                val isOwned    = isDefault || ownerEmail.isBlank() ||
                    ownerEmail.equals(userEmail, ignoreCase = true)
                MsCalendarInfo(id = id, name = name, isOwned = isOwned, isRoom = false)
            }
        }.getOrElse { emptyList() }

        // Delegate rooms
        val rooms = runCatching { fetchRooms(token) }.getOrElse { emptyList() }

        return personal + rooms
    }

    /**
     * Calls /me/findRooms and returns Exchange room mailboxes as [MsCalendarInfo].
     * Room entries use the room email as [MsCalendarInfo.id] and isRoom=true so
     * callers use /users/{email}/calendarView instead of /me/calendars/{id}/calendarView.
     */
    private suspend fun fetchRooms(token: String): List<MsCalendarInfo> {
        val json  = graphGet(token, "$GRAPH_BASE/me/findRooms()", preferTimezone = false) ?: return emptyList()
        val value = json.getAsJsonArray("value")               ?: return emptyList()
        return value.mapNotNull { elem ->
            val obj     = elem.asJsonObject
            val name    = obj.get("name")?.asString    ?: return@mapNotNull null
            val address = obj.get("address")?.asString ?: return@mapNotNull null
            MsCalendarInfo(id = address, name = name, isOwned = false, isRoom = true)
        }
    }

    /** Returns the signed-in user's primary email address. */
    private suspend fun fetchUserEmail(token: String): String? {
        val json = graphGet(token, "$GRAPH_BASE/me?\$select=mail,userPrincipalName")
            ?: return null
        return json.get("mail")?.asString?.takeIf { it.isNotBlank() }
            ?: json.get("userPrincipalName")?.asString
    }

    /**
     * Fetches events for a single calendar.
     * - Regular calendars: GET /me/calendars/{guid}/calendarView
     * - Room mailboxes:    GET /users/{email}/calendarView  (delegate access)
     */
    private suspend fun fetchCalendarView(
        token: String,
        cal: MsCalendarInfo,
        tagCalendarId: String?,
        daysAhead: Int
    ): List<CalendarEvent> {
        val now           = ZonedDateTime.now()
        val startDateTime = now.format(ISO_FMT)
        val endDateTime   = now.plusDays(daysAhead.toLong()).format(ISO_FMT)
        val base = if (cal.isRoom) "$GRAPH_BASE/users/${cal.id}/calendarView"
                   else            "$GRAPH_BASE/me/calendars/${cal.id}/calendarView"
        val url = "$base" +
            "?startDateTime=$startDateTime" +
            "&endDateTime=$endDateTime" +
            "&\$top=100" +
            "&\$select=$EVENT_SELECT" +
            "&\$orderby=start/dateTime"
        val json  = graphGet(token, url) ?: return emptyList()
        val value = json.getAsJsonArray("value") ?: return emptyList()
        return value.mapNotNull { mapEvent(it.asJsonObject, tagCalendarId, cal.name) }
    }

    /** Executes a GET request against the Graph API and returns the parsed JSON body. */
    private fun graphGet(token: String, url: String, preferTimezone: Boolean = true): JsonObject? {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
        if (preferTimezone) {
            builder.addHeader("Prefer", "outlook.timezone=\"${ZoneId.systemDefault().id}\"")
        }
        return runCatching {
            httpClient.newCall(builder.build()).execute().use { response ->
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
