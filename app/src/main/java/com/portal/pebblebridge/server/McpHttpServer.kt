package com.portal.pebblebridge.server

import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.model.VoiceNote
import com.portal.pebblebridge.muse.MuseDeliveryClient
import com.portal.pebblebridge.state.BridgeRepository
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

class McpHttpServer(
  private val port: Int = 8787,
  private val deliveryClient: MuseDeliveryClient = MuseDeliveryClient(),
) {

  companion object {
    const val MAX_BODY_BYTES = 1024 * 1024 // 1 MB payload protection against OOM
  }

  private var serverSocket: ServerSocket? = null
  private var serverJob: Job? = null

  @Synchronized
  fun start(scope: CoroutineScope) {
    if (serverJob?.isActive == true) return

    serverJob = scope.launch(Dispatchers.IO) {
      try {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket

        BridgeRepository.updateServerStatus(
          isRunning = true,
          localIp = BridgeRepository.serverStatus.value.localIp,
          port = port,
        )

        supervisorScope {
          while (isActive && !socket.isClosed) {
            try {
              val client = socket.accept()
              launch(Dispatchers.IO) {
                try {
                  handleClient(client)
                } catch (_: Throwable) {
                  // Catch all connection errors so parent accept loop never terminates
                } finally {
                  try {
                    client.close()
                  } catch (_: Throwable) {}
                }
              }
            } catch (e: Exception) {
              if (!socket.isClosed) {
                BridgeRepository.updateServerStatus(
                  isRunning = true,
                  localIp = BridgeRepository.serverStatus.value.localIp,
                  port = port,
                  error = e.message,
                )
              }
            }
          }
        }
      } catch (e: Exception) {
        BridgeRepository.updateServerStatus(
          isRunning = false,
          localIp = BridgeRepository.serverStatus.value.localIp,
          port = port,
          error = "Server failed to start on port $port: ${e.message}",
        )
      }
    }
  }

  @Synchronized
  fun stop() {
    try {
      serverSocket?.close()
      serverSocket = null
      serverJob?.cancel()
      serverJob = null
      BridgeRepository.updateServerStatus(
        isRunning = false,
        localIp = BridgeRepository.serverStatus.value.localIp,
        port = port,
      )
    } catch (_: Exception) {}
  }

  private suspend fun handleClient(client: Socket) {
    client.soTimeout = 10000 // 10 second read timeout
    val input = client.getInputStream()
    val out = client.getOutputStream()

    // Read headers as raw bytes until \r\n\r\n
    val headerBytes = readHeaderBytes(input) ?: return
    val headerText = String(headerBytes, StandardCharsets.US_ASCII)
    val lines = headerText.split("\r\n").filter { it.isNotBlank() }
    if (lines.isEmpty()) return

    val requestLine = lines[0]
    val parts = requestLine.split(" ")
    if (parts.size < 2) return

    val method = parts[0].uppercase()
    val path = parts[1].split("?")[0]

    val headers = mutableMapOf<String, String>()
    for (i in 1 until lines.size) {
      val line = lines[i]
      val colonIdx = line.indexOf(':')
      if (colonIdx > 0) {
        val k = line.substring(0, colonIdx).trim().lowercase()
        val v = line.substring(colonIdx + 1).trim()
        headers[k] = v
      }
    }

    // Handle CORS preflight
    if (method == "OPTIONS") {
      sendNoContent(out)
      return
    }

    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
    if (contentLength > MAX_BODY_BYTES) {
      val errJson = JSONObject().apply {
        put("error", "Payload Too Large: exceeds 1MB limit")
      }
      sendResponse(out, 413, "Payload Too Large", errJson.toString(), "application/json")
      return
    }

    val bodyBytes = if (contentLength > 0) {
      readExactBytes(input, contentLength) ?: return
    } else {
      ByteArray(0)
    }
    val body = String(bodyBytes, StandardCharsets.UTF_8)

    when {
      method == "GET" && (path == "/health" || path == "/") -> {
        val res = JSONObject().apply {
          put("ok", true)
          put("service", "Portal Pebble Muse Bridge")
          put("port", port)
          put("notesReceived", BridgeRepository.serverStatus.value.totalNotesReceived)
        }
        sendResponse(out, 200, "OK", res.toString(), "application/json")
      }

      path == "/ingest" && method == "POST" -> {
        handleIngest(headers, body, out)
      }

      (path == "/api/mcp" || path == "/mcp") && method == "POST" -> {
        handleMcp(headers, body, out)
      }

      (path == "/api/mcp" || path == "/mcp") -> {
        val notAllowed = JSONObject().apply {
          put("ok", false)
          put("error", "Method Not Allowed")
          put("allow", "POST")
        }
        sendResponse(out, 405, "Method Not Allowed", notAllowed.toString(), "application/json")
      }

      else -> {
        val notFound = JSONObject().apply {
          put("ok", false)
          put("error", "Not Found")
          put("path", path)
        }
        sendResponse(out, 404, "Not Found", notFound.toString(), "application/json")
      }
    }
  }

  private fun readHeaderBytes(input: InputStream): ByteArray? {
    val baos = ByteArrayOutputStream()
    var state = 0
    val maxHeaderSize = 32 * 1024 // 32 KB header limit
    while (true) {
      val b = input.read()
      if (b == -1) break
      baos.write(b)
      if (baos.size() > maxHeaderSize) return null

      when (state) {
        0 -> if (b == '\r'.code) state = 1 else if (b == '\n'.code) state = 3
        1 -> if (b == '\n'.code) state = 2 else state = 0
        2 -> if (b == '\r'.code) state = 3 else state = 0
        3 -> if (b == '\n'.code) return baos.toByteArray() else state = 0
      }
    }
    return if (baos.size() > 0) baos.toByteArray() else null
  }

  private fun readExactBytes(input: InputStream, length: Int): ByteArray? {
    val buffer = ByteArray(length)
    var total = 0
    while (total < length) {
      val read = input.read(buffer, total, length - total)
      if (read == -1) break
      total += read
    }
    return if (total == length) buffer else null
  }

  private suspend fun handleIngest(
    headers: Map<String, String>,
    body: String,
    out: OutputStream,
  ) {
    val config = BridgeRepository.config.value
    if (!checkAuth(headers, config.mcpToken)) {
      sendResponse(out, 401, "Unauthorized", "{\"ok\":false,\"error\":\"Invalid token\"}", "application/json")
      return
    }

    val text = parseIncomingText(body, headers["content-type"].orEmpty())
    if (text.isBlank()) {
      sendResponse(out, 400, "Bad Request", "{\"ok\":false,\"error\":\"Empty text\"}", "application/json")
      return
    }

    val note = processNote(text, "Voice Ingest")
    val res = JSONObject().apply {
      put("ok", note.status != NoteStatus.FAILED)
      put("id", note.id)
      put("text", note.text)
      put("status", note.status.name)
      put("detail", note.museReply ?: note.error)
    }
    val statusCode = if (note.status != NoteStatus.FAILED) 200 else 502
    sendResponse(out, statusCode, if (statusCode == 200) "OK" else "Bad Gateway", res.toString(), "application/json")
  }

  private suspend fun handleMcp(
    headers: Map<String, String>,
    body: String,
    out: OutputStream,
  ) {
    val config = BridgeRepository.config.value
    if (!checkAuth(headers, config.mcpToken)) {
      val errJson = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", JSONObject.NULL)
        put("error", JSONObject().apply {
          put("code", -32001)
          put("message", "Unauthorized")
        })
      }
      sendResponse(out, 401, "Unauthorized", errJson.toString(), "application/json")
      return
    }

    if (body.isBlank()) {
      val errJson = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", JSONObject.NULL)
        put("error", JSONObject().apply {
          put("code", -32700)
          put("message", "Parse error: Empty body")
        })
      }
      sendResponse(out, 400, "Bad Request", errJson.toString(), "application/json")
      return
    }

    val req = try {
      JSONObject(body)
    } catch (e: Exception) {
      val errJson = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", JSONObject.NULL)
        put("error", JSONObject().apply {
          put("code", -32700)
          put("message", "Invalid JSON: ${e.message}")
        })
      }
      sendResponse(out, 400, "Bad Request", errJson.toString(), "application/json")
      return
    }

    val id = if (req.has("id") && !req.isNull("id")) req.get("id") else JSONObject.NULL
    val method = req.optString("method")
    val params = req.optJSONObject("params") ?: JSONObject()

    when (method) {
      "ping" -> {
        val response = JSONObject().apply {
          put("jsonrpc", "2.0")
          put("id", id)
          put("result", JSONObject())
        }
        sendResponse(out, 200, "OK", response.toString(), "application/json")
      }

      "initialize" -> {
        val result = JSONObject().apply {
          put("protocolVersion", "2024-11-05")
          put("capabilities", JSONObject().apply {
            put("tools", JSONObject().apply {
              put("listChanged", false)
            })
            put("prompts", JSONObject().apply {
              put("listChanged", false)
            })
          })
          put("serverInfo", JSONObject().apply {
            put("name", "portal-pebble-muse-bridge")
            put("title", "Portal Pebble Muse Bridge")
            put("version", "1.0.0")
          })
        }
        val response = JSONObject().apply {
          put("jsonrpc", "2.0")
          put("id", id)
          put("result", result)
        }
        sendResponse(out, 200, "OK", response.toString(), "application/json")
      }

      "notifications/initialized" -> {
        sendNoContent(out)
      }

      "tools/list" -> {
        val toolsArray = JSONArray().apply {
          put(JSONObject().apply {
            put("name", "send_muse_note")
            put("description", "Send a voice note or task from Pebble Index ring to Meta Muse AI")
            put("inputSchema", JSONObject().apply {
              put("type", "object")
              put("properties", JSONObject().apply {
                put("text", JSONObject().apply {
                  put("type", "string")
                  put("description", "The transcribed voice note or request from your Index ring")
                })
                put("title", JSONObject().apply {
                  put("type", "string")
                  put("description", "Optional brief title or subject")
                })
              })
              put("required", JSONArray().apply { put("text") })
            })
          })
        }
        val response = JSONObject().apply {
          put("jsonrpc", "2.0")
          put("id", id)
          put("result", JSONObject().apply {
            put("tools", toolsArray)
          })
        }
        sendResponse(out, 200, "OK", response.toString(), "application/json")
      }

      "tools/call" -> {
        val toolName = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        if (toolName == "send_muse_note") {
          val text = args.optString("text", "")
          val title = if (args.has("title") && !args.isNull("title")) args.getString("title") else null
          if (text.isBlank()) {
            val errRes = JSONObject().apply {
              put("jsonrpc", "2.0")
              put("id", id)
              put("error", JSONObject().apply {
                put("code", -32602)
                put("message", "Missing required argument 'text'")
              })
            }
            sendResponse(out, 200, "OK", errRes.toString(), "application/json")
            return
          }

          val note = processNote(text, title)
          val resultText = when (note.status) {
            NoteStatus.DELIVERED -> "✓ Logged to Muse: \"${note.text}\""
            NoteStatus.FAILED -> "Delivery failed: ${note.error ?: "Unknown error"}"
            NoteStatus.PENDING -> "Logged locally on Portal: \"${note.text}\" (Pending Muse delivery)"
          }

          val contentArray = JSONArray().apply {
            put(JSONObject().apply {
              put("type", "text")
              put("text", resultText)
            })
          }
          val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", JSONObject().apply {
              put("content", contentArray)
              put("isError", note.status == NoteStatus.FAILED)
            })
          }
          sendResponse(out, 200, "OK", response.toString(), "application/json")
        } else {
          val unknown = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
              put("code", -32601)
              put("message", "Method not found: $toolName")
            })
          }
          sendResponse(out, 200, "OK", unknown.toString(), "application/json")
        }
      }

      "prompts/list" -> {
        val prompts = JSONArray().apply {
          put(JSONObject().apply {
            put("name", "muse_notes")
            put("description", "Forward all Pebble voice notes directly to Meta Muse AI")
          })
        }
        val response = JSONObject().apply {
          put("jsonrpc", "2.0")
          put("id", id)
          put("result", JSONObject().apply {
            put("prompts", prompts)
          })
        }
        sendResponse(out, 200, "OK", response.toString(), "application/json")
      }

      "prompts/get" -> {
        val promptName = params.optString("name")
        if (promptName == "muse_notes") {
          val messages = JSONArray().apply {
            put(JSONObject().apply {
              put("role", "user")
              put("content", JSONObject().apply {
                put("type", "text")
                put("text", "Whenever I speak a voice note into my Pebble Index ring, automatically call send_muse_note with the transcription.")
              })
            })
          }
          val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", JSONObject().apply {
              put("messages", messages)
            })
          }
          sendResponse(out, 200, "OK", response.toString(), "application/json")
        } else {
          val unknown = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
              put("code", -32601)
              put("message", "Prompt not found: $promptName")
            })
          }
          sendResponse(out, 200, "OK", unknown.toString(), "application/json")
        }
      }

      else -> {
        val unknown = JSONObject().apply {
          put("jsonrpc", "2.0")
          put("id", id)
          put("error", JSONObject().apply {
            put("code", -32601)
            put("message", "Method not found: $method")
          })
        }
        sendResponse(out, 200, "OK", unknown.toString(), "application/json")
      }
    }
  }

  private suspend fun processNote(text: String, title: String?): VoiceNote {
    val noteId = UUID.randomUUID().toString()
    val note = VoiceNote(
      id = noteId,
      timestampEpochMs = System.currentTimeMillis(),
      text = text,
      title = title,
      status = NoteStatus.PENDING,
    )
    BridgeRepository.addNote(note)

    val deliveryResult = deliveryClient.deliverNote(note, BridgeRepository.config.value)
    if (deliveryResult.isSuccess) {
      val reply = deliveryResult.getOrNull()
      BridgeRepository.updateNoteStatus(noteId, NoteStatus.DELIVERED, reply = reply)
      return note.copy(status = NoteStatus.DELIVERED, museReply = reply)
    } else {
      val errorMsg = deliveryResult.exceptionOrNull()?.message ?: "Unknown delivery error"
      BridgeRepository.updateNoteStatus(noteId, NoteStatus.FAILED, error = errorMsg)
      return note.copy(status = NoteStatus.FAILED, error = errorMsg)
    }
  }

  private fun checkAuth(headers: Map<String, String>, expectedToken: String): Boolean {
    if (expectedToken.isBlank()) return true
    val authHeader = headers["authorization"] ?: ""
    val presented = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
      authHeader.substring(7).trim()
    } else {
      headers["x-pebble-token"] ?: ""
    }
    return MessageDigest.isEqual(
      presented.toByteArray(StandardCharsets.UTF_8),
      expectedToken.toByteArray(StandardCharsets.UTF_8)
    )
  }

  private fun parseIncomingText(body: String, contentType: String = ""): String {
    if (body.isBlank()) return ""
    if (contentType.startsWith("multipart/form-data", ignoreCase = true)) {
      // The Pebble app's Index webhook posts a multipart form (see INDEX_WEBHOOK_API.md).
      val fields = parseMultipartFields(body, contentType)
      return fields["transcription"].orEmpty()
        .ifBlank { fields["text"].orEmpty() }
        .ifBlank { fields["transcript"].orEmpty() }
    }
    return try {
      val json = JSONObject(body)
      json.optString("transcription")
        .ifBlank { json.optString("text") }
        .ifBlank { json.optString("transcript") }
    } catch (_: Exception) {
      body.trim()
    }
  }

  /** Text fields of a multipart/form-data body, keyed by part name. File parts are skipped. */
  internal fun parseMultipartFields(body: String, contentType: String): Map<String, String> {
    val boundary = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
      .find(contentType)?.groupValues?.get(1) ?: return emptyMap()
    val fields = mutableMapOf<String, String>()
    for (part in body.split("--$boundary")) {
      val split = part.indexOf("\r\n\r\n")
      if (split < 0) continue
      val partHeaders = part.substring(0, split)
      if (Regex("filename=", RegexOption.IGNORE_CASE).containsMatchIn(partHeaders)) continue
      val name = Regex("name=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        .find(partHeaders)?.groupValues?.get(1) ?: continue
      fields[name] = part.substring(split + 4).removeSuffix("\r\n").trim()
    }
    return fields
  }

  private fun sendNoContent(out: OutputStream) {
    val headers = StringBuilder()
      .append("HTTP/1.1 204 No Content\r\n")
      .append("Access-Control-Allow-Origin: *\r\n")
      .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
      .append("Access-Control-Allow-Headers: Authorization, Content-Type, X-Pebble-Token, Mcp-Session-Id, Last-Event-ID\r\n")
      .append("Connection: close\r\n\r\n")
    out.write(headers.toString().toByteArray(StandardCharsets.US_ASCII))
    out.flush()
  }

  private fun sendResponse(
    out: OutputStream,
    statusCode: Int,
    statusText: String,
    body: String,
    contentType: String,
  ) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val headerBuilder = StringBuilder()
      .append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n")
      .append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
      .append("Content-Length: ").append(bodyBytes.size).append("\r\n")
      .append("Access-Control-Allow-Origin: *\r\n")
      .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
      .append("Access-Control-Allow-Headers: Authorization, Content-Type, X-Pebble-Token, Mcp-Session-Id, Last-Event-ID\r\n")
      .append("Connection: close\r\n\r\n")

    out.write(headerBuilder.toString().toByteArray(StandardCharsets.US_ASCII))
    if (bodyBytes.isNotEmpty()) {
      out.write(bodyBytes)
    }
    out.flush()
  }
}
