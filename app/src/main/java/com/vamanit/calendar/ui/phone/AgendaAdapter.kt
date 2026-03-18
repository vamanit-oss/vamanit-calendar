package com.vamanit.calendar.ui.phone

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vamanit.calendar.data.model.CalendarEvent
import com.vamanit.calendar.databinding.ItemAgendaEventBinding
import com.vamanit.calendar.databinding.ItemAgendaHeaderBinding
import com.vamanit.calendar.ui.detail.EventDetailActivity
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class AgendaItem {
    data class Header(val date: LocalDate) : AgendaItem()
    data class Event(val event: CalendarEvent) : AgendaItem()
}

class AgendaAdapter : ListAdapter<AgendaItem, RecyclerView.ViewHolder>(AgendaDiff()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_EVENT = 1
    }

    inner class HeaderViewHolder(private val b: ItemAgendaHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(date: LocalDate) {
            val today = LocalDate.now()
            b.tvDayName.text = when (date) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> date.format(DateTimeFormatter.ofPattern("EEEE"))
            }
            b.tvDayDate.text = date.format(DateTimeFormatter.ofPattern("d MMMM"))
        }
    }

    inner class EventViewHolder(private val b: ItemAgendaEventBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(event: CalendarEvent) {
            b.root.setOnClickListener {
                it.context.startActivity(EventDetailActivity.createIntent(it.context, event))
            }
            b.tvTitle.text = event.title
            b.tvTime.text = if (event.isAllDay) "All day"
            else {
                val fmt = DateTimeFormatter.ofPattern("HH:mm")
                "${event.startTime.format(fmt)} – ${event.endTime.format(fmt)}"
            }
            b.tvLocation.text = event.location ?: ""
            b.tvSource.text = event.source.label

            val color = try {
                Color.parseColor(event.colorHex ?: event.source.colorFallback)
            } catch (e: Exception) {
                Color.parseColor(event.source.colorFallback)
            }
            b.viewColorDot.setBackgroundColor(color)

            val isPast = event.endTime.isBefore(ZonedDateTime.now())
            b.root.alpha = if (isPast) 0.5f else 1.0f
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is AgendaItem.Header -> TYPE_HEADER
        is AgendaItem.Event -> TYPE_EVENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemAgendaHeaderBinding.inflate(inflater, parent, false))
            else -> EventViewHolder(ItemAgendaEventBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is AgendaItem.Header -> (holder as HeaderViewHolder).bind(item.date)
            is AgendaItem.Event -> (holder as EventViewHolder).bind(item.event)
        }
    }

    class AgendaDiff : DiffUtil.ItemCallback<AgendaItem>() {
        override fun areItemsTheSame(old: AgendaItem, new: AgendaItem): Boolean = when {
            old is AgendaItem.Header && new is AgendaItem.Header -> old.date == new.date
            old is AgendaItem.Event && new is AgendaItem.Event -> old.event.id == new.event.id
            else -> false
        }
        override fun areContentsTheSame(old: AgendaItem, new: AgendaItem) = old == new
    }
}

fun List<CalendarEvent>.toAgendaItems(): List<AgendaItem> {
    val result = mutableListOf<AgendaItem>()
    var lastDate: LocalDate? = null
    forEach { event ->
        val date = event.startTime.toLocalDate()
        if (date != lastDate) {
            result.add(AgendaItem.Header(date))
            lastDate = date
        }
        result.add(AgendaItem.Event(event))
    }
    return result
}
