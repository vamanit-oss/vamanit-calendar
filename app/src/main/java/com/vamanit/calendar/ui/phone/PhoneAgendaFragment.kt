package com.vamanit.calendar.ui.phone

import android.content.Intent
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
import com.vamanit.calendar.R
import com.vamanit.calendar.data.model.CalendarResource
import com.vamanit.calendar.databinding.FragmentPhoneAgendaBinding
import com.vamanit.calendar.ui.dashboard.DashboardViewModel
import com.vamanit.calendar.ui.detail.EventDetailViewModel
import com.vamanit.calendar.ui.detail.ResourceUiState
import com.vamanit.calendar.ui.signin.SignInActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PhoneAgendaFragment : Fragment() {

    private var _binding: FragmentPhoneAgendaBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private val roomViewModel: EventDetailViewModel by viewModels()
    private lateinit var adapter: AgendaAdapter

    /** Spinner entries: first is always personal (null resource). */
    private data class CalendarEntry(val label: String, val resource: CalendarResource?)
    private var calendarEntries = listOf<CalendarEntry>()

    /** The calendarId currently selected; null = show all events. */
    private var selectedCalendarId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneAgendaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AgendaAdapter()
        binding.rvAgenda.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@PhoneAgendaFragment.adapter
        }

        // Load delegated resource calendars and wire the selector spinner
        roomViewModel.loadResources()
        observeCalendarSelector()

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        // Sign out
        binding.btnSignOut.setOnClickListener {
            viewModel.signOut()
            startActivity(
                Intent(requireContext(), SignInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }

        // Observe events — re-render on every change
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { allEvents ->
                    val filtered = if (selectedCalendarId == null) allEvents
                                   else allEvents.filter { it.calendarId == selectedCalendarId }
                    adapter.submitList(filtered.toAgendaItems())
                    binding.swipeRefresh.isRefreshing = false
                    binding.tvEmptyState.visibility =
                        if (filtered.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ── Calendar selector spinner ─────────────────────────────────────────────

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
            R.layout.item_spinner_calendar,
            labels
        ).also { it.setDropDownViewResource(R.layout.item_spinner_calendar) }

        // Preserve selection across lifecycle restarts
        val restorePos = calendarEntries.indexOfFirst {
            it.resource?.calendarId == selectedCalendarId
        }.coerceAtLeast(0)

        // Detach listener before swapping adapter to prevent spurious position-0 callbacks
        binding.spinnerCalendarSelector.onItemSelectedListener = null
        binding.spinnerCalendarSelector.adapter = spinnerAdapter
        binding.spinnerCalendarSelector.setSelection(restorePos, false)
        binding.spinnerCalendarSelector.isEnabled = true

        binding.spinnerCalendarSelector.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, position: Int, id: Long
                ) {
                    val newId = calendarEntries.getOrNull(position)?.resource?.calendarId
                    if (newId == selectedCalendarId) return
                    selectedCalendarId = newId
                    // Re-filter with current events snapshot
                    val allEvents = viewModel.events.value
                    val filtered = if (selectedCalendarId == null) allEvents
                                   else allEvents.filter { it.calendarId == selectedCalendarId }
                    adapter.submitList(filtered.toAgendaItems())
                    binding.tvEmptyState.visibility =
                        if (filtered.isEmpty()) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
