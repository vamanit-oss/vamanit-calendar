package com.vamanit.calendar.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vamanit.calendar.auth.AuthManager
import com.vamanit.calendar.auth.AuthState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Renews Microsoft Graph subscriptions and Google Calendar watch channels
 * before they expire.
 *
 * Scheduled daily — well within the 72-hour MS expiry and 7-day Google expiry.
 * Runs only when network is available.
 */
@HiltWorker
class PushSubscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params:  WorkerParameters,
    private val authManager:           AuthManager,
    private val pushSubscriptionManager: PushSubscriptionManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val state = authManager.authState.value
        if (state !is AuthState.Authenticated) {
            Timber.d("PushSubscriptionWorker: not authenticated — skipping")
            return Result.success()
        }

        val freshMsToken = if (state.hasMicrosoft) {
            runCatching { authManager.getFreshMicrosoftToken() }.getOrNull()
        } else null

        return try {
            pushSubscriptionManager.ensureSubscriptions(
                microsoftToken    = freshMsToken,
                isGoogleSignedIn  = state.hasGoogle
            )
            Timber.d("PushSubscriptionWorker: subscriptions renewed")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "PushSubscriptionWorker: renewal failed (attempt $runAttemptCount)")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "vamanit_push_subscription_renewal"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<PushSubscriptionWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
