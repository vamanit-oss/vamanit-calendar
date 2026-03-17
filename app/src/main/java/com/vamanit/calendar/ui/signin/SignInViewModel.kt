package com.vamanit.calendar.ui.signin

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vamanit.calendar.auth.AuthManager
import com.vamanit.calendar.auth.AuthState
import com.vamanit.calendar.auth.GoogleAuthProvider
import com.vamanit.calendar.auth.MicrosoftAuthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authManager: AuthManager,
    val googleAuthProvider: GoogleAuthProvider,
    private val microsoftAuthProvider: MicrosoftAuthProvider
) : ViewModel() {

    val authState: StateFlow<AuthState> = authManager.authState

    fun isAlreadySignedIn(): Boolean = authManager.isAnyAccountSignedIn()

    fun handleGoogleAuthResult(resultCode: Int, data: Intent?) {
        if (data == null) return
        viewModelScope.launch {
            runCatching { googleAuthProvider.handleAuthResponse(data) }
                .onSuccess { token ->
                    authManager.onGoogleSignedIn(token)
                    Timber.d("Google sign-in success")
                }
                .onFailure { Timber.e(it, "Google sign-in failed") }
        }
    }

    fun signInWithMicrosoft(activity: android.app.Activity) {
        viewModelScope.launch {
            runCatching {
                microsoftAuthProvider.initialize()
                microsoftAuthProvider.signIn(activity)
            }
                .onSuccess { token ->
                    authManager.onMicrosoftSignedIn(token)
                    Timber.d("Microsoft sign-in success")
                }
                .onFailure { Timber.e(it, "Microsoft sign-in failed") }
        }
    }

    fun signOut() {
        authManager.signOutAll()
    }
}
