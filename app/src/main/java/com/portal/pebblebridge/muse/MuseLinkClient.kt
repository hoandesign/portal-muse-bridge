package com.portal.pebblebridge.muse

import android.util.Log
import com.portal.pebblebridge.BuildConfig
import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.LinkState
import com.portal.pebblebridge.state.BridgeRepository
import java.io.IOException
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
      .minWebSocketMessageToCompress(Long.MAX_VALUE) // Noise frames are ciphertext (upstream #317)
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
  /** Whether the last fetch_vms ended with the device token rejected (401/403). */
  @Volatile private var lastFetchUnauthorized = false

  @Volatile
  private var noiseTransport: NoiseTransport? = null
  @Volatile
  private var controlStreamId: Long = -1
  @Volatile
  private var subscribeStreamId: Long = -1
  @Volatile private var subscribeOpen = false
  @Volatile
  private var registerId = ""
  @Volatile
  private var controlDecoder = ControlDecoder()
  @Volatile
  private var subscribeLines = LineBuffer()
  /** Request streams waiting for their whole response (e.g. the /chat/stream acknowledgment). */
  private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()
  /** Encrypt-and-send must stay in nonce order, from any thread. */
  private val sendLock = Any()
  /** The questions whose answers we're collecting from /chat/subscribe. */
  private val router = TurnRouter()

  internal enum class ControlSendResult { SENT, TOO_LARGE, NOT_CONNECTED, FAILED }

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

  /**
   * Resets session state. When [expectedSocket] is non-null, does nothing and returns `false` if a
   * newer WebSocket has already replaced it, preventing stale callbacks from clobbering a live session.
   */
  internal fun resetSessionState(expectedSocket: WebSocket? = null): Boolean {
    synchronized(sendLock) {
      if (expectedSocket != null && expectedSocket !== activeWebSocket) {
        return false
      }
      if (expectedSocket != null) {
        activeWebSocket = null
        noiseTransport = null
      }
      controlDecoder.reset()
      subscribeLines = LineBuffer()
      controlStreamId = -1
      subscribeStreamId = -1
      subscribeOpen = false
      registerId = ""
    }
    failPendingRequests()
    return true
  }

  private fun failPendingRequests() {
    val iter = pendingRequests.entries.iterator()
    while (iter.hasNext()) {
      val entry = iter.next()
      iter.remove()
      entry.value.done.completeExceptionally(IOException("Noise session closed"))
    }
  }

  private fun closeWebSocket() {
    val ws = synchronized(sendLock) {
      val current = activeWebSocket
      activeWebSocket = null
      noiseTransport = null
      controlDecoder.reset()
      subscribeLines = LineBuffer()
      controlStreamId = -1
      subscribeStreamId = -1
      subscribeOpen = false
      registerId = ""
      current
    }
    failPendingRequests()
    try {
      ws?.close(1000, "Normal closure")
    } catch (e: Exception) {
      Log.w(TAG, "Error closing websocket", e)
    }
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
        Log.i(TAG, "Fetching VMs from Meta cloud (token length=${token.length})")
        val vm = fetchVm(config)
        if (vm == null) {
          // A rejected token only changes once a refresh can go out (or the device is re-paired,
          // which fires reconnectTrigger), so polling fetch_vms sooner just burns rate limit.
          val waitMs = if (lastFetchUnauthorized) maxOf(backoffMs, RefreshGate.remainingMs()) else backoffMs
          Log.w(TAG, "No active VM found; retrying in ${waitMs / 1000}s")
          setLinkState(LinkState.ERROR)
          withTimeoutOrNull(waitMs) {
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
      .addHeader("User-Agent", "musegadget/${BuildConfig.VERSION_NAME} (Android)")
      .get()
      .build()

    try {
      sharedClient.newCall(req).execute().use { resp ->
        lastFetchUnauthorized = resp.code == 401 || resp.code == 403
        if (resp.code == 401 && allowRefreshRetry && config.museRefreshToken.isNotBlank()) {
          Log.i(TAG, "fetch_vms returned 401; attempting token refresh with refresh_token...")
          val refreshed = MuseDeliveryClient().refreshAccessToken(config, BridgeRepository.nodeId)
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
      lastFetchUnauthorized = false
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

    Log.i(TAG, "Connecting WebSocket to $wsUrl")
    val req = Request.Builder()
      .url(wsUrl)
      .addHeader("Authorization", "Bearer ${vm.vmAuthToken}")
      .addHeader("User-Agent", "musegadget/${BuildConfig.VERSION_NAME} (Android)")
      .build()

    val initiator = NoiseXXInitiator()
    initiator.initialize()

    val listener = object : WebSocketListener() {
      private var step = 1
      @Volatile private var sessionTransport: NoiseTransport? = null

      override fun onOpen(webSocket: WebSocket, response: Response) {
        if (webSocket !== activeWebSocket) return
        Log.i(TAG, "WebSocket open; initiating Noise XX handshake step 1...")
        setLinkState(LinkState.HANDSHAKING)
        val msg1 = initiator.writeMessage1()
        webSocket.send(msg1.toByteString())
      }

      override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (webSocket !== activeWebSocket) return
        val raw = bytes.toByteArray()
        when (step) {
          1 -> {
            try {
              initiator.readMessage2(raw)
              val msg3 = initiator.writeMessage3()
              webSocket.send(msg3.toByteString())

              val (sendCipher, recvCipher) = initiator.split()
              val transport = NoiseTransport(sendCipher, recvCipher)
              sessionTransport = transport
              val installed = synchronized(sendLock) {
                if (webSocket !== activeWebSocket) {
                  false
                } else {
                  noiseTransport = transport
                  true
                }
              }
              if (!installed) return
              Log.i(TAG, "Noise XX handshake completed successfully!")

              step = 2
              setLinkState(LinkState.REGISTERING)
              if (!openControlStream(webSocket)) {
                completion.complete(false)
                if (!disconnection.isCompleted) disconnection.complete(Unit)
                return
              }
              setLinkState(LinkState.CONNECTED_ONLINE)
              completion.complete(true)
            } catch (e: Exception) {
              Log.e(TAG, "Noise XX handshake failed at step 1", e)
              completion.complete(false)
              if (!disconnection.isCompleted) disconnection.complete(Unit)
            }
          }
          else -> {
            // Established Noise transport frame (always decrypt with this socket's own transport)
            try {
              val transport = sessionTransport ?: return
              val frame = transport.decryptFrame(raw) ?: return
              if (webSocket !== activeWebSocket) return
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
        if (resetSessionState(webSocket)) {
          setLinkState(LinkState.ERROR)
        }
        if (!completion.isCompleted) completion.complete(false)
        if (!disconnection.isCompleted) disconnection.complete(Unit)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
        if (resetSessionState(webSocket)) {
          setLinkState(LinkState.DISCONNECTED)
        }
        if (!completion.isCompleted) completion.complete(false)
        if (!disconnection.isCompleted) disconnection.complete(Unit)
      }
    }

    activeWebSocket = sharedClient.newWebSocket(req, listener)
  }

  private fun openControlStream(ws: WebSocket): Boolean {
    val started = synchronized(sendLock) {
      if (ws !== activeWebSocket) return@synchronized false
      val transport = noiseTransport ?: return@synchronized false
      val (streamId, frames) = transport.startStreamRequest("POST", "/link-control")
      controlStreamId = streamId
      controlDecoder = ControlDecoder()
      for (frame in frames) {
        ws.send(frame.toByteString())
      }
      true
    }
    if (!started) return false

    val currentNodeId = BridgeRepository.nodeId.ifBlank { "homelink-e1890c" }
    val commands = PortalCommands.registerSpec()
    val registerId = UUID.randomUUID().toString()
    val registerObj = DeviceGuide.registerMessage(
      requestId = registerId,
      nodeId = currentNodeId,
      displayName = DeviceGuide.DISPLAY_NAME,
      version = BuildConfig.VERSION_NAME,
      commands = commands,
    )
    this.registerId = registerId
    if (sendControl(ws, registerObj) != ControlSendResult.SENT) {
      Log.e(TAG, "Failed to send link.register; closing control session")
      ws.close(1000, "link.register send failed")
      return false
    }
    Log.i(
      TAG,
      "Sent link.register as $currentNodeId (${DeviceGuide.DISPLAY_NAME}, family=${DeviceGuide.DEVICE_FAMILY}, model=${DeviceGuide.MODEL_ID}) with ${commands.length()} commands"
    )
    openSubscribe(ws)
    return true
  }

  /** Length-prefixed JSON on the control stream (link.register, link.result). */
  internal fun sendControl(ws: WebSocket, message: JSONObject): ControlSendResult {
    val prefixed = NoiseTransport.encodeLengthPrefixedMessage(message.toString().toByteArray(Charsets.UTF_8))
    if (prefixed.size > DeviceGuide.MAX_CONTROL_MESSAGE_BYTES) {
      Log.e(TAG, "Control message exceeds ${DeviceGuide.MAX_CONTROL_MESSAGE_BYTES} byte budget (${prefixed.size} bytes)")
      return ControlSendResult.TOO_LARGE
    }
    synchronized(sendLock) {
      if (ws !== activeWebSocket) return ControlSendResult.NOT_CONNECTED
      val transport = noiseTransport ?: return ControlSendResult.NOT_CONNECTED
      val sid = controlStreamId
      if (sid <= 0L) return ControlSendResult.NOT_CONNECTED
      return try {
        transport.encryptBodyChunk(sid, prefixed).forEach { ws.send(it.toByteString()) }
        ControlSendResult.SENT
      } catch (e: Exception) {
        Log.e(TAG, "Failed to encrypt/send control message", e)
        ws.close(1008, "control send failed")
        ControlSendResult.FAILED
      }
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
    synchronized(sendLock) {
      if (ws !== activeWebSocket) return
      val transport = noiseTransport ?: return
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

  internal fun attachSessionForTesting(
    ws: WebSocket,
    transport: NoiseTransport,
    controlStreamId: Long,
    registerId: String = "",
    subscribeStreamId: Long = -1L,
  ) {
    synchronized(sendLock) {
      activeWebSocket = ws
      noiseTransport = transport
      this.controlStreamId = controlStreamId
      this.subscribeStreamId = subscribeStreamId
      this.subscribeOpen = subscribeStreamId > 0L
      this.registerId = registerId
      controlDecoder = ControlDecoder()
      subscribeLines = LineBuffer()
    }
  }

  internal fun handleInboundFrame(frame: ServiceFrame) {
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
        if (frame.payload is ServiceFramePayload.Resp && status !in 200..299) {
          Log.w(TAG, "/link-control refused: HTTP $status")
          activeWebSocket?.close(1000, "control stream refused: HTTP $status")
          return
        }
        val messages = try {
          controlDecoder.feed(data)
        } catch (e: Exception) {
          Log.w(TAG, "bad control stream data; closing session to reconnect", e)
          activeWebSocket?.close(1008, "bad control stream data")
          return
        }
        for (m in messages) {
          if (!handleControl(m)) return
        }
        // Upstream #470: end_body on /link-control's Response or BodyChunk means the VM closed the
        // control channel and this device is no longer registered; close so the loop reconnects.
        if (ended) {
          Log.i(TAG, "/link-control stream ended by VM; reconnecting")
          activeWebSocket?.close(1000, "control stream ended")
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
            if (e.event == "client.invoke") clientInvoke(e) else router.add(e)
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

  /** Processes one control message. Returns `false` if the session is being closed and trailing messages should stop. */
  private fun handleControl(m: JSONObject): Boolean {
    val method = m.optString("method")
    when {
      m.optString("id") == registerId && registerId.isNotEmpty() && method.isEmpty() -> {
        val err = m.opt("error")
        val hasError = err != null && err != JSONObject.NULL && err != false && (err !is String || err.isNotEmpty())
        val rejected = hasError || m.opt("ok") == false
        if (rejected) {
          Log.e(TAG, "link.register rejected: ${if (hasError) err else "ok=false"}")
          activeWebSocket?.close(1000, "link.register rejected")
          return false
        } else {
          Log.i(TAG, "Registered with Muse (reply keys: ${m.keys().asSequence().toList()}, result: ${m.optJSONObject("result")?.keys()?.asSequence()?.toList()})")
        }
      }
      m.optString("event") in setOf("link.unpaired", "node.unpaired") -> {
        Log.w(TAG, "Muse removed this device (${m.optString("event")})")
        activeWebSocket?.close(1000, "unpaired")
        return false
      }
      method == "link.invoke" && m.optString("id").isNotEmpty() ->
        runInvoke(m.optString("id"), m.optString("command"), m.optJSONObject("params"))
    }
    return true
  }

  /** A command from a turn started by this device arrives on the chat stream instead (hey-muse). */
  private fun clientInvoke(e: ChatEvent) {
    val id = e.str("invoke_id").ifEmpty { return }
    val params = runCatching { JSONObject(e.str("params_json")) }.getOrNull()
    runInvoke(id, e.str("command_id"), params)
  }

  private fun runInvoke(id: String, command: String, params: JSONObject?) {
    val originWs = activeWebSocket ?: return
    scope.launch(Dispatchers.IO) {
      Log.i(TAG, "invoke $command")
      val result = PortalCommands.invoke(command, params)
      val msg = JSONObject().put("method", "link.result").put("id", id)
      result.keys().forEach { k -> msg.put(k, result.get(k)) }
      when (sendControl(originWs, msg)) {
        ControlSendResult.SENT ->
          Log.i(TAG, "$command -> ${if (result.optBoolean("ok")) "ok" else result.optString("error")}")
        ControlSendResult.TOO_LARGE -> {
          val fallback = JSONObject()
            .put("method", "link.result")
            .put("id", id)
            .put("ok", false)
            .put("error", "command result exceeded control message budget")
          sendControl(originWs, fallback)
          Log.w(TAG, "$command -> failed (exceeded control message budget)")
        }
        ControlSendResult.NOT_CONNECTED ->
          Log.w(TAG, "$command -> dropped (session disconnected)")
        ControlSendResult.FAILED ->
          Log.w(TAG, "$command -> failed (transport error)")
      }
    }
  }

  /** What [ask] reports while Muse answers. */
  /**
   * [mergedInto] is set (with done) when Muse answered this message together with a later one:
   * the combined answer is reported to that later message.
   */
  data class AnswerUpdate(val heard: String, val text: String, val done: Boolean, val mergedInto: String? = null)

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
    router.open(turn)
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
      router.close(turn)
      return@withContext Result.failure(IOException("Muse did not acknowledge the message"))
    }
    if (request.status !in 200..299) {
      router.close(turn)
      return@withContext Result.failure(IOException("Muse refused the message: HTTP ${request.status}"))
    }
    val messageId = ackMessageId(request.body.toString("UTF-8"))
    if (messageId.isEmpty() || onAnswer == null) {
      router.close(turn)
      return@withContext Result.success(messageId)
    }
    router.acknowledged(turn, messageId)
    scope.launch { followAnswer(turn, onAnswer) }
    Result.success(messageId)
  }

  /** Reports the answer as it grows; done once it is whole and Muse has been quiet for 3 s. */
  private suspend fun followAnswer(turn: MuseTurn, onAnswer: (AnswerUpdate) -> Unit) {
    val started = System.currentTimeMillis()
    var shownText = ""
    var shownHeard = ""
    try {
      while (router.isOpen(turn)) {
        val text = turn.text()
        val heard = turn.heard
        val quiet = System.currentTimeMillis() - turn.lastEventAt
        val elapsed = System.currentTimeMillis() - started
        if (text != shownText || heard != shownHeard) {
          shownText = text
          shownHeard = heard
          onAnswer(AnswerUpdate(heard, text, done = false))
        }
        val merged = turn.mergedInto
        when {
          merged != null -> { onAnswer(AnswerUpdate(heard, "", done = true, mergedInto = merged)); return }
          turn.whole() && quiet >= ANSWER_SETTLE_MS -> { onAnswer(AnswerUpdate(heard, text, done = true)); return }
          text.isEmpty() && elapsed > FIRST_REPLY_MS -> { onAnswer(AnswerUpdate(heard, "", done = true)); return }
          elapsed > TURN_CAP_MS -> { onAnswer(AnswerUpdate(heard, text, done = true)); return }
        }
        delay(250)
      }
    } finally {
      router.close(turn)
    }
  }

  /** Text message to Muse, used for ring notes; the answer (if wanted) comes via [onAnswer]. */
  suspend fun sendChat(
    message: String,
    sessionId: String = "",
    onAnswer: ((AnswerUpdate) -> Unit)? = null,
  ): Result<String> = ask(message, null, sessionId, onAnswer)
}
