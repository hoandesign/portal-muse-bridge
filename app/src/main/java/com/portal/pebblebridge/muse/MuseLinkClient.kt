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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  private var controlStreamId: Long = 0
  private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<String>>()
  private val txMutex = Mutex()

  private fun setLinkState(state: LinkState) {
    _linkState.value = state
    BridgeRepository.updateMuseLinkState(state)
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
    val (streamId, frames) = transport.startStreamRequest("POST", "/link-control")
    controlStreamId = streamId

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
        put("commands_v2", JSONObject())
      })
    }

    val registerBytes = registerObj.toString().toByteArray(Charsets.UTF_8)
    val prefixed = NoiseTransport.encodeLengthPrefixedMessage(registerBytes)
    val chunkFrames = transport.encryptBodyChunk(streamId, prefixed)
    for (frame in chunkFrames) {
      ws.send(frame.toByteString())
    }
    Log.i(TAG, "Sent link.register as $currentNodeId ($currentDisplayName) on streamId=$streamId")
  }

  private fun handleInboundFrame(frame: ServiceFrame) {
    when (val p = frame.payload) {
      is ServiceFramePayload.Resp -> {
        Log.i(TAG, "Received ApplicationResponse: status=${p.response.status}")
        val deferred = pendingRequests.remove(frame.streamId)
        val bodyText = if (frame.streamId == controlStreamId) {
          NoiseTransport.decodeLengthPrefixedMessage(p.response.body)?.toString(Charsets.UTF_8)
            ?: String(p.response.body, Charsets.UTF_8)
        } else {
          String(p.response.body, Charsets.UTF_8)
        }
        deferred?.complete(bodyText)
      }
      is ServiceFramePayload.Chunk -> {
        val deferred = pendingRequests.remove(frame.streamId)
        val chunkText = if (frame.streamId == controlStreamId) {
          NoiseTransport.decodeLengthPrefixedMessage(p.chunk.data)?.toString(Charsets.UTF_8)
            ?: String(p.chunk.data, Charsets.UTF_8)
        } else {
          String(p.chunk.data, Charsets.UTF_8)
        }
        deferred?.complete(chunkText)
      }
      else -> {}
    }
  }

  suspend fun sendChat(message: String, sessionId: String = ""): Result<String> = withContext(Dispatchers.IO) {
    val ws = activeWebSocket ?: return@withContext Result.failure(IOException("Not connected to Muse cloud"))
    val transport = noiseTransport ?: return@withContext Result.failure(IOException("Noise transport not ready"))

    try {
      val (streamId, deferred) = txMutex.withLock {
        val currentNodeId = BridgeRepository.nodeId.ifBlank { "homelink-e1890c" }
        val reqBody = JSONObject().apply {
          put("message", message)
          put("output_modality", "text")
          put("device_id", currentNodeId)
          if (sessionId.isNotBlank()) {
            put("session_id", sessionId)
          }
        }.toString().toByteArray(Charsets.UTF_8)

        val headers = listOf(
          NoiseHeader("Content-Type", "application/json"),
          NoiseHeader("x-request-id", UUID.randomUUID().toString()),
          NoiseHeader("x-app-id", APP_ID)
        )

        val (sid, frames) = transport.startStreamRequest("POST", "/chat/stream", headers)
        val chunkFrames = transport.encryptBodyChunk(sid, reqBody, endBody = true)

        val d = CompletableDeferred<String>()
        pendingRequests[sid] = d

        for (f in frames) ws.send(f.toByteString())
        for (f in chunkFrames) ws.send(f.toByteString())

        Pair(sid, d)
      }

      val reply = try {
        withTimeoutOrNull(45_000L) {
          deferred.await()
        } ?: "Voice note delivered to Muse AI"
      } finally {
        pendingRequests.remove(streamId)
      }

      Result.success(reply)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send chat to Muse", e)
      Result.failure(e)
    }
  }
}
