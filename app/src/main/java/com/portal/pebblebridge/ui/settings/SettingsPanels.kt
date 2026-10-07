package com.portal.pebblebridge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.pebblebridge.home.HomePrefs
import com.portal.pebblebridge.home.HomeSettings
import com.portal.pebblebridge.home.ScreensaverGuard
import com.portal.pebblebridge.home.WeatherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CardColor = Color(0xFF27272A)
private val Title = Color(0xFFE4E4E7)
private val Muted = Color(0xFFA1A1AA)
private val Good = Color(0xFF34D399)
private val Bad = Color(0xFFF87171)

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
  Card(
    colors = CardDefaults.cardColors(containerColor = CardColor),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Title)
      content()
    }
  }
}

@Composable
private fun ToggleRow(label: String, hint: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.weight(1f)) {
      Text(label, fontSize = 14.sp, color = Title)
      if (hint != null) Text(hint, fontSize = 12.sp, color = Muted)
    }
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

/** Weather, clock, calendar, robot and display options for the pixel home screen. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeSettingsPanel() {
  val s by HomePrefs.settings.collectAsState()
  val set: ((HomeSettings) -> HomeSettings) -> Unit = { HomePrefs.update(it) }
  val scope = rememberCoroutineScope()
  var city by remember(s.weatherCity) { mutableStateOf(s.weatherCity) }
  var checkResult by remember { mutableStateOf<String?>(null) }
  var checking by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      SettingsCard("Weather") {
        OutlinedTextField(
          value = city,
          onValueChange = { city = it; checkResult = null },
          label = { Text("City (leave blank to detect automatically)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Button(
            enabled = !checking,
            onClick = {
              checking = true
              scope.launch {
                val draft = s.copy(weatherCity = city.trim())
                val result = withContext(Dispatchers.IO) { runCatching { WeatherClient().fetch(draft) } }
                checking = false
                result.onSuccess { w ->
                  set { it.copy(weatherCity = city.trim()) }
                  checkResult = "Saved: ${w.place.ifBlank { "your location" }}, ${w.temp}°${w.unit}"
                }.onFailure { e ->
                  checkResult = "Couldn't find weather: ${e.message}"
                }
              }
            },
          ) { Text(if (checking) "Checking…" else "Check & save") }
          if (s.weatherCity.isNotBlank()) {
            OutlinedButton(onClick = { city = ""; set { it.copy(weatherCity = "") }; checkResult = "Using automatic location" }) {
              Text("Use automatic")
            }
          }
        }
        checkResult?.let {
          Text(it, fontSize = 12.sp, color = if (it.startsWith("Couldn't")) Bad else Good)
        }
        ToggleRow("Fahrenheit (°F)", "Off shows Celsius", s.fahrenheit) { v -> set { it.copy(fahrenheit = v) } }
      }

      SettingsCard("Clock & calendar") {
        ToggleRow("24-hour clock", null, s.use24h) { v -> set { it.copy(use24h = v) } }
        ToggleRow("Show seconds", null, s.showSeconds) { v -> set { it.copy(showSeconds = v) } }
        ToggleRow("Week starts on Monday", "Off starts on Sunday", s.weekStartsMonday) { v -> set { it.copy(weekStartsMonday = v) } }
      }
    }

    Column(
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      SettingsCard("Robot & ring notes") {
        ToggleRow("Robot dances", "Off: it stands and bobs gently", s.robotDances) { v -> set { it.copy(robotDances = v) } }
        Text("Keep a ring note on screen for", fontSize = 14.sp, color = Title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HomePrefs.BUBBLE_CHOICES.forEach { secs ->
            FilterChip(
              selected = s.bubbleSeconds == secs,
              onClick = { set { it.copy(bubbleSeconds = secs) } },
              label = { Text(if (secs < 60) "${secs}s" else "${secs / 60} min") },
            )
          }
        }
      }

      SettingsCard("Color theme") {
        Text("Tip: tap any empty spot on the home screen to switch.", fontSize = 12.sp, color = Muted)
        androidx.compose.foundation.layout.FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          com.portal.pebblebridge.ui.home.THEMES.forEachIndexed { i, theme ->
            FilterChip(
              selected = s.themeIndex == i,
              onClick = { set { it.copy(themeIndex = i) } },
              label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  listOf(theme.darkest, theme.dark, theme.light, theme.lightest).forEach { c ->
                    androidx.compose.foundation.layout.Box(
                      Modifier.padding(end = 2.dp).width(10.dp).height(14.dp)
                        .background(c),
                    )
                  }
                  Spacer(Modifier.width(6.dp))
                  Text(theme.name.lowercase().replaceFirstChar { it.uppercase() })
                }
              },
            )
          }
        }
      }

      SettingsCard("Display") {
        ToggleRow("Dim at night", "Darker between 22:00 and 06:00", s.nightDim) { v -> set { it.copy(nightDim = v) } }
        ToggleRow("Protect the screen", "Shifts everything a few pixels each minute", s.pixelShift) { v -> set { it.copy(pixelShift = v) } }
      }
    }
  }
}

/** Turn the pixel home on/off as the Portal screensaver, with a permission check. */
@Composable
fun ScreensaverSettingsPanel() {
  val context = LocalContext.current
  val s by HomePrefs.settings.collectAsState()
  var refresh by remember { mutableIntStateOf(0) }
  var hasPermission by remember { mutableStateOf(ScreensaverGuard.hasPermission(context)) }
  var current by remember { mutableStateOf(ScreensaverGuard.currentComponent(context)) }
  LaunchedEffect(refresh) {
    delay(300)
    hasPermission = ScreensaverGuard.hasPermission(context)
    current = ScreensaverGuard.currentComponent(context)
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    SettingsCard("Screensaver") {
      ToggleRow(
        "Use as Portal screensaver",
        "Shows the robot, clock and calendar when the Portal is idle",
        s.screensaverEnabled,
      ) { v ->
        HomePrefs.update { it.copy(screensaverEnabled = v) }
        ScreensaverGuard.apply(context, v)
        refresh++
      }
      ToggleRow(
        "Keep the screen on",
        "While showing as the screensaver. Night dimming still applies.",
        s.keepAwake,
      ) { v -> HomePrefs.update { it.copy(keepAwake = v) } }
    }

    SettingsCard("Status") {
      val ours = current == ScreensaverGuard.COMPONENT
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Permission to set the screensaver: ", fontSize = 14.sp, color = Title)
        Text(if (hasPermission) "granted" else "missing", fontSize = 14.sp, color = if (hasPermission) Good else Bad, fontWeight = FontWeight.SemiBold)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Current screensaver: ", fontSize = 14.sp, color = Title)
        Text(
          if (ours) "this app" else current?.substringBefore('/')?.ifBlank { null } ?: "none",
          fontSize = 14.sp,
          color = if (ours) Good else Muted,
          fontWeight = FontWeight.SemiBold,
        )
      }
      if (!hasPermission) {
        Text("Grant it once from a computer connected to the Portal:", fontSize = 13.sp, color = Muted)
        SelectionContainer {
          Text(ScreensaverGuard.GRANT_COMMAND, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFF60A5FA))
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { ScreensaverGuard.apply(context); refresh++ }) { Text("Apply again") }
        Spacer(Modifier.width(4.dp))
      }
    }
  }
}

/** Muse answers, voice, speaking and the commands Muse may run on this Portal. */
@Composable
fun MuseSettingsPanel() {
  val context = LocalContext.current
  val s by HomePrefs.settings.collectAsState()
  val ttsStatus by com.portal.pebblebridge.muse.Speaker.status.collectAsState()
  val timers by com.portal.pebblebridge.home.Timers.timers.collectAsState()
  var micGranted by remember { mutableStateOf(com.portal.pebblebridge.home.Assistant.hasMicPermission(context)) }
  LaunchedEffect(Unit) {
    while (true) { micGranted = com.portal.pebblebridge.home.Assistant.hasMicPermission(context); delay(2_000) }
  }

  Row(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      SettingsCard("Answers") {
        ToggleRow("Show Muse's answers", "After a ring note, the robot shows what Muse replied", s.showAnswers) { v ->
          HomePrefs.update { it.copy(showAnswers = v) }
        }
        ToggleRow("Auto-play answers", "Show and speak each answer as it arrives, one after another. Off: they wait in History.", s.autoPlay) { v ->
          HomePrefs.update { it.copy(autoPlay = v) }
        }
        ToggleRow("Speak answers aloud", "Reads Muse's answers and messages with the Portal's speaker", s.speakAnswers) { v ->
          HomePrefs.update { it.copy(speakAnswers = v) }
        }
        val (ttsText, ttsColor) = when (ttsStatus) {
          com.portal.pebblebridge.muse.Speaker.Status.READY ->
            (if (com.portal.pebblebridge.muse.Speaker.vietnameseVoice) "Voice engine: ready (Vietnamese too)"
            else "Voice engine: ready. No Vietnamese voice, so Vietnamese answers are shown but not read aloud.") to Good
          com.portal.pebblebridge.muse.Speaker.Status.STARTING -> "Voice engine: starting…" to Muted
          com.portal.pebblebridge.muse.Speaker.Status.NO_ENGINE -> "Voice engine: none installed. Install a text-to-speech app (e.g. RHVoice) on the Portal." to Bad
        }
        Text(ttsText, fontSize = 12.sp, color = ttsColor)
        OutlinedButton(onClick = { com.portal.pebblebridge.muse.Speaker.speak("Beep boop! Hello, I'm your Portal robot.") }) { Text("Test voice") }
      }

      SettingsCard("Talk to Muse") {
        ToggleRow("Hold the robot to talk", "Hold, speak, let go: your voice goes to Muse as a voice note", s.holdToTalk) { v ->
          HomePrefs.update { it.copy(holdToTalk = v) }
        }
        Text(
          if (micGranted) "Microphone: allowed" else "Microphone: not allowed. Restart the app to be asked, or run: adb shell pm grant com.portal.pebblebridge android.permission.RECORD_AUDIO",
          fontSize = 12.sp,
          color = if (micGranted) Good else Bad,
        )
        Text("The Portal's mic switch must be on. Recordings are stored in your Muse chat.", fontSize = 12.sp, color = Muted)
      }
    }

    Column(
      modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      SettingsCard("Muse controls the Portal") {
        ToggleRow("Let Muse run Portal commands", "Muse uses these when you ask, e.g. \"set a 5 minute timer on my Portal\". Changing this reconnects; allow a few minutes.", s.museCommands) { v ->
          HomePrefs.update { it.copy(museCommands = v) }
          // Commands are announced at registration, so reconnect to update Muse.
          com.portal.pebblebridge.muse.MuseLinkClient.activeInstance?.reconnect()
        }
        com.portal.pebblebridge.muse.PortalCommands.all.forEach { c ->
          Text("• ${c.name.removePrefix("portal.")}: ${c.description.substringBefore(". ").removePrefix("Use when asked to ")}",
            fontSize = 12.sp, color = if (s.museCommands) Muted else Color(0xFF52525B))
        }
      }
      SettingsCard("Timers & alarms") {
        if (timers.isEmpty()) Text("None running.", fontSize = 13.sp, color = Muted)
        val now = System.currentTimeMillis()
        timers.forEach { t ->
          Text("${if (t.isAlarm) "Alarm" else "Timer"} ${t.label}: ${com.portal.pebblebridge.home.describeDuration(t.endsAt - now)} left", fontSize = 13.sp, color = Title)
        }
        if (timers.isNotEmpty()) OutlinedButton(onClick = { com.portal.pebblebridge.home.Timers.clear() }) { Text("Cancel all") }
      }
    }
  }
}
