package com.portal.pebblebridge.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

private enum class Arm { DOWN, OUT, UP }

/** Each routine is a list of (left, right) arm poses, one per beat. */
private val ROUTINES = listOf(
  listOf(Arm.UP to Arm.DOWN, Arm.DOWN to Arm.UP, Arm.UP to Arm.DOWN, Arm.DOWN to Arm.UP,
    Arm.OUT to Arm.OUT, Arm.UP to Arm.UP, Arm.OUT to Arm.OUT, Arm.DOWN to Arm.DOWN),
  listOf(Arm.OUT to Arm.DOWN, Arm.DOWN to Arm.OUT, Arm.OUT to Arm.DOWN, Arm.DOWN to Arm.OUT,
    Arm.UP to Arm.OUT, Arm.OUT to Arm.UP, Arm.UP to Arm.UP, Arm.DOWN to Arm.DOWN),
  listOf(Arm.UP to Arm.UP, Arm.OUT to Arm.OUT, Arm.UP to Arm.UP, Arm.DOWN to Arm.DOWN,
    Arm.UP to Arm.OUT, Arm.OUT to Arm.UP, Arm.UP to Arm.OUT, Arm.OUT to Arm.UP),
)

private const val BEAT_MS = 520f
private const val JUMP_MS = 650f
private const val EXCITED_MS = 1600L

/** Art-space size: robot plus room for arms, jump, hearts and notes. */
private const val STAGE_W = 48f
private const val STAGE_H = 58f
private const val HOLD_MS = 350L

/**
 * An 8-bit robot that dances, blinks and smiles. Tapping makes it jump with hearts and switch
 * dance routines; [talking] animates its mouth while a speech bubble types out.
 */
@Composable
fun PixelRobot(
  modifier: Modifier = Modifier,
  dancing: Boolean,
  talking: Boolean,
  onTap: () -> Unit,
  /** Recording the user's voice: ear cupped, eyes wide, sound waves. */
  listening: Boolean = false,
  /** Waiting for Muse: eyes up, slow bob. */
  thinking: Boolean = false,
  /** Bump to make the robot jump and cheer (e.g. Muse's portal.celebrate). */
  celebrateKey: Int = 0,
  /** When on, holding the robot calls [onHoldStart] and releasing it [onHoldEnd]. */
  holdToTalk: Boolean = false,
  onHoldStart: () -> Unit = {},
  onHoldEnd: () -> Unit = {},
) {
  var now by remember { mutableLongStateOf(0L) }
  var tappedAt by remember { mutableLongStateOf(-100_000L) }
  var routine by remember { mutableStateOf(0) }

  LaunchedEffect(celebrateKey) {
    if (celebrateKey > 0) {
      tappedAt = now
      routine = (routine + 1) % ROUTINES.size
    }
  }

  LaunchedEffect(Unit) {
    val start = withFrameMillis { it }
    while (true) {
      withFrameMillis { frame -> now = frame - start }
    }
  }

  Canvas(
    modifier = modifier.pointerInput(holdToTalk) {
      var held = false
      detectTapGestures(
        onPress = {
          held = false
          if (holdToTalk) {
            // A short tap is a tap; holding for HOLD_MS starts talking until release.
            kotlinx.coroutines.coroutineScope {
              val job = this.launch {
                kotlinx.coroutines.delay(HOLD_MS)
                held = true
                onHoldStart()
              }
              tryAwaitRelease()
              job.cancel()
            }
            if (held) onHoldEnd()
          }
        },
        onTap = {
          if (!held) {
            tappedAt = now
            routine = (routine + 1) % ROUTINES.size
            onTap()
          }
        },
      )
    },
  ) {
    val unit = floor(min(size.width / STAGE_W, size.height / STAGE_H)).coerceAtLeast(1f)
    val origin = Offset((size.width - STAGE_W * unit) / 2f, size.height - STAGE_H * unit)
    // Blink once per ~3.6 s cycle, at a varying moment so it doesn't look mechanical.
    val cycle = now / 3600L
    val blinkStart = 600L + (cycle * 7919L) % 2400L
    val blinking = (now % 3600L) in blinkStart..(blinkStart + 140L)
    val sinceTap = (now - tappedAt).toFloat()
    val excited = sinceTap in 0f..EXCITED_MS.toFloat()

    // Motion. While talking it sways gently and waves instead of dancing.
    val phase = now / BEAT_MS
    val dance = dancing && !talking && !listening && !thinking
    val bounce = if (dance) abs(sin(PI * phase)).toFloat() * 2.5f else (sin(PI * now / 900.0).toFloat() + 1f) * 0.4f
    val sway = if (dance) sin(PI * phase / 2).toFloat() * 2f else 0f
    val headLag = if (dance) sin(PI * (phase - 0.3f) / 2).toFloat() * 0.8f else 0f
    val jump = if (sinceTap in 0f..JUMP_MS) {
      val p = sinceTap / JUMP_MS
      16f * 4f * p * (1f - p)
    } else 0f
    val step = floor(phase).toInt()

    val (leftArm, rightArm) = when {
      listening -> Arm.UP to Arm.DOWN
      thinking -> Arm.DOWN to Arm.OUT
      talking -> Arm.DOWN to (if ((now / 300) % 2 == 0L) Arm.UP else Arm.OUT)
      excited -> Arm.UP to Arm.UP
      dance -> ROUTINES[routine][step % ROUTINES[routine].size]
      else -> Arm.DOWN to Arm.DOWN
    }
    val leftLift = if (dance && step % 2 == 0) abs(sin(PI * phase)).toFloat() * 2f else 0f
    val rightLift = if (dance && step % 2 == 1) abs(sin(PI * phase)).toFloat() * 2f else 0f

    pixels(origin, unit) {
      // Robot's own 30-wide box sits centred on the stage, feet on the floor line.
      val rx = 9f + sway
      val floorY = STAGE_H - 4f
      val up = bounce + jump
      val top = floorY - 44f - up // top of antenna

      // Shadow shrinks as the robot rises.
      val shadowW = (24f - up * 0.8f).coerceAtLeast(10f)
      rect(9f + 15f - shadowW / 2f, floorY + 1f, shadowW, 2f, GB.Dark)

      // Legs stretch to keep the feet planted while the body bounces.
      // Legs stretch with the dance bounce (feet planted) but leave the floor on a jump.
      drawLeg(rx + 9f, top + 36f, floorY - jump - leftLift, GB.Light)
      drawLeg(rx + 17f, top + 36f, floorY - jump - rightLift, GB.Light)

      // Arms behind the body.
      drawArm(rx, top, leftArm, mirrored = false)
      drawArm(rx, top, rightArm, mirrored = true)

      // Body with a chest screen and a beating heart.
      box(rx + 6f, top + 23f, 18f, 13f, GB.Light)
      rect(rx + 7f, top + 24f, 16f, 1f, GB.Lightest)
      rect(rx + 7f, top + 34f, 16f, 1f, GB.Dark)
      box(rx + 10f, top + 26f, 10f, 7f, GB.Darkest, GB.Darkest)
      val heartOn = (now / (BEAT_MS / 2).toLong()) % 2 == 0L
      art(rx + 12.5f, top + 27f, HEART.take(4), if (heartOn) GB.Lightest else GB.Light)

      // Neck.
      rect(rx + 13f, top + 21f, 4f, 2f, GB.Dark)

      // Head (lags slightly behind the body for a looser dance).
      val hx = rx + headLag
      drawHead(hx, top, blinking, excited, talking, now, mood = if (listening) 1 else if (thinking) 2 else 0)
      if (listening) {
        // Sound waves arriving at the cupped ear.
        val w = ((now / 200) % 3).toInt()
        for (i in 0..w) art(hx - 4f - i * 3f, top + 9f + i, List(4 - i) { "X" }, if (i == w) GB.Dark else GB.Darkest)
      }
      if (thinking) {
        // "..." dots popping in turn above the head.
        val n = ((now / 400) % 4).toInt()
        for (i in 0 until n) rect(hx + 26f + i * 3f, top + 2f - i, 2f, 2f, GB.Darkest)
      }

      // Antenna with a blinking tip.
      rect(hx + 14f, top + 3f, 2f, 3f, GB.Darkest)
      val tipOn = if (dance) step % 2 == 0 else (now / 700) % 2 == 0L
      box(hx + 13f, top, 4f, 3f, if (tipOn) GB.Lightest else GB.Light)

      // Hearts float up after a tap.
      if (excited) {
        val p = sinceTap / EXCITED_MS
        listOf(-10f, 0f, 10f).forEachIndexed { i, dx ->
          val rise = p * 18f + i * 2f
          val color = if (p < 0.6f) GB.Darkest else GB.Dark
          art(hx + 12.5f + dx, top - 4f - rise, HEART, color)
        }
      }

      // Music notes drift up beside the robot while dancing.
      if (dance) {
        for (i in 0 until 2) {
          val t = ((now + i * 1400L) % 2800L) / 2800f
          val side = if (i == 0) 1f else 40f
          val y = top + 20f - t * 22f
          art(side + sin(PI * t * 2).toFloat() * 1.5f, y, MUSIC_NOTE, if (t < 0.65f) GB.Dark else GB.Light)
        }
      }
    }
  }
}

private fun PixelPen.drawLeg(x: Float, hipY: Float, footY: Float, color: androidx.compose.ui.graphics.Color) {
  val legTop = hipY
  val footTop = footY - 3f
  rect(x, legTop, 4f, footTop - legTop + 1f, GB.Darkest)
  rect(x + 1f, legTop, 2f, footTop - legTop, color)
  box(x - 2f, footTop, 7f, 3f, GB.Dark)
}

private fun PixelPen.drawArm(rx: Float, top: Float, pose: Arm, mirrored: Boolean) {
  // Positions are for the left arm; mirror across the robot's centre (x = 15) for the right.
  fun m(x: Float, w: Float) = if (mirrored) rx + 30f - (x - rx) - w else x
  when (pose) {
    Arm.DOWN -> {
      box(m(rx + 2f, 4f), top + 24f, 4f, 9f, GB.Light)
      box(m(rx + 1f, 6f), top + 32f, 6f, 4f, GB.Lightest)
    }
    Arm.OUT -> {
      box(m(rx - 3f, 10f), top + 24f, 10f, 4f, GB.Light)
      box(m(rx - 6f, 4f), top + 23f, 4f, 6f, GB.Lightest)
    }
    Arm.UP -> {
      box(m(rx + 2f, 4f), top + 15f, 4f, 11f, GB.Light)
      box(m(rx + 1f, 6f), top + 12f, 6f, 4f, GB.Lightest)
    }
  }
}

private fun PixelPen.drawHead(hx: Float, top: Float, blinking: Boolean, excited: Boolean, talking: Boolean, now: Long, mood: Int = 0) {
  val y = top + 6f
  // Ears.
  box(hx + 2f, y + 5f, 3f, 6f, GB.Dark)
  box(hx + 25f, y + 5f, 3f, 6f, GB.Dark)
  // Head shell with highlight and shade.
  box(hx + 4f, y, 22f, 15f, GB.Light)
  rect(hx + 5f, y + 1f, 20f, 1f, GB.Lightest)
  rect(hx + 5f, y + 1f, 1f, 12f, GB.Lightest)
  rect(hx + 5f, y + 13f, 20f, 1f, GB.Dark)
  // Face screen.
  box(hx + 7f, y + 3f, 16f, 9f, GB.Darkest, GB.Darkest)

  val eyeY = y + 5f
  when {
    mood == 1 -> {
      // Wide, attentive eyes.
      rect(hx + 10f, eyeY - 1f, 2f, 4f, GB.Lightest)
      rect(hx + 18f, eyeY - 1f, 2f, 4f, GB.Lightest)
    }
    mood == 2 -> {
      // Looking up and to the side, thinking.
      rect(hx + 11f, eyeY - 1f, 2f, 2f, GB.Lightest)
      rect(hx + 19f, eyeY - 1f, 2f, 2f, GB.Lightest)
    }
    excited -> {
      // Happy ^ ^ eyes.
      art(hx + 9f, eyeY, listOf(".XX.", "X..X"), GB.Lightest)
      art(hx + 17f, eyeY, listOf(".XX.", "X..X"), GB.Lightest)
    }
    blinking -> {
      rect(hx + 10f, eyeY + 2f, 2f, 1f, GB.Lightest)
      rect(hx + 18f, eyeY + 2f, 2f, 1f, GB.Lightest)
    }
    else -> {
      rect(hx + 10f, eyeY, 2f, 3f, GB.Lightest)
      rect(hx + 18f, eyeY, 2f, 3f, GB.Lightest)
    }
  }
  // Cheeks.
  rect(hx + 8f, y + 9f, 2f, 1f, GB.Dark)
  rect(hx + 20f, y + 9f, 2f, 1f, GB.Dark)

  val mouthY = y + 9f
  when {
    mood == 1 -> box(hx + 14f, mouthY, 3f, 3f, GB.Darkest, GB.Lightest) // "o"
    mood == 2 -> rect(hx + 14f, mouthY + 1f, 4f, 1f, GB.Lightest) // "hmm"
    talking && (now / 140) % 2 == 0L -> box(hx + 13f, mouthY, 5f, 3f, GB.Light, GB.Lightest)
    excited -> {
      rect(hx + 12f, mouthY, 7f, 1f, GB.Lightest)
      rect(hx + 13f, mouthY + 1f, 5f, 1f, GB.Lightest)
    }
    else -> {
      // Smile.
      rect(hx + 12f, mouthY, 1f, 1f, GB.Lightest)
      rect(hx + 13f, mouthY + 1f, 5f, 1f, GB.Lightest)
      rect(hx + 18f, mouthY, 1f, 1f, GB.Lightest)
    }
  }
}
