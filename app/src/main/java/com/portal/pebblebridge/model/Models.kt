package com.portal.pebblebridge.model

enum class NoteStatus {
  PENDING,
  DELIVERED,
  FAILED,
}

enum class LinkState {
  DISCONNECTED,
  FETCHING_VM,
  CONNECTING_WS,
  HANDSHAKING,
  REGISTERING,
  CONNECTED_ONLINE,
  ERROR,
}

data class VoiceNote(
  val id: String,
  val timestampEpochMs: Long,
  val text: String,
  val title: String? = null,
  val source: String = "Pebble Index Ring",
  val status: NoteStatus = NoteStatus.PENDING,
  val museReply: String? = null,
  val error: String? = null,
)

val DEVELOPER_SDK_TOKEN_REGEX = Regex("^mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")

fun isValidDeveloperSdkToken(token: String): Boolean =
  DEVELOPER_SDK_TOKEN_REGEX.matches(token.trim())

data class BridgeConfig(
  val port: Int = 8787,
  val mcpToken: String = "",
  val developerSdkToken: String = "",
  val museAccessToken: String = "",
  val museRefreshToken: String = "",
  val museSessionId: String = "",
  val museApiUrl: String = "https://api.muse.ai",
  val museNoiseHost: String = "",
) {
  // Backward compatibility property for delivery client and UI: returns effective token
  val museSdkToken: String
    get() = effectiveMuseToken

  val effectiveMuseToken: String
    get() = museAccessToken.ifBlank { developerSdkToken }
}

data class ServerStatus(
  val isRunning: Boolean = false,
  val port: Int = 8787,
  val localIp: String = "127.0.0.1",
  val totalNotesReceived: Int = 0,
  val lastError: String? = null,
)
