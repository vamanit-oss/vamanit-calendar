package com.vamanit.calendar.ui.signin

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vamanit.calendar.auth.AuthManager
import com.vamanit.calendar.auth.AuthState
import com.vamanit.calendar.auth.DeviceCodeResponse
import com.vamanit.calendar.auth.GoogleAuthProvider
import com.vamanit.calendar.auth.MicrosoftAuthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed class DeviceFlowUiState {
    /** No device flow in progress — show sign-in buttons normally. */
    object Idle : DeviceFlowUiState()
    /** Requesting device code from Google — brief loading state. */
    object Loading : DeviceFlowUiState()
    /** Displaying user code and URL; polling for user to authorize on another device. */
    data class ShowingCode(
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int
    ) : DeviceFlowUiState()
    /** Terminal error — show message and re-enable the sign-in button. */
    data class Error(val message: String) : DeviceFlowUiState()
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authManager: AuthManager,
    val googleAuthProvider: GoogleAuthProvider,
    private val microsoftAuthProvider: MicrosoftAuthProvider
) : ViewModel() {

    val authState: StateFlow<AuthState> = authManager.authState

    private val _deviceFlowState = MutableStateFlow<DeviceFlowUiState>(DeviceFlowUiState.Idle)
    val deviceFlowState: StateFlow<DeviceFlowUiState> = _deviceFlowState.asStateFlow()

    /** One-shot error message to show in the UI (null = no error). */
    private val _signInError = MutableStateFlow<String?>(null)
    val signInError: StateFlow<String?> = _signInError.asStateFlow()

    private var deviceFlowJob: Job? = null

    fun isAlreadySignedIn(): Boolean = authManager.isAnyAccountSignedIn()

    // ── Phone: handle AppAuth callback ───────────────────────────────────────

    fun handleGoogleAuthResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return
        viewModelScope.launch {
            runCatching { googleAuthProvider.handleAuthResponse(data) }
                .onSuccess { token ->
                    authManager.onGoogleSignedIn(token)
                    Timber.d("Google sign-in success")
                }
                .onFailure { e ->
                    Timber.e(e, "Google sign-in failed")
                    _signInError.value = "Google sign-in failed: ${e.message}"
                }
        }
    }

    // ── TV: Device Authorization Grant flow ──────────────────────────────────

    fun startGoogleDeviceFlow() {
        deviceFlowJob?.cancel()
        _deviceFlowState.value = DeviceFlowUiState.Loading
        deviceFlowJob = viewModelScope.launch {
            // Step 1: request device code
            val response: DeviceCodeResponse = runCatching { googleAuthProvider.requestDeviceCode() }
                .getOrElse { e ->
                    Timber.e(e, "Device code request failed")
                    _deviceFlowState.value = DeviceFlowUiState.Error(
                        "Could not start sign-in: ${e.message}"
                    )
                    return@launch
                }

            // Step 2: show code to user and start polling
            _deviceFlowState.value = DeviceFlowUiState.ShowingCode(
                userCode        = response.userCode,
                verificationUrl = response.verificationUrl,
                expiresIn       = response.expiresIn
            )
            Timber.d("TV device flow — code: ${response.userCode}  url: ${response.verificationUrl}")

            runCatching {
                googleAuthProvider.pollForDeviceToken(response.deviceCode, response.interval)
            }
                .onSuccess { token ->
                    authManager.onGoogleSignedIn(token)
                    _deviceFlowState.value = DeviceFlowUiState.Idle
                    Timber.d("Google TV sign-in success")
                }
                .onFailure { e ->
                    Timber.e(e, "Device token polling failed")
                    val msg = when (e.message) {
                        "access_denied" -> "Sign-in was denied on the other device"
                        "expired_token" -> "Code expired — press Sign In to try again"
                        else            -> "Sign-in failed: ${e.message}"
                    }
                    _deviceFlowState.value = DeviceFlowUiState.Error(msg)
                    _signInError.value = msg
                }
        }
    }

    // ── Microsoft ────────────────────────────────────────────────────────────

    fun signInWithMicrosoft(activity: Activity) {
        viewModelScope.launch {
            runCatching {
                microsoftAuthProvider.initialize()
                microsoftAuthProvider.signIn(activity)
            }
                .onSuccess { token ->
                    authManager.onMicrosoftSignedIn(token)
                    Timber.d("Microsoft sign-in success")
                }
                .onFailure { e ->
                    Timber.e(e, "Microsoft sign-in failed")
                    _signInError.value = "Microsoft sign-in failed: ${e.message}"
                }
        }
    }

    fun clearSignInError() {
        _signInError.value = null
    }

    fun signOut() {
        authManager.signOutAll()
    }

    override fun onCleared() {
        super.onCleared()
        deviceFlowJob?.cancel()
    }
}
