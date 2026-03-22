package com.vamanit.calendar.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.vamanit.calendar.BuildConfig
import net.openid.appauth.*
import net.openid.appauth.ClientSecretPost
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int
)

/**
 * Handles Google OAuth2 authentication.
 *
 * Phone: AppAuth browser flow (Apache 2.0 library) using a Desktop app OAuth client.
 *        Redirect URI: com.googleusercontent.apps.534654568144-qbbo6knmoqo3uqga35e0ipsq92d7dskl:/oauth2redirect
 *
 * TV:    Device Authorization Grant (RFC 8628) using a "TVs and Limited Input devices"
 *        OAuth client. The TV displays a short user code + verification URL; the user
 *        authenticates on any other device (phone, PC) and the TV polls for the token.
 *
 * Google Cloud Console setup:
 *  1. Enable Google Calendar API
 *  2. OAuth consent screen → add scope calendar.readonly, add test users
 *  3. Phone client: type "Desktop app" (534654568144-qbbo6knmoqo3uqga35e0ipsq92d7dskl) →
 *     reverse-scheme redirect automatically allowed for Desktop clients (no registration needed)
 *  4. TV client: type "TVs and Limited Input devices" (no redirect URI needed)
 */
@Singleton
class GoogleAuthProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Desktop type OAuth client — phone AppAuth redirect flow */
        const val PHONE_CLIENT_ID =
            "534654568144-qbbo6knmoqo3uqga35e0ipsq92d7dskl.apps.googleusercontent.com"

        // Injected at build time from local.properties → BuildConfig (never committed to git)
        private val PHONE_CLIENT_SECRET get() = BuildConfig.PHONE_CLIENT_SECRET

        /** TVs and Limited Input devices OAuth client — TV device flow */
        const val TV_CLIENT_ID =
            "534654568144-r4ljh9had1sr3d6e5fdgvpahst0ipglp.apps.googleusercontent.com"

        // Injected at build time from local.properties → BuildConfig (never committed to git)
        private val TV_CLIENT_SECRET get() = BuildConfig.TV_CLIENT_SECRET

        // Desktop clients: Google automatically allows the reverse client ID as redirect scheme
        const val REDIRECT_URI =
            "com.googleusercontent.apps.534654568144-qbbo6knmoqo3uqga35e0ipsq92d7dskl:/oauth2redirect"

        val SCOPES = listOf(
            "openid",
            "email",
            "profile",
            "https://www.googleapis.com/auth/calendar.readonly"
        )

        private const val DEVICE_AUTH_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL       = "https://oauth2.googleapis.com/token"

        private const val PREFS             = "google_auth"
        private const val KEY_ACCESS_TOKEN  = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    private val prefs       = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val authService = AuthorizationService(context)

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse(TOKEN_URL)
    )

    // ── Phone: AppAuth browser flow ──────────────────────────────────────────

    fun buildAuthIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            PHONE_CLIENT_ID,
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
        val error    = AuthorizationException.fromIntent(intent)
        if (error != null) throw error
        requireNotNull(response) { "No authorization response" }

        return suspendCancellableCoroutine { cont ->
            authService.performTokenRequest(
                response.createTokenExchangeRequest(),
                ClientSecretPost(PHONE_CLIENT_SECRET)
            ) { tokenResponse, tokenException ->
                if (tokenException != null) {
                    cont.resumeWithException(tokenException)
                } else {
                    val accessToken = tokenResponse?.accessToken
                        ?: run {
                            cont.resumeWithException(Exception("No access token"))
                            return@performTokenRequest
                        }
                    prefs.edit()
                        .putString(KEY_ACCESS_TOKEN, accessToken)
                        .putString(KEY_REFRESH_TOKEN, tokenResponse.refreshToken)
                        .apply()
                    cont.resume(accessToken)
                }
            }
        }
    }

    // ── TV: Device Authorization Grant (RFC 8628) ────────────────────────────

    /**
     * Step 1 — Request device and user codes from Google.
     * Show [DeviceCodeResponse.userCode] and [DeviceCodeResponse.verificationUrl] on screen,
     * then call [pollForDeviceToken] in a coroutine to await user authorization.
     */
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val body = "client_id=${TV_CLIENT_ID.enc()}&scope=${SCOPES.joinToString(" ").enc()}"
        val json = postForm(DEVICE_AUTH_URL, body)
        DeviceCodeResponse(
            deviceCode      = json.getString("device_code"),
            userCode        = json.getString("user_code"),
            verificationUrl = json.getString("verification_url"),
            expiresIn       = json.getInt("expires_in"),
            interval        = json.optInt("interval", 5)
        )
    }

    /**
     * Step 2 — Poll until the user completes authorization or the code expires.
     * Suspends between polls; cancel the coroutine to abort the flow.
     * On success, persists the access token and returns it.
     */
    suspend fun pollForDeviceToken(deviceCode: String, intervalSecs: Int): String {
        var pollInterval = intervalSecs.coerceAtLeast(5)
        while (true) {
            delay(pollInterval * 1000L)
            when (val result = withContext(Dispatchers.IO) { exchangeDeviceCode(deviceCode) }) {
                is PollResult.Token    -> {
                    prefs.edit().putString(KEY_ACCESS_TOKEN, result.token).apply()
                    Timber.d("Google TV device flow: token acquired")
                    return result.token
                }
                is PollResult.Pending  -> { /* keep polling */ }
                is PollResult.SlowDown -> { pollInterval += 5 }
                is PollResult.Error    -> throw Exception(result.error)
            }
        }
    }

    private sealed class PollResult {
        data class Token(val token: String) : PollResult()
        object Pending                      : PollResult()
        object SlowDown                     : PollResult()
        data class Error(val error: String) : PollResult()
    }

    private fun exchangeDeviceCode(deviceCode: String): PollResult {
        return try {
            val body = "client_id=${TV_CLIENT_ID.enc()}" +
                "&client_secret=${TV_CLIENT_SECRET.enc()}" +
                "&device_code=${deviceCode.enc()}" +
                "&grant_type=${"urn:ietf:params:oauth:grant-type:device_code".enc()}"
            val json = postForm(TOKEN_URL, body)
            when {
                json.has("access_token") ->
                    PollResult.Token(json.getString("access_token"))
                else -> when (json.optString("error")) {
                    "authorization_pending" -> PollResult.Pending
                    "slow_down"             -> PollResult.SlowDown
                    else                    -> PollResult.Error(json.optString("error", "unknown_error"))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Device code exchange error — treating as pending")
            PollResult.Pending // transient network errors: keep trying
        }
    }

    // ── Common ───────────────────────────────────────────────────────────────

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun isSignedIn(): Boolean = getAccessToken() != null

    fun buildCredential(): GoogleCredential =
        GoogleCredential().setAccessToken(getAccessToken())

    fun signOut() {
        prefs.edit().clear().apply()
        Timber.d("Google signed out")
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun String.enc() = URLEncoder.encode(this, "UTF-8")

    private fun postForm(urlStr: String, body: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput      = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout    = 10_000
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        val text = if (code < 400) conn.inputStream.bufferedReader().readText()
                   else conn.errorStream?.bufferedReader()?.readText() ?: "{}"
        conn.disconnect()
        return JSONObject(text)
    }
}
