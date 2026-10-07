package com.portal.pebblebridge.state

import com.portal.pebblebridge.home.HistoryEntry.Status as HStatus
import android.content.Context
import android.content.SharedPreferences
import com.portal.pebblebridge.BuildConfig
import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.model.ServerStatus
import com.portal.pebblebridge.model.VoiceNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object BridgeRepository {

  private const val PREFS_NAME = "portal_pebble_bridge_prefs"
  private const val KEY_PORT = "pref_port"
  private const val KEY_MCP_TOKEN = "pref_mcp_token"
  private const val KEY_DEVELOPER_SDK_TOKEN = "pref_dev_sdk_token"
  private const val KEY_MUSE_ACCESS_TOKEN = "pref_muse_access_token"
  private const val KEY_MUSE_REFRESH_TOKEN = "pref_muse_refresh_token"
  private const val KEY_MUSE_NOISE_HOST = "pref_muse_noise_host"
  private const val KEY_MUSE_SESSION = "pref_muse_session"
  private const val KEY_MUSE_API_URL = "pref_muse_api_url"
  private const val LEGACY_KEY_MUSE_TOKEN = "pref_muse_token"

  private var sharedPreferences: SharedPreferences? = null

  private val _isPaired = MutableStateFlow(false)
  val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

  private val _notes = MutableStateFlow<List<VoiceNote>>(emptyList())
  val notes: StateFlow<List<VoiceNote>> = _notes.asStateFlow()

  private val _serverStatus = MutableStateFlow(ServerStatus())
  val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

  private val _config = MutableStateFlow(
    BridgeConfig(
      port = 8787,
      mcpToken = BuildConfig.DEFAULT_INDEX_MCP_TOKEN,
      developerSdkToken = BuildConfig.DEFAULT_MUSE_SDK_TOKEN,
      museAccessToken = "",
      museRefreshToken = "",
      museSessionId = BuildConfig.DEFAULT_MUSE_SESSION_ID,
      museApiUrl = BuildConfig.DEFAULT_MUSE_API_URL.ifBlank { "https://api.muse.ai" },
    )
  )
  val config: StateFlow<BridgeConfig> = _config.asStateFlow()

  @Volatile
  var deviceId: String = ""

  data class PairingPrompt(
    val peerAddress: String = "",
    val timestamp: Long = System.currentTimeMillis(),
  )

  private val _pairingPrompt = MutableStateFlow<PairingPrompt?>(null)
  val pairingPrompt: StateFlow<PairingPrompt?> = _pairingPrompt.asStateFlow()

  fun showPairingPrompt(peerAddress: String) {
    _pairingPrompt.value = PairingPrompt(peerAddress)
  }

  fun dismissPairingPrompt() {
    _pairingPrompt.value = null
  }

  private val _bleDeviceName = MutableStateFlow("MuseGadget")
  val bleDeviceName: StateFlow<String> = _bleDeviceName.asStateFlow()

  fun setBleDeviceName(name: String) {
    _bleDeviceName.value = name
  }

  @Volatile
  var nodeId: String = "homelink-e1890c"

  private val _museLinkState = MutableStateFlow(com.portal.pebblebridge.model.LinkState.DISCONNECTED)
  val museLinkState: StateFlow<com.portal.pebblebridge.model.LinkState> = _museLinkState.asStateFlow()

  fun updateMuseLinkState(state: com.portal.pebblebridge.model.LinkState) {
    _museLinkState.value = state
  }

  fun resetForTesting() {
    sharedPreferences = null
    _isPaired.value = false
    _pairingPrompt.value = null
    _notes.value = emptyList()
    _bleDeviceName.value = "MuseGadget"
    _museLinkState.value = com.portal.pebblebridge.model.LinkState.DISCONNECTED
    nodeId = "homelink-e1890c"
  }

  fun initPersistence(context: Context) {
    if (sharedPreferences != null) return
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sharedPreferences = prefs

    val savedMcpToken = if (prefs.contains(KEY_MCP_TOKEN)) prefs.getString(KEY_MCP_TOKEN, "") ?: "" else BuildConfig.DEFAULT_INDEX_MCP_TOKEN
    var savedDevToken = if (prefs.contains(KEY_DEVELOPER_SDK_TOKEN)) prefs.getString(KEY_DEVELOPER_SDK_TOKEN, "") ?: "" else BuildConfig.DEFAULT_MUSE_SDK_TOKEN
    var savedAccessToken = prefs.getString(KEY_MUSE_ACCESS_TOKEN, "") ?: ""
    val savedRefreshToken = prefs.getString(KEY_MUSE_REFRESH_TOKEN, "") ?: ""
    val savedNoiseHost = prefs.getString(KEY_MUSE_NOISE_HOST, "") ?: ""
    val savedMuseSession = if (prefs.contains(KEY_MUSE_SESSION)) prefs.getString(KEY_MUSE_SESSION, "") ?: "" else BuildConfig.DEFAULT_MUSE_SESSION_ID
    val savedMuseApiUrl = prefs.getString(KEY_MUSE_API_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_MUSE_API_URL.ifBlank { "https://api.muse.ai" }
    val savedPort = prefs.getInt(KEY_PORT, 8787)

    // Clean migration from legacy pref_muse_token
    val legacyToken = prefs.getString(LEGACY_KEY_MUSE_TOKEN, null)
    if (!legacyToken.isNullOrBlank()) {
      val editor = prefs.edit().remove(LEGACY_KEY_MUSE_TOKEN)
      if (com.portal.pebblebridge.model.isValidDeveloperSdkToken(legacyToken)) {
        if (!prefs.contains(KEY_DEVELOPER_SDK_TOKEN)) {
          savedDevToken = legacyToken
          editor.putString(KEY_DEVELOPER_SDK_TOKEN, savedDevToken)
        }
      } else {
        if (savedAccessToken.isBlank()) {
          savedAccessToken = legacyToken
          editor.putString(KEY_MUSE_ACCESS_TOKEN, savedAccessToken)
        }
      }
      editor.apply()
    }

    if (!prefs.contains(KEY_DEVELOPER_SDK_TOKEN) && savedDevToken.isNotBlank()) {
      prefs.edit().putString(KEY_DEVELOPER_SDK_TOKEN, savedDevToken).apply()
    }

    _config.value = BridgeConfig(
      port = savedPort,
      mcpToken = savedMcpToken,
      developerSdkToken = savedDevToken,
      museAccessToken = savedAccessToken,
      museRefreshToken = savedRefreshToken,
      museSessionId = savedMuseSession,
      museApiUrl = savedMuseApiUrl,
      museNoiseHost = savedNoiseHost,
    )
    _isPaired.value = prefs.getBoolean("pref_is_paired", false)
  }

  fun addNote(note: VoiceNote) {
    com.portal.pebblebridge.home.History.add(com.portal.pebblebridge.home.HistoryEntry(
      id = note.id, time = note.timestampEpochMs, kind = com.portal.pebblebridge.home.HistoryEntry.Kind.RING,
      question = note.text, status = com.portal.pebblebridge.home.HistoryEntry.Status.SENDING, played = true))
    _notes.update { current ->
      listOf(note) + current.take(99) // Keep last 100 notes
    }
    _serverStatus.update { it.copy(totalNotesReceived = it.totalNotesReceived + 1) }
  }

  /** Muse's answer to a note, as it streams in. */
  fun updateNoteReply(id: String, reply: String, done: Boolean) {
    _notes.update { current ->
      current.map { if (it.id == id) it.copy(museReply = reply, replyDone = done) else it }
    }
    if (done) {
      com.portal.pebblebridge.home.History.update(id) {
        it.copy(answer = reply, status = if (reply.isBlank()) HStatus.NO_ANSWER else HStatus.ANSWERED, played = reply.isBlank())
      }
      if (reply.isNotBlank()) com.portal.pebblebridge.home.Playback.enqueue(id)
    }
  }

  /** Muse answered this note together with a later one, in that one's reply. */
  fun markNoteMerged(id: String) {
    _notes.update { current -> current.map { if (it.id == id) it.copy(replyDone = true) else it } }
    com.portal.pebblebridge.home.History.update(id) { it.copy(status = com.portal.pebblebridge.home.HistoryEntry.Status.MERGED) }
  }

  fun updateNoteStatus(
    id: String,
    status: NoteStatus,
    reply: String? = null,
    error: String? = null,
  ) {
    _notes.update { current ->
      current.map { note ->
        if (note.id == id) {
          note.copy(status = status, museReply = reply?.takeIf { it.isNotBlank() } ?: note.museReply, error = error)
            .also { updated ->
              com.portal.pebblebridge.home.History.update(id) { e ->
                when {
                  updated.status == NoteStatus.FAILED -> e.copy(status = HStatus.FAILED)
                  updated.status == NoteStatus.DELIVERED && e.status == HStatus.SENDING -> e.copy(status = HStatus.WAITING)
                  else -> e
                }
              }
            }
        } else {
          note
        }
      }
    }
  }

  fun updateServerStatus(
    isRunning: Boolean,
    localIp: String,
    port: Int,
    error: String? = null,
  ) {
    _serverStatus.update {
      it.copy(
        isRunning = isRunning,
        localIp = localIp,
        port = port,
        lastError = error,
      )
    }
  }

  fun updateConfig(newConfig: BridgeConfig) {
    _config.value = newConfig
    sharedPreferences?.edit()?.apply {
      putInt(KEY_PORT, newConfig.port)
      putString(KEY_MCP_TOKEN, newConfig.mcpToken)
      putString(KEY_DEVELOPER_SDK_TOKEN, newConfig.developerSdkToken)
      putString(KEY_MUSE_ACCESS_TOKEN, newConfig.museAccessToken)
      putString(KEY_MUSE_REFRESH_TOKEN, newConfig.museRefreshToken)
      putString(KEY_MUSE_SESSION, newConfig.museSessionId)
      putString(KEY_MUSE_API_URL, newConfig.museApiUrl)
      putString(KEY_MUSE_NOISE_HOST, newConfig.museNoiseHost)
      apply()
    }
  }

  fun clearNotes() {
    _notes.value = emptyList()
  }

  fun isPaired(): Boolean {
    return _isPaired.value
  }

  fun setPaired(paired: Boolean) {
    _isPaired.value = paired
    sharedPreferences?.edit()?.putBoolean("pref_is_paired", paired)?.apply()
  }
}
