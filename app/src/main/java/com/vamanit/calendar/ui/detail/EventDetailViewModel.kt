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
            runCatching {
                // ── Google: display name + delegated resource calendars ──
                val name            = googleDataSource.fetchUserDisplayName()
                val googleResources = googleDataSource.fetchDelegatedResources()

                // ── Microsoft: calendars where user is manager/delegate ──
                // Guard with isSignedIn() first — avoids touching MSAL (which can hang
                // on initialize()) when the user has never signed in with Microsoft.
                // withTimeout ensures a stuck MSAL callback never blocks the spinner.
                val msResources: List<CalendarResource> = if (!microsoftAuth.isSignedIn()) {
                    emptyList()
                } else {
                    runCatching {
                        withTimeout(10_000) {
                            val token = microsoftAuth.acquireTokenSilent()
                            if (token != null) microsoftDataSource.fetchManagedResources(token)
                            else emptyList()
                        }
                    }.onFailure { Timber.w(it, "Microsoft managed resources failed — skipping") }
                        .getOrDefault(emptyList())
                }

                ResourceUiState.Ready(name, googleResources + msResources)
            }.onSuccess { state ->
                _resourceState.value = state
            }.onFailure { e ->
                Timber.e(e, "Failed to load resource calendars")
                _resourceState.value = ResourceUiState.Error(e.message ?: "Failed to load rooms")
            }
        }
    }
}
