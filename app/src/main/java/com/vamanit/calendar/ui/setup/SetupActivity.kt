package com.vamanit.calendar.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

/**
 * First-run installation wizard.
 *
 * Displays the app's pre-configured OAuth Client IDs for both Google and
 * Microsoft, collects the Google client secrets from the user, and provides
 * step-by-step instructions for configuring each cloud console.
 *
 * Shown automatically before SignInActivity whenever [SecretsStore.isSetupDone]
 * returns false.
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
        prefillSavedSecrets()
        setupButtons()
    }

    // ── Populate pre-configured IDs ──────────────────────────────────────────

    private fun populateClientIds() {
        binding.tvPhoneClientIdValue.text = GoogleAuthProvider.PHONE_CLIENT_ID
        binding.tvTvClientIdValue.text    = GoogleAuthProvider.TV_CLIENT_ID
        binding.tvMsClientIdValue.text    = MS_CLIENT_ID
        binding.tvMsRedirectUriValue.text = MS_REDIRECT_URI
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

    // ── Pre-fill secrets if they were already saved ──────────────────────────

    private fun prefillSavedSecrets() {
        if (secretsStore.hasRuntimeSecrets()) {
            binding.etPhoneSecret.setText(secretsStore.getPhoneClientSecret())
            binding.etTvSecret.setText(secretsStore.getTvClientSecret())
        }
    }

    // ── Continue / Skip ──────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnContinue.setOnClickListener {
            val phoneSecret = binding.etPhoneSecret.text.toString().trim()
            val tvSecret    = binding.etTvSecret.text.toString().trim()

            if (phoneSecret.isEmpty() || tvSecret.isEmpty()) {
                Snackbar.make(
                    binding.root,
                    "Enter both Google client secrets to continue.",
                    Snackbar.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            secretsStore.saveSecrets(phoneSecret, tvSecret)
            launchSignIn()
        }

        // Skip: rely on secrets baked in at build time via local.properties
        binding.btnSkip.setOnClickListener {
            secretsStore.markSetupDone()
            launchSignIn()
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
