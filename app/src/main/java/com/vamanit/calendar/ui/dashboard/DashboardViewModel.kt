package com.vamanit.calendar.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.domain.usecase.GetUpcomingEventsUseCase
import com.vamanit.calendar.domain.usecase.RefreshEventsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    getUpcomingEvents: GetUpcomingEventsUseCase,
    private val refreshEvents: RefreshEventsUseCase
) : ViewModel() {

    val events: StateFlow<List<CalendarEvent>> = getUpcomingEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch {
            runCatching { refreshEvents() }
                .onFailure { Timber.e(it, "Manual refresh failed") }
        }
    }

    init {
        refresh()
    }
}
