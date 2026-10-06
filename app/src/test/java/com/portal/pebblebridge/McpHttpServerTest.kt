package com.portal.pebblebridge

import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.server.McpHttpServer
import com.portal.pebblebridge.state.BridgeRepository
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpHttpServerTest {

  private val testPort = 18787
  private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var server: McpHttpServer

  @Before
  fun setUp() = runBlocking {
    BridgeRepository.clearNotes()
    BridgeRepository.updateConfig(BridgeConfig())
    server = McpHttpServer(port = testPort)
    server.start(testScope)
    delay(200) // Give server a moment to bind
  }

  @After
  fun tearDown() {
    server.stop()
    testScope.cancel()
  }

  @Test
  fun testHealthEndpoint() {
    val url = URL("http://127.0.0.1:$testPort/health")
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 3000
      readTimeout = 3000
    }
    assertEquals(200, conn.responseCode)
    val body = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(body)
    assertTrue(json.getBoolean("ok"))
    assertEquals("Portal Pebble Muse Bridge", json.getString("service"))
  }

  @Test
  fun testMcpInitialize() {
    val req = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("id", 1)
      put("method", "initialize")
    }
    val res = postJson("http://127.0.0.1:$testPort/api/mcp", req.toString())
    assertEquals(200, res.statusCode)
    val json = JSONObject(res.body)
    val result = json.getJSONObject("result")
    assertEquals("2024-11-05", result.getString("protocolVersion"))
    assertEquals("portal-pebble-muse-bridge", result.getJSONObject("serverInfo").getString("name"))
  }

  @Test
  fun testMcpPing() {
    val req = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("id", 42)
      put("method", "ping")
    }
    val res = postJson("http://127.0.0.1:$testPort/api/mcp", req.toString())
    assertEquals(200, res.statusCode)
    val json = JSONObject(res.body)
    assertEquals(42, json.getInt("id"))
    assertTrue(json.has("result"))
  }

  @Test
  fun testMcpNotificationsInitialized() {
    val req = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("method", "notifications/initialized")
    }
    val res = postJson("http://127.0.0.1:$testPort/api/mcp", req.toString())
    assertEquals(204, res.statusCode)
  }

  @Test
  fun testMcpToolsList() {
    val req = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("id", 2)
      put("method", "tools/list")
    }
    val res = postJson("http://127.0.0.1:$testPort/api/mcp", req.toString())
    assertEquals(200, res.statusCode)
    val json = JSONObject(res.body)
    val tools = json.getJSONObject("result").getJSONArray("tools")
    assertEquals(1, tools.length())
    assertEquals("send_muse_note", tools.getJSONObject(0).getString("name"))
  }

  @Test
  fun testMcpToolsCallUtf8MultiByteAndEmoji() {
    val utf8Text = "Gặp mặt ở quán cà phê lúc 10h sáng mai nha! 💍 Nhớ mang tài liệu."
    val req = JSONObject().apply {
      put("jsonrpc", "2.0")
      put("id", 3)
      put("method", "tools/call")
      put("params", JSONObject().apply {
        put("name", "send_muse_note")
        put("arguments", JSONObject().apply {
          put("text", utf8Text)
          put("title", "Cuộc họp sáng mai")
        })
      })
    }
    val res = postJson("http://127.0.0.1:$testPort/api/mcp", req.toString())
    assertEquals(200, res.statusCode)
    val json = JSONObject(res.body)
    val content = json.getJSONObject("result").getJSONArray("content")
    assertTrue(content.getJSONObject(0).getString("text").contains("cà phê"))

    // Verify state in repository preserves full UTF-8
    val notes = BridgeRepository.notes.value
    assertEquals(1, notes.size)
    assertEquals(utf8Text, notes[0].text)
    assertEquals("Cuộc họp sáng mai", notes[0].title)
    assertEquals(NoteStatus.DELIVERED, notes[0].status)
  }

  @Test
  fun testWebhookIngest() {
    val payload = JSONObject().apply {
      put("transcription", "Quick note from ring ingest")
    }
    val res = postJson("http://127.0.0.1:$testPort/ingest", payload.toString())
    assertEquals(200, res.statusCode)
    val json = JSONObject(res.body)
    assertTrue(json.getBoolean("ok"))

    val notes = BridgeRepository.notes.value
    assertEquals(1, notes.size)
    assertEquals("Quick note from ring ingest", notes[0].text)
  }

  @Test
  fun testAuthVerification() {
    BridgeRepository.updateConfig(BridgeConfig(mcpToken = "secret-token-123"))

    // Missing auth header -> 401
    val unauth = postJson("http://127.0.0.1:$testPort/api/mcp", "{\"method\":\"tools/list\"}")
    assertEquals(401, unauth.statusCode)

    // With correct Bearer token -> 200
    val auth = postJson(
      "http://127.0.0.1:$testPort/api/mcp",
      "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\"}",
      headers = mapOf("Authorization" to "Bearer secret-token-123")
    )
    assertEquals(200, auth.statusCode)
  }

  private data class HttpResponse(val statusCode: Int, val body: String)

  private fun postJson(urlString: String, json: String, headers: Map<String, String> = emptyMap()): HttpResponse {
    val url = URL(urlString)
    val conn = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = 3000
      readTimeout = 3000
      setRequestProperty("Content-Type", "application/json")
      headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    val bytes = json.toByteArray(Charsets.UTF_8)
    conn.outputStream.write(bytes)
    conn.outputStream.flush()

    val statusCode = conn.responseCode
    val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.readText().orEmpty()
    return HttpResponse(statusCode, body)
  }

  @Test
  fun `parses Pebble Index webhook multipart form`() {
    val boundary = "3f1c2d4e-0b7a-4c3e-9f5d-2a8b1e0c7d93"
    val body = listOf(
      "--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"r1.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n\u0000\u0001binary\r\n",
      "--$boundary\r\nContent-Disposition: form-data; name=\"transcription\"\r\n\r\nBuy milk and call mom\r\n",
      "--$boundary\r\nContent-Disposition: form-data; name=\"recordedAt\"\r\n\r\n1791303649000\r\n",
      "--$boundary\r\nContent-Disposition: form-data; name=\"client\"\r\n\r\nring\r\n",
      "--$boundary--\r\n",
    ).joinToString("")
    val fields = server.parseMultipartFields(body, "multipart/form-data; boundary=$boundary")
    assertEquals("Buy milk and call mom", fields["transcription"])
    assertEquals("ring", fields["client"])
    assertFalse("File parts must be skipped", fields.containsKey("audio"))
  }
}
