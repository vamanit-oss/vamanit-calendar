package com.vamanit.calendar.push

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the FCM registration token in EncryptedSharedPreferences.
 *
 * The token is issued by Firebase and must be forwarded to your backend so it
 * can send push messages to this device.  Expose it via an API call or show it
 * in developer settings — the backend uses it with the FCM HTTP v1 API to
 * trigger a calendar refresh on this specific device.
 *
 * Token rotation: Firebase calls onNewToken() whenever the token changes
 * (reinstall, data-clear, etc.).  [VamanitFirebaseMessagingService] updates
 * this store and should also notify the backend of the new token.
 */
@Singleton
class FCMTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = EncryptedSharedPreferences.create(
        context, PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        Timber.d("FCM token saved (${token.take(12)}…)")
    }

    fun get(): String? = prefs.getString(KEY_TOKEN, null)

    fun clear() = prefs.edit().remove(KEY_TOKEN).apply()

    companion object {
        private const val PREFS_NAME = "fcm_token_store"
        private const val KEY_TOKEN  = "fcm_token"
    }
}
