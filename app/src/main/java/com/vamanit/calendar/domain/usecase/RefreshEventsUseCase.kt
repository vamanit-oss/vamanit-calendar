package com.vamanit.calendar.domain.usecase

import com.vamanit.calendar.data.repository.CalendarRepository
import javax.inject.Inject

class RefreshEventsUseCase @Inject constructor(
    private val repository: CalendarRepository
) {
    suspend operator fun invoke() = repository.refresh()
}
