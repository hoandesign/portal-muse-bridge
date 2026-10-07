package com.portal.pebblebridge.home

import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Things the home screen reacts to, from Muse commands or timers. */
sealed class DeviceEvent {
  /** Muse asked the robot to say something. */
  data class Say(val text: String, val title: String = "MUSE") : DeviceEvent()
  /** A timer or alarm went off. */
  data class Ring(val label: String, val isAlarm: Boolean) : DeviceEvent()
  /** Muse asked the robot to celebrate. */
  object Celebrate : DeviceEvent()
}

object DeviceEvents {
  private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 16)
  val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()
  fun emit(e: DeviceEvent) { _events.tryEmit(e) }
}

data class PortalTimer(val id: String, val label: String, val endsAt: Long, val isAlarm: Boolean)

/** Timers and alarms on this Portal. Held in memory; the bridge service ticks them. */
object Timers {
  private val _timers = MutableStateFlow<List<PortalTimer>>(emptyList())
  val timers: StateFlow<List<PortalTimer>> = _timers.asStateFlow()

  fun startTimer(seconds: Long, label: String, now: Long = System.currentTimeMillis()): PortalTimer {
    val t = PortalTimer(UUID.randomUUID().toString(), label.ifBlank { "TIMER" }, now + seconds * 1000, isAlarm = false)
    _timers.update { (it + t).sortedBy { x -> x.endsAt } }
    return t
  }

  fun setAlarm(hour: Int, minute: Int, label: String, now: Calendar = Calendar.getInstance()): PortalTimer {
    val t = PortalTimer(UUID.randomUUID().toString(), label.ifBlank { "ALARM" }, nextOccurrence(hour, minute, now), isAlarm = true)
    _timers.update { (it + t).sortedBy { x -> x.endsAt } }
    return t
  }

  /** Cancels timers whose label contains [label] (all when blank). Returns how many. */
  fun cancel(label: String = "", alarms: Boolean? = null): Int {
    var removed = 0
    _timers.update { list ->
      list.filterNot { t ->
        val match = (label.isBlank() || t.label.contains(label, ignoreCase = true)) && (alarms == null || t.isAlarm == alarms)
        if (match) removed++
        match
      }
    }
    return removed
  }

  /** Removes and returns everything due at [now]. */
  fun takeDue(now: Long = System.currentTimeMillis()): List<PortalTimer> {
    var due = emptyList<PortalTimer>()
    _timers.update { list -> due = list.filter { it.endsAt <= now }; list - due.toSet() }
    return due
  }

  fun clear() { _timers.value = emptyList() }

  /** The next time the clock reads hour:minute, today or tomorrow. */
  fun nextOccurrence(hour: Int, minute: Int, now: Calendar): Long {
    val c = (now.clone() as Calendar).apply {
      set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
      set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (c.timeInMillis <= now.timeInMillis) c.add(Calendar.DAY_OF_MONTH, 1)
    return c.timeInMillis
  }

  /** "7:30", "07:30", "19:05" → (hour, minute). */
  fun parseTime(text: String): Pair<Int, Int>? {
    val m = Regex("""^\s*(\d{1,2})[:.h](\d{2})\s*$""").find(text) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h to min else null
  }
}

/** "2 min 5 s" style text for a duration. */
fun describeDuration(ms: Long): String {
  val total = (ms / 1000).coerceAtLeast(0)
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return listOfNotNull(
    if (h > 0) "${h}h" else null,
    if (m > 0) "${m}m" else null,
    if (s > 0 || (h == 0L && m == 0L)) "${s}s" else null,
  ).joinToString(" ")
}
