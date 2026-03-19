package com.vamanit.calendar.ui.dashboard

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.work.WorkManager
import com.vamanit.calendar.databinding.ActivityDashboardBinding
import com.vamanit.calendar.ui.phone.PhoneAgendaFragment
import com.vamanit.calendar.ui.tv.TvDashboardFragment
import com.vamanit.calendar.worker.CalendarRefreshWorker
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets so content doesn't draw behind system bars (phone only;
        // TV has no visible system bars, but the padding is harmless there).
        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            val fragment = if (isTelevision()) TvDashboardFragment() else PhoneAgendaFragment()
            supportFragmentManager.beginTransaction()
                .replace(binding.fragmentContainer.id, fragment)
                .commit()
        }

        // Schedule periodic background refresh
        CalendarRefreshWorker.schedule(WorkManager.getInstance(this))
    }

    private fun isTelevision(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
