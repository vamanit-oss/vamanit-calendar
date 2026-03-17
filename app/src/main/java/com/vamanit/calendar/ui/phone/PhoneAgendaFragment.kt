package com.vamanit.calendar.ui.phone

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.vamanit.calendar.databinding.FragmentPhoneAgendaBinding
import com.vamanit.calendar.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.launch

class PhoneAgendaFragment : Fragment() {

    private var _binding: FragmentPhoneAgendaBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private lateinit var adapter: AgendaAdapter

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

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
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
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
