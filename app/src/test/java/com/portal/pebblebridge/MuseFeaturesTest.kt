package com.portal.pebblebridge

import com.portal.pebblebridge.home.HomePrefs
import com.portal.pebblebridge.home.Timers
import com.portal.pebblebridge.muse.ChatEvent
import com.portal.pebblebridge.muse.ControlDecoder
import com.portal.pebblebridge.muse.LineBuffer
import com.portal.pebblebridge.muse.MuseTurn
import com.portal.pebblebridge.muse.PortalCommands
import com.portal.pebblebridge.muse.Speaker
import com.portal.pebblebridge.muse.ackMessageId
import com.portal.pebblebridge.muse.pcmToWav
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MuseFeaturesTest {

  @After fun tearDown() { Timers.clear(); HomePrefs.update { it.copy(themeIndex = 0, museCommands = true) } }

  private fun framed(json: String): ByteArray {
    val b = json.toByteArray()
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size).array() + b
  }

  @Test fun `control decoder joins messages split across chunks and skips keepalives`() {
    val d = ControlDecoder()
    val all = framed("""{"method":"link.invoke","id":"1"}""") + framed("") + framed("""{"event":"x"}""")
    assertEquals(0, d.feed(all.copyOfRange(0, 10)).size)
    val out = d.feed(all.copyOfRange(10, all.size))
    assertEquals(listOf("link.invoke", ""), out.map { it.optString("method") })
    assertEquals("x", out[1].optString("event"))
  }

  @Test fun `line buffer splits NDJSON across chunks`() {
    val lb = LineBuffer()
    val stream = "{\"a\":1}\n{\"b\":2}\n{\"c\"".toByteArray()
    assertEquals(emptyList<String>(), lb.feed(stream.copyOfRange(0, 4)))
    assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), lb.feed(stream.copyOfRange(4, stream.size)))
    assertEquals(listOf("{\"c\":3}"), lb.feed(":3}\n".toByteArray()))
  }

  private fun ev(event: String, payload: String) = ChatEvent.parse("""{"type":"event","event":"$event","payload":$payload}""")!!

  @Test fun `turn collects the answer that follows our message, even if events beat the ack`() {
    val t = MuseTurn()
    // Events race the acknowledgment: our message echoes, then the reply starts.
    t.add(ev("message.user", """{"message_id":"m1","display_text":"what time is it\n[file:audio/wav x.wav]"}"""))
    t.add(ev("delta.message_start", """{"message_id":"r1","parent_message_id":"m1"}"""))
    t.add(ev("delta.text_append", """{"message_id":"r1","text":"It's "}"""))
    t.acknowledged("m1")
    t.add(ev("delta.text_append", """{"message_id":"r1","text":"noon."}"""))
    assertEquals("what time is it", t.heard)
    assertEquals("It's noon.", t.text())
    assertFalse(t.whole())
    t.add(ev("delta.message_done", """{"message_id":"r1"}"""))
    assertTrue(t.whole())
  }

  @Test fun `turn ignores other people's messages and waits while Muse is busy`() {
    val t = MuseTurn()
    t.acknowledged("m1")
    t.add(ev("message.user", """{"message_id":"other","display_text":"hi"}"""))
    t.add(ev("agent.status", """{"activity_code":"thinking"}"""))
    t.add(ev("message.assistant", """{"message_id":"r1","display_text":"Done"}"""))
    assertEquals("", t.heard)
    assertEquals("Done", t.text())
    assertFalse("busy holds the turn open", t.whole())
    t.add(ev("agent.status", """{"activity_code":"idle"}"""))
    assertTrue(t.whole())
  }

  @Test fun `ack message id is read from either shape`() {
    assertEquals("a", ackMessageId("""{"message_id":"a"}"""))
    assertEquals("b", ackMessageId("""{"result":{"message_id":"b"}}"""))
    assertEquals("", ackMessageId("not json"))
  }

  @Test fun `wav header describes 16 kHz mono 16-bit PCM`() {
    val wav = pcmToWav(ByteArray(3200))
    val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("RIFF", String(wav, 0, 4))
    assertEquals(36 + 3200, bb.getInt(4))
    assertEquals(1, bb.getShort(22).toInt())
    assertEquals(16_000, bb.getInt(24))
    assertEquals(3200, bb.getInt(40))
    assertEquals(44 + 3200, wav.size)
  }

  @Test fun `alarm times parse and roll to tomorrow`() {
    assertEquals(7 to 30, Timers.parseTime("7:30"))
    assertEquals(19 to 5, Timers.parseTime("19.05"))
    assertNull(Timers.parseTime("25:00"))
    val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 7, 8, 0, 0) }
    val next = Calendar.getInstance().apply { timeInMillis = Timers.nextOccurrence(7, 30, now) }
    assertEquals(8, next.get(Calendar.DAY_OF_MONTH))
  }

  @Test fun `timers come due and can be cancelled by label`() {
    Timers.startTimer(1, "TEA", now = 0)
    Timers.startTimer(600, "EGGS", now = 0)
    assertEquals(listOf("TEA"), Timers.takeDue(now = 2_000).map { it.label })
    assertEquals(1, Timers.cancel("egg"))
    assertTrue(Timers.timers.value.isEmpty())
  }

  @Test fun `commands are announced in the SDK commands_v2 shape`() {
    val spec = PortalCommands.registerSpec(enabled = true)
    val timer = spec.getJSONObject("portal.start_timer")
    assertEquals("integer", timer.getJSONObject("required").getJSONObject("seconds").getString("type"))
    assertTrue(timer.has("optional"))
    assertEquals(0, PortalCommands.registerSpec(enabled = false).length())
  }

  @Test fun `invoke runs a command and reports errors as link result fields`() {
    val ok = PortalCommands.invoke("portal.set_theme", JSONObject().put("name", "ice"))
    assertTrue(ok.getBoolean("ok"))
    assertEquals(2, HomePrefs.settings.value.themeIndex)
    val missing = PortalCommands.invoke("portal.start_timer", JSONObject())
    assertFalse(missing.getBoolean("ok"))
    assertEquals("missing parameter: seconds", missing.getString("error"))
    assertFalse(PortalCommands.invoke("system.run", JSONObject()).getBoolean("ok"))
    HomePrefs.update { it.copy(museCommands = false) }
    assertFalse(PortalCommands.invoke("portal.celebrate", JSONObject()).getBoolean("ok"))
  }

  @Test fun `speaker cleans markdown and detects Vietnamese`() {
    assertEquals("Hello world", Speaker.forSpeech("**Hello** [world](https://x.y)"))
    assertTrue(Speaker.looksVietnamese("nhắc mình mua sữa"))
    assertFalse(Speaker.looksVietnamese("buy milk"))
    assertEquals(2, Speaker.chunks("a".repeat(30) + ". " + "b".repeat(30), 40).size)
  }
}
