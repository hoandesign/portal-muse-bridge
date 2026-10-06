package com.portal.pebblebridge.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.dreams.DreamService
import android.util.Log
import com.portal.pebblebridge.ui.MainActivity

/** Opens the pixel home screen when the Portal goes idle (same approach as portalani). */
class HomeDreamService : DreamService() {
  override fun onDreamingStarted() {
    super.onDreamingStarted()
    startActivity(
      Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(MainActivity.EXTRA_DREAM_MODE, true),
    )
    finish()
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
  private const val PREFS = "screensaver_guard"
  private const val KEY_PREVIOUS = "previous_component"

  fun hasPermission(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
      PackageManager.PERMISSION_GRANTED

  fun currentComponent(context: Context): String? =
    Settings.Secure.getString(context.contentResolver, KEY_COMPONENTS)

  fun isActive(context: Context): Boolean = currentComponent(context) == COMPONENT

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
        Settings.Secure.putInt(cr, "screensaver_enabled", 1)
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

  const val GRANT_COMMAND =
    "adb shell pm grant com.portal.pebblebridge android.permission.WRITE_SECURE_SETTINGS"
}
