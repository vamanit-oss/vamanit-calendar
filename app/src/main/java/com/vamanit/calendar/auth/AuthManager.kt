package com.vamanit.calendar.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthManager @Inject constructor(
    private val google: GoogleAuthProvider,
    private val microsoft: MicrosoftAuthProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        refreshState()
    }

    fun refreshState() {
        scope.launch {
            val googleToken = google.getAccessToken()
            // acquireTokenSilent now auto-initializes MSAL, enabling silent restore on app restart
            val msToken = runCatching { microsoft.acquireTokenSilent() }.getOrNull()
            val newState = if (googleToken != null || msToken != null) {
                AuthState.Authenticated(googleToken = googleToken, microsoftToken = msToken)
            } else {
                AuthState.Unauthenticated
            }
            // Never overwrite a freshly set Authenticated state (e.g. from interactive sign-in)
            // with Unauthenticated from a racing silent-refresh that completed too late.
            val current = _authState.value
            if (current !is AuthState.Authenticated || newState is AuthState.Authenticated) {
                _authState.emit(newState)
            }
            Timber.d("Auth refresh → $newState (current was $current)")
        }
    }

    fun onGoogleSignedIn(token: String) {
        val current = _authState.value
        val msToken = if (current is AuthState.Authenticated) current.microsoftToken else null
        _authState.value = AuthState.Authenticated(googleToken = token, microsoftToken = msToken)
    }

    fun onMicrosoftSignedIn(token: String) {
        val current = _authState.value
        val googleToken = if (current is AuthState.Authenticated) current.googleToken else null
        _authState.value = AuthState.Authenticated(googleToken = googleToken, microsoftToken = token)
    }

    /**
     * Always fetches a fresh Microsoft access token via MSAL's silent refresh
     * (uses the stored refresh token, no browser required).
     *
     * Also updates the shared authState so all observers see the latest token.
     * Call this before every Graph API request to avoid stale-token 401s.
     */
    suspend fun getFreshMicrosoftToken(): String? {
        val token = runCatching { microsoft.acquireTokenSilent() }.getOrNull()
        if (token != null) {
            val current    = _authState.value
            val googleTok  = if (current is AuthState.Authenticated) current.googleToken else null
            _authState.emit(AuthState.Authenticated(googleToken = googleTok, microsoftToken = token))
        }
        return token
    }

    fun signOutAll() {
        google.signOut()
        scope.launch {
            runCatching { microsoft.signOut() }
            _authState.emit(AuthState.Unauthenticated)
        }
    }

    fun isAnyAccountSignedIn(): Boolean =
        google.isSignedIn() || microsoft.isSignedIn()
}
