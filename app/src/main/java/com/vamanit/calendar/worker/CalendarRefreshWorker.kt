package com.vamanit.calendar.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vamanit.calendar.domain.usecase.RefreshEventsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class CalendarRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refreshEvents: RefreshEventsUseCase
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            refreshEvents()
            Timber.d("Background refresh complete")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Background refresh failed (attempt $runAttemptCount)")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "vamanit_calendar_refresh"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CalendarRefreshWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
