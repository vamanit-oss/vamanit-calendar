package com.vamanit.calendar.ui.detail

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.vamanit.calendar.data.model.CalendarSource
import com.vamanit.calendar.databinding.ActivityEventDetailBinding
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class EventDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
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

        binding.btnBack.setOnClickListener { finish() }

        val title        = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val source       = intent.getStringExtra(EXTRA_SOURCE) ?: CalendarSource.GOOGLE.name
        val colorHex     = intent.getStringExtra(EXTRA_COLOR)
        val calendarName = intent.getStringExtra(EXTRA_CALENDAR_NAME)
        val location     = intent.getStringExtra(EXTRA_LOCATION)
        val description  = intent.getStringExtra(EXTRA_DESCRIPTION)
        val attendees    = intent.getStringArrayListExtra(EXTRA_ATTENDEES) ?: arrayListOf()
        val isAllDay     = intent.getBooleanExtra(EXTRA_IS_ALL_DAY, false)
        val startEpoch   = intent.getLongExtra(EXTRA_START_EPOCH, 0L)
        val endEpoch     = intent.getLongExtra(EXTRA_END_EPOCH, 0L)
        val startZone    = intent.getStringExtra(EXTRA_START_ZONE) ?: "UTC"
        val endZone      = intent.getStringExtra(EXTRA_END_ZONE) ?: "UTC"

        val calSource = runCatching { CalendarSource.valueOf(source) }.getOrDefault(CalendarSource.GOOGLE)
        val fallbackColor = calSource.colorFallback
        val eventColor = try {
            Color.parseColor(colorHex ?: fallbackColor)
        } catch (e: Exception) {
            Color.parseColor(fallbackColor)
        }

        binding.viewColorBar.setBackgroundColor(eventColor)
        binding.tvEventTitle.text = title
        binding.tvSourceLabel.text = calSource.label
        binding.tvCalendarName.text = calendarName ?: calSource.label

        // Timing
        binding.tvTiming.text = if (isAllDay) {
            val start = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(startEpoch),
                java.time.ZoneId.of(startZone)
            )
            val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
            "All day · ${start.format(dateFmt)}"
        } else {
            val start = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(startEpoch),
                java.time.ZoneId.of(startZone)
            )
            val end = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(endEpoch),
                java.time.ZoneId.of(endZone)
            )
            val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            if (start.toLocalDate() == end.toLocalDate()) {
                "${start.format(dateFmt)} · ${start.format(timeFmt)} – ${end.format(timeFmt)}"
            } else {
                "${start.format(dateFmt)} ${start.format(timeFmt)} – ${end.format(dateFmt)} ${end.format(timeFmt)}"
            }
        }

        // Location / Room
        binding.tvLocation.text = location?.takeIf { it.isNotBlank() } ?: "—"

        // Attendees
        binding.tvAttendees.text = if (attendees.isEmpty()) "—" else attendees.joinToString("\n")

        // Description / Agenda
        binding.tvDescription.text = description?.takeIf { it.isNotBlank() } ?: "—"
    }

    companion object {
        private const val EXTRA_TITLE         = "title"
        private const val EXTRA_SOURCE        = "source"
        private const val EXTRA_COLOR         = "color"
        private const val EXTRA_CALENDAR_NAME = "calendarName"
        private const val EXTRA_LOCATION      = "location"
        private const val EXTRA_DESCRIPTION   = "description"
        private const val EXTRA_ATTENDEES     = "attendees"
        private const val EXTRA_IS_ALL_DAY    = "isAllDay"
        private const val EXTRA_START_EPOCH   = "startEpoch"
        private const val EXTRA_END_EPOCH     = "endEpoch"
        private const val EXTRA_START_ZONE    = "startZone"
        private const val EXTRA_END_ZONE      = "endZone"

        fun createIntent(context: Context, event: com.vamanit.calendar.data.model.CalendarEvent): Intent =
            Intent(context, EventDetailActivity::class.java).apply {
                putExtra(EXTRA_TITLE, event.title)
                putExtra(EXTRA_SOURCE, event.source.name)
                putExtra(EXTRA_COLOR, event.colorHex)
                putExtra(EXTRA_CALENDAR_NAME, event.calendarName)
                putExtra(EXTRA_LOCATION, event.location)
                putExtra(EXTRA_DESCRIPTION, event.description)
                putStringArrayListExtra(EXTRA_ATTENDEES, ArrayList(event.attendees))
                putExtra(EXTRA_IS_ALL_DAY, event.isAllDay)
                putExtra(EXTRA_START_EPOCH, event.startTime.toInstant().toEpochMilli())
                putExtra(EXTRA_END_EPOCH, event.endTime.toInstant().toEpochMilli())
                putExtra(EXTRA_START_ZONE, event.startTime.zone.id)
                putExtra(EXTRA_END_ZONE, event.endTime.zone.id)
            }
    }
}
