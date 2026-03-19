package com.vamanit.calendar.security

import android.content.Context
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps the Play Integrity API for client-side device/app attestation.
 *
 * In this client-only mode the token payload is decoded locally (no signature
 * verification) and the verdict is logged via Timber.  The app always proceeds
 * regardless of the result — move enforcement to server-side verification later
 * by forwarding the raw [IntegrityResult.token] to your backend and calling the
 * Play Integrity API there.
 *
 * Usage:
 *   val result = IntegrityHelper.check(context, action = "sign_in")
 *   // result is Pass / Warn / Error — inspect and log, always continue
 */
object IntegrityHelper {

    // ── Sealed result ─────────────────────────────────────────────────────────

    sealed class IntegrityResult {
        /** Raw JWT token returned by Play — forward this to your backend for server-side verification. */
        abstract val token: String?

        /** All verdicts look healthy. */
        data class Pass(override val token: String) : IntegrityResult()

        /**
         * The token was obtained but one or more verdict fields are below the
         * expected level (e.g. sideloaded APK, uncertified device).  In client-only
         * mode we log and continue — enforce this server-side when ready.
         */
        data class Warn(override val token: String, val reasons: List<String>) : IntegrityResult()

        /** Play Integrity API call itself failed (no network, Play Services unavailable, etc.). */
        data class Error(override val token: String? = null, val cause: Throwable) : IntegrityResult()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Request a Play Integrity token, decode its payload client-side and return
     * an [IntegrityResult].  Must be called from a coroutine.
     *
     * @param context  Any context — application context is used internally.
     * @param action   A short label included in the nonce and all log lines (e.g. "sign_in").
     */
    suspend fun check(context: Context, action: String = "default"): IntegrityResult {
        return try {
            val nonce = generateNonce(action)
            val manager = IntegrityManagerFactory.create(context.applicationContext)

            // Request the integrity token (network call to Play servers).
            val response = suspendCancellableCoroutine { cont ->
                val task = manager.requestIntegrityToken(
                    IntegrityTokenRequest.builder().setNonce(nonce).build()
                )
                task.addOnSuccessListener { cont.resume(it) }
                task.addOnFailureListener { cont.resumeWithException(it) }
            }

            val rawToken = response.token()
            val payload = decodePayload(rawToken)
            val warnings = evaluateVerdict(payload, action)

            if (warnings.isEmpty()) {
                Timber.d("IntegrityHelper [$action] ✓ PASS")
                IntegrityResult.Pass(rawToken)
            } else {
                Timber.w("IntegrityHelper [$action] ⚠ WARN: ${warnings.joinToString("; ")}")
                IntegrityResult.Warn(rawToken, warnings)
            }

        } catch (e: Exception) {
            Timber.e(e, "IntegrityHelper [$action] ✗ ERROR: integrity check failed")
            IntegrityResult.Error(cause = e)
        }
    }

    // ── Verdict evaluation ────────────────────────────────────────────────────

    private fun evaluateVerdict(payload: JsonObject?, action: String): List<String> {
        if (payload == null) return listOf("payload_decode_failed")

        val warnings = mutableListOf<String>()

        // — App integrity —
        val appVerdict = payload
            .getAsJsonObject("appIntegrity")
            ?.get("appRecognitionVerdict")?.asString
        Timber.d("IntegrityHelper [$action] appRecognitionVerdict=$appVerdict")
        if (appVerdict != null && appVerdict != "PLAY_RECOGNIZED") {
            warnings += "app_recognition=$appVerdict"
        }

        // — Device integrity —
        val deviceVerdicts = payload
            .getAsJsonObject("deviceIntegrity")
            ?.getAsJsonArray("deviceRecognitionVerdict")
            ?.map { it.asString }
            ?: emptyList()
        Timber.d("IntegrityHelper [$action] deviceRecognitionVerdict=$deviceVerdicts")
        when {
            deviceVerdicts.isEmpty()                               ->
                warnings += "device_integrity=NONE"
            !deviceVerdicts.contains("MEETS_DEVICE_INTEGRITY") ->
                warnings += "device_integrity=${deviceVerdicts.joinToString()}"
        }

        // — Licensing —
        val licenseVerdict = payload
            .getAsJsonObject("accountDetails")
            ?.get("appLicensingVerdict")?.asString
        Timber.d("IntegrityHelper [$action] appLicensingVerdict=$licenseVerdict")
        if (licenseVerdict == "UNLICENSED") {
            warnings += "licensing=UNLICENSED"
        }

        return warnings
    }

    // ── JWT payload decoding ──────────────────────────────────────────────────

    /**
     * Decodes the payload (second) segment of the Play Integrity JWT.
     * No signature verification is done here — this is intentional for
     * client-only mode.  Move to server-side verification for production enforcement.
     */
    private fun decodePayload(token: String): JsonObject? {
        return try {
            val payloadB64 = token.split(".").getOrNull(1) ?: return null
            val json = String(
                Base64.decode(payloadB64, Base64.URL_SAFE or Base64.NO_PADDING),
                Charsets.UTF_8
            )
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            Timber.w(e, "IntegrityHelper: failed to decode token payload")
            null
        }
    }

    // ── Nonce generation ──────────────────────────────────────────────────────

    /**
     * Generates a URL-safe base64 nonce: 24 random bytes + action label.
     * Play Integrity requires the nonce to be base64-encoded and at least 16 bytes.
     */
    private fun generateNonce(action: String): String {
        val randomBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val randomB64 = Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP)
        val raw = "$randomB64::$action"
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
