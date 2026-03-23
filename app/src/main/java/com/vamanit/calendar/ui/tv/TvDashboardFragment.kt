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
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.databinding.FragmentTvDashboardBinding
import com.vamanit.calendar.ui.dashboard.DashboardViewModel
import com.vamanit.calendar.ui.detail.EventDetailActivity
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

    /** Spinner entries for the clock-panel calendar selector. */
    private data class CalendarEntry(val label: String, val resource: CalendarResource?)
    private var calendarEntries = listOf<CalendarEntry>()

    /** The calendarId currently selected; null = personal (show all events). */
    private var selectedCalendarId: String? = null

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

        adapter = TvEventCardAdapter()
        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
            this.adapter = this@TvDashboardFragment.adapter
        }

        var currentNextEvent: CalendarEvent? = null

        // Tap next-meeting card to open full detail
        binding.cardNextMeeting.setOnClickListener {
            currentNextEvent?.let { event ->
                startActivity(EventDetailActivity.createIntent(requireContext(), event))
            }
        }
        binding.cardNextMeeting.isFocusable = true
        binding.cardNextMeeting.isClickable = true

        // Load delegated resource calendars once and populate the clock-panel spinner
        roomViewModel.loadResources()
        observeCalendarSelector()

        // Exit / sign-out
        binding.btnExit.setOnClickListener {
            viewModel.signOut()
            startActivity(
                Intent(requireContext(), SignInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        // Observe events — re-render whenever events change
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { allEvents ->
                    currentNextEvent = renderEvents(allEvents, timeFmt)
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

    // ── Calendar selector spinner (clock panel) ───────────────────────────────

    private fun observeCalendarSelector() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                roomViewModel.resourceState.collect { state ->
                    when (state) {
                        is ResourceUiState.Loading -> binding.spinnerCalendarSelector.isEnabled = false
                        is ResourceUiState.Ready   -> buildCalendarSpinner(state.userDisplayName, state.resources)
                        is ResourceUiState.Error   -> buildCalendarSpinner("My Calendar", emptyList())
                    }
                }
            }
        }
    }

    private fun buildCalendarSpinner(userDisplayName: String, resources: List<CalendarResource>) {
        val personal = CalendarEntry("$userDisplayName's Calendar", null)
        calendarEntries = listOf(personal) + resources.map { CalendarEntry(it.spinnerLabel, it) }

        val labels = calendarEntries.map { it.label }
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerCalendarSelector.adapter = spinnerAdapter
        binding.spinnerCalendarSelector.setSelection(0, false)
        binding.spinnerCalendarSelector.isEnabled = true

        binding.spinnerCalendarSelector.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, position: Int, id: Long
                ) {
                    selectedCalendarId = calendarEntries.getOrNull(position)?.resource?.calendarId
                    // Re-render the meeting list with the new filter
                    renderEvents(viewModel.events.value, DateTimeFormatter.ofPattern("HH:mm"))
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    // ── Event rendering ───────────────────────────────────────────────────────

    /**
     * Filters [allEvents] by [selectedCalendarId] (null = show all),
     * populates the next-meeting card and remaining list, returns the next event.
     */
    private fun renderEvents(
        allEvents: List<CalendarEvent>,
        timeFmt: DateTimeFormatter
    ): CalendarEvent? {
        val events = if (selectedCalendarId == null) allEvents
                     else allEvents.filter { it.calendarId == selectedCalendarId }

        val now       = ZonedDateTime.now()
        val nextIdx   = events.indexOfFirst { !it.endTime.isBefore(now) }
        val nextEvent = if (nextIdx >= 0) events[nextIdx] else null
        val remaining = if (nextIdx >= 0) events.drop(nextIdx + 1) else events

        // ── Next meeting card ──
        if (nextEvent != null) {
            binding.cardNextMeeting.visibility = View.VISIBLE
            val eventColor = try {
                Color.parseColor(nextEvent.colorHex ?: nextEvent.source.colorFallback)
            } catch (e: Exception) {
                Color.parseColor(nextEvent.source.colorFallback)
            }
            binding.viewNextColorBar.setBackgroundColor(eventColor)
            binding.tvNextSource.text = nextEvent.source.label
            binding.tvNextTitle.text  = nextEvent.title
            binding.tvNextTime.text   = if (nextEvent.isAllDay) "All day"
                else "${nextEvent.startTime.format(timeFmt)} – ${nextEvent.endTime.format(timeFmt)}"
            binding.tvNextLocation.text = nextEvent.location?.takeIf { it.isNotBlank() } ?: ""
            binding.tvNextLocation.visibility =
                if (nextEvent.location.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            binding.cardNextMeeting.visibility = View.GONE
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

        return nextEvent
    }

    override fun onDestroyView() {
        clockJob?.cancel()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
        super.onDestroyView()
    }
}
