package com.vamanit.calendar.domain.usecase

import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.repository.CalendarRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class GetUpcomingEventsUseCase @Inject constructor(
    private val repository: CalendarRepository
) {
    operator fun invoke(): StateFlow<List<CalendarEvent>> = repository.events
}
