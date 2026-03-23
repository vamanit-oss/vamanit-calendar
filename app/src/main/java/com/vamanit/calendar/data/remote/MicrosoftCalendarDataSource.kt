package com.vamanit.calendar.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vamanit.calendar.data.model.CalendarEvent
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
        private val ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val PARSE_FMT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd'T'HH:mm:ss[.SSSSSSS][.SSS][.SS][.S]"
        )
    }

    suspend fun fetchEvents(token: String, daysAhead: Int = 14): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            val now = ZonedDateTime.now()
            val startDateTime = now.format(ISO_FMT)
            val endDateTime = now.plusDays(daysAhead.toLong()).format(ISO_FMT)

            val url = "$GRAPH_BASE/me/calendarView" +
                "?startDateTime=$startDateTime" +
                "&endDateTime=$endDateTime" +
                "&\$top=100" +
                "&\$select=id,subject,start,end,location,bodyPreview,organizer,isAllDay,attendees" +
                "&\$orderby=start/dateTime"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Prefer", "outlook.timezone=\"${ZoneId.systemDefault().id}\"")
                .build()

            val events = mutableListOf<CalendarEvent>()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.e("Graph API error: ${response.code}")
                        return@withContext emptyList<CalendarEvent>()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList<CalendarEvent>()
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val value = json.getAsJsonArray("value") ?: return@withContext emptyList<CalendarEvent>()
                    value.forEach { elem -> mapEvent(elem.asJsonObject)?.let { events.add(it) } }
                }
            }.onFailure { Timber.e(it, "Microsoft Calendar fetch failed") }
            events
        }

    private fun mapEvent(obj: JsonObject): CalendarEvent? {
        val id = obj.get("id")?.asString ?: return null
        val title = obj.get("subject")?.asString ?: return null
        val startObj = obj.getAsJsonObject("start") ?: return null
        val endObj   = obj.getAsJsonObject("end")   ?: return null
        val deviceZone = ZoneId.systemDefault()
        // Parse in whatever timezone the API returned, then normalise to device timezone.
        // Graph may return Windows-style IDs ("Pacific Standard Time") that ZoneId.of()
        // can't parse — fall back to device zone so the event is never silently dropped.
        val apiTz = runCatching {
            ZoneId.of(startObj.get("timeZone")?.asString ?: "")
        }.getOrDefault(deviceZone)
        val start = parseGraphDateTime(startObj.get("dateTime")?.asString ?: return null, apiTz)
            ?.withZoneSameInstant(deviceZone) ?: return null
        val end   = parseGraphDateTime(endObj.get("dateTime")?.asString   ?: return null, apiTz)
            ?.withZoneSameInstant(deviceZone) ?: start.plusHours(1)
        return CalendarEvent(
            id = id, title = title, startTime = start, endTime = end,
            location = obj.getAsJsonObject("location")?.get("displayName")?.asString?.takeIf { it.isNotBlank() },
            description = obj.get("bodyPreview")?.asString,
            source = CalendarSource.MICROSOFT, colorHex = null,
            isAllDay = obj.get("isAllDay")?.asBoolean ?: false,
            organizer = obj.getAsJsonObject("organizer")?.getAsJsonObject("emailAddress")?.get("name")?.asString,
            calendarName = "Outlook",
            attendees = obj.getAsJsonArray("attendees")
                ?.mapNotNull { it.asJsonObject.getAsJsonObject("emailAddress")?.let { ea ->
                    ea.get("name")?.asString?.takeIf { n -> n.isNotBlank() } ?: ea.get("address")?.asString
                } } ?: emptyList()
        )
    }

    private fun parseGraphDateTime(dt: String, tz: ZoneId): ZonedDateTime? = runCatching {
        ZonedDateTime.parse(dt, PARSE_FMT.withZone(tz))
    }.getOrNull()
}
