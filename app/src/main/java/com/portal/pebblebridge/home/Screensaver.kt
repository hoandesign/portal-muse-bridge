package com.portal.pebblebridge.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.dreams.DreamService
import android.util.Log
import com.portal.pebblebridge.ui.MainActivity

/**
 * Opens the pixel home screen when the Portal goes idle (same approach as portalani).
 *
 * Some launchers (e.g. Immortal launcher with its photo frame off) wake the device ~20 ms after
 * any dream starts, before onDreamingStarted runs. So launch from onCreate, the earliest hook;
 * the activity stays open even if the dream itself is then stopped.
 */
class HomeDreamService : DreamService() {
  private var launched = false

  override fun onCreate() {
    super.onCreate()
    launchHome()
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()
    launchHome()
    finish()
  }

  private fun launchHome() {
    if (launched) return
    launched = true
    startActivity(
      Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(MainActivity.EXTRA_DREAM_MODE, true),
    )
  }
}

/**
 * Keeps [HomeDreamService] registered as the Portal screensaver. The Portal's launcher resets
 * `screensaver_components` on boot, so the bridge service re-applies this periodically.
 *
 * Needs `adb shell pm grant com.portal.pebblebridge android.permission.WRITE_SECURE_SETTINGS`.
 * The previous screensaver is remembered and restored when the user turns ours off.
 */
object ScreensaverGuard {
  private const val TAG = "ScreensaverGuard"
  const val COMPONENT = "com.portal.pebblebridge/com.portal.pebblebridge.home.HomeDreamService"
  private const val KEY_COMPONENTS = "screensaver_components"
  private const val KEY_ENABLED = "screensaver_enabled"
  private const val PREFS = "screensaver_guard"
  private const val KEY_PREVIOUS = "previous_component"

  fun hasPermission(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
      PackageManager.PERMISSION_GRANTED

  fun currentComponent(context: Context): String? =
    Settings.Secure.getString(context.contentResolver, KEY_COMPONENTS)

  /** Ours is the screensaver and screensavers are switched on. */
  fun isActive(context: Context): Boolean =
    currentComponent(context) == COMPONENT &&
      Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 0) == 1

  /** Applies the user's choice. Returns false when the permission is missing. */
  fun apply(context: Context, enabled: Boolean = HomePrefs.settings.value.screensaverEnabled): Boolean {
    if (!hasPermission(context)) return false
    val cr = context.contentResolver
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return try {
      val current = currentComponent(context)
      if (enabled) {
        if (current != COMPONENT) {
          if (!current.isNullOrBlank()) prefs.edit().putString(KEY_PREVIOUS, current).apply()
          Settings.Secure.putString(cr, KEY_COMPONENTS, COMPONENT)
          Log.i(TAG, "screensaver set to ours (was: $current)")
        }
        Settings.Secure.putInt(cr, KEY_ENABLED, 1)
        Settings.Secure.putInt(cr, "screensaver_activate_on_sleep", 1)
      } else if (current == COMPONENT) {
        val previous = prefs.getString(KEY_PREVIOUS, null)
        if (!previous.isNullOrBlank()) {
          Settings.Secure.putString(cr, KEY_COMPONENTS, previous)
          Log.i(TAG, "screensaver restored to $previous")
        }
      }
      true
    } catch (e: SecurityException) {
      Log.w(TAG, "WRITE_SECURE_SETTINGS not granted", e)
      false
    }
  }

  private var lastReassertMs = 0L
  private var observer: android.database.ContentObserver? = null

  /**
   * Some launchers (e.g. Immortal launcher) put their own screensaver back whenever their home
   * screen resumes, or switch screensavers off entirely. Watch both settings and re-apply ours
   * straight away, at most once every few seconds so two apps can't ping-pong forever.
   */
  fun watch(context: Context) {
    if (observer != null) return
    val app = context.applicationContext
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val obs = object : android.database.ContentObserver(handler) {
      override fun onChange(selfChange: Boolean) {
        if (!HomePrefs.settings.value.screensaverEnabled || isActive(app)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReassertMs < 5_000L) {
          // Too soon: try once more after the cool-down instead of fighting.
          handler.postDelayed({ if (!isActive(app)) apply(app) }, 5_000L)
          return
        }
        lastReassertMs = now
        Log.i(TAG, "screensaver changed to ${currentComponent(app)} (enabled=${Settings.Secure.getInt(app.contentResolver, KEY_ENABLED, 0)}); re-applying ours")
        apply(app)
      }
    }
    app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY_COMPONENTS), false, obs)
    app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY_ENABLED), false, obs)
    observer = obs
  }

  const val GRANT_COMMAND =
    "adb shell pm grant com.portal.pebblebridge android.permission.WRITE_SECURE_SETTINGS"
}
