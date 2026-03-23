package com.vamanit.calendar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vamanit.calendar.auth.MicrosoftAuthProvider
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.data.remote.GoogleCalendarDataSource
import com.vamanit.calendar.data.remote.MicrosoftCalendarDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

sealed class ResourceUiState {
    object Loading : ResourceUiState()
    data class Ready(
        val userDisplayName: String,
        val resources: List<CalendarResource>
    ) : ResourceUiState()
    data class Error(val message: String) : ResourceUiState()
}

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val googleDataSource: GoogleCalendarDataSource,
    private val microsoftDataSource: MicrosoftCalendarDataSource,
    private val microsoftAuth: MicrosoftAuthProvider
) : ViewModel() {

    private val _resourceState = MutableStateFlow<ResourceUiState>(ResourceUiState.Loading)
    val resourceState: StateFlow<ResourceUiState> = _resourceState

    fun loadResources() {
        viewModelScope.launch {
            _resourceState.value = ResourceUiState.Loading

            // ── Google: display name + delegated resource calendars ──
            // Wrapped independently — a Google failure (e.g. not signed in) must NOT
            // block Microsoft resources from loading.
            var userName        = "My Calendar"
            var googleResources = emptyList<CalendarResource>()
            runCatching {
                userName        = googleDataSource.fetchUserDisplayName()
                googleResources = googleDataSource.fetchDelegatedResources()
            }.onFailure { Timber.w(it, "Google resources unavailable (not signed in?)") }

            // ── Microsoft: display name fallback + managed/delegate calendars ──
            // Guard with isSignedIn() first — avoids touching MSAL when not signed in.
            // withTimeout ensures a stuck MSAL callback never blocks the spinner.
            var msResources = emptyList<CalendarResource>()
            if (microsoftAuth.isSignedIn()) {
                runCatching {
                    withTimeout(10_000) {
                        val token = microsoftAuth.acquireTokenSilent() ?: return@withTimeout
                        // Use Microsoft display name if Google didn't provide one
                        if (userName == "My Calendar") {
                            userName = microsoftDataSource.fetchUserDisplayName(token)
                        }
                        msResources = microsoftDataSource.fetchManagedResources(token)
                    }
                }.onFailure { Timber.w(it, "Microsoft resources failed — skipping") }
            }

            _resourceState.value = ResourceUiState.Ready(userName, googleResources + msResources)
        }
    }
}
