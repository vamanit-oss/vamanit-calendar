package com.vamanit.calendar.ui.tv

import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.vamanit.calendar.databinding.FragmentTvDashboardBinding
import com.vamanit.calendar.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TvDashboardFragment : Fragment() {

    private var _binding: FragmentTvDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private lateinit var adapter: TvEventCardAdapter
    private var clockJob: Job? = null

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

    override fun onDestroyView() {
        clockJob?.cancel()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
        super.onDestroyView()
    }
}
