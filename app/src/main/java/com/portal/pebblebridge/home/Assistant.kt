package com.portal.pebblebridge.home

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.portal.pebblebridge.muse.MuseLinkClient
import com.portal.pebblebridge.muse.Speaker
import com.portal.pebblebridge.muse.pcmToWav
import com.portal.pebblebridge.state.BridgeRepository
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hold-the-robot voice turns, like hey-muse: record from the Portal mic, send the WAV to Muse as
 * a voice note (Muse transcribes it), then show and speak the answer.
 */
object Assistant {
  private const val TAG = "Assistant"
  private const val SAMPLE_RATE = 16_000
  private const val MAX_RECORD_MS = 60_000L
  private const val MIN_RECORD_MS = 600L

  sealed class State {
    object Idle : State()
    data class Listening(val level: Float) : State()
    data class Thinking(val heard: String) : State()
    data class Answer(val heard: String, val text: String, val done: Boolean) : State()
    data class Problem(val message: String) : State()
  }

  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile private var recording = false
  @Volatile private var recorder: AudioRecord? = null
  private val pcm = ByteArrayOutputStream()
  private var startedAt = 0L

  fun hasMicPermission(context: Context) =
    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  /** Starts recording. Returns false (with a Problem state) when it can't. */
  fun startListening(context: Context): Boolean {
    if (recording) return true
    if (!hasMicPermission(context)) return fail("MIC PERMISSION NEEDED")
    if (MuseLinkClient.activeInstance?.linkState?.value != com.portal.pebblebridge.model.LinkState.CONNECTED_ONLINE) {
      return fail("MUSE IS OFFLINE")
    }
    Speaker.stop()
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = try {
      @Suppress("MissingPermission")
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE))
    } catch (e: Exception) {
      Log.w(TAG, "AudioRecord failed", e); return fail("MIC UNAVAILABLE")
    }
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return fail("MIC UNAVAILABLE") }
    pcm.reset()
    recorder = rec
    recording = true
    startedAt = System.currentTimeMillis()
    _state.value = State.Listening(0f)
    rec.startRecording()
    scope.launch {
      val buf = ShortArray(1024)
      val bytes = ByteArray(2048)
      while (recording) {
        val n = rec.read(buf, 0, buf.size)
        if (n <= 0) continue
        var sum = 0.0
        for (i in 0 until n) {
          val v = buf[i].toInt()
          sum += v.toDouble() * v
          bytes[2 * i] = (v and 0xFF).toByte()
          bytes[2 * i + 1] = (v shr 8 and 0xFF).toByte()
        }
        synchronized(pcm) { pcm.write(bytes, 0, 2 * n) }
        val level = (sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0).toFloat()
        if (recording) _state.value = State.Listening(level)
        if (System.currentTimeMillis() - startedAt > MAX_RECORD_MS) stopAndSend()
      }
    }
    return true
  }

  /** Stops recording and sends the voice note; too-short presses are dropped. */
  fun stopAndSend() {
    if (!recording) return
    recording = false
    val rec = recorder
    recorder = null
    runCatching { rec?.stop() }
    rec?.release()
    val audio = synchronized(pcm) { pcm.toByteArray() }
    if (System.currentTimeMillis() - startedAt < MIN_RECORD_MS || audio.size < SAMPLE_RATE / 2) {
      _state.value = State.Idle
      return
    }
    _state.value = State.Thinking("")
    scope.launch {
      val link = MuseLinkClient.activeInstance
      if (link == null) { fail("MUSE IS OFFLINE"); return@launch }
      val result = link.ask(null, pcmToWav(audio, SAMPLE_RATE), BridgeRepository.config.value.museSessionId) { u ->
        _state.value = when {
          u.text.isEmpty() && u.done -> State.Problem("NO ANSWER FROM MUSE")
          u.text.isEmpty() -> State.Thinking(u.heard)
          else -> State.Answer(u.heard, u.text, u.done)
        }
        if (u.done && u.text.isNotBlank()) Speaker.speakIfEnabled(u.text)
      }
      result.onFailure { fail("COULDN'T REACH MUSE") }
    }
  }

  fun cancel() {
    recording = false
    runCatching { recorder?.stop() }
    recorder?.release()
    recorder = null
    _state.value = State.Idle
  }

  /** Back to idle (e.g. the user closed the bubble). */
  fun dismiss() { if (!recording) _state.value = State.Idle }

  private fun fail(message: String): Boolean {
    _state.value = State.Problem(message)
    scope.launch { delay(3_000); if (_state.value is State.Problem) _state.value = State.Idle }
    return false
  }
}
