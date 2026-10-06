package com.portal.pebblebridge.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.portal.pebblebridge.R

/** A four-shade palette, darkest to lightest, like the original Game Boy LCD. */
data class PixelTheme(val name: String, val darkest: Color, val dark: Color, val light: Color, val lightest: Color)

val THEMES = listOf(
  PixelTheme("CLASSIC", Color(0xFF0F380F), Color(0xFF306230), Color(0xFF8BAC0F), Color(0xFF9BBC0F)),
  PixelTheme("POCKET", Color(0xFF1A1A1A), Color(0xFF4A4A4A), Color(0xFF9A9A9A), Color(0xFFDCDCD4)),
  PixelTheme("ICE", Color(0xFF0E1F3A), Color(0xFF2A4C7A), Color(0xFF6FA3D8), Color(0xFFCFE8FF)),
  PixelTheme("SUNSET", Color(0xFF2B0F0E), Color(0xFF7A2E1E), Color(0xFFE07A3A), Color(0xFFFFD9A0)),
  PixelTheme("SAKURA", Color(0xFF2D1B2E), Color(0xFF6B3B6E), Color(0xFFD48BB8), Color(0xFFFBE3EF)),
  PixelTheme("VIRTUAL BOY", Color(0xFF120000), Color(0xFF4F0000), Color(0xFFA30000), Color(0xFFFF2A2A)),
  PixelTheme("MATCHA", Color(0xFF1E2A1E), Color(0xFF4F6B45), Color(0xFFA8C08A), Color(0xFFE6F0D2)),
)

fun themeAt(index: Int): PixelTheme = THEMES[Math.floorMod(index, THEMES.size)]

/**
 * The active palette. Everything on the home screen uses only these four shades. Backed by
 * Compose state, so switching themes recomposes and redraws the whole screen.
 */
object GB {
  var theme by mutableStateOf(THEMES[0])
  val Darkest get() = theme.darkest
  val Dark get() = theme.dark
  val Light get() = theme.light
  val Lightest get() = theme.lightest
}

/** Press Start 2P: clock, labels, numbers (ASCII only). */
val PixelFont = FontFamily(Font(R.font.press_start_2p))

/** VT323: note text. Covers Vietnamese diacritics. */
val TerminalFont = FontFamily(Font(R.font.vt323))

/**
 * Draws in "virtual pixels": [unit] screen pixels per art pixel, origin at [origin].
 * Coordinates may be fractional so motion stays smooth while shapes stay blocky.
 */
class PixelPen(private val scope: DrawScope, private val origin: Offset, private val unit: Float) {
  fun rect(x: Float, y: Float, w: Float, h: Float, color: Color) {
    if (w <= 0f || h <= 0f) return
    scope.drawRect(
      color = color,
      topLeft = Offset(origin.x + x * unit, origin.y + y * unit),
      size = Size(w * unit, h * unit),
    )
  }

  /** A box with a 1-pixel outline and clipped corners, the classic 8-bit rounded look. */
  fun box(x: Float, y: Float, w: Float, h: Float, fill: Color, outline: Color = GB.Darkest) {
    rect(x + 1, y, w - 2, 1f, outline)
    rect(x + 1, y + h - 1, w - 2, 1f, outline)
    rect(x, y + 1, 1f, h - 2, outline)
    rect(x + w - 1, y + 1, 1f, h - 2, outline)
    rect(x + 1, y + 1, w - 2, h - 2, fill)
  }

  /** Draws ASCII art: any non-space, non-'.' character is a filled pixel. */
  fun art(x: Float, y: Float, rows: List<String>, color: Color) {
    rows.forEachIndexed { ry, row ->
      row.forEachIndexed { rx, c -> if (c != '.' && c != ' ') rect(x + rx, y + ry, 1f, 1f, color) }
    }
  }
}

fun DrawScope.pixels(origin: Offset, unit: Float, block: PixelPen.() -> Unit) =
  PixelPen(this, origin, unit).block()

val HEART = listOf(
  ".X.X.",
  "XXXXX",
  "XXXXX",
  ".XXX.",
  "..X..",
)

val MUSIC_NOTE = listOf(
  "..XX",
  "..XX",
  "..X.",
  "..X.",
  "XXX.",
  "XXX.",
)
