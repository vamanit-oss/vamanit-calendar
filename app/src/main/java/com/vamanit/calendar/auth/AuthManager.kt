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
        val googleToken = google.getAccessToken()
        scope.launch {
            val msToken = runCatching { microsoft.acquireTokenSilent() }.getOrNull()
            val state = if (googleToken != null || msToken != null) {
                AuthState.Authenticated(googleToken = googleToken, microsoftToken = msToken)
            } else {
                AuthState.Unauthenticated
            }
            _authState.emit(state)
            Timber.d("Auth state: $state")
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
