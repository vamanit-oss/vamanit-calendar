package com.vamanit.calendar.data.repository

import com.vamanit.calendar.auth.AuthManager
import com.vamanit.calendar.auth.AuthState
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.remote.GoogleCalendarDataSource
import com.vamanit.calendar.data.remote.MicrosoftCalendarDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Serialises concurrent refresh calls — only one fetch runs at a time. */
    private val refreshMutex = Mutex()

    /**
     * In-memory MS token cache.
     * MS access tokens are valid ~1 hour. We reuse the cached token for all polls
     * within that window instead of calling acquireTokenSilent() on every 30s cycle.
     * The token is refreshed when it is within TOKEN_REFRESH_BUFFER_MS of expiry,
     * or when a 401 is returned by Graph.
     */
    private var cachedMsToken: String? = null
    private var cachedMsTokenExpiryMs: Long = 0L

    private suspend fun getMsToken(state: AuthState.Authenticated): String? {
        val now = System.currentTimeMillis()
        if (cachedMsToken != null && now < cachedMsTokenExpiryMs - TOKEN_REFRESH_BUFFER_MS) {
            return cachedMsToken
        }
        // Refresh from MSAL (network call only when token is stale)
        val fresh = authManager.getFreshMicrosoftToken() ?: state.microsoftToken
        if (fresh != null) {
            cachedMsToken = fresh
            // MS tokens last ~3600s; conservative 55-minute cache window
            cachedMsTokenExpiryMs = now + 55 * 60 * 1_000L
        }
        return fresh
    }

    /** Invalidates the in-memory token cache (call on sign-out or 401). */
    fun invalidateMsTokenCache() {
        cachedMsToken = null
        cachedMsTokenExpiryMs = 0L
    }

    override suspend fun refresh() = refreshMutex.withLock {
        val state = authManager.authState.value
        if (state !is AuthState.Authenticated) {
            Timber.w("Cannot refresh: not authenticated")
            return
        }

        val results = mutableListOf<CalendarEvent>()

        // Google events — independent, failure does not block Microsoft
        if (state.hasGoogle) {
            runCatching { results.addAll(googleSource.fetchEvents(daysAhead = DAYS_AHEAD)) }
                .onFailure { Timber.e(it, "Google Calendar fetch failed") }
        }

        // Microsoft events — uses cached token to avoid MSAL round-trip on every poll
        if (state.hasMicrosoft) {
            val token = getMsToken(state) ?: return
            val fetchResult = runCatching {
                microsoftSource.fetchEvents(token, daysAhead = DAYS_AHEAD)
            }
            fetchResult
                .onSuccess  { results.addAll(it) }
                .onFailure  { e ->
                    Timber.e(e, "Microsoft Calendar fetch failed")
                    // 401 → bust the token cache so next call re-authenticates
                    if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                        invalidateMsTokenCache()
                    }
                }
        }

        val sorted = results.sortedBy { it.startTime }
        // Never overwrite a non-empty list with empty — guards against worker races
        // before auth tokens settle on startup.
        if (sorted.isNotEmpty() || _events.value.isEmpty()) {
            _events.emit(sorted)
        }
        Timber.d("Refreshed: ${sorted.size} events total")
    }

    override suspend fun refreshIfChanged(): Boolean {
        val state = authManager.authState.value
        if (state !is AuthState.Authenticated || !state.hasMicrosoft) return false

        // Reuse cached token — cheap, no MSAL call unless near expiry
        val token = getMsToken(state) ?: return false

        val changed = runCatching {
            microsoftSource.hasChangedSinceDelta(token, daysAhead = DAYS_AHEAD)
        }.getOrElse { e ->
            Timber.w(e, "Delta check failed — forcing full refresh")
            true
        }

        return if (changed) {
            Timber.d("Delta: changes detected — full refresh")
            refresh()
            true
        } else {
            Timber.v("Delta: no changes")
            false
        }
    }

    companion object {
        const val DAYS_AHEAD = 30

        /** Refresh cached token this many ms before actual expiry. */
        private const val TOKEN_REFRESH_BUFFER_MS = 5 * 60 * 1_000L  // 5 min
    }
}
