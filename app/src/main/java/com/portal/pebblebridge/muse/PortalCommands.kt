package com.portal.pebblebridge.muse

import android.content.Context
import android.media.AudioManager
import com.portal.pebblebridge.home.DeviceEvent
import com.portal.pebblebridge.home.DeviceEvents
import com.portal.pebblebridge.home.HomePrefs
import com.portal.pebblebridge.home.Timers
import com.portal.pebblebridge.home.describeDuration
import com.portal.pebblebridge.ui.home.THEMES
import org.json.JSONArray
import org.json.JSONObject

/** One parameter of a Muse command: type is "string", "integer" or "boolean". */
data class CommandParam(val name: String, val type: String, val description: String)

/**
 * A command Muse may call on this Portal. Muse reads [description] to decide when, so each says
 * when to use it. [run] gets the params and returns the payload of `link.result`.
 */
data class PortalCommand(
  val name: String,
  val description: String,
  val required: List<CommandParam> = emptyList(),
  val optional: List<CommandParam> = emptyList(),
  val run: (JSONObject) -> JSONObject,
)

class CommandError(message: String) : Exception(message)

/**
 * What Muse can do on the Portal, announced in `link.register` as `commands_v2` (the SDK
 * executor's format) and run on `link.invoke` / `client.invoke`. Everything acts on this Portal
 * only: nothing runs shell commands, reads files or reaches other devices.
 */
object PortalCommands {
  @Volatile private var appContext: Context? = null

  fun init(context: Context) { appContext = context.applicationContext }

  private fun ok(result: String) = JSONObject().put("result", result)

  val all: List<PortalCommand> = listOf(
    PortalCommand(
      "portal.show_message",
      "Use when asked to show, display or say something on the Portal's screen, or to leave a note or message on it. The robot shows it in a speech bubble and reads it aloud if speaking is on.",
      required = listOf(CommandParam("text", "string", "What to show, short and plain.")),
      optional = listOf(CommandParam("title", "string", "A one-word heading, e.g. REMINDER. Default MUSE.")),
    ) { p ->
      val text = p.optString("text").trim().ifEmpty { throw CommandError("text is empty") }
      val title = p.optString("title").trim().uppercase().take(16).ifEmpty { "MUSE" }
      DeviceEvents.emit(DeviceEvent.Say(text.take(600), title))
      Speaker.speakIfEnabled(text)
      ok("Shown on the Portal.")
    },
    PortalCommand(
      "portal.start_timer",
      "Use when asked to set or start a timer or countdown on the Portal.",
      required = listOf(CommandParam("seconds", "integer", "Length of the timer in seconds, 1 to 86400.")),
      optional = listOf(CommandParam("label", "string", "What the timer is for, e.g. tea.")),
    ) { p ->
      val seconds = p.optLong("seconds", -1)
      if (seconds !in 1..86_400) throw CommandError("seconds must be between 1 and 86400")
      val t = Timers.startTimer(seconds, p.optString("label").trim().uppercase().take(24))
      ok("Timer '${t.label}' set for ${describeDuration(seconds * 1000)}.")
    },
    PortalCommand(
      "portal.set_alarm",
      "Use when asked to set an alarm or a reminder at a clock time on the Portal, or to be woken at a time.",
      required = listOf(CommandParam("time", "string", "24-hour local time, HH:MM, e.g. 07:30.")),
      optional = listOf(CommandParam("label", "string", "What the alarm is for.")),
    ) { p ->
      val (h, m) = Timers.parseTime(p.optString("time")) ?: throw CommandError("time must be HH:MM in 24-hour form")
      val t = Timers.setAlarm(h, m, p.optString("label").trim().uppercase().take(24))
      ok("Alarm '${t.label}' set for %02d:%02d, in ${describeDuration(t.endsAt - System.currentTimeMillis())}.".format(h, m))
    },
    PortalCommand(
      "portal.list_timers",
      "Use when asked what timers or alarms are set on the Portal, or how long is left.",
    ) { _ ->
      val now = System.currentTimeMillis()
      val list = Timers.timers.value
      val items = JSONArray()
      list.forEach {
        items.put(JSONObject().put("label", it.label).put("kind", if (it.isAlarm) "alarm" else "timer")
          .put("remaining", describeDuration(it.endsAt - now)))
      }
      JSONObject().put("result", if (list.isEmpty()) "No timers or alarms." else "${list.size} set.").put("items", items)
    },
    PortalCommand(
      "portal.cancel_timers",
      "Use when asked to cancel, stop or delete a timer or alarm on the Portal: the one named, or all of them.",
      optional = listOf(CommandParam("label", "string", "Cancel only those whose label contains this. Empty cancels all.")),
    ) { p ->
      val n = Timers.cancel(p.optString("label").trim())
      ok(if (n == 0) "Nothing matched." else "Cancelled $n.")
    },
    PortalCommand(
      "portal.set_volume",
      "Use when asked to make the Portal louder or quieter, or set its volume.",
      required = listOf(CommandParam("level", "integer", "Volume from 0 (silent) to 100 (loudest).")),
    ) { p ->
      val level = p.optInt("level", -1)
      if (level !in 0..100) throw CommandError("level must be 0 to 100")
      val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: throw CommandError("audio is not available")
      val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level / 100f * max), 0)
      ok("Volume set to $level%.")
    },
    PortalCommand(
      "portal.set_theme",
      "Use when asked to change the colors or theme of the Portal's screen.",
      required = listOf(CommandParam("name", "string", "One of: ${THEMES.joinToString(", ") { it.name.lowercase() }}.")),
    ) { p ->
      val wanted = p.optString("name").trim()
      val index = THEMES.indexOfFirst { it.name.equals(wanted, ignoreCase = true) }
      if (index < 0) throw CommandError("unknown theme; use one of ${THEMES.joinToString { it.name.lowercase() }}")
      HomePrefs.update { it.copy(themeIndex = index) }
      ok("Theme set to ${THEMES[index].name.lowercase()}.")
    },
    PortalCommand(
      "portal.celebrate",
      "Use when asked to celebrate, dance, cheer or make the Portal's robot happy.",
    ) { _ ->
      DeviceEvents.emit(DeviceEvent.Celebrate)
      ok("The robot is celebrating.")
    },
  )

  private val byName = all.associateBy { it.name }

  /** `commands_v2` for link.register, or empty when the user turned commands off. */
  fun registerSpec(enabled: Boolean = HomePrefs.settings.value.museCommands): JSONObject {
    val spec = JSONObject()
    if (!enabled) return spec
    for (c in all) {
      fun params(list: List<CommandParam>) = JSONObject().apply {
        list.forEach { put(it.name, JSONObject().put("type", it.type).put("description", it.description)) }
      }
      spec.put(c.name, JSONObject()
        .put("description", c.description)
        .put("required", params(c.required))
        .put("optional", params(c.optional)))
    }
    return spec
  }

  /** Runs a command and builds the link.result fields: ok + payload, or ok=false + error. */
  fun invoke(name: String, params: JSONObject?): JSONObject {
    val result = JSONObject()
    val command = byName[name]
    if (command == null || !HomePrefs.settings.value.museCommands) {
      return result.put("ok", false).put("error", "unsupported command: $name")
    }
    for (p in command.required) {
      if (params == null || !params.has(p.name)) return result.put("ok", false).put("error", "missing parameter: ${p.name}")
    }
    return try {
      result.put("ok", true).put("payload", command.run(params ?: JSONObject()))
    } catch (e: CommandError) {
      result.put("ok", false).put("error", e.message)
    } catch (e: Exception) {
      result.put("ok", false).put("error", "failed: ${e.message}")
    }
  }
}
