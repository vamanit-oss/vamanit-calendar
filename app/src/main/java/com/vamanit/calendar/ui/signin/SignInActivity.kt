package com.vamanit.calendar.ui.signin

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.vamanit.calendar.auth.AuthState
import com.vamanit.calendar.auth.SecretsStore
import com.vamanit.calendar.databinding.ActivitySignInBinding
import com.vamanit.calendar.security.IntegrityHelper
import com.vamanit.calendar.ui.dashboard.DashboardActivity
import com.vamanit.calendar.ui.setup.SetupActivity
import com.vamanit.calendar.ui.setup.SetupEmailHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SignInActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInBinding
    private val viewModel: SignInViewModel by viewModels()

    @Inject lateinit var secretsStore: SecretsStore

    /** True when running on an Android TV (D-pad navigation, no touch). */
    private val isTv by lazy {
        (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    // AppAuth result launcher for Google OAuth2 — phone only
    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleGoogleAuthResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // First-run: show setup wizard so the user can enter OAuth secrets
        if (!secretsStore.isSetupDone()) {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }

        // Skip sign-in if already authenticated
        if (viewModel.isAlreadySignedIn()) {
            startDashboard()
            return
        }

        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets so content doesn't draw behind system bars
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        setupClickListeners()
        observeAuthState()
        observeDeviceFlowState()
        observeSignInError()
    }

    private fun setupClickListeners() {
        binding.btnGoogleSignIn.setOnClickListener {
            // Run Play Integrity check before initiating the OAuth flow.
            // Client-only mode: we always proceed regardless of verdict.
            runWithIntegrityCheck(action = "google_sign_in") {
                if (isTv) {
                    // TV: Device Authorization Grant — displays code + URL, polls for token
                    viewModel.startGoogleDeviceFlow()
                } else {
                    // Phone: AppAuth browser-based OAuth2
                    val intent = viewModel.googleAuthProvider.buildAuthIntent()
                    googleAuthLauncher.launch(intent)
                }
            }
        }

        binding.btnMicrosoftSignIn.setOnClickListener {
            // Run Play Integrity check before initiating the MSAL flow.
            runWithIntegrityCheck(action = "microsoft_sign_in") {
                viewModel.signInWithMicrosoft(this)
            }
        }
    }

    /**
     * Runs a Play Integrity check, then always calls [proceed].
     *
     * While the check is in flight both sign-in buttons are disabled to prevent
     * double-taps.  A Snackbar warning is shown when the verdict is non-ideal,
     * but we never block the user — server-side enforcement can be added later.
     */
    private fun runWithIntegrityCheck(action: String, proceed: () -> Unit) {
        lifecycleScope.launch {
            setSignInButtonsEnabled(false)

            when (val result = IntegrityHelper.check(applicationContext, action)) {
                is IntegrityHelper.IntegrityResult.Pass -> {
                    Timber.d("IntegrityHelper [$action] check passed — proceeding")
                }
                is IntegrityHelper.IntegrityResult.Warn -> {
                    Timber.w("IntegrityHelper [$action] check warned: ${result.reasons}")
                    Snackbar.make(
                        binding.root,
                        "Device security check: ${result.reasons.joinToString()}",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                is IntegrityHelper.IntegrityResult.Error -> {
                    // API unavailable (no network, Play Services missing, etc.) — proceed anyway
                    Timber.e(result.cause, "IntegrityHelper [$action] check error — proceeding anyway")
                }
            }

            setSignInButtonsEnabled(true)
            proceed()
        }
    }

    private fun setSignInButtonsEnabled(enabled: Boolean) {
        binding.btnGoogleSignIn.isEnabled    = enabled
        binding.btnMicrosoftSignIn.isEnabled = enabled
    }

    private fun observeAuthState() {
        // repeatOnLifecycle(STARTED) ensures we only act on auth state while the activity
        // is in the foreground. Without it, startDashboard() can fire while the Microsoft
        // auth browser is covering this activity (stopped state), which Android 10+ blocks
        // as a background activity launch — the browser then closes back to SignInActivity,
        // the collect re-triggers on the still-Authenticated state, causing an infinite loop.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authState.collect { state ->
                    Timber.d("Auth state in SignIn: $state")
                    if (state is AuthState.Authenticated && state.hasAnyAccount) {
                        startDashboard()
                    }
                }
            }
        }
    }

    private fun observeDeviceFlowState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deviceFlowState.collect { state ->
                    when (state) {
                        is DeviceFlowUiState.Idle -> {
                            binding.layoutDeviceCode.visibility = View.GONE
                            binding.btnGoogleSignIn.isEnabled   = true
                            binding.btnMicrosoftSignIn.isEnabled = true
                        }
                        is DeviceFlowUiState.Loading -> {
                            binding.layoutDeviceCode.visibility = View.VISIBLE
                            binding.tvUserCode.text             = ""
                            binding.tvVerificationUrl.text      = ""
                            binding.tvDeviceStatus.text         = getString(
                                com.vamanit.calendar.R.string.device_flow_connecting
                            )
                            binding.btnGoogleSignIn.isEnabled   = false
                        }
                        is DeviceFlowUiState.ShowingCode -> {
                            binding.layoutDeviceCode.visibility = View.VISIBLE
                            binding.tvVerificationUrl.text      = state.verificationUrl
                            binding.tvUserCode.text             = state.userCode
                            binding.tvDeviceStatus.text         = getString(
                                com.vamanit.calendar.R.string.device_flow_waiting
                            )
                            binding.btnGoogleSignIn.isEnabled   = false
                        }
                        is DeviceFlowUiState.Error -> {
                            binding.layoutDeviceCode.visibility = View.VISIBLE
                            binding.tvVerificationUrl.text      = ""
                            binding.tvUserCode.text             = ""
                            binding.tvDeviceStatus.text         = state.message
                            binding.btnGoogleSignIn.isEnabled   = true
                        }
                    }
                }
            }
        }
    }

    private fun observeSignInError() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.signInError.collect { message ->
                    if (message != null) {
                        showSignInError(message)
                        viewModel.clearSignInError()
                    }
                }
            }
        }
    }

    /**
     * Shows the sign-in error message.
     *
     * When the error looks like an OAuth configuration problem (invalid_client,
     * unauthorized, access_denied) the snackbar includes an "Email Setup"
     * action that opens the user's email app pre-filled with full cloud console
     * setup instructions — so they can configure the OAuth clients on a desktop.
     */
    private fun showSignInError(message: String) {
        val isAuthConfigError = message.contains("invalid_client",    ignoreCase = true) ||
                                message.contains("unauthorized",      ignoreCase = true) ||
                                message.contains("access_denied",     ignoreCase = true) ||
                                message.contains("client",            ignoreCase = true)

        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)

        if (isAuthConfigError) {
            snackbar.duration = Snackbar.LENGTH_INDEFINITE
            snackbar.setAction("Email Setup") {
                SetupEmailHelper.sendSetupInstructions(this)
            }
        }

        snackbar.show()
    }

    private fun startDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
