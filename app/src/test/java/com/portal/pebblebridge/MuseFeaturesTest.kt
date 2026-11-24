package com.portal.pebblebridge

import com.portal.pebblebridge.home.HomePrefs
import com.portal.pebblebridge.home.Timers
import com.portal.pebblebridge.muse.ChatEvent
import com.portal.pebblebridge.muse.ControlDecoder
import com.portal.pebblebridge.muse.LineBuffer
import com.portal.pebblebridge.muse.MuseTurn
import com.portal.pebblebridge.muse.TurnRouter
import com.portal.pebblebridge.home.History
import com.portal.pebblebridge.home.HistoryEntry
import com.portal.pebblebridge.home.Playback
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
    assertEquals(10, d.pendingBytes())
    val out = d.feed(all.copyOfRange(10, all.size))
    assertEquals(0, d.pendingBytes())
    assertEquals(listOf("link.invoke", ""), out.map { it.optString("method") })
    assertEquals("x", out[1].optString("event"))
  }

  @Test fun `control decoder reassembles header and body split byte-by-byte across BodyChunks`() {
    val d = ControlDecoder()
    val msg1 = """{"method":"link.invoke","id":"inv-1","command":"device.guide","params":{}}"""
    val msg2 = """{"id":"reg-1","type":"res","result":{"status":"registered"}}"""
    val wire = framed(msg1) + framed("") + framed(msg2)

    val collected = mutableListOf<JSONObject>()
    // Feed empty chunk + 1-byte chunks across the 4-byte length header and into the body, then small slices.
    assertEquals(emptyList<JSONObject>(), d.feed(ByteArray(0)))
    for (i in 0 until 12) {
      collected += d.feed(byteArrayOf(wire[i]))
    }
    var pos = 12
    while (pos < wire.size) {
      val end = minOf(wire.size, pos + 7)
      collected += d.feed(wire.copyOfRange(pos, end))
      pos = end
    }
    assertEquals(0, d.pendingBytes())
    assertEquals(2, collected.size)
    assertEquals("device.guide", collected[0].getString("command"))
    assertEquals("reg-1", collected[1].getString("id"))
  }

  @Test fun `control decoder drops malformed or non-object records and keeps decoding`() {
    val d = ControlDecoder()
    val wire = framed("""{"id":"before"}""") +
      framed("{not json") +
      framed("[1,2,3]") +
      framed("""{"id":"after"}""")
    val out = d.feed(wire)
    assertEquals(listOf("before", "after"), out.map { it.getString("id") })
  }

  @Test fun `control decoder rejects oversized unsigned lengths and reset clears partial state`() {
    val d = ControlDecoder(maxMessage = 1024)
    val partial = framed("""{"id":"abandoned"}""").copyOfRange(0, 8)
    assertEquals(0, d.feed(partial).size)
    assertEquals(8, d.pendingBytes())
    d.reset()
    assertEquals(0, d.pendingBytes())
    val fresh = d.feed(framed("""{"id":"fresh"}"""))
    assertEquals(listOf("fresh"), fresh.map { it.getString("id") })

    // Unsigned 0x80000000 (2 GiB) length prefix must fail and clear buffer.
    val hugeHeader = byteArrayOf(0x00, 0x00, 0x00, 0x80.toByte())
    var threw = false
    try {
      d.feed(hugeHeader)
    } catch (_: IllegalArgumentException) {
      threw = true
    }
    assertTrue("Oversized u32 length must throw IllegalArgumentException", threw)
    assertEquals(0, d.pendingBytes())
  }

  @Test fun `line buffer splits NDJSON across chunks`() {
    val lb = LineBuffer()
    val stream = "{\"a\":1}\n{\"b\":2}\n{\"c\"".toByteArray()
    assertEquals(emptyList<String>(), lb.feed(stream.copyOfRange(0, 4)))
    assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), lb.feed(stream.copyOfRange(4, stream.size)))
    assertEquals(listOf("{\"c\":3}"), lb.feed(":3}\n".toByteArray()))
  }

  private fun ev(event: String, payload: String) = ChatEvent.parse("""{"type":"event","event":"$event","payload":$payload}""")!!

  @Test fun `answer is routed to our question even when events beat the ack`() {
    val r = TurnRouter(); val t = MuseTurn(); r.open(t)
    r.add(ev("message.user", """{"message_id":"m1","display_text":"what time is it\n[file:audio/wav x.wav]"}"""), 1)
    r.add(ev("delta.message_start", """{"message_id":"r1"}"""), 2)
    r.add(ev("delta.text_append", """{"message_id":"r1","text":"It's "}"""), 3)
    r.acknowledged(t, "m1", 4)
    r.add(ev("delta.text_append", """{"message_id":"r1","text":"noon."}"""), 5)
    assertEquals("what time is it", t.heard)
    assertEquals("It's noon.", t.text())
    assertFalse(t.whole())
    r.add(ev("delta.message_done", """{"message_id":"r1"}"""), 6)
    assertTrue(t.whole())
  }

  @Test fun `two queued notes answered in one reply - the earlier is marked merged`() {
    // The sequence captured from Muse: ack A, echo A, ack B, echo B, then ONE reply naming neither.
    val r = TurnRouter(); val a = MuseTurn(); val b = MuseTurn()
    r.open(a); r.acknowledged(a, "A")
    r.add(ev("message.user", """{"message_id":"A","display_text":"fruit?"}"""))
    r.open(b); r.acknowledged(b, "B")
    r.add(ev("message.user", """{"message_id":"B","display_text":"color?"}"""))
    r.add(ev("agent.status", """{"activity_code":"responding"}"""))
    r.add(ev("delta.message_start", """{"message_id":"R"}"""))
    r.add(ev("delta.text_append", """{"message_id":"R","parent_message_id":"R","text":"Apple and blue."}"""))
    r.add(ev("agent.status", """{"activity_code":"online"}"""))
    r.add(ev("delta.message_done", """{"message_id":"R"}"""))
    assertEquals("B", a.mergedInto)
    assertEquals("", a.text())
    assertEquals("Apple and blue.", b.text())
    assertTrue(b.whole())
  }

  @Test fun `a reply already streaming stays with its question when another is asked`() {
    val r = TurnRouter(); val a = MuseTurn(); val b = MuseTurn()
    r.open(a); r.acknowledged(a, "A")
    r.add(ev("delta.message_start", """{"message_id":"RA"}"""))
    r.add(ev("delta.text_append", """{"message_id":"RA","text":"First "}"""))
    r.open(b)
    r.add(ev("delta.text_append", """{"message_id":"RA","text":"answer."}""")) // B not acked yet
    r.acknowledged(b, "B")
    r.add(ev("delta.message_done", """{"message_id":"RA"}"""))
    r.add(ev("delta.message_start", """{"message_id":"RB"}"""))
    r.add(ev("delta.text_append", """{"message_id":"RB","text":"Second."}"""))
    assertEquals("First answer.", a.text())
    assertEquals(null, a.mergedInto)
    assertEquals("Second.", b.text())
  }

  @Test fun `other people's messages are ignored`() {
    val r = TurnRouter(); val t = MuseTurn(); r.open(t); r.acknowledged(t, "m1")
    r.add(ev("message.user", """{"message_id":"other","display_text":"hi"}"""))
    r.add(ev("agent.status", """{"activity_code":"thinking"}"""))
    r.add(ev("message.assistant", """{"message_id":"r1","display_text":"Done"}"""))
    assertEquals("", t.heard)
    assertEquals("Done", t.text())
    assertFalse("busy holds the turn open", t.whole())
    r.add(ev("agent.status", """{"activity_code":"idle"}"""))
    assertTrue(t.whole())
  }

  @Test fun `long answers flip through pages at word boundaries`() {
    val words = List(60) { "word$it" }.joinToString(" ")
    val pages = Playback.paginate(words, max = 50)
    assertTrue(pages.size > 1)
    assertTrue(pages.all { it.length <= 50 })
    assertEquals(words, pages.joinToString(" "))
    assertEquals(listOf("short"), Playback.paginate("  short \n\n "))
  }

  @Test fun `history survives a restart and unfinished questions become no-answer`() {
    val list = listOf(
      HistoryEntry("1", 10, HistoryEntry.Kind.RING, "hi", "hello", HistoryEntry.Status.ANSWERED, played = false),
      HistoryEntry("2", 20, HistoryEntry.Kind.VOICE, "q", "", HistoryEntry.Status.WAITING),
    )
    val back = History.decode(History.encode(list))
    assertEquals(list[0], back[0])
    assertEquals(HistoryEntry.Status.NO_ANSWER, back[1].status)
    assertEquals(1, History.unseen(back))
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
    assertTrue(spec.has("device.guide"))
    val guideSpec = spec.getJSONObject("device.guide")
    assertEquals(0, guideSpec.getJSONObject("required").length())
    assertEquals(0, guideSpec.getJSONObject("optional").length())
    val timer = spec.getJSONObject("portal.start_timer")
    assertEquals("integer", timer.getJSONObject("required").getJSONObject("seconds").getString("type"))
    assertTrue(timer.has("optional"))
    assertTrue(spec.has("portal.anilist_lists"))
    assertTrue(spec.has("portal.anilist_airing"))
    assertTrue(spec.has("portal.anilist_search"))
    assertTrue(spec.has("portal.anilist_update"))
    assertFalse("device.ota must not be listed (upstream #402)", spec.has("device.ota"))

    // When Portal action commands are disabled, only read-only device.guide remains.
    val disabled = PortalCommands.registerSpec(enabled = false)
    assertEquals(1, disabled.length())
    assertTrue(disabled.has("device.guide"))
  }

  @Test fun `device guide conforms to protocols DEVICE md contract and describes Portal capabilities`() {
    val res = PortalCommands.invoke("device.guide", JSONObject())
    assertTrue(res.getBoolean("ok"))
    val payload = res.getJSONObject("payload")
    assertEquals("markdown", payload.getString("format"))
    val guide = payload.getString("guide")
    val bytes = guide.toByteArray(Charsets.UTF_8).size
    assertTrue("guide must be 1..4096 UTF-8 bytes, was $bytes", bytes in 1..4096)
    assertTrue("guide should stay concise (<= 2048 bytes), was $bytes", bytes <= 2048)
    assertTrue("guide must start with a top-level heading", guide.startsWith("# "))
    assertFalse("guide must not contain fenced code blocks", guide.contains("```"))
    assertFalse("guide must not contain raw HTML tags", Regex("<[a-zA-Z][^>]*>").containsMatchIn(guide))
    // Covers required Portal capabilities
    val lower = guide.lowercase()
    assertTrue(lower.contains("pixel home"))
    assertTrue(lower.contains("ring notes"))
    assertTrue(lower.contains("timers"))
    assertTrue(lower.contains("voice"))
    assertTrue(lower.contains("speech") || lower.contains("tts"))
    assertTrue(lower.contains("weather"))
    assertTrue(lower.contains("portalani"))

    // device.guide remains available even when action commands are toggled off.
    HomePrefs.update { it.copy(museCommands = false) }
    val whileDisabled = PortalCommands.invoke("device.guide", null)
    assertTrue(whileDisabled.getBoolean("ok"))
    assertEquals(guide, whileDisabled.getJSONObject("payload").getString("guide"))
  }

  @Test fun `link register message matches protocols DEVICE md identity contract`() {
    val msg = com.portal.pebblebridge.muse.DeviceGuide.registerMessage(
      requestId = "00000000-0000-0000-0000-000000000000",
      nodeId = "homelink-e1890c",
      version = "1.1.0",
      commands = PortalCommands.registerSpec(enabled = true),
    )
    assertEquals("req", msg.getString("type"))
    assertEquals("link.register", msg.getString("method"))
    val params = msg.getJSONObject("params")
    assertEquals("gadget", params.getString("device_family"))
    val modelId = params.getString("model_id")
    assertEquals("meta-portal-gen1", modelId)
    assertFalse("custom model_id must not use reserved muse- prefix", modelId.startsWith("muse-"))
    assertFalse("custom model_id must not use reserved esp-link prefix", modelId.startsWith("esp-link"))
    assertEquals("Meta Portal (1st Gen)", params.getJSONObject("metadata").getString("model_name"))
    assertTrue(params.getJSONObject("commands_v2").has("device.guide"))

    // Valid boundary values pass
    com.portal.pebblebridge.muse.DeviceGuide.validateModel("a".repeat(64), "M".repeat(128), "g".repeat(4096))

    // Validation rejects out-of-spec values, reserved prefixes, control chars, and boundary overflows
    val invalidCases = listOf(
      Triple("Bad_ID", "Meta Portal", "# OK"),
      Triple("", "Meta Portal", "# OK"),
      Triple("a".repeat(65), "Meta Portal", "# OK"),
      Triple("muse-custom", "Meta Portal", "# OK"),
      Triple("esp-link", "Meta Portal", "# OK"),
      Triple("meta-portal", "", "# OK"),
      Triple("meta-portal", "M".repeat(129), "# OK"),
      Triple("meta-portal", "Bad \"Quote\"", "# OK"),
      Triple("meta-portal", "Bad\\Slash", "# OK"),
      Triple("meta-portal", "Bad\nNewline", "# OK"),
      Triple("meta-portal", "Bad\rReturn", "# OK"),
      Triple("meta-portal", "Bad\tTab", "# OK"),
      Triple("meta-portal", "Bad\u007fDel", "# OK"),
      Triple("meta-portal", "NonASCII \u00e9", "# OK"),
      Triple("meta-portal", "Meta Portal", ""),
      Triple("meta-portal", "Meta Portal", "a".repeat(4097)),
    )
    for ((id, name, guide) in invalidCases) {
      var failed = false
      try {
        com.portal.pebblebridge.muse.DeviceGuide.validateModel(id, name, guide)
      } catch (_: IllegalArgumentException) {
        failed = true
      }
      assertTrue("Expected validation failure for ($id, $name, len=${guide.length})", failed)
    }
  }

  @Test fun `anilist commands forward to PortalAniClient`() {
    com.portal.pebblebridge.muse.PortalAniClient.testHandler = { action, params ->
      JSONObject()
        .put("ok", true)
        .put("action", action)
        .put("status", params.optString("status"))
        .put("mediaId", params.optInt("mediaId"))
        .put("progress", params.optInt("progress"))
    }
    try {
      val lists = PortalCommands.invoke("portal.anilist_lists", JSONObject().put("status", "WATCHING"))
      assertTrue(lists.getBoolean("ok"))
      assertEquals("WATCHING", lists.getJSONObject("payload").getString("status"))

      val upd = PortalCommands.invoke("portal.anilist_update", JSONObject().put("media_id", 154587).put("progress", 12))
      assertTrue(upd.getBoolean("ok"))
      assertEquals(154587, upd.getJSONObject("payload").getInt("mediaId"))
      assertEquals(12, upd.getJSONObject("payload").getInt("progress"))

      val badScore = PortalCommands.invoke("portal.anilist_update", JSONObject().put("media_id", 154587).put("score", "not-a-number"))
      assertFalse(badScore.getBoolean("ok"))
      val badProg = PortalCommands.invoke("portal.anilist_update", JSONObject().put("media_id", 154587).put("progress", "abc"))
      assertFalse(badProg.getBoolean("ok"))
    } finally {
      com.portal.pebblebridge.muse.PortalAniClient.testHandler = null
    }
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

  @Test fun `Vietnamese is only read aloud with a Vietnamese voice`() {
    assertTrue(Speaker.shouldSpeak(isVietnamese = false, hasVietnameseVoice = false))
    assertFalse(Speaker.shouldSpeak(isVietnamese = true, hasVietnameseVoice = false))
    assertTrue(Speaker.shouldSpeak(isVietnamese = true, hasVietnameseVoice = true))
  }

  @Test fun `timers and alarms survive a restart`() {
    val list = listOf(com.portal.pebblebridge.home.PortalTimer("a", "WAKE", 1_000, isAlarm = true))
    assertEquals(list, Timers.decode(Timers.encode(list)))
  }

  @Test fun `markdown is cleaned for the dialog box and long answers are capped`() {
    assertEquals("Use alarm.set and bold here", Playback.forDisplay("Use `alarm.set` and **bold** here"))
    assertEquals("Title\n• one", Playback.forDisplay("## Title\n- one"))
    val pages = Playback.capPages(List(20) { "p$it" })
    assertEquals(Playback.MAX_PAGES, pages.size)
    assertTrue(pages.last().contains("HISTORY"))
  }

  private class FakeWebSocket : okhttp3.WebSocket {
    val sentFrames = java.util.concurrent.CopyOnWriteArrayList<okio.ByteString>()
    @Volatile var closedCode: Int? = null
    @Volatile var closedReason: String? = null

    override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("https://example.invalid").build()
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean = true
    override fun send(bytes: okio.ByteString): Boolean {
      sentFrames.add(bytes)
      return true
    }
    override fun close(code: Int, reason: String?): Boolean {
      closedCode = code
      closedReason = reason
      return true
    }
    override fun cancel() { closedCode = -1 }
  }

  @Test fun `control stream handles multi-BodyChunk device guide invoke and closes on end_body`() {
    val key1 = ByteArray(32) { 0x55.toByte() }
    val key2 = ByteArray(32) { 0x66.toByte() }
    val clientTransport = com.portal.pebblebridge.muse.NoiseTransport(
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key1) },
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key2) },
    )
    val serverTransport = com.portal.pebblebridge.muse.NoiseTransport(
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key2) },
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key1) },
    )

    val ws = FakeWebSocket()
    val client = com.portal.pebblebridge.muse.MuseLinkClient()
    client.attachSessionForTesting(ws, clientTransport, controlStreamId = 1L, registerId = "reg-42")

    val invokeJson = JSONObject()
      .put("method", "link.invoke")
      .put("id", "inv-guide-1")
      .put("command", "device.guide")
      .put("params", JSONObject())
      .toString()
      .toByteArray(Charsets.UTF_8)
    val wire = com.portal.pebblebridge.muse.NoiseTransport.encodeLengthPrefixedMessage(invokeJson)

    // Split inside the 4-byte header (2 bytes in Resp, 2 bytes in Chunk 1) and across body chunks
    client.handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(
        1L,
        com.portal.pebblebridge.muse.ServiceFramePayload.Resp(
          com.portal.pebblebridge.muse.ApplicationResponse(status = 200, body = wire.copyOfRange(0, 2))
        ),
      )
    )
    client.handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(
        1L,
        com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(wire.copyOfRange(2, 10))),
      )
    )
    client.handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(
        1L,
        com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(wire.copyOfRange(10, 30))),
      )
    )
    client.handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(
        1L,
        com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(wire.copyOfRange(30, wire.size))),
      )
    )

    val deadline = System.currentTimeMillis() + 3_000L
    while (ws.sentFrames.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20)
    }
    assertTrue("Expected link.result frame for device.guide", ws.sentFrames.isNotEmpty())
    assertNull("Session should stay open after valid multi-chunk invoke", ws.closedCode)

    val sentFrame = serverTransport.decryptFrame(ws.sentFrames[0].toByteArray())!!
    val sentChunk = (sentFrame.payload as com.portal.pebblebridge.muse.ServiceFramePayload.Chunk).chunk
    val decodedBytes = com.portal.pebblebridge.muse.NoiseTransport.decodeLengthPrefixedMessage(sentChunk.data)!!
    val resultJson = JSONObject(String(decodedBytes, Charsets.UTF_8))
    assertEquals("link.result", resultJson.getString("method"))
    assertEquals("inv-guide-1", resultJson.getString("id"))
    assertTrue(resultJson.getBoolean("ok"))
    val payload = resultJson.getJSONObject("payload")
    assertEquals("markdown", payload.getString("format"))
    assertEquals(com.portal.pebblebridge.muse.DeviceGuide.GUIDE_MARKDOWN, payload.getString("guide"))

    // end_body=true on /link-control BodyChunk must close WebSocket so client reconnects (upstream #470)
    client.handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(
        1L,
        com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(ByteArray(0), endBody = true)),
      )
    )
    assertEquals(1000, ws.closedCode)
    assertEquals("control stream ended", ws.closedReason)
  }

  @Test fun `control stream closes session on HTTP refusal, register rejection, or unpaired event`() {
    val key1 = ByteArray(32) { 0x71.toByte() }
    val key2 = ByteArray(32) { 0x72.toByte() }
    fun newClient(ws: FakeWebSocket, regId: String): com.portal.pebblebridge.muse.MuseLinkClient =
      com.portal.pebblebridge.muse.MuseLinkClient().apply {
        attachSessionForTesting(
          ws,
          com.portal.pebblebridge.muse.NoiseTransport(
            com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key1) },
            com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key2) },
          ),
          controlStreamId = 1L,
          registerId = regId,
        )
      }

    // 1. Non-2xx HTTP status on /link-control closes immediately without poisoning ControlDecoder
    val ws1 = FakeWebSocket()
    newClient(ws1, "reg-1").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(
        1L,
        com.portal.pebblebridge.muse.ServiceFramePayload.Resp(
          com.portal.pebblebridge.muse.ApplicationResponse(status = 403, body = "{\"error\":\"forbidden\"}".toByteArray(), endBody = true)
        ),
      )
    )
    assertEquals(1000, ws1.closedCode)
    assertTrue(ws1.closedReason?.contains("403") == true)

    // 2. link.register rejected with ok=false closes WebSocket
    val ws2 = FakeWebSocket()
    val rejectWire = com.portal.pebblebridge.muse.NoiseTransport.encodeLengthPrefixedMessage("""{"id":"reg-2","ok":false}""".toByteArray(Charsets.UTF_8))
    newClient(ws2, "reg-2").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(1L, com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(rejectWire)))
    )
    assertEquals(1000, ws2.closedCode)
    assertEquals("link.register rejected", ws2.closedReason)

    // 3. link.unpaired closes WebSocket
    val ws3 = FakeWebSocket()
    val unpairedWire = com.portal.pebblebridge.muse.NoiseTransport.encodeLengthPrefixedMessage("""{"event":"link.unpaired"}""".toByteArray(Charsets.UTF_8))
    newClient(ws3, "reg-3").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(1L, com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(unpairedWire)))
    )
    assertEquals(1000, ws3.closedCode)
    assertEquals("unpaired", ws3.closedReason)

    // 4. link.register rejected via error string closes WebSocket; empty error string does NOT reject
    val ws4 = FakeWebSocket()
    val errWire = com.portal.pebblebridge.muse.NoiseTransport.encodeLengthPrefixedMessage("""{"id":"reg-4","error":"denied"}""".toByteArray(Charsets.UTF_8))
    newClient(ws4, "reg-4").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(1L, com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(errWire)))
    )
    assertEquals(1000, ws4.closedCode)
    assertEquals("link.register rejected", ws4.closedReason)

    val ws5 = FakeWebSocket()
    val okEmptyErrWire = com.portal.pebblebridge.muse.NoiseTransport.encodeLengthPrefixedMessage("""{"id":"reg-5","error":""}""".toByteArray(Charsets.UTF_8))
    newClient(ws5, "reg-5").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(1L, com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(okEmptyErrWire)))
    )
    assertNull("Empty error string must not reject link.register", ws5.closedCode)

    // 5. Reset and Resp(200, endBody=true) on control stream close WebSocket
    val ws6 = FakeWebSocket()
    newClient(ws6, "reg-6").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(1L, com.portal.pebblebridge.muse.ServiceFramePayload.Reset(2, "cancelled"))
    )
    assertEquals(1000, ws6.closedCode)
    assertEquals("control stream reset", ws6.closedReason)

    val ws7 = FakeWebSocket()
    newClient(ws7, "reg-7").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(1L, com.portal.pebblebridge.muse.ServiceFramePayload.Resp(com.portal.pebblebridge.muse.ApplicationResponse(status = 200, endBody = true)))
    )
    assertEquals(1000, ws7.closedCode)
    assertEquals("control stream ended", ws7.closedReason)

    // 6. node.unpaired followed by link.invoke in the same BodyChunk closes and stops dispatching trailing invokes
    val ws8 = FakeWebSocket()
    val batchWire = com.portal.pebblebridge.muse.NoiseTransport.encodeLengthPrefixedMessage("""{"event":"node.unpaired"}""".toByteArray(Charsets.UTF_8)) +
      com.portal.pebblebridge.muse.NoiseTransport.encodeLengthPrefixedMessage("""{"method":"link.invoke","id":"inv-after-unpair","command":"device.guide","params":{}}""".toByteArray(Charsets.UTF_8))
    newClient(ws8, "reg-8").handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(1L, com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(batchWire)))
    )
    Thread.sleep(100)
    assertEquals(1000, ws8.closedCode)
    assertTrue("Trailing link.invoke after node.unpaired must not run", ws8.sentFrames.isEmpty())
  }

  @Test fun `device guide works via client invoke on chat subscribe and stale sockets cannot clobber live session`() {
    val key1 = ByteArray(32) { 0x21.toByte() }
    val key2 = ByteArray(32) { 0x22.toByte() }
    val clientTransport = com.portal.pebblebridge.muse.NoiseTransport(
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key1) },
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key2) },
    )
    val serverTransport = com.portal.pebblebridge.muse.NoiseTransport(
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key2) },
      com.portal.pebblebridge.muse.CipherState().apply { initializeKey(key1) },
    )

    val oldWs = FakeWebSocket()
    val liveWs = FakeWebSocket()
    val client = com.portal.pebblebridge.muse.MuseLinkClient()
    client.attachSessionForTesting(liveWs, clientTransport, controlStreamId = 1L, registerId = "reg-live", subscribeStreamId = 2L)

    // Stale socket cleanup and sendControl must not affect the live session
    assertFalse("Stale resetSessionState must return false", client.resetSessionState(oldWs))
    assertEquals(
      com.portal.pebblebridge.muse.MuseLinkClient.ControlSendResult.NOT_CONNECTED,
      client.sendControl(oldWs, JSONObject().put("method", "link.result").put("id", "stale")),
    )
    assertTrue("Stale send must not emit frames on liveWs", liveWs.sentFrames.isEmpty())

    // Oversized control message returns TOO_LARGE without sending
    val huge = JSONObject().put("method", "link.result").put("id", "big").put("data", "x".repeat(com.portal.pebblebridge.muse.DeviceGuide.MAX_CONTROL_MESSAGE_BYTES))
    assertEquals(
      com.portal.pebblebridge.muse.MuseLinkClient.ControlSendResult.TOO_LARGE,
      client.sendControl(liveWs, huge),
    )
    assertTrue(liveWs.sentFrames.isEmpty())

    // client.invoke on /chat/subscribe invokes device.guide and returns link.result on /link-control
    val ndjson = """{"type":"event","event":"client.invoke","payload":{"invoke_id":"ci-1","command_id":"device.guide","params_json":"{}"}}""" + "\n"
    client.handleInboundFrame(
      com.portal.pebblebridge.muse.ServiceFrame(2L, com.portal.pebblebridge.muse.ServiceFramePayload.Chunk(com.portal.pebblebridge.muse.BodyChunk(ndjson.toByteArray(Charsets.UTF_8))))
    )
    val deadline = System.currentTimeMillis() + 3_000L
    while (liveWs.sentFrames.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20)
    }
    assertTrue("Expected link.result for client.invoke device.guide", liveWs.sentFrames.isNotEmpty())
    val sentFrame = serverTransport.decryptFrame(liveWs.sentFrames[0].toByteArray())!!
    val sentChunk = (sentFrame.payload as com.portal.pebblebridge.muse.ServiceFramePayload.Chunk).chunk
    val decoded = JSONObject(String(com.portal.pebblebridge.muse.NoiseTransport.decodeLengthPrefixedMessage(sentChunk.data)!!, Charsets.UTF_8))
    assertEquals("ci-1", decoded.getString("id"))
    assertTrue(decoded.getBoolean("ok"))
    assertEquals("markdown", decoded.getJSONObject("payload").getString("format"))
  }

  @Test fun `control decoder survives deeply nested JSON without stalling subsequent records`() {
    val d = ControlDecoder()
    val depth = 5000
    val deep = "[".repeat(depth) + "]".repeat(depth)
    val wire = framed("""{"id":"before"}""") + framed(deep) + framed("""{"id":"after"}""")
    val out = d.feed(wire)
    assertEquals(listOf("before", "after"), out.map { it.getString("id") })
  }
}
