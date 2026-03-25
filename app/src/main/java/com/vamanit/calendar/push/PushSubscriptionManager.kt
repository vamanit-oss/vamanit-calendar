package com.vamanit.calendar.push

import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.vamanit.calendar.BuildConfig
import com.vamanit.calendar.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and renews push-notification subscriptions for both calendar providers.
 *
 * ── Microsoft Graph ───────────────────────────────────────────────────────────
 * Creates a change-notification subscription on /me/events. When a calendar
 * event changes, Graph POSTs to the Cloud Function, which looks up the FCM
 * token and sends a silent push to this device.
 *
 * Subscription lifetime: up to 4,320 min (72 h). Renewed by [PushSubscriptionWorker].
 *
 * ── Google Calendar ───────────────────────────────────────────────────────────
 * Creates a watch channel per calendar via the Calendar Events.watch API.
 * The FCM token is embedded as the channel "token" so the Cloud Function
 * can retrieve it from the X-Goog-Channel-Token header without a DB lookup.
 *
 * Watch lifetime: up to 7 days. Renewed by [PushSubscriptionWorker].
 *
 * ── Backend URL ───────────────────────────────────────────────────────────────
 * Set BACKEND_WEBHOOK_BASE_URL in local.properties:
 *   BACKEND_WEBHOOK_BASE_URL=https://us-central1-<project>.cloudfunctions.net
 */
@Singleton
class PushSubscriptionManager @Inject constructor(
    private val httpClient:    OkHttpClient,
    private val gson:          Gson,
    private val subscriptionStore: SubscriptionStore,
    private val tokenStore:    FCMTokenStore,
    private val googleAuth:    GoogleAuthProvider
) {
    companion object {
        private const val GRAPH_BASE  = "https://graph.microsoft.com/v1.0"
        private val JSON_MEDIA        = "application/json; charset=utf-8".toMediaType()
        // MS subscription lifetime: 72 h (4320 min) — the API maximum for calendar
        private const val MS_EXPIRY_MINUTES = 4320L
        // Google watch lifetime: 6 days in ms
        private const val GOOGLE_EXPIRY_MS  = 6L * 24 * 60 * 60 * 1_000
    }

    private val backendBase get() = BuildConfig.BACKEND_WEBHOOK_BASE_URL.trimEnd('/')

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sets up (or renews) all push subscriptions.
     * Safe to call on every app launch — skips setup if subscriptions are still valid.
     */
    suspend fun ensureSubscriptions(microsoftToken: String?, isGoogleSignedIn: Boolean) {
        val fcmToken = getFcmToken() ?: run {
            Timber.w("PushSubscriptions: FCM token unavailable — skipping")
            return
        }
        if (backendBase.isEmpty()) {
            Timber.w("PushSubscriptions: BACKEND_WEBHOOK_BASE_URL not set — skipping")
            return
        }

        if (microsoftToken != null) {
            runCatching { ensureMicrosoftSubscription(microsoftToken, fcmToken) }
                .onFailure { Timber.e(it, "MS subscription setup failed") }
        }
        if (isGoogleSignedIn) {
            runCatching { ensureGoogleWatches(fcmToken) }
                .onFailure { Timber.e(it, "Google watch setup failed") }
        }
    }

    /** Tears down all subscriptions and clears stored IDs (call on sign-out). */
    suspend fun cancelAll(microsoftToken: String?) {
        microsoftToken?.let {
            subscriptionStore.msSubscriptionId?.let { subId ->
                runCatching { deleteMicrosoftSubscription(it, subId) }
                    .onFailure { Timber.w(it, "MS subscription delete failed") }
            }
        }
        subscriptionStore.clearAll()
    }

    // ── Microsoft ─────────────────────────────────────────────────────────────

    private suspend fun ensureMicrosoftSubscription(msToken: String, fcmToken: String) {
        val existingId = subscriptionStore.msSubscriptionId
        if (existingId != null && !subscriptionStore.isMsSubscriptionExpired()) {
            Timber.d("MS subscription $existingId still valid — skipping")
            return
        }
        // Create a fresh subscription
        val expiry   = ZonedDateTime.now().plusMinutes(MS_EXPIRY_MINUTES)
        val expiryStr = expiry.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val webhookUrl = "$backendBase/microsoftWebhook"

        val body = JsonObject().apply {
            addProperty("changeType",                "created,updated,deleted")
            addProperty("notificationUrl",           webhookUrl)
            addProperty("resource",                  "me/events")
            addProperty("expirationDateTime",        expiryStr)
            addProperty("clientState",               "vamanit-calendar")
        }

        val subscriptionId = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$GRAPH_BASE/subscriptions")
                .addHeader("Authorization", "Bearer $msToken")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(body).toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.e("MS subscription create failed: ${resp.code} ${resp.body?.string()}")
                    return@withContext null
                }
                val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                json.get("id")?.asString
            }
        } ?: return

        subscriptionStore.msSubscriptionId    = subscriptionId
        subscriptionStore.msSubscriptionExpiry = expiry.toInstant().toEpochMilli()

        // Tell the Cloud Function which FCM token to push for this subscription
        registerWithBackend(
            url  = "$backendBase/registerMicrosoftSubscription",
            body = mapOf("subscriptionId" to subscriptionId, "fcmToken" to fcmToken)
        )
        Timber.d("MS subscription created: $subscriptionId (expires $expiryStr)")
    }

    private suspend fun deleteMicrosoftSubscription(msToken: String, subscriptionId: String) =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$GRAPH_BASE/subscriptions/$subscriptionId")
                .addHeader("Authorization", "Bearer $msToken")
                .delete()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                Timber.d("MS subscription delete → ${resp.code}")
            }
        }

    // ── Google Calendar ───────────────────────────────────────────────────────

    private suspend fun ensureGoogleWatches(fcmToken: String) {
        if (!subscriptionStore.areGoogleChannelsExpired()) {
            Timber.d("Google watches still valid — skipping")
            return
        }
        val service  = googleAuth.buildCalendarService()
        val expiry   = System.currentTimeMillis() + GOOGLE_EXPIRY_MS
        val channels = mutableMapOf<String, String>()   // calendarId → channelId

        withContext(Dispatchers.IO) {
            val calList = service.calendarList().list().execute()
            calList.items?.forEach { cal ->
                runCatching {
                    val channelId = UUID.randomUUID().toString()
                    val channel   = com.google.api.services.calendar.model.Channel().apply {
                        id         = channelId
                        type       = "web_hook"
                        address    = "$backendBase/googleWebhook"
                        token      = fcmToken            // embedded so CF reads it from header
                        expiration = expiry
                    }
                    service.events().watch(cal.id, channel).execute()
                    channels[cal.id] = channelId

                    // Also register in Firestore (fallback path)
                    registerWithBackend(
                        url  = "$backendBase/registerGoogleChannel",
                        body = mapOf(
                            "channelId"  to channelId,
                            "calendarId" to cal.id,
                            "fcmToken"   to fcmToken
                        )
                    )
                    Timber.d("Google watch created for ${cal.id}: $channelId")
                }.onFailure { Timber.w(it, "Google watch failed for ${cal.id}") }
            }
        }

        subscriptionStore.googleChannels     = gson.toJson(channels)
        subscriptionStore.googleChannelExpiry = expiry
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun registerWithBackend(url: String, body: Map<String, String>) =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .post(gson.toJson(body).toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) Timber.w("Backend register failed: ${resp.code}")
                }
            }.onFailure { Timber.e(it, "Backend registration error") }
        }

    private suspend fun getFcmToken(): String? {
        // Return cached token first
        tokenStore.get()?.let { return it }
        // Fetch fresh from Firebase
        return runCatching {
            FirebaseMessaging.getInstance().token.await().also { tokenStore.save(it) }
        }.getOrNull()
    }
}
