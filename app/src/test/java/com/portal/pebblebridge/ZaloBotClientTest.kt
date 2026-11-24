package com.portal.pebblebridge

import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.muse.MuseLinkClient
import com.portal.pebblebridge.state.BridgeRepository
import com.portal.pebblebridge.zalo.ZaloBotClient
import com.portal.pebblebridge.zalo.ZaloIncomingMessage
import java.io.IOException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ZaloBotClientTest {

  private data class RecordedCall(val path: String, val body: JSONObject)

  private val recorded = mutableListOf<RecordedCall>()

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
    BridgeRepository.resetForTesting()
    recorded.clear()
  }

  @Test
  fun parseUpdatesHandlesOfficialSingleObjectAndArrayAndIgnoresBots() {
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
  fun chunkForZaloSplitsLongRepliesWithinLimit() {
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
  }

  @Test
  fun sendMessageFallsBackToPlainTextWhenMarkdownRejected() = runTest {
    val http = mockHttpClient { path, body ->
      assertTrue(path.endsWith("/sendMessage"))
      if (body.optString("parse_mode") == "markdown") {
        400 to """{"ok":false,"error_code":400,"description":"bad markdown"}"""
      } else {
        200 to """{"ok":true,"result":{"message_id":"sent-1"}}"""
      }
    }

    val client = ZaloBotClient(scope = this, httpClient = http)
    val res = client.sendMessage("secret-token-xyz", "chat-1", "Hello *broken")
    assertTrue(res.isSuccess)
    assertEquals(2, recorded.size)
    assertEquals("markdown", recorded[0].body.optString("parse_mode"))
    assertEquals("", recorded[1].body.optString("parse_mode"))
  }

  @Test
  fun getUpdatesTreats408AsEmptyAndNeverLeaksTokenOn401() = runTest {
    val http408 = mockHttpClient { _, _ -> 408 to """{"ok":false,"error_code":408}""" }
    val c408 = ZaloBotClient(scope = this, httpClient = http408)
    val res408 = c408.getUpdates("top-secret-bot-token", 30)
    assertTrue(res408.isSuccess)
    assertTrue(res408.getOrThrow().isEmpty())

    val http401 = mockHttpClient { _, _ -> 401 to """{"ok":false,"error_code":401,"description":"Unauthorized"}""" }
    val c401 = ZaloBotClient(scope = this, httpClient = http401)
    val res401 = c401.getUpdates("top-secret-bot-token", 30)
    assertTrue(res401.isFailure)
    val errMsg = res401.exceptionOrNull()?.message.orEmpty()
    assertTrue(errMsg.contains("401"))
    assertFalse("Error message must never contain bot token", errMsg.contains("top-secret-bot-token"))
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun handleIncomingMessageBridgesToMuseAndRepliesOnZalo() = runTest {
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

    val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
    val client = ZaloBotClient(
      scope = this,
      httpClient = http,
      ioDispatcher = testDispatcher,
      museSender = { text, sessionId, onAnswer ->
        sentToMuseText = text
        sentToMuseSession = sessionId
        onAnswer(MuseLinkClient.AnswerUpdate(heard = "", text = "Chào bạn, Muse đây!", done = true))
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
  }

  @Test
  fun handleIncomingMessageNotifiesZaloWhenMuseOffline() = runTest {
    BridgeRepository.updateConfig(BridgeConfig(zaloBotToken = "tok-123"))
    val http = mockHttpClient { _, _ -> 200 to """{"ok":true,"result":{"message_id":"ok"}}""" }

    val client = ZaloBotClient(
      scope = this,
      httpClient = http,
      museSender = { _, _, _ -> Result.failure(IOException("Meta Muse Cloud AI is not connected")) },
    )

    client.handleIncomingMessage(
      "tok-123",
      ZaloIncomingMessage("m2", "chat-owner", "u1", "Hoan", "ping"),
    )

    assertEquals(NoteStatus.FAILED, BridgeRepository.notes.value.first().status)
    val sentMessages = recorded.filter { it.path.endsWith("/sendMessage") }
    assertEquals(1, sentMessages.size)
    assertTrue(sentMessages[0].body.getString("text").contains("Meta Muse"))
  }
}
