package com.vamanit.calendar.ui.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.data.model.CalendarSource
import com.vamanit.calendar.databinding.FragmentTvDashboardBinding
import com.vamanit.calendar.ui.dashboard.DashboardViewModel
import com.vamanit.calendar.ui.detail.BookingState
import com.vamanit.calendar.ui.detail.EventDetailViewModel
import com.vamanit.calendar.ui.detail.ResourceUiState
import com.vamanit.calendar.ui.signin.SignInActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class TvDashboardFragment : Fragment() {

    private var _binding: FragmentTvDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private val roomViewModel: EventDetailViewModel by viewModels()
    private lateinit var adapter: TvEventCardAdapter
    private var clockJob: Job? = null

    /** Spinner entries for the TV room picker. */
    private var tvSpinnerItems = listOf<TvSpinnerEntry>()
    private var tvSpinnerInitialPos = 0
    private var tvSpinnerReady = false
    private data class TvSpinnerEntry(val label: String, val resource: CalendarResource?)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Vertical list for the right-page remaining events
        adapter = TvEventCardAdapter()
        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
            this.adapter = this@TvDashboardFragment.adapter
        }

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        var currentNextEvent: com.vamanit.calendar.data.model.CalendarEvent? = null

        // Make next meeting card clickable → open EventDetailActivity
        binding.cardNextMeeting.setOnClickListener {
            currentNextEvent?.let { event ->
                startActivity(
                    com.vamanit.calendar.ui.detail.EventDetailActivity.createIntent(requireContext(), event)
                )
            }
        }
        binding.cardNextMeeting.isFocusable = true
        binding.cardNextMeeting.isClickable = true

        // Load delegated room resources once and keep for the session
        roomViewModel.loadResources()
        observeTvResourceState()
        observeTvBookingState()

        // Exit button — signs out all accounts and returns to the sign-in selection screen
        binding.btnExit.setOnClickListener {
            viewModel.signOut()
            startActivity(
                Intent(requireContext(), SignInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { events ->
                    val now = ZonedDateTime.now()

                    // Split: next upcoming vs the rest
                    val nextIdx = events.indexOfFirst { !it.endTime.isBefore(now) }
                    val nextEvent = if (nextIdx >= 0) events[nextIdx] else null
                    currentNextEvent = nextEvent
                    val remaining = if (nextIdx >= 0) events.drop(nextIdx + 1) else events

                    // ── Next meeting card ──
                    if (nextEvent != null) {
                        binding.cardNextMeeting.visibility = View.VISIBLE

                        val eventColor = try {
                            Color.parseColor(
                                nextEvent.colorHex ?: nextEvent.source.colorFallback
                            )
                        } catch (e: Exception) {
                            Color.parseColor(nextEvent.source.colorFallback)
                        }
                        binding.viewNextColorBar.setBackgroundColor(eventColor)
                        binding.tvNextSource.text = nextEvent.source.label
                        binding.tvNextTitle.text = nextEvent.title
                        binding.tvNextTime.text = if (nextEvent.isAllDay) {
                            "All day"
                        } else {
                            "${nextEvent.startTime.format(timeFmt)} – ${nextEvent.endTime.format(timeFmt)}"
                        }
                        binding.tvNextLocation.text = nextEvent.location?.takeIf { it.isNotBlank() } ?: ""
                        binding.tvNextLocation.visibility =
                            if (nextEvent.location.isNullOrBlank()) View.GONE else View.VISIBLE

                        // Show room picker for this event (always — resources load separately)
                        refreshTvRoomPicker(
                            calendarId = nextEvent.calendarId ?: "primary",
                            eventId = nextEvent.id,
                            currentLocation = nextEvent.location,
                            isGoogleEvent = nextEvent.source == CalendarSource.GOOGLE
                        )
                    } else {
                        binding.cardNextMeeting.visibility = View.GONE
                        binding.layoutTvRoomPicker.visibility = View.GONE
                    }

                    // ── Remaining list ──
                    adapter.submitList(remaining)
                    binding.tvAlsoToday.visibility =
                        if (remaining.isNotEmpty() && nextEvent != null) View.VISIBLE else View.GONE

                    // ── Event count badge ──
                    val total = events.count { !it.endTime.isBefore(now) }
                    binding.tvEventCount.text = if (total > 0) "$total today" else ""

                    // ── Empty state ──
                    binding.tvEmptyState.visibility =
                        if (nextEvent == null && remaining.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Live clock
        clockJob = viewLifecycleOwner.lifecycleScope.launch {
            val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
            val dateFmt  = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
            while (true) {
                val now = ZonedDateTime.now()
                binding.tvTime.text = now.format(clockFmt)
                binding.tvDate.text = now.format(dateFmt)
                delay(1_000)
            }
        }
    }

    // ── TV inline room picker ─────────────────────────────────────────────────

    private var tvCurrentCalendarId = "primary"
    private var tvCurrentEventId    = ""

    /** Called whenever the "next meeting" changes — re-wires the spinner for the new event. */
    private fun refreshTvRoomPicker(
        calendarId: String,
        eventId: String,
        currentLocation: String?,
        isGoogleEvent: Boolean
    ) {
        tvCurrentCalendarId = calendarId
        tvCurrentEventId    = eventId
        binding.layoutTvRoomPicker.visibility = View.VISIBLE

        // Re-build spinner from whatever resource state is already available
        val state = roomViewModel.resourceState.value
        when (state) {
            is ResourceUiState.Ready -> buildTvSpinner(
                calendarId, eventId, state.userDisplayName,
                state.resources, currentLocation, isGoogleEvent
            )
            is ResourceUiState.Error -> buildTvSpinner(
                calendarId, eventId, "My Calendar",
                emptyList(), currentLocation, isGoogleEvent
            )
            is ResourceUiState.Loading -> {
                binding.progressTvResources.visibility = View.VISIBLE
                binding.spinnerTvRoom.isEnabled = false
            }
        }
    }

    private fun observeTvResourceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                roomViewModel.resourceState.collect { state ->
                    when (state) {
                        is ResourceUiState.Loading -> {
                            binding.progressTvResources.visibility = View.VISIBLE
                            binding.spinnerTvRoom.isEnabled = false
                        }
                        is ResourceUiState.Ready -> {
                            binding.progressTvResources.visibility = View.GONE
                            if (binding.layoutTvRoomPicker.visibility == View.VISIBLE) {
                                buildTvSpinner(
                                    tvCurrentCalendarId, tvCurrentEventId,
                                    state.userDisplayName, state.resources,
                                    null, true
                                )
                            }
                        }
                        is ResourceUiState.Error -> {
                            binding.progressTvResources.visibility = View.GONE
                            if (binding.layoutTvRoomPicker.visibility == View.VISIBLE) {
                                buildTvSpinner(
                                    tvCurrentCalendarId, tvCurrentEventId,
                                    "My Calendar", emptyList(), null, false
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildTvSpinner(
        calendarId: String,
        eventId: String,
        userDisplayName: String,
        resources: List<CalendarResource>,
        currentLocation: String?,
        isGoogleEvent: Boolean
    ) {
        val personal = TvSpinnerEntry("$userDisplayName's Calendar", null)
        val resourceEntries = resources.map { TvSpinnerEntry(it.spinnerLabel, it) }
        tvSpinnerItems = listOf(personal) + resourceEntries

        tvSpinnerInitialPos = if (currentLocation != null) {
            tvSpinnerItems.indexOfFirst { entry ->
                entry.resource?.displayName?.let { currentLocation.contains(it, ignoreCase = true) } == true
            }.takeIf { it >= 0 } ?: 0
        } else 0

        val labels = tvSpinnerItems.map { it.label }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        tvSpinnerReady = false
        binding.spinnerTvRoom.adapter = adapter
        binding.spinnerTvRoom.setSelection(tvSpinnerInitialPos, false)
        binding.spinnerTvRoom.isEnabled = true
        tvSpinnerReady = true

        // Book button only appears when selection changes AND it's a Google event (write scope)
        binding.spinnerTvRoom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!tvSpinnerReady) return
                val changed = position != tvSpinnerInitialPos
                binding.btnTvBookRoom.visibility =
                    if (changed && isGoogleEvent) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.btnTvBookRoom.setOnClickListener {
            val selected = tvSpinnerItems.getOrNull(binding.spinnerTvRoom.selectedItemPosition)
                ?: return@setOnClickListener
            roomViewModel.bookRoom(calendarId, eventId, selected.resource?.calendarId)
        }
    }

    private fun observeTvBookingState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                roomViewModel.bookingState.collect { state ->
                    when (state) {
                        is BookingState.Idle -> {
                            binding.btnTvBookRoom.isEnabled = true
                            binding.tvTvBookingResult.visibility = View.GONE
                        }
                        is BookingState.Saving -> {
                            binding.btnTvBookRoom.isEnabled = false
                            binding.progressTvResources.visibility = View.VISIBLE
                        }
                        is BookingState.Success -> {
                            binding.progressTvResources.visibility = View.GONE
                            binding.btnTvBookRoom.visibility = View.GONE
                            binding.tvTvBookingResult.visibility = View.VISIBLE
                            binding.tvTvBookingResult.text = "✓ Room booked"
                            binding.tvTvBookingResult.setTextColor(Color.parseColor("#16A765"))
                            tvSpinnerInitialPos = binding.spinnerTvRoom.selectedItemPosition
                            roomViewModel.resetBookingState()
                        }
                        is BookingState.Error -> {
                            binding.progressTvResources.visibility = View.GONE
                            binding.btnTvBookRoom.isEnabled = true
                            binding.tvTvBookingResult.visibility = View.VISIBLE
                            binding.tvTvBookingResult.text = "⚠ ${state.message}"
                            binding.tvTvBookingResult.setTextColor(Color.parseColor("#F83A22"))
                            roomViewModel.resetBookingState()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        clockJob?.cancel()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
        super.onDestroyView()
    }
}
