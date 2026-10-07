package com.portal.pebblebridge.muse

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import com.portal.pebblebridge.home.HomePrefs
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads Muse's answers aloud with Android's text-to-speech. Picks Vietnamese for text with
 * Vietnamese letters when the engine has it, otherwise the device default.
 */
object Speaker {
  private const val TAG = "Speaker"

  enum class Status { STARTING, READY, NO_ENGINE }

  private val _status = MutableStateFlow(Status.STARTING)
  val status: StateFlow<Status> = _status.asStateFlow()

  private val _speaking = MutableStateFlow(false)
  /** True while an utterance is playing; the robot "talks" along. */
  val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

  @Volatile private var tts: TextToSpeech? = null
  private val VIETNAMESE_LOCALE = Locale("vi", "VN")

  /** Whether the engine has a Vietnamese voice (the Portal's built-in one doesn't). */
  @Volatile var vietnameseVoice = false
    private set

  /** Vietnamese text is only read when there's a Vietnamese voice for it. */
  fun shouldSpeak(isVietnamese: Boolean, hasVietnameseVoice: Boolean) = !isVietnamese || hasVietnameseVoice

  fun init(context: Context) {
    if (tts != null) return
    tts = TextToSpeech(context.applicationContext) { code ->
      val engine = tts
      if (code != TextToSpeech.SUCCESS || engine == null) {
        Log.w(TAG, "no text-to-speech engine (code $code)")
        _status.value = Status.NO_ENGINE
        return@TextToSpeech
      }
      engine.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
        override fun onStart(id: String?) { _speaking.value = true }
        override fun onDone(id: String?) { _speaking.value = false }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { _speaking.value = false }
      })
      vietnameseVoice = engine.isLanguageAvailable(VIETNAMESE_LOCALE) >= TextToSpeech.LANG_AVAILABLE
      _status.value = Status.READY
      Log.i(TAG, "text-to-speech ready: ${engine.defaultEngine}")
    }
  }

  fun speakIfEnabled(text: String) {
    if (HomePrefs.settings.value.speakAnswers) speak(text)
  }

  fun speak(text: String) {
    val engine = tts ?: return
    if (_status.value != Status.READY) return
    val clean = forSpeech(text)
    if (clean.isBlank()) return
    val vietnamese = looksVietnamese(clean)
    if (!shouldSpeak(vietnamese, vietnameseVoice)) {
      // An English voice would mangle Vietnamese; the answer stays on screen only.
      Log.i(TAG, "no Vietnamese voice; showing the answer without reading it")
      return
    }
    engine.language = if (vietnamese) VIETNAMESE_LOCALE else Locale.getDefault()
    // Engines cap one utterance (often ~4000 chars); speak in sentence-sized pieces.
    chunks(clean, 3_500).forEachIndexed { i, part ->
      engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }
  }

  fun stop() {
    tts?.stop()
    _speaking.value = false
  }

  private val VIETNAMESE = Regex("[ăâđêôơưĂÂĐÊÔƠƯàảãáạằẳẵắặầẩẫấậèẻẽéẹềểễếệìỉĩíịòỏõóọồổỗốộờởỡớợùủũúụừửữứựỳỷỹýỵ]")

  fun looksVietnamese(text: String) = VIETNAMESE.containsMatchIn(text)

  /** Drops Markdown marks and links so they aren't read out. */
  fun forSpeech(text: String): String = text
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("""https?://\S+"""), "")
    .replace(Regex("""[*_`#>]+"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

  fun chunks(text: String, max: Int): List<String> {
    if (text.length <= max) return listOf(text)
    val out = mutableListOf<String>()
    var rest = text
    while (rest.length > max) {
      val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ','), max).takeIf { it > max / 2 } ?: max
      out += rest.substring(0, cut + 1).trim()
      rest = rest.substring(cut + 1)
    }
    if (rest.isNotBlank()) out += rest.trim()
    return out
  }
}
