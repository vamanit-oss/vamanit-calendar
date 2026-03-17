package com.vamanit.calendar.auth

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Loading : AuthState()
    data class Authenticated(
        val googleToken: String? = null,
        val microsoftToken: String? = null
    ) : AuthState() {
        val hasGoogle: Boolean get() = googleToken != null
        val hasMicrosoft: Boolean get() = microsoftToken != null
        val hasAnyAccount: Boolean get() = hasGoogle || hasMicrosoft
    }
    data class Error(val message: String) : AuthState()
}
