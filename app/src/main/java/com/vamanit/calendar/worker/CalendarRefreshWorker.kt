package com.vamanit.calendar.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vamanit.calendar.domain.usecase.RefreshEventsUseCase
import com.vamanit.calendar.security.IntegrityHelper
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
        // Run a Play Integrity check before syncing calendar data.
        // Client-only mode: we log the verdict but always proceed with the refresh.
        runIntegrityCheck()

        return try {
            refreshEvents()
            Timber.d("Background refresh complete")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Background refresh failed (attempt $runAttemptCount)")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * Requests a Play Integrity token and logs the verdict.
     * Failures are swallowed — a failed integrity check must not prevent the
     * calendar from refreshing (enforcement happens server-side when ready).
     */
    private suspend fun runIntegrityCheck() {
        when (val result = IntegrityHelper.check(applicationContext, action = "calendar_sync")) {
            is IntegrityHelper.IntegrityResult.Pass  ->
                Timber.d("IntegrityHelper [calendar_sync] ✓ PASS")
            is IntegrityHelper.IntegrityResult.Warn  ->
                Timber.w("IntegrityHelper [calendar_sync] ⚠ WARN: ${result.reasons}")
            is IntegrityHelper.IntegrityResult.Error ->
                Timber.e(result.cause, "IntegrityHelper [calendar_sync] ✗ ERROR — continuing refresh")
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
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
