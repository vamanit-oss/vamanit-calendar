package com.vamanit.calendar.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles Google OAuth2 authentication using AppAuth (Apache 2.0).
 * Does NOT use proprietary com.google.android.gms:play-services-auth.
 *
 * Setup required in Google Cloud Console:
 *  1. Enable Google Calendar API
 *  2. Create OAuth 2.0 client ID → type: Web Application
 *     (Android apps use web client ID with custom scheme redirect)
 *  3. Authorized redirect URI: com.vamanit.calendar:/oauth2redirect
 *  4. Copy the client ID + client secret below (or via BuildConfig)
 */
@Singleton
class GoogleAuthProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Replace with your Google Cloud Console OAuth2 Web Client ID
        const val CLIENT_ID = "YOUR_GOOGLE_CLIENT_ID"
        // For installed apps, client secret may be empty or set in Cloud Console
        const val CLIENT_SECRET = ""
        const val REDIRECT_URI = "com.vamanit.calendar:/oauth2redirect"
        val SCOPES = listOf(
            "openid",
            "email",
            "profile",
            "https://www.googleapis.com/auth/calendar.readonly"
        )
        private const val PREFS = "google_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val authService = AuthorizationService(context)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )

    fun buildAuthIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI)
        )
            .setScopes(SCOPES)
            .setPrompt("select_account")
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    suspend fun handleAuthResponse(intent: Intent): String {
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        if (error != null) throw error
        requireNotNull(response) { "No authorization response" }

        return suspendCancellableCoroutine { cont ->
            authService.performTokenRequest(
                response.createTokenExchangeRequest()
            ) { tokenResponse, tokenException ->
                if (tokenException != null) {
                    cont.resumeWithException(tokenException)
                } else {
                    val accessToken = tokenResponse?.accessToken
                        ?: run { cont.resumeWithException(Exception("No access token")); return@performTokenRequest }
                    prefs.edit()
                        .putString(KEY_ACCESS_TOKEN, accessToken)
                        .putString(KEY_REFRESH_TOKEN, tokenResponse.refreshToken)
                        .apply()
                    cont.resume(accessToken)
                }
            }
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun isSignedIn(): Boolean = getAccessToken() != null

    fun buildCredential(): GoogleCredential =
        GoogleCredential().setAccessToken(getAccessToken())

    fun signOut() {
        prefs.edit().clear().apply()
        Timber.d("Google signed out")
    }
}
