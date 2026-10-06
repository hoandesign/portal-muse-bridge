package com.portal.pebblebridge.home

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings for the pixel home screen and screensaver. Kept apart from the bridge's tokens. */
data class HomeSettings(
  /** Blank means "detect from IP address". */
  val weatherCity: String = "",
  val fahrenheit: Boolean = false,
  val use24h: Boolean = true,
  val showSeconds: Boolean = false,
  val weekStartsMonday: Boolean = true,
  val robotDances: Boolean = true,
  /** How long a Pebble note bubble stays after it finishes typing. */
  val bubbleSeconds: Int = 60,
  /** Dim the screen between 22:00 and 06:00. */
  val nightDim: Boolean = true,
  /** Nudge the layout a few pixels every minute to avoid burn-in. */
  val pixelShift: Boolean = true,
  /** Register this app as the Portal's screensaver. */
  val screensaverEnabled: Boolean = true,
  /** Keep the screen on while shown as the screensaver. */
  val keepAwake: Boolean = true,
  /** Index into the pixel color themes. */
  val themeIndex: Int = 0,
)

object HomePrefs {
  private const val FILE = "home_prefs"
  private const val K_CITY = "weather_city"
  private const val K_FAHRENHEIT = "fahrenheit"
  private const val K_24H = "use_24h"
  private const val K_SECONDS = "show_seconds"
  private const val K_MONDAY = "week_starts_monday"
  private const val K_DANCE = "robot_dances"
  private const val K_BUBBLE = "bubble_seconds"
  private const val K_NIGHT_DIM = "night_dim"
  private const val K_SHIFT = "pixel_shift"
  private const val K_SCREENSAVER = "screensaver_enabled"
  private const val K_AWAKE = "keep_awake"
  private const val K_THEME = "theme_index"

  val BUBBLE_CHOICES = listOf(15, 30, 60, 120, 300)

  private var prefs: SharedPreferences? = null
  private val _settings = MutableStateFlow(HomeSettings())
  val settings: StateFlow<HomeSettings> = _settings.asStateFlow()

  fun init(context: Context) {
    if (prefs != null) return
    val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    prefs = p
    val d = HomeSettings()
    _settings.value = HomeSettings(
      weatherCity = p.getString(K_CITY, d.weatherCity) ?: d.weatherCity,
      fahrenheit = p.getBoolean(K_FAHRENHEIT, d.fahrenheit),
      use24h = p.getBoolean(K_24H, d.use24h),
      showSeconds = p.getBoolean(K_SECONDS, d.showSeconds),
      weekStartsMonday = p.getBoolean(K_MONDAY, d.weekStartsMonday),
      robotDances = p.getBoolean(K_DANCE, d.robotDances),
      bubbleSeconds = p.getInt(K_BUBBLE, d.bubbleSeconds),
      nightDim = p.getBoolean(K_NIGHT_DIM, d.nightDim),
      pixelShift = p.getBoolean(K_SHIFT, d.pixelShift),
      screensaverEnabled = p.getBoolean(K_SCREENSAVER, d.screensaverEnabled),
      keepAwake = p.getBoolean(K_AWAKE, d.keepAwake),
      themeIndex = p.getInt(K_THEME, d.themeIndex),
    )
  }

  fun update(transform: (HomeSettings) -> HomeSettings) {
    val s = transform(_settings.value)
    _settings.value = s
    prefs?.edit()?.apply {
      putString(K_CITY, s.weatherCity.trim())
      putBoolean(K_FAHRENHEIT, s.fahrenheit)
      putBoolean(K_24H, s.use24h)
      putBoolean(K_SECONDS, s.showSeconds)
      putBoolean(K_MONDAY, s.weekStartsMonday)
      putBoolean(K_DANCE, s.robotDances)
      putInt(K_BUBBLE, s.bubbleSeconds)
      putBoolean(K_NIGHT_DIM, s.nightDim)
      putBoolean(K_SHIFT, s.pixelShift)
      putBoolean(K_SCREENSAVER, s.screensaverEnabled)
      putBoolean(K_AWAKE, s.keepAwake)
      putInt(K_THEME, s.themeIndex)
      apply()
    }
  }
}
