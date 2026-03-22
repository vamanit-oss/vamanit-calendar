package com.vamanit.calendar.auth

import android.content.Context
import com.vamanit.calendar.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists OAuth client secrets that the user enters during first-time setup.
 *
 * Priority:
 *   1. Value saved in SharedPreferences (entered via SetupActivity)
 *   2. Build-time value from local.properties → BuildConfig (developer builds)
 *
 * This lets self-hosted users supply their own secrets at runtime without
 * needing to recompile the app.
 */
@Singleton
class SecretsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS                  = "secrets_store"
        const val KEY_SETUP_DONE                 = "setup_done"
        private const val KEY_PHONE_CLIENT_SECRET = "phone_client_secret"
        private const val KEY_TV_CLIENT_SECRET    = "tv_client_secret"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True when setup should be skipped / is already complete.
     *
     * Auto-passes when build-time secrets are baked in (developer / CI builds with
     * local.properties populated), so the wizard never appears for those installs.
     * Self-hosted users without build-time secrets still see the wizard on first run.
     */
    fun isSetupDone(): Boolean =
        prefs.getBoolean(KEY_SETUP_DONE, false) || hasBuildTimeSecrets()

    /** True when both Google client secrets were supplied at build time via local.properties. */
    fun hasBuildTimeSecrets(): Boolean =
        BuildConfig.PHONE_CLIENT_SECRET.isNotBlank() &&
        BuildConfig.TV_CLIENT_SECRET.isNotBlank()

    /** Mark setup complete without saving secrets (skip / already have build-time secrets). */
    fun markSetupDone() {
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /** Persist both secrets and mark setup as done in one write. */
    fun saveSecrets(phoneSecret: String, tvSecret: String) {
        prefs.edit()
            .putString(KEY_PHONE_CLIENT_SECRET, phoneSecret.trim())
            .putString(KEY_TV_CLIENT_SECRET, tvSecret.trim())
            .putBoolean(KEY_SETUP_DONE, true)
            .apply()
    }

    /**
     * Returns the Google Desktop OAuth client secret.
     * Prefers the runtime value entered in setup; falls back to the build-time BuildConfig field.
     */
    fun getPhoneClientSecret(): String =
        prefs.getString(KEY_PHONE_CLIENT_SECRET, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.PHONE_CLIENT_SECRET

    /**
     * Returns the Google TV/Limited Input OAuth client secret.
     * Prefers the runtime value entered in setup; falls back to the build-time BuildConfig field.
     */
    fun getTvClientSecret(): String =
        prefs.getString(KEY_TV_CLIENT_SECRET, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.TV_CLIENT_SECRET

    /** True if at least one runtime secret has been saved (setup was not just skipped). */
    fun hasRuntimeSecrets(): Boolean =
        !prefs.getString(KEY_PHONE_CLIENT_SECRET, null).isNullOrBlank() &&
        !prefs.getString(KEY_TV_CLIENT_SECRET, null).isNullOrBlank()
}
