package com.vamanit.calendar.data.remote

import com.microsoft.graph.models.DateTimeTimeZone
import com.microsoft.graph.models.Event
import com.microsoft.graph.requests.GraphServiceClient
import com.microsoft.graph.requests.EventCollectionPage
import com.vamanit.calendar.auth.MicrosoftAuthProvider
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.model.CalendarSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicrosoftCalendarDataSource @Inject constructor(
    private val authProvider: MicrosoftAuthProvider
) {
    private fun buildClient(token: String): GraphServiceClient<Request> {
        val authProvider = com.microsoft.graph.authentication.IAuthenticationProvider { request ->
            request.addHeader("Authorization", "Bearer $token")
        }
        return GraphServiceClient.builder()
            .authenticationProvider(authProvider)
            .buildClient()
    }

    suspend fun fetchEvents(token: String, daysAhead: Int = 14): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            val client = buildClient(token)
            val now = ZonedDateTime.now()
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            val start = now.format(fmt)
            val end = now.plusDays(daysAhead.toLong()).format(fmt)

            val events = mutableListOf<CalendarEvent>()
            runCatching {
                var page: EventCollectionPage? = client.me()
                    .calendarView()
                    .buildRequest(
                        listOf(
                            com.microsoft.graph.options.QueryOption("startDateTime", start),
                            com.microsoft.graph.options.QueryOption("endDateTime", end),
                            com.microsoft.graph.options.QueryOption("\$top", "100"),
                            com.microsoft.graph.options.QueryOption(
                                "\$select",
                                "id,subject,start,end,location,bodyPreview,organizer,isAllDay,categories"
                            )
                        )
                    )
                    .get()

                while (page != null) {
                    page.currentPage.forEach { event ->
                        mapEvent(event)?.let { events.add(it) }
                    }
                    page = page.nextPage?.buildRequest()?.get()
                }
            }.onFailure { Timber.e(it, "Microsoft calendar fetch failed") }

            events.sortedBy { it.startTime }
        }

    private fun mapEvent(event: Event): CalendarEvent? {
        val title = event.subject ?: return null
        val start = event.start?.toZdt() ?: return null
        val end = event.end?.toZdt() ?: start.plusHours(1)
        return CalendarEvent(
            id = event.id ?: return null,
            title = title,
            startTime = start,
            endTime = end,
            location = event.location?.displayName,
            description = event.bodyPreview,
            source = CalendarSource.MICROSOFT,
            colorHex = null, // Graph API doesn't expose calendar color per-event easily
            isAllDay = event.isAllDay == true,
            organizer = event.organizer?.emailAddress?.name,
            calendarName = "Outlook"
        )
    }

    private fun DateTimeTimeZone.toZdt(): ZonedDateTime? = runCatching {
        val tz = ZoneId.of(timeZone ?: "UTC")
        ZonedDateTime.parse(
            dateTime,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSSSSSS][.SSS]").withZone(tz)
        )
    }.getOrNull()
}
