package com.vamanit.calendar.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.*
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
        val SCOPES = arrayOf("User.Read", "Calendars.Read", "offline_access")
    }

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

    suspend fun acquireTokenSilent(): String? {
        val app = msalApp ?: return null
        val accounts = suspendCancellableCoroutine<List<IAccount>> { cont ->
            app.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
                override fun onTaskCompleted(result: List<IAccount>) = cont.resume(result)
                override fun onError(exception: MsalException) = cont.resume(emptyList())
            })
        }
        val account = accounts.firstOrNull() ?: return null
        return try {
            suspendCancellableCoroutine { cont ->
                val params = AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(account.authority)
                    .withScopes(SCOPES.toList())
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
        } catch (e: Exception) {
            Timber.w(e, "Silent token refresh failed")
            null
        }
    }

    fun isSignedIn(): Boolean = msalApp != null

    suspend fun signOut() {
        val app = msalApp ?: return
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
        Timber.d("Microsoft signed out")
    }
}
