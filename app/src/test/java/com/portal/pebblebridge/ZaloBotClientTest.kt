package com.portal.pebblebridge

import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.muse.MuseLinkClient
import com.portal.pebblebridge.state.BridgeRepository
import com.portal.pebblebridge.zalo.ZaloApiException
import com.portal.pebblebridge.zalo.ZaloBotClient
import com.portal.pebblebridge.zalo.ZaloIncomingMessage
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ZaloBotClientTest {

  private data class RecordedCall(val path: String, val body: JSONObject)

  private val recorded = Collections.synchronizedList(mutableListOf<RecordedCall>())
  private lateinit var savedConfig: BridgeConfig

  private fun mockHttpClient(handler: (String, JSONObject) -> Pair<Int, String>): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val buf = Buffer()
        req.body?.writeTo(buf)
        val json = buf.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        recorded += RecordedCall(req.url.encodedPath, json)
        val (code, respBody) = handler(req.url.encodedPath, json)
        Response.Builder()
          .request(req)
          .protocol(Protocol.HTTP_1_1)
          .code(code)
          .message("mock")
          .body(respBody.toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

  @Before
  fun setUp() {
    savedConfig = BridgeRepository.config.value
    BridgeRepository.resetForTesting()
    recorded.clear()
  }

  @After
  fun tearDown() {
    BridgeRepository.updateConfig(savedConfig)
    BridgeRepository.resetForTesting()
  }

  @Test
  fun parseUpdatesHandlesOfficialSingleObjectArrayJsonNullAndIgnoresBots() {
    val officialSingle = """
      {
        "ok": true,
        "result": {
          "event_name": "message.text.received",
          "message": {
            "message_id": "2d758cb5e222177a4e35",
            "date": 1750316131602,
            "message_type": "CHAT_TEXT",
            "from": { "id": "6ede9afa66b88fe6d6a9", "display_name": "Ted", "is_bot": false },
            "chat": { "id": "6ede9afa66b88fe6d6a9", "chat_type": "PRIVATE" },
            "text": "Hello Muse"
          }
        }
      }
    """.trimIndent()

    val parsed = ZaloBotClient.parseUpdates(officialSingle)
    assertEquals(1, parsed.size)
    assertEquals("2d758cb5e222177a4e35", parsed[0].messageId)
    assertEquals("6ede9afa66b88fe6d6a9", parsed[0].chatId)
    assertEquals("Ted", parsed[0].senderName)
    assertEquals("Hello Muse", parsed[0].text)

    // Explicit JSON null for text (sticker/image event) must NOT become literal "null"
    val explicitNullText = """
      {
        "ok": true,
        "result": {
          "event_name": "message.sticker.received",
          "message": {
            "message_id": "m-sticker",
            "from": { "id": "u1", "display_name": null, "is_bot": false },
            "chat": { "id": "c1" },
            "text": null
          }
        }
      }
    """.trimIndent()
    assertTrue("Explicit JSON null text must be ignored", ZaloBotClient.parseUpdates(explicitNullText).isEmpty())

    val arrayWithBot = """
      {
        "ok": true,
        "result": [
          {
            "event_name": "message.text.received",
            "message": {
              "message_id": "m-bot",
              "from": { "id": "bot1", "display_name": "Bot", "is_bot": true },
              "chat": { "id": "c1" },
              "text": "echo"
            }
          },
          {
            "event_name": "message.text.received",
            "message": {
              "message_id": "m-human",
              "from": { "id": "u1", "display_name": "Hoan", "is_bot": false },
              "chat": { "id": "c1" },
              "text": "nhac toi 5 phut nua"
            }
          }
        ]
      }
    """.trimIndent()

    val arrayParsed = ZaloBotClient.parseUpdates(arrayWithBot)
    assertEquals(1, arrayParsed.size)
    assertEquals("m-human", arrayParsed[0].messageId)

    assertTrue(ZaloBotClient.parseUpdates("""{"ok":true,"result":null}""").isEmpty())
    assertTrue(ZaloBotClient.parseUpdates("""{"ok":false,"error_code":408}""").isEmpty())
  }

  @Test
  fun isChatAllowedSupportsOpenAndCommaSeparatedWhitelist() {
    assertTrue(ZaloBotClient.isChatAllowed("chat-123", ""))
    assertTrue(ZaloBotClient.isChatAllowed("chat-123", "   "))
    assertTrue(ZaloBotClient.isChatAllowed("chat-123", "chat-111, chat-123, chat-999"))
    assertFalse(ZaloBotClient.isChatAllowed("chat-stranger", "chat-111, chat-123"))
    assertFalse(ZaloBotClient.isChatAllowed("", ""))
  }

  @Test
  fun chunkForZaloSplitsLongRepliesWithinLimitAndPreservesSurrogatePairs() {
    assertEquals(listOf("Short reply"), ZaloBotClient.chunkForZalo("Short reply", maxLen = 50))

    val longText = (1..30).joinToString("\n") { "Line $it with some Vietnamese content xin chao Muse." }
    val chunks = ZaloBotClient.chunkForZalo(longText, maxLen = 200)
    assertTrue(chunks.size > 1)
    assertTrue(chunks.all { it.length in 1..200 })

    val unbreakable = "a".repeat(4500)
    val hardChunks = ZaloBotClient.chunkForZalo(unbreakable, maxLen = 1900)
    assertEquals(3, hardChunks.size)
    assertEquals(4500, hardChunks.sumOf { it.length })
    assertTrue(hardChunks.all { it.length in 1..1900 })

    // Emoji surrogate pair straddling the boundary must not be split in half
    val emoji = "\uD83D\uDE80" // rocket emoji (2 UTF-16 code units)
    val straddling = "a".repeat(9) + emoji + "b".repeat(5)
    val emojiChunks = ZaloBotClient.chunkForZalo(straddling, maxLen = 10)
    assertEquals(2, emojiChunks.size)
    assertFalse("First chunk must not end with lone high surrogate", Character.isHighSurrogate(emojiChunks[0].last()))
    assertTrue("Second chunk must start with intact emoji", emojiChunks[1].startsWith(emoji))
  }

  @Test
  fun sendMessageFallsBackToPlainTextOnlyOn400Not500() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val http400 = mockHttpClient { path, body ->
      assertTrue(path.endsWith("/sendMessage"))
      if (body.optString("parse_mode") == "markdown") {
        400 to """{"ok":false,"error_code":400,"description":"bad markdown"}"""
      } else {
        200 to """{"ok":true,"result":{"message_id":"sent-1"}}"""
      }
    }

    val client400 = ZaloBotClient(scope = this, httpClient = http400, ioDispatcher = dispatcher)
    val res = client400.sendMessage("secret-token-xyz", "chat-1", "Hello *broken")
    assertTrue(res.isSuccess)
    assertEquals(2, recorded.size)
    assertEquals("markdown", recorded[0].body.optString("parse_mode"))
    assertEquals("", recorded[1].body.optString("parse_mode"))

    // On HTTP 500, must NOT retry with plain text (avoids duplicate messages on transient errors)
    recorded.clear()
    val http500 = mockHttpClient { _, _ -> 500 to """{"ok":false,"error_code":500}""" }
    val client500 = ZaloBotClient(scope = this, httpClient = http500, ioDispatcher = dispatcher)
    val res500 = client500.sendMessage("secret-token-xyz", "chat-1", "Hello")
    assertTrue(res500.isFailure)
    assertEquals(1, recorded.size)
  }

  @Test
  fun getUpdatesHandles408CaptivePortalNonJsonMalformedTokenAndRedactsToken() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)

    // 408 timeout -> empty success
    val http408 = mockHttpClient { _, _ -> 408 to """{"ok":false,"error_code":408}""" }
    val c408 = ZaloBotClient(scope = this, httpClient = http408, ioDispatcher = dispatcher)
    val res408 = c408.getUpdates("top-secret-bot-token", 30)
    assertTrue(res408.isSuccess)
    assertTrue(res408.getOrThrow().isEmpty())

    // HTTP 200 HTML (captive portal) -> failure (never tight spin loop)
    val httpHtml = mockHttpClient { _, _ -> 200 to "<html><body>Captive Portal</body></html>" }
    val cHtml = ZaloBotClient(scope = this, httpClient = httpHtml, ioDispatcher = dispatcher)
    assertTrue(cHtml.getUpdates("top-secret-bot-token", 30).isFailure)

    // 401 with echoed token in description -> structured ZaloApiException(401) and token redacted
    val http401 = mockHttpClient { _, _ ->
      401 to """{"ok":false,"error_code":401,"description":"bad token top-secret-bot-token"}"""
    }
    val c401 = ZaloBotClient(scope = this, httpClient = http401, ioDispatcher = dispatcher)
    val res401 = c401.getUpdates("top-secret-bot-token", 30)
    assertTrue(res401.isFailure)
    val ex401 = res401.exceptionOrNull()
    assertTrue(ex401 is ZaloApiException && ex401.code == 401)
    assertFalse("Error message must never contain bot token", ex401?.message.orEmpty().contains("top-secret-bot-token"))

    // Malformed token with spaces/newlines -> safe failure without crashing or leaking token
    val badToken = "secret-with space\nnewline"
    val resBad = c408.getUpdates(badToken, 30)
    assertTrue(resBad.isFailure)
    assertFalse(resBad.exceptionOrNull()?.message.orEmpty().contains(badToken))
  }

  @Test
  fun getMeAndDeleteWebhookEncodeSpecialTokenCharsSafely() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val http = mockHttpClient { path, _ ->
      when {
        path.endsWith("/getMe") -> 200 to """{"ok":true,"result":{"display_name":"Portal Bot"}}"""
        path.endsWith("/deleteWebhook") -> 200 to """{"ok":true,"result":true}"""
        else -> 404 to """{"ok":false}"""
      }
    }
    val client = ZaloBotClient(scope = this, httpClient = http, ioDispatcher = dispatcher)
    val me = client.getMe("12345:abc?foo#bar")
    assertTrue(me.isSuccess)
    assertEquals("Portal Bot", me.getOrThrow())
    assertTrue(client.deleteWebhook("12345:abc?foo#bar").isSuccess)
    // '?' and '#' must be path-encoded, not interpreted as query/fragment
    assertTrue(recorded[0].path.endsWith("/getMe"))
  }

  @Test
  fun markSeenIfNewDeduplicatesAndEvictsOldest() = runTest {
    val client = ZaloBotClient(scope = this, ioDispatcher = StandardTestDispatcher(testScheduler))
    assertTrue(client.markSeenIfNew("m-1"))
    assertFalse(client.markSeenIfNew("m-1"))
    for (i in 2..300) {
      assertTrue(client.markSeenIfNew("m-$i"))
    }
    // Oldest entry ("m-1") was evicted past MAX_SEEN_IDS (256)
    assertTrue(client.markSeenIfNew("m-1"))
  }

  @Test
  fun handleIncomingMessageBridgesToMuseAndSkipsReplyIfTokenRotated() = runTest {
    BridgeRepository.updateConfig(
      BridgeConfig(
        museSessionId = "sess-42",
        zaloBotToken = "tok-123",
        zaloAllowedChatIds = "chat-owner",
      )
    )
    val http = mockHttpClient { _, _ -> 200 to """{"ok":true,"result":{"message_id":"ok"}}""" }
    var sentToMuseText = ""
    var sentToMuseSession = ""
    var lateAnswerCallback: ((MuseLinkClient.AnswerUpdate) -> Unit)? = null

    val testDispatcher = StandardTestDispatcher(testScheduler)
    val client = ZaloBotClient(
      scope = this,
      httpClient = http,
      ioDispatcher = testDispatcher,
      museSender = { text, sessionId, onAnswer ->
        sentToMuseText = text
        sentToMuseSession = sessionId
        if (text == "slow") {
          lateAnswerCallback = onAnswer
        } else {
          onAnswer(MuseLinkClient.AnswerUpdate(heard = "", text = "Chào bạn, Muse đây!", done = true))
        }
        Result.success("msg-ack-1")
      },
    )

    // Non-whitelisted chat is ignored (no Muse call, no sendMessage)
    client.handleIncomingMessage(
      "tok-123",
      ZaloIncomingMessage("m0", "chat-stranger", "u0", "Stranger", "spam"),
    )
    assertEquals("", sentToMuseText)
    assertEquals("chat-stranger", BridgeRepository.zaloBotStatus.value.lastChatId)
    assertTrue(recorded.isEmpty())

    // Whitelisted owner chat goes to Muse and sends reply back to Zalo
    client.handleIncomingMessage(
      "tok-123",
      ZaloIncomingMessage("m1", "chat-owner", "u1", "Hoan", "Thời tiết hôm nay sao?"),
    )
    advanceUntilIdle()

    assertEquals("Thời tiết hôm nay sao?", sentToMuseText)
    assertEquals("sess-42", sentToMuseSession)
    assertEquals(1, BridgeRepository.notes.value.size)
    assertEquals(NoteStatus.DELIVERED, BridgeRepository.notes.value[0].status)
    assertEquals("Chào bạn, Muse đây!", BridgeRepository.notes.value[0].museReply)

    val sentMessages = recorded.filter { it.path.endsWith("/sendMessage") }
    assertEquals(1, sentMessages.size)
    assertEquals("chat-owner", sentMessages[0].body.getString("chat_id"))
    assertEquals("Chào bạn, Muse đây!", sentMessages[0].body.getString("text"))

    // If token is disabled/rotated before a slow Muse answer completes, must NOT send with old token
    recorded.clear()
    client.handleIncomingMessage(
      "tok-123",
      ZaloIncomingMessage("m2", "chat-owner", "u1", "Hoan", "slow"),
    )
    advanceUntilIdle()
    BridgeRepository.updateConfig(BridgeRepository.config.value.copy(zaloBotToken = ""))
    lateAnswerCallback?.invoke(MuseLinkClient.AnswerUpdate(heard = "", text = "Late reply", done = true))
    advanceUntilIdle()
    assertTrue(recorded.none { it.path.endsWith("/sendMessage") })
    client.stop()
  }

  @Test
  fun handleIncomingMessageNotifiesZaloWhenMuseOffline() = runTest {
    BridgeRepository.updateConfig(BridgeConfig(zaloBotToken = "tok-123"))
    val http = mockHttpClient { _, _ -> 200 to """{"ok":true,"result":{"message_id":"ok"}}""" }
    val testDispatcher = StandardTestDispatcher(testScheduler)

    val client = ZaloBotClient(
      scope = this,
      httpClient = http,
      ioDispatcher = testDispatcher,
      museSender = { _, _, _ -> Result.failure(IOException("Meta Muse Cloud AI is not connected")) },
    )

    client.handleIncomingMessage(
      "tok-123",
      ZaloIncomingMessage("m2", "chat-owner", "u1", "Hoan", "ping"),
    )
    advanceUntilIdle()

    assertEquals(NoteStatus.FAILED, BridgeRepository.notes.value.first().status)
    val sentMessages = recorded.filter { it.path.endsWith("/sendMessage") }
    assertEquals(1, sentMessages.size)
    assertTrue(sentMessages[0].body.getString("text").contains("Meta Muse"))
    client.stop()
  }
}
