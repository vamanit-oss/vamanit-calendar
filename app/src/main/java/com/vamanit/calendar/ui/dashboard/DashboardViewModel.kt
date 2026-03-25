package com.vamanit.calendar.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.vamanit.calendar.auth.AuthManager
import com.vamanit.calendar.auth.AuthState
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.repository.CalendarRepository
import com.vamanit.calendar.domain.usecase.GetUpcomingEventsUseCase
import com.vamanit.calendar.domain.usecase.RefreshEventsUseCase
import com.vamanit.calendar.push.PushSubscriptionManager
import com.vamanit.calendar.push.PushSubscriptionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    getUpcomingEvents: GetUpcomingEventsUseCase,
    private val refreshEvents: RefreshEventsUseCase,
    private val calendarRepository: CalendarRepository,
    private val authManager: AuthManager,
    private val pushSubscriptionManager: PushSubscriptionManager,
    private val workManager: WorkManager
) : ViewModel() {

    val events: StateFlow<List<CalendarEvent>> = getUpcomingEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch {
            runCatching { refreshEvents() }
                .onFailure { Timber.e(it, "Manual refresh failed") }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val state = authManager.authState.value
            val msToken = if (state is AuthState.Authenticated && state.hasMicrosoft)
                authManager.getFreshMicrosoftToken() else null
            runCatching { pushSubscriptionManager.cancelAll(msToken) }
        }
        authManager.signOutAll()
    }

    /**
     * Two-tier foreground refresh loop:
     *
     * Fast tier  (every [DELTA_INTERVAL_MS] = 30 s):
     *   Calls refreshIfChanged() — uses Graph delta link to check for changes in
     *   the PRIMARY calendar only. Very cheap: one Graph call + MSAL token refresh.
     *   Triggers a full refresh immediately when the primary calendar changes.
     *
     * Full tier  (every [FULL_INTERVAL_MS] = 60 s):
     *   Forces a full refresh of ALL sources regardless of delta result.
     *   Covers room calendars (Yamuna, Godavari) and Google calendars which are
     *   not tracked by the delta endpoint.
     *
     * FCM push from the Cloud Functions backend (when deployed) fires a full
     * refresh instantly on any change — making both tiers a safety net only.
     */
    private fun startRefreshLoop() {
        // Fast-tier: delta check every 30 s
        viewModelScope.launch {
            delay(DELTA_INTERVAL_MS)
            while (true) {
                runCatching {
                    val refreshed = calendarRepository.refreshIfChanged()
                    if (refreshed) Timber.d("Poll: delta change → full refresh done")
                    else           Timber.v("Poll: no delta changes")
                }.onFailure { Timber.w(it, "Delta poll error") }
                delay(DELTA_INTERVAL_MS)
            }
        }

        // Full-tier: complete refresh every 60 s (catches room calendars + Google)
        viewModelScope.launch {
            delay(FULL_INTERVAL_MS)
            while (true) {
                runCatching {
                    refreshEvents()
                    Timber.d("Poll: full refresh (all calendars incl. rooms)")
                }.onFailure { Timber.w(it, "Full poll error") }
                delay(FULL_INTERVAL_MS)
            }
        }
    }

    /**
     * Ensures Graph + Google Calendar push subscriptions are active.
     * Called once per Dashboard session — no-op if already valid.
     */
    private fun setupPushSubscriptions() {
        viewModelScope.launch {
            val state = authManager.authState.value
            if (state !is AuthState.Authenticated) return@launch
            val msToken = if (state.hasMicrosoft) authManager.getFreshMicrosoftToken() else null
            runCatching {
                pushSubscriptionManager.ensureSubscriptions(
                    microsoftToken   = msToken,
                    isGoogleSignedIn = state.hasGoogle
                )
            }.onFailure { Timber.e(it, "Push subscription setup failed") }
        }
    }

    init {
        refresh()
        startRefreshLoop()
        setupPushSubscriptions()
        PushSubscriptionWorker.schedule(workManager)
    }

    companion object {
        private const val DELTA_INTERVAL_MS = 15_000L   // 15 s — primary calendar delta check
        private const val FULL_INTERVAL_MS  = 30_000L   // 30 s — full refresh (rooms + Google)
    }
}
