package com.vamanit.calendar.ui.tv

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.vamanit.calendar.databinding.FragmentTvDashboardBinding
import com.vamanit.calendar.ui.dashboard.DashboardActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TvDashboardFragment : Fragment() {

    private var _binding: FragmentTvDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel by activityViewModels<DashboardActivity.() -> Unit> {
        // activityViewModels delegates to DashboardActivity's ViewModel
        throw UnsupportedOperationException("use activityViewModels<DashboardViewModel>")
    }

    private val dashboardViewModel by activityViewModels<com.vamanit.calendar.ui.dashboard.DashboardViewModel>()
    private lateinit var adapter: TvEventCardAdapter
    private var clockJob: Job? = null

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupRecyclerView()
        observeEvents()
        startClock()
    }

    private fun setupRecyclerView() {
        adapter = TvEventCardAdapter()
        binding.rvEvents.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            this.adapter = this@TvDashboardFragment.adapter
            // Request focus for D-pad navigation
            requestFocus()
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dashboardViewModel.events.collect { events ->
                    adapter.submitList(events)
                    // Auto-scroll to next upcoming event
                    val nextIdx = events.indexOfFirst { !it.startTime.isBefore(ZonedDateTime.now()) }
                    if (nextIdx >= 0) binding.rvEvents.scrollToPosition(nextIdx)
                    binding.tvEmptyState.visibility =
                        if (events.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun startClock() {
        clockJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val now = ZonedDateTime.now()
                binding.tvTime.text = now.format(timeFmt)
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
