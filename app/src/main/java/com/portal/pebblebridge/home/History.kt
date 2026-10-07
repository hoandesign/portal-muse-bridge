package com.portal.pebblebridge.home

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One thing that happened with Muse, kept for the History screen. */
data class HistoryEntry(
  val id: String,
  val time: Long,
  val kind: Kind,
  /** What was asked: the ring note's text or what Muse heard. Empty for messages and timers. */
  val question: String = "",
  /** Muse's answer, message or the timer's text. */
  val answer: String = "",
  val status: Status = Status.WAITING,
  /** Shown/spoken at least once (auto-play or from History). */
  val played: Boolean = false,
) {
  enum class Kind { RING, VOICE, MESSAGE, TIMER }
  enum class Status { SENDING, WAITING, ANSWERED, MERGED, FAILED, NO_ANSWER }

  val label: String
    get() = when (kind) {
      Kind.RING, Kind.VOICE -> "MUSE"
      Kind.MESSAGE -> "MUSE"
      Kind.TIMER -> "TIMER"
    }
}

/** Notes, questions and Muse's answers, newest first, saved across restarts (last 200). */
@OptIn(FlowPreview::class)
object History {
  private const val TAG = "History"
  private const val MAX = 200
  private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
  val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()
  private var file: File? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun init(context: Context) {
    if (file != null) return
    val f = File(context.applicationContext.filesDir, "history.json")
    file = f
    _entries.value = runCatching { if (f.exists()) decode(f.readText()) else emptyList() }
      .onFailure { Log.w(TAG, "history unreadable; starting fresh", it) }
      .getOrDefault(emptyList())
    _entries.drop(1).debounce(500).onEach { list -> runCatching { f.writeText(encode(list)) } }.launchIn(scope)
  }

  fun add(entry: HistoryEntry) = _entries.update { (listOf(entry) + it.filterNot { e -> e.id == entry.id }).take(MAX) }

  fun update(id: String, change: (HistoryEntry) -> HistoryEntry) =
    _entries.update { list -> list.map { if (it.id == id) change(it) else it } }

  fun get(id: String): HistoryEntry? = _entries.value.firstOrNull { it.id == id }

  /** Answers nobody has seen yet (auto-play off, or they arrived while Settings was open). */
  fun unseen(list: List<HistoryEntry> = _entries.value) =
    list.count { !it.played && it.answer.isNotBlank() }

  fun clear() { _entries.value = emptyList() }

  fun encode(list: List<HistoryEntry>): String = JSONArray().apply {
    list.forEach {
      put(JSONObject().put("id", it.id).put("time", it.time).put("kind", it.kind.name)
        .put("question", it.question).put("answer", it.answer).put("status", it.status.name).put("played", it.played))
    }
  }.toString()

  fun decode(text: String): List<HistoryEntry> {
    val arr = JSONArray(text)
    return (0 until arr.length()).mapNotNull { i ->
      val o = arr.getJSONObject(i)
      runCatching {
        HistoryEntry(
          id = o.getString("id"),
          time = o.getLong("time"),
          kind = HistoryEntry.Kind.valueOf(o.getString("kind")),
          question = o.optString("question"),
          answer = o.optString("answer"),
          // Anything still waiting when the app stopped will never be answered now.
          status = HistoryEntry.Status.valueOf(o.optString("status", "ANSWERED")).let {
            if (it == HistoryEntry.Status.WAITING || it == HistoryEntry.Status.SENDING) HistoryEntry.Status.NO_ANSWER else it
          },
          played = o.optBoolean("played", true),
        )
      }.getOrNull()
    }
  }
}
