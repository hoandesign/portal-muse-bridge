package com.portal.pebblebridge.muse

import org.json.JSONObject

/**
 * Gadget identity and `device.guide` contract per Meta's muse-gadget-sdk `protocols/DEVICE.md`.
 */
object DeviceGuide {
  const val DEVICE_FAMILY = "gadget"

  /** Stable hardware model id (`[a-z0-9-]{1,64}`, avoiding reserved `muse-` / `esp-link` prefixes). */
  const val MODEL_ID = "meta-portal-gen1"

  /** Human-readable hardware name (1..128 printable ASCII chars, without `"` or `\`). */
  const val MODEL_NAME = "Meta Portal (1st Gen)"

  const val PLATFORM = "linux"
  const val DISPLAY_NAME = "Portal"

  const val COMMAND = "device.guide"
  const val DESCRIPTION = "This device's usage guide (Markdown): what it is, its controls and limits."
  const val MAX_BYTES = 4096
  const val MAX_MODEL_NAME_BYTES = 128

  /** Documented VM limit: 256 KiB per device control message (including u32 length prefix). */
  const val MAX_CONTROL_MESSAGE_BYTES = 256 * 1024

  private val MODEL_ID_REGEX = Regex("\\A[a-z0-9-]{1,64}\\z")

  // Printable ASCII (0x20..0x7E) excluding '"' (0x22) and '\' (0x5C), per protocols/DEVICE.md.
  private val MODEL_NAME_REGEX = Regex("\\A[ !#-\\[\\]-~]{1,128}\\z")

  /**
   * Usage guide returned by `device.guide`. Describes capabilities, controls, recovery, and limits
   * without setup/pairing steps or personal data (per `protocols/DEVICE.md`).
   */
  val GUIDE_MARKDOWN: String = """
# Meta Portal Muse Bridge

A 1st-gen Meta Portal (1280x800 landscape touchscreen, Android 9) running a Game Boy-style four-shade pixel home screen and screensaver for Muse.

## What it shows and can do

- **Pixel home**: 8-bit robot with a typed speech bubble on the left; pixel clock, date, local weather, active timer countdowns, and month calendar on the right. Seven themes (`classic`, `pocket`, `ice`, `sunset`, `sakura`, `virtual boy`, `matcha`) switch via `portal.set_theme`. `portal.show_message` shows a note in the bubble; `portal.celebrate` cheers; `portal.set_volume` sets media volume (0-100).
- **Ring notes**: Receives transcribed Pebble Index 01 ring voice notes over a local webhook and forwards them to Muse (main or side chat). Replies appear in the bubble and History (last 200).
- **Voice and speech**: Holding the robot records a 16 kHz WAV voice note to Muse. Replies paginate on screen and speak via Android TTS (EN, FR, DE, IT, ES; no built-in Vietnamese voice, so Vietnamese displays silently unless installed).
- **Timers and alarms**: `portal.start_timer`, `portal.set_alarm` (24-hour HH:MM), `portal.list_timers`, and `portal.cancel_timers` persist across restarts, count down under the date, and ring with beeps and a bubble.
- **Weather**: Shows current local temperature and conditions from Open-Meteo (C/F); display-only (no weather command).
- **PortalAni**: When `portal.anilist_*` commands are listed, bridges locally to PortalAni for AniList lists (`portal.anilist_lists`), airing schedules (`portal.anilist_airing`), search (`portal.anilist_search`), and list updates (`portal.anilist_update`); look up `media_id` before updating.

## Controls

- Tap robot to cheer; hold robot to talk; tap bubble to skip/close; tap empty space to cycle themes; tap HISTORY to replay; long-press anywhere for Settings.

## Recovery and limits

- No shell, files, or network tunnel: only listed commands work while connected and enabled in Settings > Muse.
- Reconnects automatically; restart app or reboot if unresponsive.
  """.trimIndent()

  /** Validates `model_id`, `model_name`, and optional `guide` against `protocols/DEVICE.md`. */
  fun validateModel(
    modelId: String = MODEL_ID,
    modelName: String = MODEL_NAME,
    guide: String? = GUIDE_MARKDOWN,
    allowReservedPrefix: Boolean = false,
  ) {
    require(MODEL_ID_REGEX.matches(modelId)) {
      "model_id must be 1 to 64 lowercase letters, digits, and hyphens"
    }
    if (!allowReservedPrefix) {
      require(!modelId.startsWith("muse-") && !modelId.startsWith("esp-link")) {
        "custom model_id must not start with reserved prefix 'muse-' or 'esp-link'"
      }
    }
    require(MODEL_NAME_REGEX.matches(modelName)) {
      "model_name must be 1 to $MAX_MODEL_NAME_BYTES printable ASCII characters without \" or \\"
    }
    if (guide != null) {
      checkGuide(guide)
    }
  }

  /** Validates that [guide] is 1..[MAX_BYTES] bytes of UTF-8. */
  fun checkGuide(guide: String): String {
    val size = guide.toByteArray(Charsets.UTF_8).size
    require(size in 1..MAX_BYTES) {
      "device guide is $size bytes; it must be 1 to $MAX_BYTES"
    }
    return guide
  }

  /** `commands_v2` entry for `device.guide` (matches SDK `device_guide.SPEC`). */
  fun spec(): JSONObject = JSONObject()
    .put("description", DESCRIPTION)
    .put("required", JSONObject())
    .put("optional", JSONObject())

  /** `link.result` payload for `device.guide`. */
  fun payload(guide: String = GUIDE_MARKDOWN): JSONObject {
    checkGuide(guide)
    return JSONObject()
      .put("format", "markdown")
      .put("guide", guide)
  }

  /** Builds the `link.register` request matching `protocols/DEVICE.md`. */
  fun registerMessage(
    requestId: String,
    nodeId: String,
    displayName: String = DISPLAY_NAME,
    version: String,
    commands: JSONObject,
    modelId: String = MODEL_ID,
    modelName: String = MODEL_NAME,
  ): JSONObject {
    validateModel(modelId, modelName, GUIDE_MARKDOWN)
    val message = JSONObject().apply {
      put("type", "req")
      put("id", requestId)
      put("method", "link.register")
      put("params", JSONObject().apply {
        put("node_id", nodeId)
        put("display_name", displayName)
        put("platform", PLATFORM)
        put("version", version)
        put("device_family", DEVICE_FAMILY)
        put("model_id", modelId)
        put("metadata", JSONObject().put("model_name", modelName))
        put("is_wakeup_supported", false)
        put("commands_v2", commands)
      })
    }
    val wireBytes = 4L + message.toString().toByteArray(Charsets.UTF_8).size.toLong()
    require(wireBytes <= MAX_CONTROL_MESSAGE_BYTES.toLong()) {
      "link.register ($wireBytes bytes) exceeds control message budget ($MAX_CONTROL_MESSAGE_BYTES)"
    }
    return message
  }
}
