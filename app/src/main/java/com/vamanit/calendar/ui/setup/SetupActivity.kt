package com.vamanit.calendar.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import com.vamanit.calendar.auth.GoogleAuthProvider
import com.vamanit.calendar.auth.SecretsStore
import com.vamanit.calendar.databinding.ActivitySetupBinding
import com.vamanit.calendar.ui.signin.SignInActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.util.Patterns

/**
 * First-run installation wizard.
 *
 * • When secrets are already baked into the build (local.properties), the wizard
 *   shows a "secrets already configured" banner and lets the user proceed in one tap.
 * • For self-hosted / open-source builds without build-time secrets, the wizard
 *   collects Google client secrets with format validation.
 *
 * Shown automatically before SignInActivity whenever [SecretsStore.isSetupDone]
 * returns false (which never happens for builds with secrets baked in — they
 * auto-pass the check and never land here).
 */
@AndroidEntryPoint
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    @Inject lateinit var secretsStore: SecretsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        populateClientIds()
        setupCopyButtons()
        setupEmailButton()

        if (secretsStore.hasBuildTimeSecrets()) {
            // Secrets baked in — show simplified banner, hide manual entry fields
            showBuildSecretsMode()
        } else {
            // Self-hosted build — show full wizard with secret entry fields
            prefillSavedSecrets()
            setupButtons()
        }
    }

    // ── Build-secrets mode (one-tap continue) ────────────────────────────────

    private fun showBuildSecretsMode() {
        // Hide the manual secret entry section
        binding.layoutSecretEntry.visibility = View.GONE

        // Show the "already configured" banner
        binding.layoutBuildSecretsBanner.visibility = View.VISIBLE

        // Replace continue button label and skip without requiring input
        binding.btnContinue.text = getString(com.vamanit.calendar.R.string.setup_continue_build)
        binding.btnSkip.visibility = View.GONE

        binding.btnContinue.setOnClickListener {
            secretsStore.markSetupDone()
            launchSignIn()
        }
    }

    // ── Self-hosted mode (manual entry) ─────────────────────────────────────

    private fun prefillSavedSecrets() {
        if (secretsStore.hasRuntimeSecrets()) {
            binding.etPhoneSecret.setText(secretsStore.getPhoneClientSecret())
            binding.etTvSecret.setText(secretsStore.getTvClientSecret())
        }
    }

    private fun setupButtons() {
        binding.btnContinue.setOnClickListener {
            val phoneSecret = binding.etPhoneSecret.text.toString().trim()
            val tvSecret    = binding.etTvSecret.text.toString().trim()

            when {
                phoneSecret.isEmpty() || tvSecret.isEmpty() -> {
                    showError("Enter both Google client secrets to continue.")
                }
                !isValidGoogleSecret(phoneSecret) -> {
                    showError("Phone client secret looks wrong — it should start with GOCSPX-")
                }
                !isValidGoogleSecret(tvSecret) -> {
                    showError("TV client secret looks wrong — it should start with GOCSPX-")
                }
                else -> {
                    secretsStore.saveSecrets(phoneSecret, tvSecret)
                    launchSignIn()
                }
            }
        }

        // Skip: rely on secrets baked in at build time via local.properties
        binding.btnSkip.setOnClickListener {
            secretsStore.markSetupDone()
            launchSignIn()
        }
    }

    /** Google OAuth client secrets always start with GOCSPX- (Desktop/TV types). */
    private fun isValidGoogleSecret(secret: String): Boolean =
        secret.startsWith("GOCSPX-") && secret.length > 10

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    // ── Populate pre-configured IDs ──────────────────────────────────────────

    private fun populateClientIds() {
        binding.tvPhoneClientIdValue.text = GoogleAuthProvider.PHONE_CLIENT_ID
        binding.tvTvClientIdValue.text    = GoogleAuthProvider.TV_CLIENT_ID
        binding.tvMsClientIdValue.text    = MS_CLIENT_ID
        binding.tvMsRedirectUriValue.text = MS_REDIRECT_URI
    }

    // ── Email setup instructions ─────────────────────────────────────────────

    private fun setupEmailButton() {
        binding.btnEmailInstructions.setOnClickListener {
            val address = binding.etEmailAddress.text.toString().trim()
            if (address.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                showError("Please enter a valid email address.")
                return@setOnClickListener
            }
            SetupEmailHelper.sendSetupInstructions(this, address)
        }
    }

    // ── Copy-to-clipboard buttons ────────────────────────────────────────────

    private fun setupCopyButtons() {
        binding.btnCopyPhoneClientId.setOnClickListener {
            copy("Google Phone Client ID", GoogleAuthProvider.PHONE_CLIENT_ID)
        }
        binding.btnCopyTvClientId.setOnClickListener {
            copy("Google TV Client ID", GoogleAuthProvider.TV_CLIENT_ID)
        }
        binding.btnCopyMsClientId.setOnClickListener {
            copy("Microsoft Client ID", MS_CLIENT_ID)
        }
        binding.btnCopyMsRedirect.setOnClickListener {
            copy("Microsoft Redirect URI", MS_REDIRECT_URI)
        }
    }

    private fun launchSignIn() {
        startActivity(
            Intent(this, SignInActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    companion object {
        // Values from res/raw/msal_config.json — kept here so they appear on the
        // setup screen alongside instructions without requiring file I/O.
        const val MS_CLIENT_ID    = "55e73e24-1390-4ea5-bf95-d7927dd8ec42"
        const val MS_REDIRECT_URI = "msauth://com.vamanit.calendar/RwD+B3WBhHQoDk59NqvfGvyHnUs="
    }
}
