package com.vamanit.calendar.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists push subscription/channel IDs and expiry times in SharedPreferences.
 *
 * Microsoft Graph calendar subscriptions expire after ~72 hours — [PushSubscriptionWorker]
 * renews them every 24 hours.  Google Calendar watches expire after 1 week — renewed every
 * 5 days.
 */
@Singleton
class SubscriptionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("push_subscriptions", Context.MODE_PRIVATE)

    // ── Microsoft Graph ───────────────────────────────────────────────────────

    var msSubscriptionId: String?
        get()      = prefs.getString(KEY_MS_SUB_ID, null)
        set(value) = prefs.edit().putString(KEY_MS_SUB_ID, value).apply()

    var msSubscriptionExpiry: Long
        get()      = prefs.getLong(KEY_MS_SUB_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_MS_SUB_EXPIRY, value).apply()

    fun isMsSubscriptionExpired(): Boolean =
        System.currentTimeMillis() > msSubscriptionExpiry - RENEW_BUFFER_MS

    fun clearMsSubscription() = prefs.edit()
        .remove(KEY_MS_SUB_ID)
        .remove(KEY_MS_SUB_EXPIRY)
        .apply()

    // ── Google Calendar channels ──────────────────────────────────────────────

    /** JSON string: Map<calendarId, channelId> */
    var googleChannels: String?
        get()      = prefs.getString(KEY_GOOGLE_CHANNELS, null)
        set(value) = prefs.edit().putString(KEY_GOOGLE_CHANNELS, value).apply()

    var googleChannelExpiry: Long
        get()      = prefs.getLong(KEY_GOOGLE_CHANNEL_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_GOOGLE_CHANNEL_EXPIRY, value).apply()

    fun areGoogleChannelsExpired(): Boolean =
        System.currentTimeMillis() > googleChannelExpiry - RENEW_BUFFER_MS

    fun clearGoogleChannels() = prefs.edit()
        .remove(KEY_GOOGLE_CHANNELS)
        .remove(KEY_GOOGLE_CHANNEL_EXPIRY)
        .apply()

    fun clearAll() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_MS_SUB_ID           = "ms_subscription_id"
        private const val KEY_MS_SUB_EXPIRY        = "ms_subscription_expiry_ms"
        private const val KEY_GOOGLE_CHANNELS      = "google_channels_json"
        private const val KEY_GOOGLE_CHANNEL_EXPIRY = "google_channel_expiry_ms"

        /** Renew this many ms before actual expiry. */
        private const val RENEW_BUFFER_MS = 6 * 60 * 60 * 1_000L  // 6 hours
    }
}
