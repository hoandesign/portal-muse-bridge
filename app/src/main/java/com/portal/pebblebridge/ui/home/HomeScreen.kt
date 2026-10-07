package com.portal.pebblebridge.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.pebblebridge.home.Assistant
import com.portal.pebblebridge.home.DeviceEvent
import com.portal.pebblebridge.home.DeviceEvents
import com.portal.pebblebridge.home.Timers
import com.portal.pebblebridge.muse.Speaker
import com.portal.pebblebridge.home.History
import com.portal.pebblebridge.home.HistoryEntry
import com.portal.pebblebridge.home.Playback
import com.portal.pebblebridge.home.HomePrefs
import com.portal.pebblebridge.home.HomeSettings
import com.portal.pebblebridge.home.MonthGrid
import com.portal.pebblebridge.home.WeatherClient
import com.portal.pebblebridge.home.WeatherKind
import com.portal.pebblebridge.home.WeatherNow
import com.portal.pebblebridge.home.buildMonthGrid
import com.portal.pebblebridge.home.monthName
import com.portal.pebblebridge.home.weatherKind
import com.portal.pebblebridge.home.weatherLabel
import com.portal.pebblebridge.home.weekdayShort
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.model.VoiceNote
import com.portal.pebblebridge.state.BridgeRepository
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val ROBOT_LINES = listOf(
  "BEEP BOOP!", "HI THERE!", "LET'S DANCE!", "*HAPPY BEEPS*", "TAP ME AGAIN!",
  "I LOVE YOUR RING!", "NEW MOVES UNLOCKED!", "BZZT... HELLO!",
)

/** What a bubble looks like right now. */
private data class BubbleView(val key: String, val label: String, val text: String, val footer: String)

/** Weather fetches retry quickly after a failure, then settle to every 20 minutes. */
private const val WEATHER_REFRESH_MS = 20 * 60_000L
private const val WEATHER_RETRY_MS = 2 * 60_000L

/** A note that arrived this recently still pops up when the home screen opens. */
private const val RECENT_NOTE_MS = 2 * 60_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
  val settings by HomePrefs.settings.collectAsState()
  val notes by BridgeRepository.notes.collectAsState()

  // Clock: tick on each second boundary.
  var now by remember { mutableStateOf(Calendar.getInstance()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = Calendar.getInstance()
      delay(1000L - System.currentTimeMillis() % 1000L)
    }
  }

  // Weather.
  var weather by remember { mutableStateOf<WeatherNow?>(null) }
  var weatherFailed by remember { mutableStateOf(false) }
  LaunchedEffect(settings.weatherCity, settings.fahrenheit) {
    val client = WeatherClient()
    while (true) {
      val result = withContext(Dispatchers.IO) { runCatching { client.fetch(settings) } }
      result.onSuccess { weather = it; weatherFailed = false }.onFailure { weatherFailed = true }
      delay(if (result.isSuccess) WEATHER_REFRESH_MS else WEATHER_RETRY_MS)
    }
  }

  // Speech bubble, in priority order: a live voice turn, the playback queue (answers, Muse's
  // messages, timers, one after another), a ring note still waiting for its answer, a robot line.
  val context = androidx.compose.ui.platform.LocalContext.current
  val assistant by Assistant.state.collectAsState()
  val speaking by Speaker.speaking.collectAsState()
  val timers by Timers.timers.collectAsState()
  val playing by Playback.now.collectAsState()
  val history by History.entries.collectAsState()
  var robotLine by remember { mutableStateOf<Pair<String, Long>?>(null) }
  var hiddenNotes by remember { mutableStateOf(setOf<String>()) }
  var typingDone by remember { mutableStateOf(false) }
  var celebrateKey by remember { mutableIntStateOf(0) }
  var showHistory by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    DeviceEvents.events.collect { e ->
      when (e) {
        is DeviceEvent.Ring, DeviceEvent.Celebrate -> celebrateKey++
      }
    }
  }
  // The newest ring note, while it waits for Muse (up to 3 minutes).
  val waitingNote = history.firstOrNull()?.takeIf {
    it.kind == HistoryEntry.Kind.RING && it.id !in hiddenNotes &&
      it.status in setOf(HistoryEntry.Status.SENDING, HistoryEntry.Status.WAITING, HistoryEntry.Status.FAILED) &&
      now.timeInMillis - it.time < 3 * 60_000L
  }
  val view: BubbleView? = when {
    assistant is Assistant.State.Listening -> BubbleView("voice-listen", "LISTENING", "Speak now... let go to send.", "")
    assistant is Assistant.State.Thinking ->
      BubbleView("voice-think", "YOU", (assistant as Assistant.State.Thinking).heard.ifEmpty { "..." }, "MUSE IS THINKING...")
    assistant is Assistant.State.Problem -> BubbleView("voice-problem", "OOPS", (assistant as Assistant.State.Problem).message, "")
    playing != null -> playing!!.let { p ->
      val pageNote = if (p.pages.size > 1) "${p.page + 1}/${p.pages.size}" else ""
      val queued = if (p.queued > 0) "+${p.queued} MORE" else ""
      BubbleView("play-${p.historyId}-${p.page}", p.label, p.text, listOf(pageNote, queued).filter { it.isNotEmpty() }.joinToString("  "))
    }
    waitingNote != null -> BubbleView(
      "note-${waitingNote.id}", "YOUR RING", waitingNote.question,
      when (waitingNote.status) {
        HistoryEntry.Status.SENDING -> "SENDING TO MUSE..."
        HistoryEntry.Status.FAILED -> "COULDN'T SEND"
        else -> if (settings.showAnswers) "SENT TO MUSE · THINKING..." else "SENT TO MUSE"
      },
    )
    robotLine != null -> BubbleView("robot-${robotLine!!.second}", "BOT", robotLine!!.first, "")
    else -> null
  }
  val bubbleText = view?.text
  // Robot lines are brief.
  LaunchedEffect(robotLine, typingDone) {
    if (robotLine != null && typingDone) { delay(2_500L); robotLine = null }
  }
  // A ring note that won't get a shown answer stays only for the usual time.
  LaunchedEffect(waitingNote?.id, waitingNote?.status, typingDone) {
    val n = waitingNote ?: return@LaunchedEffect
    if (!typingDone) return@LaunchedEffect
    if (!settings.showAnswers || n.status == HistoryEntry.Status.FAILED) {
      delay(settings.bubbleSeconds * 1000L)
      hiddenNotes = hiddenNotes + n.id
    }
  }

  // Theme follows settings; tapping empty space cycles it and flashes the name.
  LaunchedEffect(settings.themeIndex) { GB.theme = themeAt(settings.themeIndex) }
  var themeFlashAt by remember { mutableLongStateOf(0L) }
  LaunchedEffect(themeFlashAt) {
    if (themeFlashAt == 0L) return@LaunchedEffect
    delay(1500L)
    themeFlashAt = 0L
  }

  val shift = if (settings.pixelShift) burnInShift(now.get(Calendar.MINUTE)) else Offset.Zero
  val hour = now.get(Calendar.HOUR_OF_DAY)
  val dimmed = settings.nightDim && (hour >= 22 || hour < 6)

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(GB.Darkest)
      .combinedClickableNoRipple(
        onLongClick = onOpenSettings,
        onClick = {
          HomePrefs.update { it.copy(themeIndex = Math.floorMod(it.themeIndex + 1, THEMES.size)) }
          themeFlashAt = System.currentTimeMillis()
        },
      ),
  ) {
    LcdTexture(GB.Dark.copy(alpha = 0.12f))
    Row(
      modifier = Modifier
        .fillMaxSize()
        .offset(shift.x.dp, shift.y.dp)
        .padding(horizontal = 40.dp, vertical = 36.dp),
      horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
      // Left: the stage. Bubble above, robot below; the bubble's tail points at the robot.
      PixelFrame(
        modifier = Modifier.weight(0.46f).fillMaxHeight(),
        fill = GB.Lightest,
        outer = GB.Dark,
        inner = GB.Light,
        border = 6.dp,
      ) {
      LcdTexture(GB.Light.copy(alpha = 0.18f))
      Box(modifier = Modifier.fillMaxSize().padding(26.dp)) {
        PixelRobot(
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.74f),
          dancing = settings.robotDances,
          talking = (bubbleText != null && !typingDone) || speaking,
          onTap = {
            if (view == null || robotLine != null) robotLine = ROBOT_LINES.random() to System.currentTimeMillis()
          },
          listening = assistant is Assistant.State.Listening,
          thinking = assistant is Assistant.State.Thinking,
          celebrateKey = celebrateKey,
          holdToTalk = settings.holdToTalk,
          onHoldStart = { Assistant.startListening(context) },
          onHoldEnd = { Assistant.stopAndSend() },
        )
        androidx.compose.animation.AnimatedVisibility(
          visible = bubbleText != null,
          modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
          enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.85f),
          exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f),
        ) {
          // Keep showing the last bubble while the exit animation runs.
          var last by remember { mutableStateOf<BubbleView?>(null) }
          if (view != null) last = view
          val shown = last ?: return@AnimatedVisibility
          DialogBubble(
            key = shown.key,
            label = shown.label,
            text = shown.text,
            footer = shown.footer,
            onTypingChanged = { typingDone = it },
            onDismiss = {
              when {
                assistant != Assistant.State.Idle -> Assistant.dismiss()
                playing != null -> Playback.skip()
                waitingNote != null -> hiddenNotes = hiddenNotes + waitingNote.id
                else -> robotLine = null
              }
            },
          )
        }
      }
      }

      // Right: time first (largest), then date + weather together, then the month.
      Column(
        modifier = Modifier.weight(0.54f).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterVertically),
      ) {
        Column(
          modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = onOpenSettings,
          ),
        ) {
          PixelClock(now, settings)
          Spacer(Modifier.height(20.dp))
          DateWeatherRow(now, weather, weatherFailed)
          if (timers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            TimersRow(timers, now.timeInMillis)
          }
        }
        MonthCalendar(buildMonthGrid(now, settings.weekStartsMonday))
      }
    }
    Row(
      modifier = Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("TAP: THEME   HOLD: SETTINGS", fontFamily = PixelFont, fontSize = 9.sp, color = GB.Dark)
      Spacer(Modifier.width(20.dp))
      val unseen = History.unseen(history)
      PixelFrame(
        modifier = Modifier.combinedClickableNoRipple { showHistory = true },
        fill = if (unseen > 0) GB.Light else Color.Transparent,
        outer = GB.Dark,
        inner = GB.Dark,
        border = 3.dp,
      ) {
        Text(
          if (unseen > 0) "HISTORY · $unseen NEW" else "HISTORY",
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
          fontFamily = PixelFont,
          fontSize = 10.sp,
          color = if (unseen > 0) GB.Darkest else GB.Light,
        )
      }
    }
    if (showHistory) HistoryScreen(history, onClose = { showHistory = false })
    if (themeFlashAt != 0L) {
      PixelFrame(
        modifier = Modifier.align(Alignment.Center),
        fill = GB.Lightest,
        outer = GB.Darkest,
        inner = GB.Dark,
        border = 5.dp,
      ) {
        Text(
          "THEME: ${GB.theme.name}",
          modifier = Modifier.padding(horizontal = 36.dp, vertical = 26.dp),
          fontFamily = PixelFont,
          fontSize = 18.sp,
          color = GB.Darkest,
        )
      }
    }
    if (dimmed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
  }
}

/** Moves the layout by a few dp each minute, cycling, so static pixels don't burn in. */
private fun burnInShift(minute: Int): Offset {
  val xs = listOf(0f, 3f, 6f, 3f, 0f, -3f, -6f, -3f)
  val ys = listOf(0f, -2f, 0f, 2f, 4f, 2f, 0f, -2f)
  return Offset(xs[minute % xs.size], ys[(minute / 2) % ys.size])
}

/** Faint horizontal scanlines, like an old handheld LCD. */
@Composable
private fun LcdTexture(line: Color) {
  Canvas(Modifier.fillMaxSize()) {
    val step = 4f
    var y = 0f
    while (y < size.height) {
      drawRect(line, topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, 1f))
      y += step
    }
  }
}

@Composable
private fun PixelClock(now: Calendar, settings: HomeSettings) {
  val h24 = now.get(Calendar.HOUR_OF_DAY)
  val hour = if (settings.use24h) h24 else (h24 % 12).let { if (it == 0) 12 else it }
  val minute = now.get(Calendar.MINUTE)
  val second = now.get(Calendar.SECOND)
  val colonOn = second % 2 == 0
  Row(verticalAlignment = Alignment.Bottom) {
    ClockText(if (settings.use24h) "%02d".format(hour) else "%d".format(hour), 96.sp)
    ClockText(":", 96.sp, if (colonOn) GB.Lightest else GB.Dark)
    ClockText("%02d".format(minute), 96.sp)
    Column(modifier = Modifier.padding(start = 14.dp, bottom = 6.dp)) {
      if (!settings.use24h) ClockText(if (h24 < 12) "AM" else "PM", 22.sp, GB.Light)
      if (settings.showSeconds) {
        Spacer(Modifier.height(8.dp))
        ClockText("%02d".format(second), 22.sp, GB.Light)
      }
    }
  }
}

@Composable
private fun ClockText(text: String, size: TextUnit, color: Color = GB.Lightest) {
  Text(text, fontFamily = PixelFont, fontSize = size, color = color, maxLines = 1)
}

private val DAY_NAMES = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
private val MONTH_SHORT = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

@Composable
private fun DateWeatherRow(now: Calendar, weather: WeatherNow?, failed: Boolean) {
  val date = "${DAY_NAMES[now.get(Calendar.DAY_OF_WEEK) - 1]} %02d ${MONTH_SHORT[now.get(Calendar.MONTH)]}"
    .format(now.get(Calendar.DAY_OF_MONTH))
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(date, fontFamily = PixelFont, fontSize = 22.sp, color = GB.Light)
    Spacer(Modifier.weight(1f))
    when {
      weather != null -> {
        val kind = weatherKind(weather.code, weather.isDay)
        WeatherIcon(kind, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column {
          Text("${weather.temp}°${weather.unit}", fontFamily = PixelFont, fontSize = 22.sp, color = GB.Lightest)
          Spacer(Modifier.height(6.dp))
          Text(
            (weatherLabel(kind) + if (weather.place.isNotBlank()) " · ${weather.place.uppercase()}" else ""),
            fontFamily = TerminalFont, fontSize = 20.sp, color = GB.Light, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      failed -> Text("NO WEATHER", fontFamily = PixelFont, fontSize = 12.sp, color = GB.Dark)
      else -> Text("...", fontFamily = PixelFont, fontSize = 16.sp, color = GB.Dark)
    }
  }
}

/** 12×12 pixel weather glyphs. */
@Composable
private fun WeatherIcon(kind: WeatherKind, modifier: Modifier) {
  val sun = listOf(
    ".....X......", "..X..X..X...", "...XXXXX....", "..XXXXXXX...", "XXXXXXXXXXX.",
    "..XXXXXXX...", "...XXXXX....", "..X..X..X...", ".....X......",
  )
  val moon = listOf(
    "....XXXX....", "..XXXX......", ".XXXX.......", ".XXXX.......", "XXXX........",
    "XXXX........", ".XXXX.....X.", ".XXXXX..XX..", "..XXXXXXX...", "....XXXX....",
  )
  val cloud = listOf(
    "....XXX.....", "..XXXXXXX...", ".XXXXXXXXXX.", "XXXXXXXXXXXX", "XXXXXXXXXXXX", ".XXXXXXXXXX.",
  )
  Canvas(modifier) {
    val unit = size.width / 12f
    pixels(Offset.Zero, unit) {
      when (kind) {
        WeatherKind.SUN -> art(0f, 1f, sun, GB.Lightest)
        WeatherKind.MOON -> art(1f, 1f, moon, GB.Lightest)
        WeatherKind.PARTLY -> { art(3f, 0f, sun.take(6), GB.Light); art(0f, 5f, cloud, GB.Lightest) }
        WeatherKind.CLOUD -> art(0f, 3f, cloud, GB.Lightest)
        WeatherKind.FOG -> { art(0f, 1f, cloud, GB.Light); rect(0f, 9f, 12f, 1f, GB.Lightest); rect(2f, 11f, 9f, 1f, GB.Lightest) }
        WeatherKind.RAIN -> { art(0f, 0f, cloud, GB.Lightest); listOf(2f, 6f, 10f).forEach { rect(it, 8f, 1f, 2f, GB.Light); rect(it - 1f, 10f, 1f, 2f, GB.Light) } }
        WeatherKind.SNOW -> { art(0f, 0f, cloud, GB.Lightest); listOf(2f, 6f, 10f).forEach { rect(it, 9f, 1f, 1f, GB.Light); rect(it - 1f, 10f, 3f, 1f, GB.Light); rect(it, 11f, 1f, 1f, GB.Light) } }
        WeatherKind.THUNDER -> { art(0f, 0f, cloud, GB.Lightest); art(4f, 6f, listOf("..XX", ".XX.", "XXXX", "..X.", ".X.."), GB.Light) }
      }
    }
  }
}

@Composable
private fun MonthCalendar(grid: MonthGrid) {
  PixelFrame(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(monthName(grid.month), fontFamily = PixelFont, fontSize = 16.sp, color = GB.Lightest)
        Spacer(Modifier.weight(1f))
        Text(grid.year.toString(), fontFamily = PixelFont, fontSize = 16.sp, color = GB.Light)
      }
      Spacer(Modifier.height(14.dp))
      Row {
        grid.columnDays.forEach { day ->
          val weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
          Text(
            weekdayShort(day),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontFamily = PixelFont,
            fontSize = 11.sp,
            color = if (weekend) GB.Light else GB.Lightest,
          )
        }
      }
      Spacer(Modifier.height(8.dp))
      grid.weeks.forEach { week ->
        Row(modifier = Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
          week.forEachIndexed { i, day ->
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
              if (day != null) {
                val isToday = day == grid.today
                val weekend = grid.columnDays[i].let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
                val past = grid.today != null && day < grid.today
                if (isToday) Box(Modifier.size(width = 40.dp, height = 28.dp).background(GB.Lightest))
                Text(
                  day.toString(),
                  fontFamily = PixelFont,
                  fontSize = 13.sp,
                  color = when {
                    isToday -> GB.Darkest
                    past -> GB.Dark
                    weekend -> GB.Light
                    else -> GB.Lightest
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

/** Game Boy style window: dark outer frame, light inner rule, clipped corners. */
@Composable
private fun PixelFrame(
  modifier: Modifier = Modifier,
  fill: Color = Color.Transparent,
  outer: Color = GB.Dark,
  inner: Color = GB.Dark,
  border: Dp = 4.dp,
  content: @Composable () -> Unit,
) {
  Box(modifier) {
    Canvas(Modifier.matchParentSize()) {
      val b = border.toPx()
      val w = size.width
      val h = size.height
      if (fill != Color.Transparent) drawRect(fill, Offset(b, b), androidx.compose.ui.geometry.Size(w - 2 * b, h - 2 * b))
      // Outer frame without the four corner blocks.
      drawRect(outer, Offset(b, 0f), androidx.compose.ui.geometry.Size(w - 2 * b, b))
      drawRect(outer, Offset(b, h - b), androidx.compose.ui.geometry.Size(w - 2 * b, b))
      drawRect(outer, Offset(0f, b), androidx.compose.ui.geometry.Size(b, h - 2 * b))
      drawRect(outer, Offset(w - b, b), androidx.compose.ui.geometry.Size(b, h - 2 * b))
      // Inner rule.
      val i = b * 2
      val t = b / 2
      drawRect(inner, Offset(i, i), androidx.compose.ui.geometry.Size(w - 2 * i, t))
      drawRect(inner, Offset(i, h - i - t), androidx.compose.ui.geometry.Size(w - 2 * i, t))
      drawRect(inner, Offset(i, i), androidx.compose.ui.geometry.Size(t, h - 2 * i))
      drawRect(inner, Offset(w - i - t, i), androidx.compose.ui.geometry.Size(t, h - 2 * i))
    }
    content()
  }
}

/**
 * A Game Boy dialog box: light window, dark double frame, text typed out letter by letter,
 * a blinking ▼ when done, and a tail pointing down at the robot. Tap to close.
 */
@Composable
private fun DialogBubble(
  key: String,
  label: String,
  text: String,
  footer: String,
  onTypingChanged: (Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  var shown by remember(key) { mutableIntStateOf(0) }
  var blink by remember { mutableLongStateOf(0L) }
  LaunchedEffect(key, text) {
    onTypingChanged(false)
    while (shown < text.length) {
      shown++
      delay(if (text[shown - 1] in ".,!?") 160L else 35L)
    }
    onTypingChanged(true)
    while (true) { delay(450L); blink++ }
  }
  val done = shown >= text.length

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.combinedClickableNoRipple(onClick = onDismiss),
  ) {
    PixelFrame(
      modifier = Modifier.fillMaxWidth(),
      fill = GB.Lightest,
      outer = GB.Darkest,
      inner = GB.Dark,
      border = 5.dp,
    ) {
      Column(modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 18.dp)) {
        Text(label, fontFamily = PixelFont, fontSize = 11.sp, color = GB.Dark)
        Spacer(Modifier.height(8.dp))
        Text(
          text.take(shown),
          fontFamily = TerminalFont,
          fontSize = 34.sp,
          lineHeight = 34.sp,
          color = GB.Darkest,
          maxLines = 6,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(footer, fontFamily = PixelFont, fontSize = 9.sp, color = GB.Dark)
          Spacer(Modifier.weight(1f))
          // ▼ "more" arrow, blinking once typing finishes.
          Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
            if (done && blink % 2 == 0L) {
              pixels(Offset.Zero, size.width / 6f) {
                art(0f, 0f, listOf("XXXXXX", ".XXXX.", "..XX."), GB.Darkest)
              }
            }
          }
        }
      }
    }
    // Tail: stepped triangle pointing down at the robot.
    Canvas(Modifier.size(width = 30.dp, height = 18.dp)) {
      pixels(Offset.Zero, size.width / 10f) {
        art(0f, -1f, listOf("XXXXXXXXXX", ".XXXXXXXX.", "..XXXXXX..", "...XXXX...", "....XX...."), GB.Darkest)
        art(0f, -1f, listOf("..........", "..XXXXXX..", "...XXXX...", "....XX....", ".........."), GB.Lightest)
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableNoRipple(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
  this.combinedClickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onLongClick = onLongClick,
    onClick = onClick,
  )

/** The next timer or alarm counting down, grouped under the date like the weather. */
@Composable
private fun TimersRow(timers: List<com.portal.pebblebridge.home.PortalTimer>, nowMs: Long) {
  val next = timers.first()
  val left = ((next.endsAt - nowMs) / 1000).coerceAtLeast(0)
  val clock = if (left >= 3600) "%d:%02d:%02d".format(left / 3600, left / 60 % 60, left % 60) else "%02d:%02d".format(left / 60, left % 60)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(if (next.isAlarm) "ALARM" else "TIMER", fontFamily = PixelFont, fontSize = 12.sp, color = GB.Dark)
    Spacer(Modifier.width(12.dp))
    Text(next.label, fontFamily = TerminalFont, fontSize = 24.sp, color = GB.Light, maxLines = 1)
    Spacer(Modifier.width(12.dp))
    Text(clock, fontFamily = PixelFont, fontSize = 16.sp, color = GB.Lightest)
    if (timers.size > 1) {
      Spacer(Modifier.width(12.dp))
      Text("+${timers.size - 1}", fontFamily = PixelFont, fontSize = 12.sp, color = GB.Dark)
    }
  }
}

/** Every note, question and answer, newest first. Tap one to show and hear it again. */
@Composable
private fun HistoryScreen(entries: List<HistoryEntry>, onClose: () -> Unit) {
  var confirmClear by remember { mutableStateOf(false) }
  androidx.activity.compose.BackHandler(onBack = onClose)
  Box(
    Modifier
      .fillMaxSize()
      .background(GB.Darkest)
      .combinedClickableNoRipple {}, // swallow taps so they don't change the theme behind
  ) {
    LcdTexture(GB.Dark.copy(alpha = 0.12f))
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        PixelButton("◀ BACK", onClose)
        Spacer(Modifier.width(24.dp))
        Text("HISTORY", fontFamily = PixelFont, fontSize = 22.sp, color = GB.Lightest)
        Spacer(Modifier.weight(1f))
        Text("TAP AN ENTRY TO PLAY IT", fontFamily = PixelFont, fontSize = 9.sp, color = GB.Dark)
        Spacer(Modifier.width(20.dp))
        if (entries.isNotEmpty()) PixelButton(if (confirmClear) "SURE? CLEAR ALL" else "CLEAR") {
          if (confirmClear) { History.clear(); confirmClear = false } else confirmClear = true
        }
      }
      Spacer(Modifier.height(18.dp))
      if (entries.isEmpty()) {
        Text("NOTHING YET. SPEAK INTO YOUR RING OR HOLD THE ROBOT.", fontFamily = PixelFont, fontSize = 12.sp, color = GB.Light)
      }
      androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries.size, key = { entries[it].id }) { i -> HistoryRow(entries[i]) }
      }
    }
  }
}

private val TIME_FORMAT = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.ENGLISH)

@Composable
private fun HistoryRow(e: HistoryEntry) {
  val canPlay = e.answer.isNotBlank()
  PixelFrame(
    modifier = Modifier.fillMaxWidth().combinedClickableNoRipple { if (canPlay) Playback.playNow(e.id) },
    fill = if (!e.played && canPlay) GB.Dark else Color.Transparent,
    outer = GB.Dark,
    inner = GB.Dark,
    border = 3.dp,
  ) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        val kind = when (e.kind) {
          HistoryEntry.Kind.RING -> "RING"
          HistoryEntry.Kind.VOICE -> "VOICE"
          HistoryEntry.Kind.MESSAGE -> "MUSE MESSAGE"
          HistoryEntry.Kind.TIMER -> e.question.ifEmpty { "TIMER" }
        }
        Text(kind, fontFamily = PixelFont, fontSize = 10.sp, color = GB.Light)
        Spacer(Modifier.width(14.dp))
        Text(TIME_FORMAT.format(java.util.Date(e.time)).uppercase(), fontFamily = PixelFont, fontSize = 10.sp,
          color = if (!e.played && canPlay) GB.Light else GB.Dark)
        Spacer(Modifier.weight(1f))
        val status = when (e.status) {
          HistoryEntry.Status.SENDING -> "SENDING..."
          HistoryEntry.Status.WAITING -> "WAITING FOR MUSE..."
          HistoryEntry.Status.MERGED -> "ANSWERED WITH THE NEXT ONE"
          HistoryEntry.Status.FAILED -> "COULDN'T SEND"
          HistoryEntry.Status.NO_ANSWER -> "NO ANSWER"
          HistoryEntry.Status.ANSWERED -> if (!e.played) "NEW ▶" else "▶ PLAY"
        }
        Text(status, fontFamily = PixelFont, fontSize = 10.sp, color = if (!e.played && canPlay) GB.Lightest else GB.Light)
      }
      if (e.question.isNotBlank() && e.kind != HistoryEntry.Kind.TIMER && e.kind != HistoryEntry.Kind.MESSAGE) {
        Spacer(Modifier.height(6.dp))
        Text("> " + e.question, fontFamily = TerminalFont, fontSize = 24.sp, color = GB.Light, maxLines = 3, overflow = TextOverflow.Ellipsis)
      }
      if (e.answer.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(e.answer, fontFamily = TerminalFont, fontSize = 26.sp, color = GB.Lightest, maxLines = 4, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun PixelButton(label: String, onClick: () -> Unit) {
  PixelFrame(
    modifier = Modifier.combinedClickableNoRipple(onClick = onClick),
    fill = GB.Light,
    outer = GB.Lightest,
    inner = GB.Dark,
    border = 3.dp,
  ) {
    Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontFamily = PixelFont, fontSize = 11.sp, color = GB.Darkest)
  }
}
