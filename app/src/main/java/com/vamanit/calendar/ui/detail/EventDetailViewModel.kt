package com.vamanit.calendar.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.data.remote.GoogleCalendarDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    private val googleDataSource: GoogleCalendarDataSource
) : ViewModel() {

    private val _resourceState = MutableStateFlow<ResourceUiState>(ResourceUiState.Loading)
    val resourceState: StateFlow<ResourceUiState> = _resourceState

    fun loadResources() {
        viewModelScope.launch {
            _resourceState.value = ResourceUiState.Loading
            runCatching {
                val name      = googleDataSource.fetchUserDisplayName()
                val resources = googleDataSource.fetchDelegatedResources()
                ResourceUiState.Ready(name, resources)
            }.onSuccess { state ->
                _resourceState.value = state
            }.onFailure { e ->
                Timber.e(e, "Failed to load resource calendars")
                _resourceState.value = ResourceUiState.Error(e.message ?: "Failed to load rooms")
            }
        }
    }

}
