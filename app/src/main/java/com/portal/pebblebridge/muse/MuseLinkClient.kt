package com.portal.pebblebridge.muse

import android.util.Log
import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.LinkState
import com.portal.pebblebridge.state.BridgeRepository
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

data class VmInfo(
  val vmId: String,
  val vmWsUrl: String,
  val vmAuthToken: String,
  val isDefault: Boolean,
)

class MuseLinkClient(
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

  companion object {
    private const val TAG = "MuseLinkClient"
    private const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
    private const val APP_ID = "musegadget"
    private const val BODY_CHUNK = 16 * 1024
    private const val ANSWER_SETTLE_MS = 3_000L
    private const val FIRST_REPLY_MS = 3 * 60_000L
    private const val TURN_CAP_MS = 6 * 60_000L

    @Volatile
    var activeInstance: MuseLinkClient? = null

    val sharedClient: OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS) // Keep WebSocket read open
      .pingInterval(20, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }

  private val _linkState = MutableStateFlow(LinkState.DISCONNECTED)
  val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

  @Volatile
  private var activeWebSocket: WebSocket? = null
  private var loopJob: Job? = null
  private val isRunning = AtomicBoolean(false)
  private val reconnectTrigger = Channel<Unit>(Channel.CONFLATED)

  @Volatile
  private var noiseTransport: NoiseTransport? = null
  private var controlStreamId: Long = -1
  private var subscribeStreamId: Long = -1
  @Volatile private var subscribeOpen = false
  private var registerId = ""
  private var controlDecoder = ControlDecoder()
  private var subscribeLines = LineBuffer()
  /** Request streams waiting for their whole response (e.g. the /chat/stream acknowledgment). */
  private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()
  /** Encrypt-and-send must stay in nonce order, from any thread. */
  private val sendLock = Any()
  /** The question whose answer we're collecting from /chat/subscribe, if any. */
  @Volatile private var currentTurn: MuseTurn? = null

  /** Collects one request stream's response until end of body. */
  class PendingRequest {
    var status = 0
    val body = java.io.ByteArrayOutputStream()
    val done = CompletableDeferred<Unit>()
  }

  private fun setLinkState(state: LinkState) {
    _linkState.value = state
    BridgeRepository.updateMuseLinkState(state)
  }

  /** Drops the connection so the loop reconnects and re-registers (e.g. new command list). */
  fun reconnect() {
    activeWebSocket?.close(1000, "re-register")
  }

  fun triggerReconnect() {
    reconnectTrigger.trySend(Unit)
  }

  fun start() {
    activeInstance = this
    if (isRunning.compareAndSet(false, true)) {
      loopJob = scope.launch {
        runClientLoop()
      }
    }
  }

  fun stop() {
    if (activeInstance == this) {
      activeInstance = null
    }
    isRunning.set(false)
    loopJob?.cancel()
    loopJob = null
    closeWebSocket()
    setLinkState(LinkState.DISCONNECTED)
  }

  private fun closeWebSocket() {
    try {
      activeWebSocket?.close(1000, "Normal closure")
    } catch (e: Exception) {
      Log.w(TAG, "Error closing websocket", e)
    }
    activeWebSocket = null
    noiseTransport = null
  }

  private suspend fun runClientLoop() {
    var backoffMs = 2000L

    while (isRunning.get()) {
      val config = BridgeRepository.config.value
      val token = config.museAccessToken
      if (token.isBlank()) {
        setLinkState(LinkState.DISCONNECTED)
        withTimeoutOrNull(5000L) {
          reconnectTrigger.receive()
        }
        continue
      }

      try {
        setLinkState(LinkState.FETCHING_VM)
        Log.i(TAG, "Fetching VMs from Meta cloud for token ${token.take(8)}...")
        val vm = fetchVm(config)
        if (vm == null) {
          Log.w(TAG, "No active VM found; retrying in ${backoffMs / 1000}s")
          setLinkState(LinkState.ERROR)
          withTimeoutOrNull(backoffMs) {
            reconnectTrigger.receive()
          }
          backoffMs = minOf(backoffMs * 2, 30_000L)
          continue
        }

        setLinkState(LinkState.CONNECTING_WS)
        val connectedDeferred = CompletableDeferred<Boolean>()
        val disconnectedDeferred = CompletableDeferred<Unit>()
        connectWebSocket(vm, connectedDeferred, disconnectedDeferred)

        // Wait for handshake outcome
        val success = connectedDeferred.await()
        if (success) {
          backoffMs = 2000L // Reset backoff on successful registration
          Log.i(TAG, "Noise session active; awaiting connection lifecycle termination...")
          disconnectedDeferred.await()
        }
      } catch (e: Exception) {
        Log.w(TAG, "Exception in link client loop", e)
        setLinkState(LinkState.ERROR)
      }

      closeWebSocket()
      if (isRunning.get()) {
        withTimeoutOrNull(backoffMs) {
          reconnectTrigger.receive()
        }
        backoffMs = minOf(backoffMs * 2, 30_000L)
      }
    }
  }

  suspend fun fetchVm(config: BridgeConfig, allowRefreshRetry: Boolean = true): VmInfo? = withContext(Dispatchers.IO) {
    val root = if (config.museApiUrl.startsWith("https://hatch-api.meta.ai")) {
      "https://api.muse.ai"
    } else {
      config.museApiUrl.trimEnd('/')
    }
    val url = "$root/fetch_vms"
    val req = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer ${config.museAccessToken}")
      .addHeader("X-API-Version", "1.0.0")
      .addHeader("User-Agent", "musegadget/1.0.0 (Android)")
      .get()
      .build()

    try {
      sharedClient.newCall(req).execute().use { resp ->
        if (resp.code == 401 && allowRefreshRetry && config.museRefreshToken.isNotBlank()) {
          Log.i(TAG, "fetch_vms returned 401; attempting token refresh with refresh_token...")
          val refreshed = MuseDeliveryClient().refreshAccessToken(config, BridgeRepository.deviceId)
          if (refreshed.isSuccess) {
            val updatedConfig = BridgeRepository.config.value
            return@withContext fetchVm(updatedConfig, allowRefreshRetry = false)
          }
        }

        if (!resp.isSuccessful) {
          Log.w(TAG, "fetch_vms failed: HTTP ${resp.code} ${resp.body?.string()}")
          return@withContext null
        }
        val text = resp.body?.string().orEmpty()
        val json = JSONObject(text)
        val list = json.optJSONArray("vm_list") ?: return@withContext null
        if (list.length() == 0) return@withContext null

        val vms = mutableListOf<VmInfo>()
        for (i in 0 until list.length()) {
          val item = list.getJSONObject(i)
          val id = item.optString("vm_id")
          val wsUrl = item.optString("vm_ws_url")
          val token = item.optString("vm_auth_token")
          val isDef = item.optBoolean("default", false)
          if (id.isNotBlank() && token.isNotBlank()) {
            vms.add(VmInfo(id, wsUrl, token, isDef))
          }
        }
        vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error executing fetch_vms", e)
      null
    }
  }

  private fun connectWebSocket(
    vm: VmInfo,
    completion: CompletableDeferred<Boolean>,
    disconnection: CompletableDeferred<Unit>,
  ) {
    // Like the SDK's service.py: always the pairing's noise_host (or the default),
    // never vm_ws_url, whose per-VM hostname does not resolve.
    val host = BridgeRepository.config.value.museNoiseHost.ifBlank { DEFAULT_NOISE_HOST }
    val wsUrl = "wss://$host/v1/noise?vm_id=${java.net.URLEncoder.encode(vm.vmId, "UTF-8")}"

    Log.i(TAG, "Connecting WebSocket to $wsUrl with bearer ${vm.vmAuthToken.take(10)}...")
    val req = Request.Builder()
      .url(wsUrl)
      .addHeader("Authorization", "Bearer ${vm.vmAuthToken}")
      .addHeader("User-Agent", "musegadget/1.0.0 (Android)")
      .build()

    val initiator = NoiseXXInitiator()
    initiator.initialize()

    val listener = object : WebSocketListener() {
      private var step = 1

      override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(TAG, "WebSocket open; initiating Noise XX handshake step 1...")
        setLinkState(LinkState.HANDSHAKING)
        val msg1 = initiator.writeMessage1()
        webSocket.send(msg1.toByteString())
      }

      override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val raw = bytes.toByteArray()
        when (step) {
          1 -> {
            try {
              initiator.readMessage2(raw)
              val msg3 = initiator.writeMessage3()
              webSocket.send(msg3.toByteString())

              val (sendCipher, recvCipher) = initiator.split()
              noiseTransport = NoiseTransport(sendCipher, recvCipher)
              Log.i(TAG, "Noise XX handshake completed successfully!")

              step = 2
              setLinkState(LinkState.REGISTERING)
              openControlStream(webSocket)
              setLinkState(LinkState.CONNECTED_ONLINE)
              completion.complete(true)
            } catch (e: Exception) {
              Log.e(TAG, "Noise XX handshake failed at step 1", e)
              completion.complete(false)
              if (!disconnection.isCompleted) disconnection.complete(Unit)
            }
          }
          else -> {
            // Established Noise transport frame
            try {
              val transport = noiseTransport ?: return
              val frame = transport.decryptFrame(raw) ?: return
              handleInboundFrame(frame)
            } catch (e: Exception) {
              Log.e(TAG, "Failed to decrypt incoming Noise frame; closing compromised connection", e)
              webSocket.close(1008, "Decryption failed: poisoned cipher")
              if (!disconnection.isCompleted) disconnection.complete(Unit)
            }
          }
        }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.w(TAG, "WebSocket failure: ${t.message} (HTTP ${response?.code})")
        setLinkState(LinkState.ERROR)
        if (!completion.isCompleted) completion.complete(false)
        if (!disconnection.isCompleted) disconnection.complete(Unit)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
        setLinkState(LinkState.DISCONNECTED)
        if (!completion.isCompleted) completion.complete(false)
        if (!disconnection.isCompleted) disconnection.complete(Unit)
      }
    }

    activeWebSocket = sharedClient.newWebSocket(req, listener)
  }

  private fun openControlStream(ws: WebSocket) {
    val transport = noiseTransport ?: return
    val (streamId, frames) = synchronized(sendLock) { transport.startStreamRequest("POST", "/link-control") }
    controlStreamId = streamId
    controlDecoder = ControlDecoder()

    // Send HTTP stream start frames
    for (frame in frames) {
      ws.send(frame.toByteString())
    }

    val currentNodeId = BridgeRepository.nodeId.ifBlank { "homelink-e1890c" }
    val currentDisplayName = BridgeRepository.bleDeviceName.value.ifBlank { "MuseGadget" }

    // Send link.register message
    val registerId = UUID.randomUUID().toString()
    val registerObj = JSONObject().apply {
      put("type", "req")
      put("id", registerId)
      put("method", "link.register")
      put("params", JSONObject().apply {
        put("node_id", currentNodeId)
        put("display_name", currentDisplayName)
        put("platform", "android")
        put("version", "1.0.0")
        put("device_family", "homehub")
        put("model_id", "portal")
        put("is_wakeup_supported", false)
        put("commands_v2", PortalCommands.registerSpec())
      })
    }
    this.registerId = registerId
    sendControl(ws, registerObj)
    Log.i(TAG, "Sent link.register as $currentNodeId ($currentDisplayName) with ${PortalCommands.registerSpec().length()} commands")
    openSubscribe(ws)
  }

  /** Length-prefixed JSON on the control stream (link.register, link.result). */
  private fun sendControl(ws: WebSocket, message: JSONObject) {
    val transport = noiseTransport ?: return
    synchronized(sendLock) {
      val prefixed = NoiseTransport.encodeLengthPrefixedMessage(message.toString().toByteArray(Charsets.UTF_8))
      transport.encryptBodyChunk(controlStreamId, prefixed).forEach { ws.send(it.toByteString()) }
    }
  }

  private fun jsonHeaders(vararg extra: NoiseHeader) = listOf(
    NoiseHeader("Content-Type", "application/json"),
    NoiseHeader("x-request-id", UUID.randomUUID().toString()),
    NoiseHeader("x-app-id", APP_ID),
  ) + extra

  /**
   * Opens /chat/subscribe: a response that never ends, one JSON event per line. Muse's answers
   * arrive here, not on the /chat/stream request (which only acknowledges). From hey-muse.
   */
  private fun openSubscribe(ws: WebSocket) {
    val transport = noiseTransport ?: return
    synchronized(sendLock) {
      val (sid, frames) = transport.startStreamRequest("POST", "/chat/subscribe", jsonHeaders(NoiseHeader("accept", "application/x-ndjson")))
      subscribeStreamId = sid
      subscribeLines = LineBuffer()
      subscribeOpen = true
      frames.forEach { ws.send(it.toByteString()) }
      // Follow the side chat ring notes go to, if one is set; answers there aren't on the main feed.
      val session = BridgeRepository.config.value.museSessionId
      val body = if (session.isBlank()) "{}" else JSONObject().put("session_id", session).toString()
      transport.encryptBodyChunk(sid, body.toByteArray(), endBody = true).forEach { ws.send(it.toByteString()) }
    }
    Log.i(TAG, "Opened /chat/subscribe on stream $subscribeStreamId")
  }

  private fun handleInboundFrame(frame: ServiceFrame) {
    val (status, data, ended) = when (val p = frame.payload) {
      is ServiceFramePayload.Resp -> Triple(p.response.status, p.response.body, p.response.endBody)
      is ServiceFramePayload.Chunk -> Triple(0, p.chunk.data, p.chunk.endBody)
      is ServiceFramePayload.Reset -> {
        Log.w(TAG, "stream ${frame.streamId} reset: ${p.reason}")
        when (frame.streamId) {
          controlStreamId -> activeWebSocket?.close(1000, "control stream reset")
          subscribeStreamId -> subscribeOpen = false
          else -> pendingRequests.remove(frame.streamId)?.done?.completeExceptionally(IOException("stream reset: ${p.reason}"))
        }
        return
      }
      else -> return
    }
    when (frame.streamId) {
      controlStreamId -> {
        if (status >= 400) Log.w(TAG, "/link-control refused: HTTP $status")
        try {
          controlDecoder.feed(data).forEach(::handleControl)
        } catch (e: Exception) {
          Log.w(TAG, "bad control stream data", e)
        }
      }
      subscribeStreamId -> {
        if (status >= 400) {
          Log.w(TAG, "/chat/subscribe refused: HTTP $status")
          subscribeOpen = false
          return
        }
        try {
          subscribeLines.feed(data).forEach { line ->
            val e = ChatEvent.parse(line)
            if (e == null) {
              return@forEach
            }
            Log.v(TAG, "event ${e.event}")
            if (e.event == "client.invoke") clientInvoke(e) else currentTurn?.add(e)
          }
        } catch (e: Exception) {
          Log.w(TAG, "bad chat event stream", e)
          subscribeOpen = false
        }
        if (ended) subscribeOpen = false
      }
      else -> pendingRequests[frame.streamId]?.let { r ->
        if (status != 0) r.status = status
        r.body.write(data)
        if (ended) {
          pendingRequests.remove(frame.streamId)
          r.done.complete(Unit)
        }
      }
    }
  }

  private fun handleControl(m: JSONObject) {
    val method = m.optString("method")
    when {
      m.optString("id") == registerId && method.isEmpty() -> {
        val err = m.opt("error")
        if (err != null && err != JSONObject.NULL && err != false) Log.e(TAG, "link.register rejected: $err")
        else Log.i(TAG, "Registered with Muse")
      }
      m.optString("event") in setOf("link.unpaired", "node.unpaired") ->
        Log.w(TAG, "Muse removed this device (${m.optString("event")})")
      method == "link.invoke" && m.optString("id").isNotEmpty() ->
        runInvoke(m.optString("id"), m.optString("command"), m.optJSONObject("params"))
    }
  }

  /** A command from a turn started by this device arrives on the chat stream instead (hey-muse). */
  private fun clientInvoke(e: ChatEvent) {
    val id = e.str("invoke_id").ifEmpty { return }
    val params = runCatching { JSONObject(e.str("params_json")) }.getOrNull()
    runInvoke(id, e.str("command_id"), params)
  }

  private fun runInvoke(id: String, command: String, params: JSONObject?) {
    scope.launch {
      Log.i(TAG, "invoke $command")
      val result = withContext(Dispatchers.Main) { PortalCommands.invoke(command, params) }
      val ws = activeWebSocket ?: return@launch
      val msg = JSONObject().put("method", "link.result").put("id", id)
      result.keys().forEach { k -> msg.put(k, result.get(k)) }
      sendControl(ws, msg)
      Log.i(TAG, "$command -> ${if (result.optBoolean("ok")) "ok" else result.optString("error")}")
    }
  }

  /** What [ask] reports while Muse answers. */
  data class AnswerUpdate(val heard: String, val text: String, val done: Boolean)

  /**
   * Sends a text message or a WAV voice note to Muse and returns once Muse acknowledges it.
   * Muse's answer is then collected from /chat/subscribe and reported through [onAnswer]
   * (on a background thread) until it is complete, like hey-muse's `ask`.
   */
  suspend fun ask(
    text: String?,
    wav: ByteArray? = null,
    sessionId: String = "",
    onAnswer: ((AnswerUpdate) -> Unit)? = null,
  ): Result<String> = withContext(Dispatchers.IO) {
    val ws = activeWebSocket ?: return@withContext Result.failure(IOException("Not connected to Muse"))
    val transport = noiseTransport ?: return@withContext Result.failure(IOException("Noise transport not ready"))
    if (!subscribeOpen) openSubscribe(ws)

    val nodeId = BridgeRepository.nodeId.ifBlank { "homelink-e1890c" }
    val body = JSONObject().apply {
      if (text != null) put("message", text)
      put("output_modality", "text")
      put("device_id", nodeId)
      if (sessionId.isNotBlank()) put("session_id", sessionId)
      if (wav != null) put("items", org.json.JSONArray().put(JSONObject()
        .put("type", "file").put("mime_type", "audio/wav").put("filename", "voice_note.wav")
        .put("data_base64", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))))
    }.toString().toByteArray(Charsets.UTF_8)

    // Register the turn before sending: its events can arrive before the acknowledgment.
    val turn = MuseTurn()
    currentTurn = turn
    val request = PendingRequest()
    val sid = synchronized(sendLock) {
      val (sid, frames) = transport.startStreamRequest("POST", "/chat/stream", jsonHeaders())
      pendingRequests[sid] = request
      frames.forEach { ws.send(it.toByteString()) }
      // Big bodies (voice notes) go in 16 KiB pieces, like the firmware.
      var start = 0
      do {
        val end = minOf(body.size, start + BODY_CHUNK)
        transport.encryptBodyChunk(sid, body.copyOfRange(start, end), endBody = end == body.size)
          .forEach { ws.send(it.toByteString()) }
        start = end
      } while (start < body.size)
      sid
    }

    val acked = withTimeoutOrNull(60_000L) { request.done.await() }
    pendingRequests.remove(sid)
    if (acked == null) {
      if (currentTurn === turn) currentTurn = null
      return@withContext Result.failure(IOException("Muse did not acknowledge the message"))
    }
    if (request.status !in 200..299) {
      if (currentTurn === turn) currentTurn = null
      return@withContext Result.failure(IOException("Muse refused the message: HTTP ${request.status}"))
    }
    val messageId = ackMessageId(request.body.toString("UTF-8"))
    if (messageId.isEmpty() || onAnswer == null) {
      if (currentTurn === turn) currentTurn = null
      return@withContext Result.success(messageId)
    }
    turn.acknowledged(messageId)
    scope.launch { followAnswer(turn, onAnswer) }
    Result.success(messageId)
  }

  /** Reports the answer as it grows; done once it is whole and Muse has been quiet for 3 s. */
  private suspend fun followAnswer(turn: MuseTurn, onAnswer: (AnswerUpdate) -> Unit) {
    val started = System.currentTimeMillis()
    var shownText = ""
    var shownHeard = ""
    try {
      while (currentTurn === turn) {
        val text = turn.text()
        val heard = turn.heard
        val quiet = System.currentTimeMillis() - turn.lastEventAt
        val elapsed = System.currentTimeMillis() - started
        if (text != shownText || heard != shownHeard) {
          shownText = text
          shownHeard = heard
          onAnswer(AnswerUpdate(heard, text, done = false))
        }
        when {
          turn.whole() && quiet >= ANSWER_SETTLE_MS -> { onAnswer(AnswerUpdate(heard, text, done = true)); return }
          text.isEmpty() && elapsed > FIRST_REPLY_MS -> { onAnswer(AnswerUpdate(heard, "", done = true)); return }
          elapsed > TURN_CAP_MS -> { onAnswer(AnswerUpdate(heard, text, done = true)); return }
        }
        delay(250)
      }
    } finally {
      if (currentTurn === turn) currentTurn = null
    }
  }

  /** Text message to Muse, used for ring notes; the answer (if wanted) comes via [onAnswer]. */
  suspend fun sendChat(
    message: String,
    sessionId: String = "",
    onAnswer: ((AnswerUpdate) -> Unit)? = null,
  ): Result<String> = ask(message, null, sessionId, onAnswer)
}
