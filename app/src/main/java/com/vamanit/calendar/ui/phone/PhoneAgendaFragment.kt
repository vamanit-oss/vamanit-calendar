package com.vamanit.calendar.ui.phone

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhoneAgendaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AgendaAdapter(
            onBookRoom = { calendarId, eventId, resourceCalendarId ->
                roomViewModel.bookRoom(calendarId, eventId, resourceCalendarId)
            }
        )
        binding.rvAgenda.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@PhoneAgendaFragment.adapter
        }

        // Load delegated resource calendars once
        roomViewModel.loadResources()
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                roomViewModel.resourceState.collect { state ->
                    when (state) {
                        is ResourceUiState.Ready -> {
                            adapter.userDisplayName = state.userDisplayName
                            adapter.resources = state.resources
                        }
                        is ResourceUiState.Error -> {
                            adapter.resources = emptyList()
                        }
                        else -> Unit
                    }
                }
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        // Sign out — clears all accounts and returns to sign-in selection screen
        binding.btnSignOut.setOnClickListener {
            viewModel.signOut()
            startActivity(
                Intent(requireContext(), SignInActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { events ->
                    adapter.submitList(events.toAgendaItems())
                    binding.swipeRefresh.isRefreshing = false
                    binding.tvEmptyState.visibility =
                        if (events.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Surface booking result as a Snackbar so user knows it worked
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                roomViewModel.bookingState.collect { state ->
                    when (state) {
                        is com.vamanit.calendar.ui.detail.BookingState.Success -> {
                            com.google.android.material.snackbar.Snackbar.make(
                                binding.root, "✓ Room booked successfully",
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                            ).show()
                            roomViewModel.resetBookingState()
                        }
                        is com.vamanit.calendar.ui.detail.BookingState.Error -> {
                            com.google.android.material.snackbar.Snackbar.make(
                                binding.root, "⚠ ${state.message}",
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                            ).show()
                            roomViewModel.resetBookingState()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
