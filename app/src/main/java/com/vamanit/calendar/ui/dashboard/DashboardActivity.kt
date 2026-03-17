package com.vamanit.calendar.ui.dashboard

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
