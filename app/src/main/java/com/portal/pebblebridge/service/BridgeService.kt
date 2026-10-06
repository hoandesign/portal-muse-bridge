package com.portal.pebblebridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.portal.pebblebridge.server.McpHttpServer
import com.portal.pebblebridge.state.BridgeRepository
import com.portal.pebblebridge.ui.MainActivity
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BridgeService : Service() {

  companion object {
    private const val CHANNEL_ID = "pebble_muse_bridge_channel"
    private const val NOTIFICATION_ID = 8787

    fun start(context: Context) {
      val intent = Intent(context, BridgeService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, BridgeService::class.java)
      context.stopService(intent)
    }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private var mcpServer: McpHttpServer? = null
  private var bleManager: com.portal.pebblebridge.ble.MuseBleManager? = null
  private var museLinkClient: com.portal.pebblebridge.muse.MuseLinkClient? = null
  private var connectivityManager: ConnectivityManager? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    BridgeRepository.initPersistence(this)
    createNotificationChannel()

    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PebbleBridge::CpuWakeLock").apply {
      setReferenceCounted(false)
      acquire()
    }

    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Suppress("DEPRECATION")
    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PebbleBridge::WifiLock").apply {
      setReferenceCounted(false)
      acquire()
    }

    val localIp = getLocalIpAddress()
    BridgeRepository.updateServerStatus(isRunning = false, localIp = localIp, port = BridgeRepository.config.value.port)

    startForeground(NOTIFICATION_ID, buildNotification(localIp, BridgeRepository.config.value.port))

    mcpServer = McpHttpServer(port = BridgeRepository.config.value.port)
    mcpServer?.start(serviceScope)

    bleManager = com.portal.pebblebridge.ble.MuseBleManager(this)
    bleManager?.start()

    museLinkClient = com.portal.pebblebridge.muse.MuseLinkClient(serviceScope)
    museLinkClient?.start()

    registerNetworkCallback()

    // The Portal launcher resets the screensaver on boot; keep ours registered if enabled.
    com.portal.pebblebridge.home.HomePrefs.init(this)
    com.portal.pebblebridge.home.ScreensaverGuard.watch(this)
    serviceScope.launch {
      while (true) {
        com.portal.pebblebridge.home.ScreensaverGuard.apply(this@BridgeService)
        kotlinx.coroutines.delay(5 * 60_000L)
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    BridgeRepository.initPersistence(this)
    val localIp = getLocalIpAddress()
    BridgeRepository.updateServerStatus(
      isRunning = BridgeRepository.serverStatus.value.isRunning,
      localIp = localIp,
      port = BridgeRepository.config.value.port
    )
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID, buildNotification(localIp, BridgeRepository.config.value.port))
    return START_STICKY
  }

  override fun onDestroy() {
    unregisterNetworkCallback()
    museLinkClient?.stop()
    museLinkClient = null
    bleManager?.stop()
    bleManager = null
    mcpServer?.stop()
    serviceScope.cancel()

    try {
      if (wakeLock?.isHeld == true) wakeLock?.release()
      if (wifiLock?.isHeld == true) wifiLock?.release()
    } catch (_: Exception) {}

    super.onDestroy()
  }

  private fun registerNetworkCallback() {
    try {
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
      connectivityManager = cm
      val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

      val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          updateIp()
        }
        override fun onLost(network: Network) {
          updateIp()
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
          updateIp()
        }
      }
      networkCallback = callback
      cm.registerNetworkCallback(request, callback)
    } catch (_: Exception) {}
  }

  private fun unregisterNetworkCallback() {
    try {
      networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
      networkCallback = null
    } catch (_: Exception) {}
  }

  private fun updateIp() {
    val newIp = getLocalIpAddress()
    BridgeRepository.updateServerStatus(
      isRunning = BridgeRepository.serverStatus.value.isRunning,
      localIp = newIp,
      port = BridgeRepository.config.value.port
    )
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    nm?.notify(NOTIFICATION_ID, buildNotification(newIp, BridgeRepository.config.value.port))
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Pebble Muse Bridge",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Keeps the local Pebble Index MCP bridge server running"
      }
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(ip: String, port: Int): Notification {
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Pebble ➔ Muse Bridge Active")
      .setContentText("Listening at http://$ip:$port/api/mcp")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .build()
  }

  private fun getLocalIpAddress(): String {
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
      val candidates = mutableListOf<String>()
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        for (addr in intf.inetAddresses) {
          if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
            val ip = addr.hostAddress ?: ""
            if (ip.isNotBlank()) {
              if (intf.name.startsWith("wlan", ignoreCase = true)) {
                return ip // Prefer wlan0 on Portal
              }
              candidates.add(ip)
            }
          }
        }
      }
      if (candidates.isNotEmpty()) return candidates.first()
    } catch (_: Exception) {}
    return "127.0.0.1"
  }
}
