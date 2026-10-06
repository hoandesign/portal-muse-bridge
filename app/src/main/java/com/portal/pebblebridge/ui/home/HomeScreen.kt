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

/** What the speech bubble is showing: a Pebble note, or the robot's own line after a tap. */
private sealed class Bubble {
  data class Note(val id: String) : Bubble()
  data class Robot(val text: String, val shownAt: Long) : Bubble()
}

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

  // Speech bubble: newest Pebble note wins; robot lines fill in after taps.
  var bubble by remember { mutableStateOf<Bubble?>(null) }
  var typingDone by remember { mutableStateOf(false) }
  var lastSeenNoteId by remember { mutableStateOf<String?>(null) }
  val newest = notes.firstOrNull()
  LaunchedEffect(newest?.id) {
    if (newest == null || newest.id == lastSeenNoteId) return@LaunchedEffect
    val firstLook = lastSeenNoteId == null
    lastSeenNoteId = newest.id
    val fresh = System.currentTimeMillis() - newest.timestampEpochMs < RECENT_NOTE_MS
    if (!firstLook || fresh) bubble = Bubble.Note(newest.id)
  }
  val bubbleNote: VoiceNote? = (bubble as? Bubble.Note)?.let { b -> notes.firstOrNull { it.id == b.id } }
  val bubbleText = when (val b = bubble) {
    is Bubble.Note -> bubbleNote?.text
    is Bubble.Robot -> b.text
    null -> null
  }
  // Auto-dismiss: notes linger for the configured time after typing; robot lines are brief.
  LaunchedEffect(bubble, typingDone) {
    val b = bubble ?: return@LaunchedEffect
    if (!typingDone) return@LaunchedEffect
    delay(if (b is Bubble.Note) settings.bubbleSeconds * 1000L else 2_500L)
    bubble = null
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
          talking = bubbleText != null && !typingDone,
          onTap = {
            if (bubble !is Bubble.Note) bubble = Bubble.Robot(ROBOT_LINES.random(), System.currentTimeMillis())
          },
        )
        androidx.compose.animation.AnimatedVisibility(
          visible = bubbleText != null,
          modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
          enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.85f),
          exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f),
        ) {
          // Keep showing the last bubble while the exit animation runs.
          var last by remember { mutableStateOf<Pair<Bubble, String>?>(null) }
          val current = bubble
          if (current != null && bubbleText != null) last = current to bubbleText
          val (shownBubble, shownText) = last ?: return@AnimatedVisibility
          DialogBubble(
            key = when (shownBubble) {
              is Bubble.Note -> shownBubble.id
              is Bubble.Robot -> shownBubble.shownAt.toString()
            },
            label = if (shownBubble is Bubble.Robot) "BOT" else "YOUR RING",
            text = shownText,
            status = bubbleNote?.status,
            onTypingChanged = { typingDone = it },
            onDismiss = { bubble = null },
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
        }
        MonthCalendar(buildMonthGrid(now, settings.weekStartsMonday))
      }
    }
    Text(
      "TAP: THEME   HOLD: SETTINGS",
      fontFamily = PixelFont,
      fontSize = 9.sp,
      color = GB.Dark,
      modifier = Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 12.dp),
    )
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
  status: NoteStatus?,
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
          val (statusText, statusColor) = when (status) {
            NoteStatus.PENDING -> "SENDING TO MUSE..." to GB.Dark
            NoteStatus.DELIVERED -> "SENT TO MUSE" to GB.Darkest
            NoteStatus.FAILED -> "COULDN'T SEND" to GB.Darkest
            null -> "" to GB.Dark
          }
          Text(statusText, fontFamily = PixelFont, fontSize = 9.sp, color = statusColor)
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
