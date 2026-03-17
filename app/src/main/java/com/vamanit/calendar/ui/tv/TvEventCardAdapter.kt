package com.vamanit.calendar.ui.tv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.databinding.ItemTvEventCardBinding
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class TvEventCardAdapter : ListAdapter<CalendarEvent, TvEventCardAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemTvEventCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1.0f)
                    .scaleY(if (hasFocus) 1.08f else 1.0f)
                    .setDuration(150)
                    .start()
                binding.root.cardElevation = if (hasFocus) 16f else 4f
            }
        }

        fun bind(event: CalendarEvent) {
            binding.tvTitle.text = event.title
            binding.tvTime.text = formatTime(event)
            binding.tvLocation.text = event.location ?: ""
            binding.tvCalendar.text = event.calendarName ?: event.source.label
            binding.tvSource.text = event.source.label

            val color = try {
                Color.parseColor(event.colorHex ?: event.source.colorFallback)
            } catch (e: IllegalArgumentException) {
                Color.parseColor(event.source.colorFallback)
            }
            binding.viewColorStrip.setBackgroundColor(color)

            // Dim past events
            val alpha = if (event.endTime.isBefore(ZonedDateTime.now())) 0.5f else 1.0f
            binding.root.alpha = alpha
        }

        private fun formatTime(event: CalendarEvent): String {
            if (event.isAllDay) return "All day"
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM")
            val now = ZonedDateTime.now()
            val daysAway = ChronoUnit.DAYS.between(now.toLocalDate(), event.startTime.toLocalDate())
            return when {
                daysAway == 0L -> "Today ${event.startTime.format(timeFmt)}–${event.endTime.format(timeFmt)}"
                daysAway == 1L -> "Tomorrow ${event.startTime.format(timeFmt)}"
                else -> "${event.startTime.format(dateFmt)} ${event.startTime.format(timeFmt)}"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTvEventCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<CalendarEvent>() {
        override fun areItemsTheSame(old: CalendarEvent, new: CalendarEvent) = old.id == new.id
        override fun areContentsTheSame(old: CalendarEvent, new: CalendarEvent) = old == new
    }
}
