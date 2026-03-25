package com.vamanit.calendar.push

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vamanit.calendar.data.repository.CalendarRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service — replaces the 1-minute polling loop with
 * true server-push.
 *
 * ── How it works ──────────────────────────────────────────────────────────────
 * 1. The device registers with FCM and receives a token (see [FCMTokenStore]).
 * 2. Your backend (or a cloud function subscribed to Microsoft Graph / Google
 *    Calendar webhooks) sends an FCM data message to this token when calendar
 *    events change.
 * 3. [onMessageReceived] fires — we call [CalendarRepository.refresh()] which
 *    fetches only the delta (changed events) from both APIs.
 * 4. The [StateFlow] in the repository emits the updated list and all UI
 *    screens update immediately — zero polling, zero battery waste.
 *
 * ── Message format (send from your backend) ──────────────────────────────────
 * FCM data payload (NOT notification payload — so it wakes the app silently):
 * {
 *   "data": {
 *     "type": "calendar_refresh",           // triggers full refresh
 *     "source": "microsoft" | "google",     // optional — future per-source refresh
 *     "event_id": "<optional event id>"     // optional — for targeted invalidation
 *   }
 * }
 *
 * ── Backend setup summary ─────────────────────────────────────────────────────
 * Microsoft:  Create a Graph subscription on /me/calendar/events → webhook →
 *             Cloud Function → FCM HTTP v1 send to device token.
 * Google:     Set up a Google Calendar push notification channel → webhook →
 *             Cloud Function → FCM HTTP v1 send to device token.
 * Token:      Read [FCMTokenStore.get()] and POST it to your backend on login
 *             and whenever [onNewToken] fires.
 */
@AndroidEntryPoint
class VamanitFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var calendarRepository: CalendarRepository
    @Inject lateinit var tokenStore: FCMTokenStore
    @Inject lateinit var subscriptionStore: SubscriptionStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Token lifecycle ────────────────────────────────────────────────────────

    /**
     * Called when FCM issues a new registration token (first launch, reinstall,
     * token rotation).  Persist it so the app can forward it to the backend.
     */
    override fun onNewToken(token: String) {
        Timber.d("FCM: token rotated — clearing old subscriptions and scheduling re-registration")
        tokenStore.save(token)
        // Old subscription → FCM token mappings are now stale; clear so ensureSubscriptions()
        // rebuilds them with the new token on next launch or worker run.
        subscriptionStore.clearAll()
        // Kick off an immediate re-subscription attempt in the background.
        WorkManager.getInstance(applicationContext).enqueue(
            OneTimeWorkRequestBuilder<PushSubscriptionWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
    }

    // ── Message handling ───────────────────────────────────────────────────────

    /**
     * Handles incoming FCM data messages.  Runs on a background thread provided
     * by the Firebase SDK.  We launch a coroutine so the refresh can suspend
     * without blocking the binder thread.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val type   = message.data["type"]   ?: "calendar_refresh"
        val source = message.data["source"] ?: "all"
        Timber.d("FCM: message received — type=$type source=$source")

        when (type) {
            "calendar_refresh",
            "calendar_event_changed",
            "calendar_event_created",
            "calendar_event_deleted" -> triggerRefresh(source)

            else -> Timber.w("FCM: unknown message type '$type' — ignored")
        }
    }

    private fun triggerRefresh(source: String) {
        serviceScope.launch {
            Timber.d("FCM: triggering calendar refresh (source=$source)")
            runCatching { calendarRepository.refresh() }
                .onSuccess { Timber.d("FCM: refresh complete") }
                .onFailure { Timber.e(it, "FCM: refresh failed") }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
