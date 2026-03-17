package com.vamanit.calendar.ui.signin

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
        lifecycleScope.launch {
            viewModel.authState.collect { state ->
                Timber.d("Auth state in SignIn: $state")
                if (state is AuthState.Authenticated && state.hasAnyAccount) {
                    startDashboard()
                }
            }
        }
    }

    private fun startDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
