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
 * One question and the answer adding up to it. Port of hey-muse's `turn.go`: Muse never links its
 * answer to the question, so any message that starts after ours (after the stream echoes our
 * message, or after the acknowledgment) belongs to this turn.
 */
class MuseTurn {
  private class Reply(val id: String, var text: String = "", var done: Boolean = false)

  var messageId: String = ""
    private set
  private val pending = mutableListOf<ChatEvent>()
  private var oursSeen = false
  private val replies = mutableListOf<Reply>()
  /** Muse saying it is still working, which holds the turn open through a pause. */
  private var busy = false
  /** What Muse heard: for a voice note, the transcript. */
  var heard: String = ""
    private set
  var lastEventAt: Long = System.currentTimeMillis()
    private set

  @Synchronized
  fun add(e: ChatEvent, now: Long = System.currentTimeMillis()) {
    lastEventAt = now
    when (e.event) {
      "agent.status", "task.status" -> {
        val activity = e.payload.opt("activity_code") as? String
        val status = e.payload.opt("status") as? String
        busy = when {
          !activity.isNullOrEmpty() -> activity != "online" && activity != "idle"
          !status.isNullOrEmpty() -> status != "completed" && status != "failed"
          else -> busy
        }
      }
      "message.user", "delta.message_start", "delta.text_append", "delta.message_done", "message.assistant" ->
        if (messageId.isNotEmpty()) apply(e, afterAck = true) else if (pending.size < 256) pending += e
    }
  }

  /** Our message's id from the /chat/stream acknowledgment; replays what arrived before it. */
  @Synchronized
  fun acknowledged(id: String) {
    messageId = id
    pending.forEach { apply(it, afterAck = false) }
    pending.clear()
  }

  private fun apply(e: ChatEvent, afterAck: Boolean) {
    val id = e.messageId
    if (e.event == "message.user") {
      if (id != messageId) return
      oursSeen = true
      val said = transcript(e.str("display_text"))
      if (said.isNotEmpty() && e.ready) heard = said
      return
    }
    var reply = replies.firstOrNull { it.id == id }
    if (reply == null) {
      if (e.event == "delta.text_append") return
      val parent = e.str("reply_to_message_id").ifEmpty { e.str("parent_message_id") }
      val linked = parent.isNotEmpty() && (parent == messageId || replies.any { it.id == parent })
      val follows = parent.isEmpty() && (oursSeen || afterAck)
      if (!(linked || follows) || replies.size >= 32) return
      reply = Reply(id).also { replies += it }
    }
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
