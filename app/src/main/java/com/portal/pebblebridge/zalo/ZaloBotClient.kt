package com.portal.pebblebridge.zalo

import android.util.Log
import com.portal.pebblebridge.model.LinkState
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.model.VoiceNote
import com.portal.pebblebridge.model.ZaloBotState
import com.portal.pebblebridge.model.ZaloBotStatus
import com.portal.pebblebridge.muse.MuseLinkClient
import com.portal.pebblebridge.state.BridgeRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ZaloIncomingMessage(
  val messageId: String,
  val chatId: String,
  val senderId: String,
  val senderName: String,
  val text: String,
  val isBot: Boolean = false,
  val eventName: String = "",
)

/**
 * Long-polling Zalo Bot Platform client (`getUpdates` -> Meta Muse AI -> `sendMessage`).
 *
 * Uses outbound HTTPS polling against `https://bot-api.zaloplatforms.com/bot<TOKEN>/<method>`,
 * so the Portal needs no public HTTPS endpoint, domain, or tunnel. Automatically calls
 * `deleteWebhook` once per token because Zalo Bot Platform treats webhooks and `getUpdates`
 * as mutually exclusive.
 */
class ZaloBotClient(
  private val scope: CoroutineScope,
  private val httpClient: OkHttpClient = sharedHttpClient,
  private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val museSender: suspend (text: String, sessionId: String, onAnswer: (MuseLinkClient.AnswerUpdate) -> Unit) -> Result<String> = defaultMuseSender,
) {

  companion object {
    private const val TAG = "ZaloBotClient"
    const val DEFAULT_API_BASE_URL = "https://bot-api.zaloplatforms.com"
    const val POLL_TIMEOUT_SECONDS = 30
    const val MAX_ZALO_TEXT_LEN = 1900 // Zalo sendMessage limit is 2000 chars
    private const val MAX_SEEN_IDS = 256
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    @Volatile
    var activeInstance: ZaloBotClient? = null

    val sharedHttpClient: OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // Must exceed getUpdates timeout (30s)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val defaultMuseSender: suspend (String, String, (MuseLinkClient.AnswerUpdate) -> Unit) -> Result<String> =
      { text, sessionId, onAnswer ->
        val client = MuseLinkClient.activeInstance
        val state = client?.linkState?.value
        if (client == null || state != LinkState.CONNECTED_ONLINE) {
          Result.failure(IOException("Meta Muse Cloud AI is not connected (${state?.name ?: "DISCONNECTED"})"))
        } else {
          client.sendChat(text, sessionId, onAnswer)
        }
      }

    /**
     * Parses a Zalo Bot `getUpdates` JSON response into incoming text messages.
     * Supports both single-object `result` (current official API shape) and array `result`.
     */
    fun parseUpdates(jsonText: String): List<ZaloIncomingMessage> {
      if (jsonText.isBlank()) return emptyList()
      val root = try {
        JSONObject(jsonText)
      } catch (_: Exception) {
        return emptyList()
      }
      if (!root.optBoolean("ok", false)) return emptyList()
      val rawResult = root.opt("result") ?: return emptyList()
      if (rawResult === JSONObject.NULL) return emptyList()

      val items = mutableListOf<JSONObject>()
      when (rawResult) {
        is JSONObject -> items.add(rawResult)
        is JSONArray -> {
          for (i in 0 until rawResult.length()) {
            rawResult.optJSONObject(i)?.let { items.add(it) }
          }
        }
      }

      val out = mutableListOf<ZaloIncomingMessage>()
      for (item in items) {
        val eventName = item.optString("event_name", "")
        val msg = item.optJSONObject("message") ?: continue
        val text = msg.optString("text", "").trim()
        if (text.isBlank()) continue
        val from = msg.optJSONObject("from")
        val chat = msg.optJSONObject("chat")
        val isBot = from?.optBoolean("is_bot", false) ?: false
        val senderId = from?.optString("id", "").orEmpty()
        val senderName = from?.optString("display_name", "").orEmpty()
        val chatId = chat?.optString("id", "").orEmpty().ifBlank { senderId }
        val messageId = msg.optString("message_id", "")
        if (chatId.isBlank() || isBot) continue
        out.add(
          ZaloIncomingMessage(
            messageId = messageId,
            chatId = chatId,
            senderId = senderId,
            senderName = senderName,
            text = text,
            isBot = isBot,
            eventName = eventName,
          )
        )
      }
      return out
    }

    /**
     * Returns `true` when [allowedChatIds] is blank (open) or contains [chatId]
     * in a comma/whitespace-separated list.
     */
    fun isChatAllowed(chatId: String, allowedChatIds: String): Boolean {
      if (chatId.isBlank()) return false
      val allowed = allowedChatIds
        .split(',', ';', '\n', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
      if (allowed.isEmpty()) return true
      return chatId.trim() in allowed
    }

    /**
     * Splits [text] into chunks of at most [maxLen] characters (Zalo sendMessage limit is 2000),
     * preferring paragraph, newline, then word boundaries.
     */
    fun chunkForZalo(text: String, maxLen: Int = MAX_ZALO_TEXT_LEN): List<String> {
      val trimmed = text.trim()
      if (trimmed.isEmpty()) return emptyList()
      require(maxLen > 0) { "maxLen must be positive" }
      if (trimmed.length <= maxLen) return listOf(trimmed)

      val chunks = mutableListOf<String>()
      var remaining = trimmed
      while (remaining.length > maxLen) {
        val window = remaining.substring(0, maxLen)
        val splitAt = listOf(
          window.lastIndexOf("\n\n"),
          window.lastIndexOf('\n'),
          window.lastIndexOf(' '),
        ).firstOrNull { it >= maxLen / 3 } ?: maxLen

        val piece = remaining.substring(0, splitAt).trimEnd()
        if (piece.isNotEmpty()) chunks.add(piece)
        remaining = remaining.substring(splitAt).trimStart()
      }
      if (remaining.isNotEmpty()) chunks.add(remaining)
      return chunks
    }
  }

  private val isRunning = AtomicBoolean(false)
  private var loopJob: Job? = null
  private val restartTrigger = Channel<Unit>(Channel.CONFLATED)
  @Volatile private var activePollCall: Call? = null
  private val seenMessageIds = LinkedHashSet<String>()

  fun start() {
    if (!isRunning.compareAndSet(false, true)) return
    activeInstance = this
    loopJob = scope.launch(ioDispatcher) { runLoop() }
  }

  fun stop() {
    isRunning.set(false)
    if (activeInstance === this) activeInstance = null
    activePollCall?.cancel()
    activePollCall = null
    loopJob?.cancel()
    loopJob = null
    BridgeRepository.updateZaloBotStatus { ZaloBotStatus(state = ZaloBotState.DISABLED) }
  }

  /** Call after saving a new Zalo Bot token or allowed chat IDs in Settings. */
  fun triggerRestart() {
    activePollCall?.cancel()
    restartTrigger.trySend(Unit)
  }

  private suspend fun runLoop() {
    var initializedToken = ""
    var backoffMs = 2_000L

    while (isRunning.get()) {
      val config = BridgeRepository.config.value
      val token = config.zaloBotToken.trim()
      if (token.isBlank()) {
        initializedToken = ""
        backoffMs = 2_000L
        BridgeRepository.updateZaloBotStatus {
          it.copy(state = ZaloBotState.DISABLED, botName = "", lastError = null)
        }
        withTimeoutOrNull(5_000L) { restartTrigger.receive() }
        continue
      }

      if (token != initializedToken) {
        BridgeRepository.updateZaloBotStatus {
          it.copy(state = ZaloBotState.CONNECTING, lastError = null)
        }
        val meResult = getMe(token)
        if (meResult.isFailure) {
          val err = meResult.exceptionOrNull()?.message ?: "Failed to connect to Zalo Bot API"
          Log.w(TAG, "getMe failed: $err")
          BridgeRepository.updateZaloBotStatus {
            it.copy(state = ZaloBotState.ERROR, lastError = err)
          }
          val waitMs = if (err.contains("401")) 60_000L else backoffMs
          withTimeoutOrNull(waitMs) { restartTrigger.receive() }
          backoffMs = minOf(backoffMs * 2, 30_000L)
          continue
        }

        val botName = meResult.getOrDefault("Zalo Bot")
        // Zalo treats webhooks and getUpdates as mutually exclusive; clear any stale webhook.
        deleteWebhook(token)
        initializedToken = token
        backoffMs = 2_000L
        Log.i(TAG, "Zalo Bot polling ready (bot=$botName)")
        BridgeRepository.updateZaloBotStatus {
          it.copy(state = ZaloBotState.POLLING, botName = botName, lastError = null)
        }
      }

      try {
        val pollResult = getUpdates(token, POLL_TIMEOUT_SECONDS)
        if (pollResult.isSuccess) {
          backoffMs = 2_000L
          BridgeRepository.updateZaloBotStatus {
            if (it.state != ZaloBotState.POLLING || it.lastError != null) {
              it.copy(state = ZaloBotState.POLLING, lastError = null)
            } else {
              it
            }
          }
          val messages = pollResult.getOrDefault(emptyList())
          for (msg in messages) {
            if (!markSeenIfNew(msg.messageId)) continue
            scope.launch(ioDispatcher) {
              handleIncomingMessage(token, msg)
            }
          }
        } else {
          val err = pollResult.exceptionOrNull()?.message ?: "Polling error"
          if (err.contains("401")) {
            initializedToken = ""
            Log.w(TAG, "getUpdates rejected (HTTP 401); check Zalo Bot token")
            BridgeRepository.updateZaloBotStatus {
              it.copy(state = ZaloBotState.ERROR, lastError = "Invalid Bot Token (HTTP 401)")
            }
            withTimeoutOrNull(60_000L) { restartTrigger.receive() }
          } else {
            Log.w(TAG, "getUpdates failed: $err; retrying in ${backoffMs / 1000}s")
            BridgeRepository.updateZaloBotStatus {
              it.copy(state = ZaloBotState.ERROR, lastError = err)
            }
            withTimeoutOrNull(backoffMs) { restartTrigger.receive() }
            backoffMs = minOf(backoffMs * 2, 30_000L)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Unexpected error in Zalo polling loop: ${e.javaClass.simpleName}")
        withTimeoutOrNull(backoffMs) { restartTrigger.receive() }
        backoffMs = minOf(backoffMs * 2, 30_000L)
      }
    }
  }

  private fun markSeenIfNew(messageId: String): Boolean {
    if (messageId.isBlank()) return true
    synchronized(seenMessageIds) {
      if (!seenMessageIds.add(messageId)) return false
      if (seenMessageIds.size > MAX_SEEN_IDS) {
        val iter = seenMessageIds.iterator()
        if (iter.hasNext()) {
          iter.next()
          iter.remove()
        }
      }
      return true
    }
  }

  internal suspend fun handleIncomingMessage(token: String, msg: ZaloIncomingMessage) {
    val config = BridgeRepository.config.value
    BridgeRepository.updateZaloBotStatus { it.copy(lastChatId = msg.chatId) }

    if (!isChatAllowed(msg.chatId, config.zaloAllowedChatIds)) {
      Log.i(TAG, "Ignoring Zalo message from non-whitelisted chat_id=${msg.chatId}")
      return
    }

    val noteId = "zalo-${msg.messageId.ifBlank { UUID.randomUUID().toString() }}"
    val senderLabel = msg.senderName.ifBlank { "Zalo" }
    val note = VoiceNote(
      id = noteId,
      timestampEpochMs = System.currentTimeMillis(),
      text = msg.text,
      title = "Zalo · $senderLabel",
      source = "Zalo Bot",
      status = NoteStatus.PENDING,
    )
    BridgeRepository.addNote(note)
    BridgeRepository.updateZaloBotStatus { it.copy(messagesBridged = it.messagesBridged + 1) }

    // Best-effort typing indicator on Zalo while Muse thinks.
    sendChatAction(token, msg.chatId, "typing")

    val replied = AtomicBoolean(false)
    val sendResult = museSender(msg.text, config.museSessionId) { update ->
      if (update.mergedInto != null) {
        BridgeRepository.markNoteMerged(noteId)
        return@museSender
      }
      if (update.text.isNotBlank() || update.done) {
        BridgeRepository.updateNoteReply(noteId, update.text, update.done)
      }
      if (update.done && replied.compareAndSet(false, true)) {
        val replyText = update.text.trim()
        if (replyText.isNotBlank()) {
          scope.launch(ioDispatcher) {
            sendMessage(token, msg.chatId, replyText)
          }
        }
      }
    }

    if (sendResult.isSuccess) {
      BridgeRepository.updateNoteStatus(noteId, NoteStatus.DELIVERED)
    } else {
      val errMsg = sendResult.exceptionOrNull()?.message ?: "Unable to reach Meta Muse"
      BridgeRepository.updateNoteStatus(noteId, NoteStatus.FAILED, error = errMsg)
      sendMessage(token, msg.chatId, "⚠️ Portal chưa gửi được sang Meta Muse: $errMsg")
    }
  }

  suspend fun getMe(token: String): Result<String> = withContext(ioDispatcher) {
    postBotApi(token, "getMe", JSONObject()).map { root ->
      val result = root.optJSONObject("result")
      result?.optString("display_name")?.takeIf { it.isNotBlank() }
        ?: result?.optString("account_name")?.takeIf { it.isNotBlank() }
        ?: result?.optString("name")?.takeIf { it.isNotBlank() }
        ?: "Zalo Bot"
    }
  }

  suspend fun deleteWebhook(token: String): Result<Unit> = withContext(ioDispatcher) {
    postBotApi(token, "deleteWebhook", JSONObject()).map { Unit }
  }

  suspend fun getUpdates(token: String, timeoutSeconds: Int = POLL_TIMEOUT_SECONDS): Result<List<ZaloIncomingMessage>> =
    withContext(ioDispatcher) {
      val body = JSONObject().put("timeout", timeoutSeconds)
      val url = "${apiBaseUrl.trimEnd('/')}/bot$token/getUpdates"
      val req = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

      val call = httpClient.newCall(req)
      activePollCall = call
      try {
        call.execute().use { resp ->
          val respText = resp.body?.string().orEmpty()
          if (resp.code == 408) {
            // Normal long-polling timeout with no new messages.
            return@withContext Result.success(emptyList())
          }
          if (!resp.isSuccessful) {
            return@withContext Result.failure(IOException("getUpdates HTTP ${resp.code}"))
          }
          val root = try {
            JSONObject(respText)
          } catch (_: Exception) {
            return@withContext Result.success(emptyList())
          }
          if (!root.optBoolean("ok", false)) {
            val code = root.optInt("error_code", 0)
            if (code == 408) return@withContext Result.success(emptyList())
            val desc = root.optString("description", "API error $code")
            return@withContext Result.failure(IOException("getUpdates failed ($code): $desc"))
          }
          Result.success(parseUpdates(respText))
        }
      } catch (_: SocketTimeoutException) {
        // Expected when no messages arrive within timeout window.
        Result.success(emptyList())
      } catch (e: IOException) {
        if (call.isCanceled()) Result.success(emptyList())
        else Result.failure(IOException("getUpdates network error: ${e.javaClass.simpleName}"))
      } finally {
        if (activePollCall === call) activePollCall = null
      }
    }

  suspend fun sendMessage(token: String, chatId: String, text: String): Result<Unit> =
    withContext(ioDispatcher) {
      val chunks = chunkForZalo(text)
      if (chunks.isEmpty()) return@withContext Result.success(Unit)
      for (chunk in chunks) {
        val mdBody = JSONObject().apply {
          put("chat_id", chatId)
          put("text", chunk)
          put("parse_mode", "markdown")
        }
        val mdResult = postBotApi(token, "sendMessage", mdBody)
        if (mdResult.isFailure) {
          // Fallback to plain text if Zalo rejects any markdown syntax.
          val plainBody = JSONObject().apply {
            put("chat_id", chatId)
            put("text", chunk)
          }
          val plainResult = postBotApi(token, "sendMessage", plainBody)
          if (plainResult.isFailure) {
            Log.w(TAG, "sendMessage failed for chat_id=$chatId: ${plainResult.exceptionOrNull()?.message}")
            return@withContext Result.failure(plainResult.exceptionOrNull() ?: IOException("sendMessage failed"))
          }
        }
      }
      Result.success(Unit)
    }

  suspend fun sendChatAction(token: String, chatId: String, action: String = "typing"): Result<Unit> =
    withContext(ioDispatcher) {
      val body = JSONObject().apply {
        put("chat_id", chatId)
        put("action", action)
      }
      postBotApi(token, "sendChatAction", body).map { Unit }
    }

  private fun postBotApi(token: String, method: String, payload: JSONObject): Result<JSONObject> {
    val url = "${apiBaseUrl.trimEnd('/')}/bot$token/$method"
    val req = Request.Builder()
      .url(url)
      .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()
    return try {
      httpClient.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
          return Result.failure(IOException("$method HTTP ${resp.code}"))
        }
        val json = try {
          JSONObject(text)
        } catch (_: Exception) {
          return Result.failure(IOException("$method invalid JSON response"))
        }
        if (!json.optBoolean("ok", false)) {
          val code = json.optInt("error_code", 0)
          val desc = json.optString("description", "API error $code")
          return Result.failure(IOException("$method failed ($code): $desc"))
        }
        Result.success(json)
      }
    } catch (e: Exception) {
      Result.failure(IOException("$method error: ${e.javaClass.simpleName}"))
    }
  }
}
