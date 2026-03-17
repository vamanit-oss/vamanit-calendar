package com.vamanit.calendar.data.repository

import com.vamanit.calendar.auth.AuthManager
import com.vamanit.calendar.auth.AuthState
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.remote.GoogleCalendarDataSource
import com.vamanit.calendar.data.remote.MicrosoftCalendarDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val authManager: AuthManager,
    private val googleSource: GoogleCalendarDataSource,
    private val microsoftSource: MicrosoftCalendarDataSource
) : CalendarRepository {

    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    override val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    override suspend fun refresh() {
        val state = authManager.authState.value
        if (state !is AuthState.Authenticated) {
            Timber.w("Cannot refresh: not authenticated")
            return
        }

        val results = mutableListOf<CalendarEvent>()

        // Fetch Google events — failure does not block Microsoft
        if (state.hasGoogle) {
            runCatching { results.addAll(googleSource.fetchEvents()) }
                .onFailure { Timber.e(it, "Google Calendar fetch failed") }
        }

        // Fetch Microsoft events — failure does not block Google
        if (state.hasMicrosoft) {
            val token = state.microsoftToken!!
            runCatching { results.addAll(microsoftSource.fetchEvents(token)) }
                .onFailure { Timber.e(it, "Microsoft Calendar fetch failed") }
        }

        _events.emit(results.sortedBy { it.startTime })
        Timber.d("Refreshed: ${results.size} events total")
    }
}
