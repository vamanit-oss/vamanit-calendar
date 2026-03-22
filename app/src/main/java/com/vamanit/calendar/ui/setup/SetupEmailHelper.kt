package com.vamanit.calendar.ui.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vamanit.calendar.auth.GoogleAuthProvider

/**
 * Composes and launches a pre-filled email containing step-by-step OAuth setup
 * instructions for both Google Cloud Console and Microsoft Azure Portal.
 *
 * Called from:
 *  • [SetupActivity] — via the "Email Setup Instructions" button in the wizard
 *  • [com.vamanit.calendar.ui.signin.SignInActivity] — when sign-in fails with an
 *    authorization error, offering the user a one-tap way to get help on their desktop
 */
object SetupEmailHelper {

    fun sendSetupInstructions(context: Context, toAddress: String = "") {
        val subject = "Vamanit Calendar — OAuth Setup Instructions"
        val body    = buildEmailBody()

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL,   if (toAddress.isNotBlank()) arrayOf(toAddress) else emptyArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT,    body)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(Intent.createChooser(intent, "Send setup instructions via…"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Email body
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildEmailBody(): String = """
Hi,

To complete the Vamanit Calendar setup you need to register the client IDs below
in Google Cloud Console and (optionally) Microsoft Azure Portal, then paste the
Google client secrets back into the app's Setup Wizard.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 PART 1 — GOOGLE CLOUD CONSOLE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Open: https://console.cloud.google.com

Step 1 — Enable the Calendar API
  • APIs & Services → Library → search "Google Calendar API" → Enable

Step 2 — Configure the OAuth Consent Screen
  • APIs & Services → OAuth consent screen
  • User Type: External
  • Add scopes: calendar.readonly, openid, email, profile
  • Add your Google account as a Test User

Step 3 — Phone (Desktop) OAuth Client
  • Credentials → Create OAuth client ID
  • Application type: Desktop app
  • Name: Vamanit Calendar Phone (or any name you like)
  • After creation, click ⬇ Download JSON — the "client_secret" field is what
    you paste into the app as the Phone Client Secret.

  Pre-configured Phone Client ID (already in the app):
    ${GoogleAuthProvider.PHONE_CLIENT_ID}

  Redirect URI (automatically allowed for Desktop clients — no action needed):
    ${GoogleAuthProvider.REDIRECT_URI}

Step 4 — TV (Limited Input) OAuth Client
  • Credentials → Create OAuth client ID
  • Application type: TVs and Limited Input devices
  • Name: Vamanit Calendar TV (or any name you like)
  • After creation, click ⬇ Download JSON — the "client_secret" field is what
    you paste into the app as the TV Client Secret.

  Pre-configured TV Client ID (already in the app):
    ${GoogleAuthProvider.TV_CLIENT_ID}

Step 5 — Add the secrets to the app
  Open the Vamanit Calendar app on your phone → Setup Wizard will appear.
  Paste the two GOCSPX-… secrets you downloaded and tap "Save & Continue".

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 PART 2 — MICROSOFT AZURE PORTAL  (optional)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Open: https://portal.azure.com

Step 1 — Find or create your App Registration
  • Microsoft Entra ID → App registrations → New registration
  • Supported account types: Accounts in any organizational directory
    and personal Microsoft accounts (multi-tenant)

Step 2 — Add the Redirect URI
  • Authentication → Add a platform → Mobile and desktop applications
  • Paste this exact URI:
    ${SetupActivity.MS_REDIRECT_URI}

Step 3 — Add API Permissions
  • API permissions → Add a permission → Microsoft Graph → Delegated:
    ✓ Calendars.Read
    ✓ User.Read
    ✓ offline_access

Step 4 — No client secret needed
  Microsoft uses a public-client flow on Android — no secret to download or paste.

  Pre-configured Microsoft Application (Client) ID (already in the app):
    ${SetupActivity.MS_CLIENT_ID}

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

That's it! Once the secrets are saved, restart the app and sign in.

— Sent by Vamanit Calendar Setup Wizard
    """.trimIndent()
}
