package com.vamanit.calendar.ui.signin

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vamanit.calendar.auth.AuthState
import com.vamanit.calendar.databinding.ActivitySignInBinding
import com.vamanit.calendar.ui.dashboard.DashboardActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class SignInActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInBinding
    private val viewModel: SignInViewModel by viewModels()

    // AppAuth result launcher for Google OAuth2
    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleGoogleAuthResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skip sign-in if already authenticated
        if (viewModel.isAlreadySignedIn()) {
            startDashboard()
            return
        }

        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        observeAuthState()
    }

    private fun setupClickListeners() {
        binding.btnGoogleSignIn.setOnClickListener {
            val intent = viewModel.googleAuthProvider.buildAuthIntent()
            googleAuthLauncher.launch(intent)
        }

        binding.btnMicrosoftSignIn.setOnClickListener {
            viewModel.signInWithMicrosoft(this)
        }
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

    private fun startDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
