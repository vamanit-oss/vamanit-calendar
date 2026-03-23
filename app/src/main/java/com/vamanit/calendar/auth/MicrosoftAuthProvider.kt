package com.vamanit.calendar.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalException
import com.vamanit.calendar.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handles Microsoft 365 / Outlook authentication using MSAL (MIT License).
 *
 * Setup required in Azure Portal:
 *  1. Register app → Accounts in any org + personal Microsoft
 *  2. Add redirect URI (Public client/native):
 *     msauth://com.vamanit.calendar/{BASE64_KEYSTORE_HASH}
 *  3. API permissions → Microsoft Graph → Delegated:
 *     User.Read, Calendars.Read, offline_access
 *  4. Copy Application (client) ID → res/raw/msal_config.json
 *
 * TV note: msal_config.json sets "authorization_user_agent": "WEBVIEW"
 * for Android TV where Chrome/Custom Tabs may not be available.
 */
@Singleton
class MicrosoftAuthProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val SCOPES = arrayOf("User.Read", "User.ReadBasic.All", "Calendars.Read", "offline_access")
        private const val PREFS = "microsoft_auth"
        private const val KEY_HAS_ACCOUNT = "has_account"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var msalApp: IMultipleAccountPublicClientApplication? = null

    suspend fun initialize() {
        if (msalApp != null) return
        msalApp = suspendCancellableCoroutine { cont ->
            PublicClientApplication.createMultipleAccountPublicClientApplication(
                context,
                R.raw.msal_config,
                object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                    override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                        cont.resume(application)
                    }
                    override fun onError(exception: MsalException) {
                        Timber.e(exception, "MSAL init failed")
                        cont.resumeWithException(exception)
                    }
                }
            )
        }
    }

    suspend fun signIn(activity: Activity): String = suspendCancellableCoroutine { cont ->
        val app = msalApp ?: run {
            cont.resumeWithException(IllegalStateException("MSAL not initialized"))
            return@suspendCancellableCoroutine
        }
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(SCOPES.toList())
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    // Persist account flag so isSignedIn() works on next launch
                    prefs.edit().putBoolean(KEY_HAS_ACCOUNT, true).apply()
                    cont.resume(result.accessToken)
                }
                override fun onError(exception: MsalException) {
                    cont.resumeWithException(exception)
                }
                override fun onCancel() {
                    cont.resumeWithException(Exception("Microsoft sign-in cancelled"))
                }
            })
            .build()
        app.acquireToken(params)
    }

    /**
     * Attempts a silent token refresh. Auto-initializes MSAL if not yet done so this
     * can be called from AuthManager.refreshState() on app restart.
     */
    suspend fun acquireTokenSilent(): String? {
        // Auto-initialize so callers don't need to call initialize() first
        if (msalApp == null) {
            runCatching { initialize() }.onFailure {
                Timber.w(it, "MSAL init failed during silent refresh")
                return null
            }
        }
        val app = msalApp ?: return null
        val accounts = suspendCancellableCoroutine<List<IAccount>> { cont ->
            app.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
                override fun onTaskCompleted(result: List<IAccount>) = cont.resume(result)
                override fun onError(exception: MsalException) = cont.resume(emptyList())
            })
        }
        val account = accounts.firstOrNull() ?: return null
        val token = try {
            silentWithScopes(app, account, SCOPES.toList())
        } catch (e: MsalDeclinedScopeException) {
            // AAD returns offline_access implicitly (as a refresh token) but omits it from
            // the scope list, causing MSAL 5.x to throw MsalDeclinedScopeException.
            // If the required scopes (User.Read, Calendars.Read) were granted, retry using
            // only the granted scopes so we get a valid access token.
            val granted = e.grantedScopes
            Timber.w("Silent refresh: offline_access not in scope response (implicit grant); retrying with granted scopes: $granted")
            if (granted.isNullOrEmpty()) null
            else runCatching { silentWithScopes(app, account, granted) }.getOrNull()
        } catch (e: Exception) {
            Timber.w(e, "Silent token refresh failed")
            null
        }
        // Sync the persisted flag with MSAL's actual account state.
        // This covers the MsalDeclinedScopeException sign-in path where onError fires instead
        // of onSuccess, so KEY_HAS_ACCOUNT is never set by signIn() — yet the account IS in
        // MSAL's cache and silent refresh succeeds.
        if (token != null) prefs.edit().putBoolean(KEY_HAS_ACCOUNT, true).apply()
        return token
    }

    private suspend fun silentWithScopes(
        app: IMultipleAccountPublicClientApplication,
        account: IAccount,
        scopes: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        val params = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(account.authority)
            .withScopes(scopes)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    cont.resume(result.accessToken)
                }
                override fun onError(exception: MsalException) {
                    cont.resumeWithException(exception)
                }
            })
            .build()
        app.acquireTokenSilentAsync(params)
    }

    /**
     * Returns true if the user has previously signed in with Microsoft.
     * Checked via a persisted flag in SharedPreferences so it works before MSAL is initialized.
     */
    fun isSignedIn(): Boolean = prefs.getBoolean(KEY_HAS_ACCOUNT, false)

    suspend fun signOut() {
        val app = msalApp ?: run {
            // Even if MSAL isn't initialized, clear the persisted flag
            prefs.edit().putBoolean(KEY_HAS_ACCOUNT, false).apply()
            return
        }
        val accounts = suspendCancellableCoroutine<List<IAccount>> { cont ->
            app.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
                override fun onTaskCompleted(result: List<IAccount>) = cont.resume(result)
                override fun onError(exception: MsalException) = cont.resume(emptyList())
            })
        }
        accounts.forEach { account ->
            suspendCancellableCoroutine { cont ->
                app.removeAccount(account, object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                    override fun onRemoved() = cont.resume(Unit)
                    override fun onError(exception: MsalException) = cont.resume(Unit)
                })
            }
        }
        prefs.edit().putBoolean(KEY_HAS_ACCOUNT, false).apply()
        Timber.d("Microsoft signed out")
    }
}
