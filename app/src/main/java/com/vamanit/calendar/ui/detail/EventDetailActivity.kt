package com.vamanit.calendar.ui.detail

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vamanit.calendar.R
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.data.model.CalendarSource
import com.vamanit.calendar.databinding.ActivityEventDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class EventDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventDetailBinding
    private val viewModel: EventDetailViewModel by viewModels()

    /** All spinner items — first entry is always the personal "no room" item. */
    private var spinnerItems = listOf<SpinnerEntry>()
    /** Index of the item matching the event's existing room (or 0 if none). */
    private var initialSpinnerPosition = 0
    /** Prevents the Book button from appearing on the programmatic initial selection. */
    private var spinnerReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left   = insets.left,
                top    = insets.top,
                right  = insets.right,
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
        val deviceZone   = ZoneId.systemDefault().id
        val startZone    = intent.getStringExtra(EXTRA_START_ZONE) ?: deviceZone
        val endZone      = intent.getStringExtra(EXTRA_END_ZONE) ?: deviceZone
        val calendarId   = intent.getStringExtra(EXTRA_CALENDAR_ID)
        val eventId      = intent.getStringExtra(EXTRA_EVENT_ID) ?: ""

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

        // Timing — always displayed in the device's local timezone.
        // Epochs are absolute; we just choose which zone to render them in.
        val displayZone = ZoneId.systemDefault()
        binding.tvTiming.text = if (isAllDay) {
            val start = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(startEpoch), displayZone
            )
            val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
            "All day · ${start.format(dateFmt)}"
        } else {
            val start = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(startEpoch), displayZone
            )
            val end = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(endEpoch), displayZone
            )
            val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            if (start.toLocalDate() == end.toLocalDate()) {
                "${start.format(dateFmt)} · ${start.format(timeFmt)} – ${end.format(timeFmt)}"
            } else {
                "${start.format(dateFmt)} ${start.format(timeFmt)} – ${end.format(dateFmt)} ${end.format(timeFmt)}"
            }
        }

        // Attendees
        binding.tvAttendees.text = if (attendees.isEmpty()) "—" else attendees.joinToString("\n")

        // Description / Agenda
        binding.tvDescription.text = description?.takeIf { it.isNotBlank() } ?: "—"

        // ── Room / Resource picker ────────────────────────────────────────────
        // Show the spinner for Google events (where we can book rooms).
        // For Microsoft/other: fall back to static location text.
        // The spinner always appears — even if resource loading fails we still show
        // the personal-calendar "no room" option so the UI is consistent.

        if (calSource == CalendarSource.GOOGLE) {
            binding.tvLocation.visibility = View.GONE
            binding.layoutResourcePicker.visibility = View.VISIBLE
            viewModel.loadResources()
            observeResourceState(calendarId ?: "primary", eventId, location)
            observeBookingState()
        } else {
            binding.tvLocation.text = location?.takeIf { it.isNotBlank() } ?: "—"
            binding.layoutResourcePicker.visibility = View.GONE
        }
    }

    // ── Resource spinner ──────────────────────────────────────────────────────

    private fun observeResourceState(calendarId: String, eventId: String, currentLocation: String?) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.resourceState.collect { state ->
                    when (state) {
                        is ResourceUiState.Loading -> {
                            binding.progressResources.visibility = View.VISIBLE
                            binding.spinnerRoom.isEnabled = false
                        }
                        is ResourceUiState.Ready -> {
                            binding.progressResources.visibility = View.GONE
                            buildSpinner(
                                calendarId      = calendarId,
                                eventId         = eventId,
                                userDisplayName = state.userDisplayName,
                                resources       = state.resources,
                                currentLocation = currentLocation
                            )
                        }
                        is ResourceUiState.Error -> {
                            binding.progressResources.visibility = View.GONE
                            // Rooms unavailable — show spinner with personal-calendar option only.
                            // Booking is disabled (no resource IDs) but the UI remains consistent.
                            buildSpinner(
                                calendarId      = calendarId,
                                eventId         = eventId,
                                userDisplayName = "My Calendar",
                                resources       = emptyList(),
                                currentLocation = currentLocation
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildSpinner(
        calendarId: String,
        eventId: String,
        userDisplayName: String,
        resources: List<CalendarResource>,
        currentLocation: String?
    ) {
        // First entry = personal "no-room" item
        val personalEntry  = SpinnerEntry(label = "$userDisplayName's Calendar", resource = null)
        val resourceEntries = resources.map { SpinnerEntry(label = it.spinnerLabel, resource = it) }
        spinnerItems = listOf(personalEntry) + resourceEntries

        // Pre-select whichever resource name matches the event's existing location (if any)
        initialSpinnerPosition = if (currentLocation != null) {
            spinnerItems.indexOfFirst { entry ->
                entry.resource?.displayName?.let { currentLocation.contains(it, ignoreCase = true) } == true
            }.takeIf { it >= 0 } ?: 0
        } else 0

        val labels = spinnerItems.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerRoom.adapter = adapter
        binding.spinnerRoom.setSelection(initialSpinnerPosition, false)
        binding.spinnerRoom.isEnabled = true
        spinnerReady = true

        binding.spinnerRoom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerReady) return
                val changed = position != initialSpinnerPosition
                binding.btnBookRoom.visibility = if (changed) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnBookRoom.setOnClickListener {
            val selected = spinnerItems.getOrNull(binding.spinnerRoom.selectedItemPosition) ?: return@setOnClickListener
            viewModel.bookRoom(calendarId, eventId, selected.resource?.calendarId)
        }
    }

    private fun observeBookingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.bookingState.collect { state ->
                    when (state) {
                        is BookingState.Idle -> {
                            binding.btnBookRoom.isEnabled = true
                            binding.tvBookingResult.visibility = View.GONE
                        }
                        is BookingState.Saving -> {
                            binding.btnBookRoom.isEnabled = false
                            binding.progressResources.visibility = View.VISIBLE
                        }
                        is BookingState.Success -> {
                            binding.progressResources.visibility = View.GONE
                            binding.btnBookRoom.visibility = View.GONE
                            binding.tvBookingResult.visibility = View.VISIBLE
                            binding.tvBookingResult.text = "✓ Room booked successfully"
                            binding.tvBookingResult.setTextColor(Color.parseColor("#16A765"))
                            // Update baseline so Book button hides again
                            initialSpinnerPosition = binding.spinnerRoom.selectedItemPosition
                            viewModel.resetBookingState()
                        }
                        is BookingState.Error -> {
                            binding.progressResources.visibility = View.GONE
                            binding.btnBookRoom.isEnabled = true
                            binding.tvBookingResult.visibility = View.VISIBLE
                            binding.tvBookingResult.text = "⚠ ${state.message}"
                            binding.tvBookingResult.setTextColor(Color.parseColor("#F83A22"))
                            viewModel.resetBookingState()
                        }
                    }
                }
            }
        }
    }

    // ── Intent helpers ────────────────────────────────────────────────────────

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
        private const val EXTRA_CALENDAR_ID   = "calendarId"
        private const val EXTRA_EVENT_ID      = "eventId"

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
                putExtra(EXTRA_CALENDAR_ID, event.calendarId)
                putExtra(EXTRA_EVENT_ID, event.id)
            }
    }

    /** A single entry in the room-picker spinner. */
    private data class SpinnerEntry(
        val label: String,
        val resource: CalendarResource?   // null = personal calendar (no room booking)
    )
}
