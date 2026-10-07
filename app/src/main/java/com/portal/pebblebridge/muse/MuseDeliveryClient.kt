package com.portal.pebblebridge.muse

import android.util.Log
import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.LinkState
import com.portal.pebblebridge.model.VoiceNote
import com.portal.pebblebridge.state.BridgeRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MuseDeliveryClient(
  private val linkClientProvider: () -> MuseLinkClient? = { MuseLinkClient.activeInstance },
  private val httpClient: OkHttpClient = sharedHttpClient,
) {

  companion object {
    private const val TAG = "MuseDeliveryClient"

    val sharedHttpClient: OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
  }

  /** Muse's answer to a ring note: stored on the note (shown by the robot) and read aloud when done. */
  private fun answerHandler(noteId: String): (MuseLinkClient.AnswerUpdate) -> Unit = { u ->
    when {
      u.mergedInto != null -> BridgeRepository.markNoteMerged(noteId)
      u.text.isNotBlank() || u.done -> BridgeRepository.updateNoteReply(noteId, u.text, u.done)
    }
  }

  suspend fun deliverNote(
    note: VoiceNote,
    config: BridgeConfig,
    deviceId: String = "",
  ): Result<String> = withContext(Dispatchers.IO) {
    try {
      val linkClient = linkClientProvider()

      // 1. If connected via Muse Noise WebSocket session, deliver note as /chat/stream
      if (linkClient != null && linkClient.linkState.value == LinkState.CONNECTED_ONLINE) {
        val chatResult = linkClient.sendChat(museMessage(note.text), config.museSessionId, answerHandler(note.id))
        if (chatResult.isSuccess) {
          return@withContext Result.success("")
        }
        Log.w(TAG, "Chat delivery via Noise link failed: ${chatResult.exceptionOrNull()?.message}; trying fallback")
      }

      // 2. If link client is in the middle of connecting, give it up to 4s to finish handshake
      if (linkClient != null && (linkClient.linkState.value == LinkState.FETCHING_VM ||
          linkClient.linkState.value == LinkState.CONNECTING_WS ||
          linkClient.linkState.value == LinkState.HANDSHAKING ||
          linkClient.linkState.value == LinkState.REGISTERING)) {
        withTimeoutOrNull(4000L) {
          linkClient.linkState.first { it == LinkState.CONNECTED_ONLINE }
        }
        if (linkClient.linkState.value == LinkState.CONNECTED_ONLINE) {
          val chatResult = linkClient.sendChat(museMessage(note.text), config.museSessionId, answerHandler(note.id))
          if (chatResult.isSuccess) {
            return@withContext Result.success("")
          }
        }
      }

      // 3. Custom REST endpoint support (for self-hosted or testing webhook servers)
      val isCustomEndpoint = !config.museApiUrl.startsWith("https://hatch-api.meta.ai") &&
                             !config.museApiUrl.startsWith("https://api.muse.ai")
      if (isCustomEndpoint && config.museSdkToken.isNotBlank()) {
        val result = executeDelivery(note, config, config.museSdkToken)
        if (result.isFailure && config.museRefreshToken.isNotBlank()) {
          val ex = result.exceptionOrNull()
          if (ex is IOException && ex.message?.contains("401") == true) {
            val effectiveDeviceId = deviceId.ifBlank { BridgeRepository.deviceId }
            val refreshResult = refreshAccessToken(config, effectiveDeviceId)
            if (refreshResult.isSuccess) {
              val newAccessToken = refreshResult.getOrThrow()
              return@withContext executeDelivery(note, BridgeRepository.config.value, newAccessToken)
            }
          }
        }
        return@withContext result
      }

      // 4. If tokens exist but cloud link is offline
      if (config.museAccessToken.isNotBlank()) {
        val linkStateStr = linkClient?.linkState?.value?.name ?: "DISCONNECTED"
        return@withContext Result.failure(
          IOException("Meta Muse Cloud link is not connected ($linkStateStr). Please ensure device is online.")
        )
      }

      // 5. No tokens configured
      Result.success("Received & logged on Portal (Pair device with Meta Muse app to stream notes to cloud)")
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  private fun executeDelivery(
    note: VoiceNote,
    config: BridgeConfig,
    token: String,
  ): Result<String> {
    val jsonBody = JSONObject().apply {
      put("message", museMessage(note.text))
      if (!note.title.isNullOrBlank()) {
        put("title", note.title)
      }
      if (config.museSessionId.isNotBlank()) {
        put("session_id", config.museSessionId)
      }
      put("timestamp", note.timestampEpochMs)
      put("source", "pebble_index_ring")
    }

    val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
    val url = if (config.museApiUrl.endsWith("/")) "${config.museApiUrl}ingest" else "${config.museApiUrl}/ingest"

    val request = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer $token")
      .addHeader("User-Agent", "PortalPebbleBridge/1.0 (Android)")
      .post(requestBody)
      .build()

    return httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        val errBody = response.body?.string().orEmpty()
        Result.failure(IOException("Muse server rejected note: HTTP ${response.code} $errBody"))
      } else {
        val replyBody = response.body?.string().orEmpty()
        Result.success(replyBody.ifBlank { "Delivered to Muse AI" })
      }
    }
  }

  suspend fun refreshAccessToken(
    config: BridgeConfig,
    deviceId: String,
  ): Result<String> = withContext(Dispatchers.IO) {
    val refreshToken = config.museRefreshToken.trim()
    if (refreshToken.isBlank()) {
      return@withContext Result.failure(IllegalStateException("No refresh token available"))
    }
    try {
      val rawRefresh = refreshToken.substringAfterLast(":")
      val root = if (config.museApiUrl.startsWith("https://hatch-api.meta.ai")) {
        "https://api.muse.ai"
      } else {
        config.museApiUrl.trim().trimEnd('/')
      }
      val url = "$root/device_token/refresh"

      val body = JSONObject().apply {
        put("device_id", deviceId)
        if (config.developerSdkToken.isNotBlank()) {
          put("sdk_token", config.developerSdkToken.trim())
        }
      }

      val request = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer hatch_refresh:$rawRefresh")
        .addHeader("Content-Type", "application/json")
        .addHeader("User-Agent", "PortalPebbleBridge/1.0 (Android)")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return@withContext Result.failure(
            IOException("Token refresh rejected: HTTP ${response.code}")
          )
        }
        val respText = response.body?.string().orEmpty()
        val json = JSONObject(respText)
        val payload = json.optJSONObject("payload") ?: json
        val newAccess = payload.optString("access_token")
        val newRefresh = payload.optString("refresh_token")
        if (newAccess.isNotBlank()) {
          BridgeRepository.updateConfig(
            config.copy(
              museAccessToken = newAccess,
              museRefreshToken = newRefresh.ifBlank { refreshToken },
            )
          )
          Result.success(newAccess)
        } else {
          Result.failure(IOException("Missing access_token in refresh response"))
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}

/** Appended to every note sent to Muse (not shown on the Portal), so Muse reads past STT errors. */
const val TRANSCRIPTION_HINT =
  "(Transcribed from speech, so some words may be misheard. " +
    "Please go by what I most likely meant.)"

/** The text Muse receives for a ring note: the transcription followed by [TRANSCRIPTION_HINT]. */
fun museMessage(transcription: String): String = "${transcription.trim()}\n\n$TRANSCRIPTION_HINT"
