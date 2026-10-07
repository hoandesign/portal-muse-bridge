package com.portal.pebblebridge.home

import com.portal.pebblebridge.muse.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the robot's bubble is playing: one page of one item. */
data class NowPlaying(val historyId: String, val label: String, val pages: List<String>, val page: Int, val queued: Int) {
  val text: String get() = pages[page]
}

/**
 * Auto play: finished answers, Muse's messages and timers are shown (and spoken) one after
 * another, so a second answer never interrupts the first. Long text is split into pages that
 * flip like a Game Boy dialog. With auto play off, items only go to [History].
 */
object Playback {
  /** Characters per bubble page (about six lines of the bubble at 34 sp). */
  const val PAGE_CHARS = 160
  private const val TYPE_MS_PER_CHAR = 35L
  private const val PAGE_PAUSE_MS = 1_800L
  private const val BETWEEN_ITEMS_MS = 2_500L

  private val queue = MutableStateFlow<List<String>>(emptyList())
  private val _now = MutableStateFlow<NowPlaying?>(null)
  val now: StateFlow<NowPlaying?> = _now.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var worker: Job? = null
  private var current: Job? = null

  /** Queues a history entry for playing; [force] plays even with auto play off (from History). */
  fun enqueue(historyId: String, force: Boolean = false, front: Boolean = false) {
    if (!force && !HomePrefs.settings.value.autoPlay) return
    queue.update { q -> if (historyId in q) q else if (front) listOf(historyId) + q else q + historyId }
    ensureWorker()
  }

  /** Plays an entry right away (tapped in History), ahead of anything queued. */
  fun playNow(historyId: String) {
    queue.update { listOf(historyId) + (it - historyId) }
    skip()
    ensureWorker()
  }

  /** Tap on the bubble: stop this item and go to the next. */
  fun skip() {
    Speaker.stop()
    current?.cancel()
  }

  fun queuedCount() = queue.value.size

  private fun ensureWorker() {
    if (worker?.isActive == true) return
    worker = scope.launch {
      while (true) {
        val id = queue.first { it.isNotEmpty() }.first()
        queue.update { it - id }
        val entry = History.get(id) ?: continue
        History.update(id) { it.copy(played = true) }
        val job = launch { play(entry) }
        current = job
        job.join()
        _now.value = null
        if (queue.value.isNotEmpty()) delay(600)
      }
    }
  }

  private suspend fun play(entry: HistoryEntry) {
    val pages = paginate(entry.answer)
    if (pages.isEmpty()) return
    if (HomePrefs.settings.value.speakAnswers) Speaker.speak(entry.answer)
    for ((i, page) in pages.withIndex()) {
      _now.value = NowPlaying(entry.id, entry.label, pages, i, queue.value.size)
      delay(page.length * TYPE_MS_PER_CHAR + PAGE_PAUSE_MS)
    }
    // Let speech finish, then linger: briefly if more is waiting, longer otherwise.
    withTimeoutOrNull(5 * 60_000L) { Speaker.speaking.first { !it } }
    delay(if (queue.value.isNotEmpty()) BETWEEN_ITEMS_MS else HomePrefs.settings.value.bubbleSeconds * 1000L)
  }

  /** Splits text into bubble pages at word boundaries (paragraph breaks start a new page). */
  fun paginate(text: String, max: Int = PAGE_CHARS): List<String> {
    val pages = mutableListOf<String>()
    for (para in listOf(text.trim().replace(Regex("\\s+"), " ")).filter { it.isNotEmpty() }) {
      var line = StringBuilder()
      for (word in para.split(' ')) {
        if (line.isNotEmpty() && line.length + 1 + word.length > max) {
          pages += line.toString(); line = StringBuilder()
        }
        if (word.length > max) {
          word.chunked(max).forEach { if (line.isNotEmpty()) { pages += line.toString(); line = StringBuilder() }; pages += it }
          continue
        }
        if (line.isNotEmpty()) line.append(' ')
        line.append(word)
      }
      if (line.isNotEmpty()) pages += line.toString()
    }
    return pages
  }
}
