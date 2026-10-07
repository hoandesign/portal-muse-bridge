package com.portal.pebblebridge.muse

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * Splits the /link-control stream into length-prefixed JSON messages (little-endian u32 length).
 * A message may span body chunks, so bytes are buffered until it is complete. Port of the SDK's
 * `link_client.MessageDecoder`.
 */
class ControlDecoder(private val maxMessage: Int = 4 * 1024 * 1024) {
  private var buf = ByteArray(0)

  fun feed(data: ByteArray): List<JSONObject> {
    buf += data
    val out = mutableListOf<JSONObject>()
    while (buf.size >= 4) {
      val length = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
      require(length in 0..maxMessage) { "inbound control message too large: $length" }
      if (buf.size < 4 + length) break
      val raw = buf.copyOfRange(4, 4 + length)
      buf = buf.copyOfRange(4 + length, buf.size)
      if (raw.isEmpty()) continue // keepalive
      runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()?.let(out::add)
    }
    return out
  }
}

/** Splits the /chat/subscribe stream into lines: one JSON event per line (NDJSON). */
class LineBuffer(private val maxLine: Int = 256 * 1024) {
  private var buf = ByteArray(0)

  fun feed(data: ByteArray): List<String> {
    buf += data
    val lines = mutableListOf<String>()
    while (true) {
      val end = buf.indexOf('\n'.code.toByte())
      if (end < 0) break
      lines += String(buf, 0, end, Charsets.UTF_8)
      buf = buf.copyOfRange(end + 1, buf.size)
    }
    require(buf.size <= maxLine) { "event line too long" }
    return lines
  }
}

/** One event from /chat/subscribe. Fields are read loosely, as the reference clients do. */
class ChatEvent(val json: JSONObject) {
  val type: String get() = json.optString("type")
  val event: String get() = json.optString("event")
  val payload: JSONObject get() = json.optJSONObject("payload") ?: JSONObject()

  fun str(key: String): String = payload.opt(key) as? String ?: ""

  val messageId: String
    get() = str("message_id").ifEmpty { json.opt("message_id") as? String ?: "" }.ifEmpty { str("id") }

  /** False only when the event says outright that its text is not final yet. */
  val ready: Boolean get() = payload.opt("display_text_ready") != false

  companion object {
    fun parse(line: String): ChatEvent? =
      runCatching { ChatEvent(JSONObject(line)) }.getOrNull()?.takeIf { it.type == "event" }
  }
}

/**
 * One question and the answer adding up to it (after hey-muse's `turn.go`). Which events belong
 * to which question is decided by [TurnRouter], since several questions can be open at once.
 */
class MuseTurn {
  private class Reply(val id: String, var text: String = "", var done: Boolean = false)

  var messageId: String = ""
    private set
  private val replies = mutableListOf<Reply>()
  /** Muse saying it is still working, which holds the turn open through a pause. */
  private var busy = false
  /** What Muse heard: for a voice note, the transcript. */
  var heard: String = ""
    private set
  var lastEventAt: Long = System.currentTimeMillis()
    private set
  /** Set when Muse answered this question together with a later one, in that one's reply. */
  @Volatile var mergedInto: String? = null

  @Synchronized fun acknowledged(id: String) { messageId = id }

  @Synchronized fun status(e: ChatEvent, now: Long) {
    lastEventAt = now
    val activity = e.payload.opt("activity_code") as? String
    val status = e.payload.opt("status") as? String
    busy = when {
      !activity.isNullOrEmpty() -> activity != "online" && activity != "idle"
      !status.isNullOrEmpty() -> status != "completed" && status != "failed"
      else -> busy
    }
  }

  /** Muse's copy of our message; for a voice note its text is the transcript. */
  @Synchronized fun user(e: ChatEvent, now: Long) {
    lastEventAt = now
    val said = transcript(e.str("display_text"))
    if (said.isNotEmpty() && e.ready) heard = said
  }

  @Synchronized fun owns(replyId: String) = replies.any { it.id == replyId }
  @Synchronized fun hasReplies() = replies.isNotEmpty()

  @Synchronized fun reply(e: ChatEvent, now: Long) {
    lastEventAt = now
    val id = e.messageId
    val reply = replies.firstOrNull { it.id == id } ?: Reply(id).also { if (replies.size < 32) replies += it }
    when (e.event) {
      "delta.message_start" -> Unit
      "delta.text_append" -> reply.text = (reply.text + e.str("text")).take(MAX_TEXT)
      else -> {
        val final = e.str("display_text").ifEmpty { e.str("content") }
        if (final.isNotEmpty()) reply.text = final.take(MAX_TEXT)
        if (e.event == "delta.message_done" || e.ready) reply.done = true
      }
    }
  }

  /** The answer so far: every reply message with words, a blank line between them. */
  @Synchronized
  fun text(): String = replies.map { it.text }.filter { it.isNotEmpty() }.joinToString("\n\n")

  /** Complete as far as anyone can tell: words, every reply done, Muse not working on more. */
  @Synchronized
  fun whole(): Boolean = !busy && replies.isNotEmpty() && replies.all { it.done } && text().isNotEmpty()

  companion object {
    private const val MAX_TEXT = 256 * 1024

    /** A stored voice note's text is the words then a "[file:audio/wav …]" line; keep the words. */
    fun transcript(display: String): String =
      display.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[file:") }.joinToString(" ")
  }
}

/**
 * Routes /chat/subscribe events to the open questions. Several can be open at once (two quick
 * ring notes, or a voice question while a note's answer streams).
 *
 * Seen on a real Muse: when two messages are queued, Muse answers them in ONE reply that names
 * neither. So a new reply goes to the newest question still waiting, and older waiting questions
 * are marked [MuseTurn.mergedInto] it. A reply that already started stays with its question.
 * Events are held while any open question still lacks its id (they can beat the acknowledgment).
 */
class TurnRouter {
  private val open = mutableListOf<MuseTurn>()
  private val pending = mutableListOf<ChatEvent>()

  @Synchronized fun open(t: MuseTurn) { open += t }
  @Synchronized fun close(t: MuseTurn) { open -= t; if (open.isEmpty()) pending.clear() }
  @Synchronized fun isOpen(t: MuseTurn) = t in open
  @Synchronized fun openCount() = open.size

  @Synchronized fun acknowledged(t: MuseTurn, id: String, now: Long = System.currentTimeMillis()) {
    t.acknowledged(id)
    val held = pending.toList()
    pending.clear()
    held.forEach { if (!route(it, now) && pending.size < 256) pending += it }
  }

  @Synchronized fun add(e: ChatEvent, now: Long = System.currentTimeMillis()) {
    if (open.isEmpty()) return
    if (!route(e, now) && pending.size < 256) pending += e
  }

  /** False when the event must wait for an acknowledgment. */
  private fun route(e: ChatEvent, now: Long): Boolean {
    val unacked = open.any { it.messageId.isEmpty() }
    when (e.event) {
      "agent.status", "task.status" -> open.forEach { it.status(e, now) }
      "message.user" -> {
        val t = open.firstOrNull { it.messageId == e.messageId } ?: return !unacked
        t.user(e, now)
      }
      "delta.message_start", "delta.text_append", "delta.message_done", "message.assistant" -> {
        val id = e.messageId
        open.firstOrNull { it.owns(id) }?.let { it.reply(e, now); return true }
        val parent = e.str("reply_to_message_id").ifEmpty { e.str("parent_message_id") }
        if (parent.isNotEmpty() && parent != id) {
          open.firstOrNull { it.messageId == parent || it.owns(parent) }?.let { it.reply(e, now); return true }
        }
        if (unacked) return false
        if (e.event == "delta.text_append") return true // the start of a reply we don't follow
        val waiting = open.filter { !it.hasReplies() && it.mergedInto == null }
        val owner = waiting.lastOrNull() ?: return true // not an answer to us
        waiting.dropLast(1).forEach { it.mergedInto = owner.messageId }
        owner.reply(e, now)
      }
    }
    return true
  }
}

/** The message id from the /chat/stream acknowledgment: `message_id` or `result.message_id`. */
fun ackMessageId(body: String): String = runCatching {
  val o = JSONObject(body)
  (o.optJSONObject("result")?.optString("message_id")).orEmpty().ifEmpty { o.optString("message_id") }
}.getOrDefault("")

/** A 16-bit mono PCM recording as a WAV file, the format Muse transcribes. */
fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
  val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
    put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
    putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
    put("data".toByteArray()); putInt(pcm.size)
  }.array()
  return header + pcm
}
