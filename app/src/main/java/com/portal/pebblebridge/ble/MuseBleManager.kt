package com.portal.pebblebridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import com.portal.pebblebridge.state.BridgeRepository
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class PairingState {
  IDLE,
  WAIT_CLIENT_FINISHED,
  CONFIRM_REQUIRED,
  READY,
  PROVISIONING,
}

class ChunkAssembler(private val maxBytes: Int = MuseBleManager.MAX_MESSAGE_BYTES) {
  private val buffer = ByteArrayOutputStream()
  private var totalChunks = 0
  private var nextIndex = 0

  @Synchronized
  fun reset() {
    buffer.reset()
    totalChunks = 0
    nextIndex = 0
  }

  @Synchronized
  fun feed(packet: ByteArray): ByteArray? {
    if (packet.size < MuseBleManager.HEADER_BYTES || packet[0] != MuseBleManager.CHUNK_MAGIC) {
      return packet.copyOf()
    }
    val index = packet[1].toInt() and 0xFF
    val total = packet[2].toInt() and 0xFF
    val fragmentLength = packet.size - MuseBleManager.HEADER_BYTES

    if (total == 0) {
      reset()
      return null
    }

    if (index == 0 || total != totalChunks) {
      reset()
      totalChunks = total
    }

    if (index != nextIndex || index >= totalChunks) {
      reset()
      return null
    }

    if (buffer.size() + fragmentLength > maxBytes) {
      reset()
      return null
    }

    buffer.write(packet, MuseBleManager.HEADER_BYTES, fragmentLength)
    nextIndex = index + 1

    if (nextIndex < totalChunks) {
      return null
    }

    val complete = buffer.toByteArray()
    reset()
    return complete
  }
}

class MuseBleManager(
  private val context: Context,
  private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

  companion object {
    private const val TAG = "MuseBleManager"

    @Volatile
    var activeInstance: MuseBleManager? = null

    fun userConfirmPairing() {
      activeInstance?.confirmPairingFromUser()
    }

    fun userDeclinePairing() {
      activeInstance?.declinePairingFromUser()
    }

    fun restartPairingMode() {
      activeInstance?.restartPairingMode()
    }

    val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
    val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
    val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val PAIRED_FLAG_COMPANY_ID = 0xFFFF

    const val CHUNK_MAGIC = 0xFE.toByte()
    /** Gap between chunks, like the SDK's `CHUNK_STAGGER_S`. */
    const val CHUNK_STAGGER_MS = 20L
    const val NOTIFY_TIMEOUT_MS = 2_000L
    const val HEADER_BYTES = 3
    const val MAX_PACKET_BYTES = 160
    const val MAX_MESSAGE_BYTES = 8192
    const val DEFAULT_ATT_MTU = 23
    const val CURRENT_CONNECTION_LABEL = "Use current connection"
    const val PAIRING_TIMEOUT_MS = 45_000L

    fun encodeChunks(data: ByteArray, mtu: Int = DEFAULT_ATT_MTU): List<ByteArray> {
      val notifyMax = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET_BYTES)
      val usable = maxOf(notifyMax - HEADER_BYTES, 1)
      val total = if (data.isEmpty()) 1 else (data.size + usable - 1) / usable
      if (total > 255) {
        throw IllegalArgumentException("Message requires $total chunks, exceeding max 255")
      }
      if (data.isEmpty()) {
        return listOf(byteArrayOf(CHUNK_MAGIC, 0, 1))
      }
      val chunks = ArrayList<ByteArray>(total)
      var offset = 0
      for (i in 0 until total) {
        val length = minOf(usable, data.size - offset)
        val packet = ByteArray(HEADER_BYTES + length)
        packet[0] = CHUNK_MAGIC
        packet[1] = i.toByte()
        packet[2] = total.toByte()
        System.arraycopy(data, offset, packet, HEADER_BYTES, length)
        chunks.add(packet)
        offset += length
      }
      return chunks
    }
  }

  private var managerJob = SupervisorJob()
  private var scope = CoroutineScope(coroutineDispatcher + managerJob)
  private val bluetoothManager: BluetoothManager? =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
  @Volatile private var advertiser: BluetoothLeAdvertiser? = null
  @Volatile private var gattServer: BluetoothGattServer? = null
  @Volatile private var pairingWatchdogJob: Job? = null
  @Volatile private var shutdownJob: Job? = null
  private val isAdvertising = AtomicBoolean(false)
  private val isRunning = AtomicBoolean(false)

  // Device identity (RFC-compliant MAC & canonical lower/upper hex conventions matching Muse Gadget SDK)
  val deviceMac: String
  val deviceSuffix: String
  val bleName: String
  val nodeId: String
  val deviceId: String

  init {
    val serial = run {
      try {
        val c = Class.forName("android.os.SystemProperties")
        val get = c.getMethod("get", String::class.java)
        (get.invoke(null, "ro.serialno") as? String)?.takeIf { it.isNotBlank() }
      } catch (_: Exception) {
        null
      }
    } ?: Build.SERIAL.takeIf { it.isNotBlank() && it != "unknown" } ?: "PortalAlohaProd"

    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(serial.toByteArray(StandardCharsets.UTF_8))
    val b0 = (hash[0].toInt() and 0xFC) or 0x02 // locally-administered unicast
    val b1 = hash[1].toInt() and 0xFF
    val b2 = hash[2].toInt() and 0xFF
    val b3 = hash[3].toInt() and 0xFF
    val b4 = hash[4].toInt() and 0xFF
    val b5 = hash[5].toInt() and 0xFF

    deviceMac = String.format("%02x:%02x:%02x:%02x:%02x:%02x", b0, b1, b2, b3, b4, b5)
    deviceSuffix = String.format("%02x%02x%02x", b3, b4, b5)
    bleName = "MuseGadget" + deviceSuffix.uppercase()
    nodeId = "homelink-$deviceSuffix"
    deviceId = "hatch-link:$deviceMac"
    BridgeRepository.deviceId = deviceId
    BridgeRepository.setBleDeviceName(bleName)
    BridgeRepository.nodeId = nodeId
  }

  // Active pairing session state (protected by pairingLock)
  private val pairingLock = Any()
  var activePairingDevice: BluetoothDevice? = null
    private set
  private var ecdhSecret: ByteArray? = null
  private var rxKey: ByteArray? = null
  private var txKey: ByteArray? = null
  @Volatile
  private var sessionIdB64: String = ""
  private val rxCounter = AtomicLong(0)
  private val txCounter = AtomicLong(0)
  @Volatile
  var isPaired: Boolean = false
    private set
  @Volatile
  var pairingState: PairingState = PairingState.IDLE
    private set

  // Per-device GATT state tracking
  private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
  private val deviceMtus = ConcurrentHashMap<String, Int>()
  private val preparedWriteBuffers = ConcurrentHashMap<BluetoothDevice, ByteArrayOutputStream>()
  private val chunkAssemblers = ConcurrentHashMap<BluetoothDevice, ChunkAssembler>()
  private val rxMutexes = ConcurrentHashMap<String, Mutex>()
  private val txMutex = Mutex()
  /** Completed by onNotificationSent; Android allows one outstanding notification at a time. */
  @Volatile private var notifySent: kotlinx.coroutines.CompletableDeferred<Int>? = null

  private val btReceiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context?, intent: Intent?) {
      if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        when (state) {
          BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
            Log.w(TAG, "Bluetooth turned off; tearing down GATT server, advertising and unbinding hook")
            stopAdvertisingInternal()
            closeGattServer()
            GattBinderHook.uninstall(context.applicationContext)
          }
          BluetoothAdapter.STATE_ON -> {
            Log.i(TAG, "Bluetooth turned back ON; recovering BLE services")
            if (isRunning.get()) {
              scope.launch {
                startServicesInternal()
              }
            }
          }
        }
      }
    }
  }

  fun start() {
    if (isRunning.getAndSet(true)) {
      Log.i(TAG, "MuseBleManager already running")
      return
    }

    activeInstance = this

    if (!managerJob.isActive) {
      managerJob = SupervisorJob()
      scope = CoroutineScope(coroutineDispatcher + managerJob)
    }

    isPaired = BridgeRepository.isPaired()

    // Register receiver for Bluetooth toggle recovery
    try {
      context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    } catch (e: Exception) {
      Log.w(TAG, "Failed to register Bluetooth state receiver: ${e.message}")
    }

    if (adapter == null || !adapter.isEnabled) {
      Log.w(TAG, "Bluetooth adapter not available or disabled")
      return
    }

    try {
      adapter.name = bleName
    } catch (e: Exception) {
      Log.w(TAG, "Could not set adapter name: ${e.message}")
    }

    scope.launch {
      startServicesInternal()
    }
  }

  private fun startServicesInternal() {
    Log.i(TAG, "Installing GattBinderHook on Meta Portal (off main thread)...")
    GattBinderHook.install(context.applicationContext) { ready ->
      if (!isRunning.get()) return@install
      scope.launch {
        if (!isRunning.get()) return@launch
        Log.i(TAG, "GattBinderHook ready=$ready. Initializing GATT server and BLE advertising off-thread...")
        setupGattServer()
        startAdvertising()
      }
    }
  }

  fun stop() {
    isRunning.set(false)
    if (activeInstance == this) {
      activeInstance = null
    }
    try {
      context.unregisterReceiver(btReceiver)
    } catch (_: Exception) {}

    synchronized(pairingLock) {
      shutdownJob?.cancel()
      shutdownJob = null
    }

    stopAdvertisingInternal()
    for (dev in subscribedDevices) {
      try {
        gattServer?.cancelConnection(dev)
      } catch (_: Exception) {}
    }
    closeGattServer()
    GattBinderHook.uninstall(context.applicationContext)
    resetPairing()
    managerJob.cancel()
    subscribedDevices.clear()
    deviceMtus.clear()
    preparedWriteBuffers.clear()
    chunkAssemblers.clear()
    rxMutexes.clear()
  }

  fun restartPairingMode() {
    synchronized(pairingLock) {
      shutdownJob?.cancel()
      shutdownJob = null
    }
    isPaired = false
    BridgeRepository.setPaired(false)
    resetPairing()
    for (dev in subscribedDevices) {
      try {
        gattServer?.cancelConnection(dev)
      } catch (e: Exception) {
        Log.w(TAG, "Error disconnecting client on pairing restart: ${e.message}")
      }
    }
    subscribedDevices.clear()
    stopAdvertisingInternal()
    startAdvertising()
    Log.i(TAG, "Restarted BLE pairing mode; advertising as $bleName")
  }

  private fun closeGattServer() {
    try {
      gattServer?.close()
    } catch (e: Exception) {
      Log.e(TAG, "Error closing GATT server", e)
    } finally {
      gattServer = null
    }
  }

  private fun startAdvertising() {
    advertiser = adapter?.bluetoothLeAdvertiser
    if (advertiser == null) {
      Log.w(TAG, "adapter.bluetoothLeAdvertiser is null, creating via reflection...")
      try {
        val iBluetoothManagerClass = Class.forName("android.bluetooth.IBluetoothManager")
        val ctor = BluetoothLeAdvertiser::class.java.getDeclaredConstructor(iBluetoothManagerClass).apply {
          isAccessible = true
        }
        val managerField = BluetoothAdapter::class.java.getDeclaredField("mManagerService").apply {
          isAccessible = true
        }
        val mgr = managerField.get(adapter)
        advertiser = ctor.newInstance(mgr)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to instantiate BluetoothLeAdvertiser via reflection", e)
      }
    }

    val adv = advertiser
    if (adv == null) {
      Log.e(TAG, "BluetoothLeAdvertiser unavailable; cannot advertise")
      return
    }

    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
      .setConnectable(true)
      .setTimeout(0)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
      .build()

    // Primary advertisement packet: Service UUID + Manufacturer data (Fits in 31 bytes limit)
    val pairedByte = if (isPaired) 1.toByte() else 0.toByte()
    val data = AdvertiseData.Builder()
      .setIncludeDeviceName(false)
      .setIncludeTxPowerLevel(false)
      .addServiceUuid(ParcelUuid(SERVICE_UUID))
      .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(pairedByte))
      .build()

    // Scan response packet: Device name (Fits in separate 31 bytes limit)
    val scanResponse = AdvertiseData.Builder()
      .setIncludeDeviceName(true)
      .build()

    try {
      adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
      Log.i(TAG, "Started BLE Advertising as $bleName with service $SERVICE_UUID (paired=$isPaired)")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start BLE advertising", e)
    }
  }

  private fun stopAdvertisingInternal() {
    if (isAdvertising.get()) {
      try {
        advertiser?.stopAdvertising(advertiseCallback)
        Log.i(TAG, "Stopped BLE Advertising")
      } catch (e: Exception) {
        Log.w(TAG, "Error stopping advertising: ${e.message}")
      } finally {
        isAdvertising.set(false)
      }
    }
  }

  private val advertiseCallback = object : AdvertiseCallback() {
    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
      isAdvertising.set(true)
      Log.i(TAG, "BLE Advertising started successfully: $bleName")
    }

    override fun onStartFailure(errorCode: Int) {
      isAdvertising.set(false)
      Log.e(TAG, "BLE Advertising failed with error code: $errorCode")
    }
  }

  private fun setupGattServer() {
    closeGattServer()
    val callback = object : BluetoothGattServerCallback() {
      override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        if (newState == BluetoothGatt.STATE_CONNECTED) {
          Log.i(TAG, "Device connected: ${device.address}")
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
          Log.i(TAG, "Device disconnected: ${device.address}")
          subscribedDevices.remove(device)
          deviceMtus.remove(device.address)
          preparedWriteBuffers.remove(device)
          chunkAssemblers.remove(device)?.reset()
          rxMutexes.remove(device.address)
          val isCurrentPairingDevice = synchronized(pairingLock) {
            activePairingDevice?.address == device.address
          }
          if (isCurrentPairingDevice) {
            Log.i(TAG, "Active pairing peer ${device.address} disconnected; resetting pairing session")
            resetPairing()
          }
        }
      }

      override fun onDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
      ) {
        if (descriptor.uuid == CCCD_UUID) {
          val value = if (subscribedDevices.contains(device)) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
          } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
          }
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        } else {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
        }
      }

      override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
      ) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
      }

      override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        notifySent?.complete(status)
      }

      override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        Log.i(TAG, "Device ${device.address} MTU changed: $mtu")
        deviceMtus[device.address] = mtu
      }

      override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
      ) {
        if (descriptor.uuid == CCCD_UUID) {
          if (preparedWrite) {
            // Prepared writes on CCCD are invalid per GATT specification §3.3.3.3
            if (responseNeeded) {
              gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, value)
            }
            return
          }
          if (value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) {
            subscribedDevices.add(device)
            Log.i(TAG, "Notifications enabled by ${device.address}")
          } else {
            subscribedDevices.remove(device)
          }
        }
        if (responseNeeded) {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
      }

      override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
      ) {
        if (characteristic.uuid != RX_UUID) {
          if (responseNeeded) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, value)
          }
          return
        }

        if (preparedWrite) {
          val status = processPreparedWriteChunk(device, offset, value)
          if (responseNeeded) {
            gattServer?.sendResponse(device, requestId, status, offset, value)
          }
          return
        }

        // Standard un-prepared write:
        if (responseNeeded) {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        if (value != null) {
          val assembler = chunkAssemblers.getOrPut(device) { ChunkAssembler() }
          val complete = assembler.feed(value)
          if (complete != null) {
            scope.launch {
              handleIncomingPayload(device, complete)
            }
          }
        }
      }

      override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
        val buffer = preparedWriteBuffers.remove(device)
        // CRITICAL GATT SPEC REQUIREMENT (Vol 3 Part F §3.3.2):
        // Immediately acknowledge execute write request before executing asynchronous work.
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

        if (execute && buffer != null) {
          val completeBytes = synchronized(buffer) { buffer.toByteArray() }
          if (completeBytes.isNotEmpty()) {
            val assembler = chunkAssemblers.getOrPut(device) { ChunkAssembler() }
            val payload = assembler.feed(completeBytes)
            if (payload != null) {
              scope.launch {
                handleIncomingPayload(device, payload)
              }
            }
          }
        } else {
          Log.d(TAG, "Prepared write canceled or empty for ${device.address}")
        }
      }
    }

    var server = bluetoothManager?.openGattServer(context.applicationContext, callback)
    if (server == null) {
      Log.i(TAG, "bluetoothManager.openGattServer returned null; invoking GattBinderHook.openGattServerDirect on background thread")
      server = GattBinderHook.openGattServerDirect(callback)
    }

    gattServer = server
    if (gattServer == null) {
      Log.e(TAG, "Failed to open GATT Server after direct fallback")
      return
    }

    val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

    // RX Characteristic (Phone writes to Device)
    val rxChar = BluetoothGattCharacteristic(
      RX_UUID,
      BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
      BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    // TX Characteristic (Device notifies to Phone)
    val txChar = BluetoothGattCharacteristic(
      TX_UUID,
      BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      BluetoothGattCharacteristic.PERMISSION_READ
    )
    val cccd = BluetoothGattDescriptor(
      CCCD_UUID,
      BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
    )
    txChar.addDescriptor(cccd)

    service.addCharacteristic(rxChar)
    service.addCharacteristic(txChar)

    gattServer?.addService(service)
    Log.i(TAG, "GATT Server configured with primary service $SERVICE_UUID")
  }

  fun processPreparedWriteChunk(device: BluetoothDevice, offset: Int, value: ByteArray?): Int {
    if (value == null) return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
    val buffer = preparedWriteBuffers.getOrPut(device) { ByteArrayOutputStream() }
    synchronized(buffer) {
      if (offset == 0) {
        buffer.reset()
      } else if (offset != buffer.size()) {
        Log.w(TAG, "Prepared write offset mismatch: expected ${buffer.size()}, got $offset")
        preparedWriteBuffers.remove(device)
        return BluetoothGatt.GATT_INVALID_OFFSET
      }
      if (buffer.size() + value.size > MAX_MESSAGE_BYTES) {
        Log.w(TAG, "Prepared write exceeded MAX_MESSAGE_BYTES ($MAX_MESSAGE_BYTES)")
        preparedWriteBuffers.remove(device)
        return 0x0D /* GATT_INSUFFICIENT_RESOURCES */
      }
      buffer.write(value)
      return BluetoothGatt.GATT_SUCCESS
    }
  }

  fun getPreparedWriteBufferSize(device: BluetoothDevice): Int {
    return preparedWriteBuffers[device]?.size() ?: 0
  }

  private suspend fun handleIncomingPayload(device: BluetoothDevice, payload: ByteArray) {
    val mutex = rxMutexes.computeIfAbsent(device.address) { Mutex() }
    mutex.withLock {
      val jsonStr = String(payload, StandardCharsets.UTF_8)
      try {
        val json = JSONObject(jsonStr)
        handleJsonCommand(device, json)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse incoming BLE JSON: $jsonStr", e)
      }
    }
  }

  private suspend fun handleJsonCommand(device: BluetoothDevice, json: JSONObject) {
    val action = json.optString("action").ifBlank { json.optString("type") }
    Log.d(TAG, "Received BLE JSON message with action: $action")

    when (action) {
      "get_device_info" -> sendDeviceInfo(device)
      "pairing_client_hello", "pairing_start", "hello" -> handleClientHello(device, json)
      "pairing_encrypted" -> handlePairingEncrypted(device, json)
      else -> {
        Log.w(TAG, "Unhandled command action: $action")
      }
    }
  }

  private suspend fun sendDeviceInfo(device: BluetoothDevice) {
    val info = JSONObject().apply {
      put("type", "device_info")
      put("node_id", nodeId)
      put("version", "1.0.0")
      put("device_id", deviceId)
      put("mac", deviceMac)
      put("model", "hatch_link")
      put("pairing_protocol", 5)
      put("pairing_auth", "none")
      put("pairing_auth_epoch", 0)
      put("pairing_policy", "confirm_app")
      put("build_sha", "")
      put("network_ready", true)
    }
    sendJson(device, info)
    Log.i(TAG, "Sent device_info response to client ${device.address}: $info")
  }

  private suspend fun handleClientHello(device: BluetoothDevice, json: JSONObject) {
    val isBusy = synchronized(pairingLock) {
      activePairingDevice != null &&
      activePairingDevice?.address != device.address &&
      pairingState != PairingState.IDLE
    }
    if (isBusy) {
      Log.w(TAG, "Rejecting client_hello from ${device.address}: pairing session in progress with ${activePairingDevice?.address}")
      sendPlaintextStatus(device, "error_pairing_busy")
      return
    }

    try {
      val mobilePubB64 = json.getString("mobile_pub")
      val mobileNonceB64 = json.getString("mobile_nonce")

      val kpg = KeyPairGenerator.getInstance("EC")
      kpg.initialize(ECGenParameterSpec("secp256r1"))
      val keyPair = kpg.generateKeyPair()

      val random = SecureRandom()
      val deviceNonce = ByteArray(16)
      random.nextBytes(deviceNonce)

      val mobilePubBytes = Base64.decode(mobilePubB64, Base64.URL_SAFE or Base64.NO_PADDING)
      val xBytes = mobilePubBytes.copyOfRange(1, 33)
      val yBytes = mobilePubBytes.copyOfRange(33, 65)

      val ecPoint = ECPoint(
        java.math.BigInteger(1, xBytes),
        java.math.BigInteger(1, yBytes)
      )
      val kf = KeyFactory.getInstance("EC")
      val mobilePubKey = kf.generatePublic(ECPublicKeySpec(ecPoint, (keyPair.public as ECPublicKey).params))

      val ka = KeyAgreement.getInstance("ECDH")
      ka.init(keyPair.private)
      ka.doPhase(mobilePubKey, true)
      val secret = ka.generateSecret()

      val ecPub = keyPair.public as ECPublicKey
      val rawDevicePub = ByteArray(65)
      rawDevicePub[0] = 0x04
      val devX = ecPub.w.affineX.toByteArray().stripLeadingZeros(32)
      val devY = ecPub.w.affineY.toByteArray().stripLeadingZeros(32)
      System.arraycopy(devX, 0, rawDevicePub, 1, 32)
      System.arraycopy(devY, 0, rawDevicePub, 33, 32)

      val devicePubB64 = base64Url(rawDevicePub)
      val deviceNonceB64 = base64Url(deviceNonce)

      val transcript = buildTranscript(
        deviceId = deviceId,
        nodeId = nodeId,
        mac = deviceMac,
        firmwareVersion = "1.0.0",
        mobilePub = mobilePubB64,
        devicePub = devicePubB64,
        mobileNonce = mobileNonceB64,
        deviceNonce = deviceNonceB64,
      )

      val transcriptHash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(StandardCharsets.UTF_8))
      val mobileNonceBytes = Base64.decode(mobileNonceB64, Base64.URL_SAFE or Base64.NO_PADDING)

      // Canonical Hatch Link Protocol 5 key derivation:
      val salt = MessageDigest.getInstance("SHA-256").digest(mobileNonceBytes + deviceNonce + transcriptHash)
      val sessionSecret = hkdfExpand(hkdfExtract(salt, secret), "hatch-link ble setup v1".toByteArray(StandardCharsets.UTF_8), 32)
      val rxK = hkdfExpand(sessionSecret, "mobile->device".toByteArray(StandardCharsets.UTF_8), 32)
      val txK = hkdfExpand(sessionSecret, "device->mobile".toByteArray(StandardCharsets.UTF_8), 32)
      val sessionBytes = MessageDigest.getInstance("SHA-256").digest(
        "hatch-link session id v1".toByteArray(StandardCharsets.UTF_8) + transcriptHash + secret
      ).copyOfRange(0, 16)
      val sidB64 = base64Url(sessionBytes)

      synchronized(pairingLock) {
        activePairingDevice = device
        ecdhSecret = secret
        rxKey = rxK
        txKey = txK
        sessionIdB64 = sidB64
        rxCounter.set(0)
        txCounter.set(0)
        pairingState = PairingState.WAIT_CLIENT_FINISHED
        armPairingWatchdog()
      }

      val response = JSONObject().apply {
        put("type", "pairing_ready")
        put("version", 5)
        put("device_id", deviceId)
        put("node_id", nodeId)
        put("mac", deviceMac)
        put("model", "hatch_link")
        put("firmware_version", "1.0.0")
        put("pairing_auth", "none")
        put("pairing_auth_epoch", 0)
        put("pairing_policy", "confirm_app")
        put("device_pub", devicePubB64)
        put("device_nonce", deviceNonceB64)
        put("transcript_hash", base64Url(transcriptHash))
        put("session_id", sidB64)
      }
      sendJson(device, response)
      Log.i(TAG, "BLE Handshake completed; Session established: $sidB64")
    } catch (e: Exception) {
      Log.e(TAG, "Pairing handshake failed", e)
      sendPlaintextStatus(device, "error_pairing_failed")
    }
  }

  private suspend fun handlePairingEncrypted(device: BluetoothDevice, envelope: JSONObject) {
    val isAuthorized = synchronized(pairingLock) {
      activePairingDevice != null && activePairingDevice?.address == device.address
    }
    if (!isAuthorized) {
      Log.w(TAG, "Rejecting encrypted packet from unauthorized peer ${device.address}")
      sendPlaintextStatus(device, "error_pairing_unauthorized")
      return
    }

    val sid = envelope.optString("session_id")
    val counter = envelope.optLong("counter", -1L)
    val ciphertextB64 = envelope.optString("ciphertext")
    val tagB64 = envelope.optString("tag")

    var validationError: String? = null
    val (key, expectedCounter, currentSessionId) = synchronized(pairingLock) {
      val k = rxKey
      if (k == null) {
        Log.e(TAG, "Received encrypted record without established session key")
        validationError = "error_pairing_decrypt"
        Triple(null, -1L, "")
      } else if (sid != sessionIdB64) {
        Log.e(TAG, "Session ID mismatch: expected $sessionIdB64, got $sid")
        validationError = "error_pairing_decrypt"
        Triple(null, -1L, "")
      } else {
        val expected = rxCounter.get()
        if (counter != expected) {
          Log.e(TAG, "Counter mismatch: received $counter, expected $expected")
          validationError = "error_pairing_replay"
          Triple(null, -1L, "")
        } else {
          Triple(k, expected, sessionIdB64)
        }
      }
    }

    if (validationError != null || key == null) {
      sendPlaintextStatus(device, validationError ?: "error_pairing_decrypt")
      return
    }

    try {
      val ciphertext = Base64.decode(ciphertextB64, Base64.URL_SAFE or Base64.NO_PADDING)
      val tag = Base64.decode(tagB64, Base64.URL_SAFE or Base64.NO_PADDING)

      val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        .put(0.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte())
        .putLong(counter)
        .array()

      val aad = "hatch-link ble setup v1|$currentSessionId|m2d|$counter".toByteArray(StandardCharsets.UTF_8)

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      cipher.updateAAD(aad)
      val plaintextBytes = cipher.doFinal(ciphertext + tag)

      // Commit counter increment only AFTER successful cryptographic tag authentication!
      val committed = synchronized(pairingLock) {
        rxCounter.compareAndSet(expectedCounter, expectedCounter + 1)
      }
      if (!committed) {
        Log.w(TAG, "rxCounter commit failed (CAS collision or session reset); aborting payload processing")
        return
      }

      val plaintextStr = String(plaintextBytes, StandardCharsets.UTF_8)
      val command = JSONObject(plaintextStr)
      val action = command.optString("action")
      // provision_v2 carries the device's access and refresh tokens; never log the payload.
      Log.i(TAG, "Decrypted client payload: action=$action")

      when (action) {
        "pairing_client_finished" -> {
          val valid = synchronized(pairingLock) {
            if (pairingState != PairingState.WAIT_CLIENT_FINISHED || rxCounter.get() != 1L) {
              Log.e(TAG, "pairing_client_finished rejected; state=$pairingState counter=${rxCounter.get()}")
              resetPairing()
              false
            } else {
              pairingState = PairingState.READY
              true
            }
          }
          if (!valid) {
            sendPlaintextStatus(device, "error_pairing_decrypt")
            return
          }
          Log.i(TAG, "pairing_client_finished accepted (app consent); sending pairing_confirmed immediately...")
          sendPairingConfirmed(device)
        }
        "wifi_scan" -> {
          val valid = synchronized(pairingLock) {
            pairingState == PairingState.READY || pairingState == PairingState.PROVISIONING
          }
          if (!valid) {
            Log.e(TAG, "wifi_scan rejected: state is $pairingState (confirm required)")
            sendEncryptedStatus(device, "error_pairing_confirm_required")
            return
          }
          val scanEntries = getWifiScanEntries()
          val scanResult = JSONObject().apply {
            put("type", "wifi_scan_result")
            val networks = org.json.JSONArray().apply {
              for (entry in scanEntries) {
                put(JSONObject().apply {
                  put("ssid", entry.ssid)
                  put("rssi", entry.rssi)
                  put("secure", entry.secure)
                })
              }
            }
            put("networks", networks)
          }
          sendEncryptedJson(device, scanResult)
          armPairingWatchdog()
          Log.i(TAG, "Sent wifi_scan_result to Muse app with ${scanEntries.size} networks: ${scanEntries.map { "${it.ssid}(sec=${it.secure})" }}")
        }
        "provision_v2" -> {
          val valid = synchronized(pairingLock) {
            if (pairingState != PairingState.READY && pairingState != PairingState.PROVISIONING) {
              false
            } else {
              pairingState = PairingState.PROVISIONING
              true
            }
          }
          if (!valid) {
            Log.e(TAG, "provision_v2 rejected: state is $pairingState (confirm required)")
            sendEncryptedStatus(device, "error_pairing_confirm_required")
            return
          }
          val creds = command.optJSONObject("credentials") ?: command
          val accessToken = creds.optString("access_token")
          val refreshToken = creds.optString("refresh_token")
          val apiUrl = creds.optString("api_url")
          val apiUrlV2 = creds.optString("api_url_v2")
          val noiseHost = creds.optString("noise_host")

          Log.i(TAG, "Received Provisioning Credentials: apiUrl=$apiUrlV2 noiseHost=$noiseHost accessToken=${accessToken.take(8)}...")

          if (accessToken.isNotBlank()) {
            BridgeRepository.updateConfig(
              BridgeRepository.config.value.copy(
                museAccessToken = accessToken,
                museRefreshToken = refreshToken,
                museApiUrl = apiUrlV2.ifBlank { apiUrl.ifBlank { "https://api.muse.ai" } },
                museNoiseHost = noiseHost,
              )
            )
            com.portal.pebblebridge.muse.MuseLinkClient.activeInstance?.triggerReconnect()
          }

          synchronized(pairingLock) {
            pairingWatchdogJob?.cancel()
            pairingWatchdogJob = null
          }

          isPaired = true
          BridgeRepository.setPaired(true)

          sendEncryptedStatus(device, "wifi_connecting")
          delay(150)
          sendEncryptedStatus(device, "wifi_connected")
          delay(150)
          sendEncryptedStatus(device, "auth_ok")
          Log.i(TAG, "Device successfully provisioned by Muse app!")

          // Conforms strictly to Meta Muse Gadget SDK (cli.py / app.c):
          // In official SDK, BLE is strictly for initial setup. Once auth_ok is transmitted,
          // the peripheral waits BLE_SHUTDOWN_DELAY_S (1.5s) to guarantee ATT delivery,
          // cleanly cancels the GATT connection, and stops BLE advertising completely.
          synchronized(pairingLock) {
            shutdownJob?.cancel()
            shutdownJob = scope.launch {
              delay(1500)
              try {
                Log.i(TAG, "Disconnecting GATT client ${device.address} after successful provisioning...")
                gattServer?.cancelConnection(device)
              } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting GATT client: ${e.message}")
              }
              stopAdvertisingInternal()
              synchronized(pairingLock) {
                shutdownJob = null
              }
              resetPairing()
              Log.i(TAG, "BLE setup session concluded; advertising stopped and session reset.")
            }
          }
        }
        else -> {
          Log.w(TAG, "Unhandled encrypted action: $action")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to decrypt record", e)
      sendPlaintextStatus(device, "error_pairing_decrypt")
    }
  }


  fun getActiveWifiSsid(): String {
    try {
      val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      val info = wifiManager?.connectionInfo
      val ssid = info?.ssid
      if (!ssid.isNullOrBlank()) {
        val cleaned = ssid.removeSurrounding("\"").trim()
        if (cleaned.isNotBlank() && !cleaned.equals("<unknown ssid>", ignoreCase = true)) {
          return cleaned
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to query active Wi-Fi SSID", e)
    }
    return CURRENT_CONNECTION_LABEL
  }

  data class WifiScanEntry(
    val ssid: String,
    val rssi: Int,
    val secure: Boolean
  )

  /**
   * One entry, exactly like the SDK's `network.current_connection_entry()`: the real SSID (the
   * Portal is already online), marked open so the app skips the password. The placeholder label
   * is only a last resort: the Muse Android app never sends `provision_v2` after the user picks
   * it (muse-gadget-sdk#79), so it must not be offered next to the real network.
   */
  fun getWifiScanEntries(): List<WifiScanEntry> =
    listOf(WifiScanEntry(getActiveWifiSsid(), -40, secure = false))

  private suspend fun sendJson(device: BluetoothDevice, json: JSONObject) {
    val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
    sendNotification(device, bytes)
  }

  private suspend fun sendPlaintextStatus(device: BluetoothDevice, status: String) {
    val json = JSONObject().apply {
      put("type", "status")
      put("status", status)
    }
    sendJson(device, json)
  }

  private suspend fun sendEncryptedJson(device: BluetoothDevice, json: JSONObject) {
    val (key, counter, currentSessionId) = synchronized(pairingLock) {
      val k = txKey ?: return
      val c = txCounter.getAndIncrement()
      Triple(k, c, sessionIdB64)
    }

    val plaintext = json.toString().toByteArray(StandardCharsets.UTF_8)
    val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
      .put(1.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte())
      .putLong(counter)
      .array()

    val aad = "hatch-link ble setup v1|$currentSessionId|d2m|$counter".toByteArray(StandardCharsets.UTF_8)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    val encrypted = cipher.doFinal(plaintext)
    val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)
    val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)

    val envelope = JSONObject().apply {
      put("type", "pairing_encrypted")
      put("session_id", currentSessionId)
      put("counter", counter.toString())
      put("ciphertext", base64Url(ciphertext))
      put("tag", base64Url(tag))
    }
    sendJson(device, envelope)
  }

  private suspend fun sendEncryptedStatus(device: BluetoothDevice, status: String) {
    val msg = JSONObject().apply {
      put("type", "status")
      put("status", status)
    }
    sendEncryptedJson(device, msg)
  }

  private suspend fun sendNotification(device: BluetoothDevice, data: ByteArray) {
    if (!subscribedDevices.contains(device)) {
      Log.w(TAG, "Device ${device.address} not in subscribedDevices; skipping notification per GATT spec §3.3.3.3")
      return
    }

    val char = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(TX_UUID) ?: return
    val negotiatedMtu = deviceMtus[device.address] ?: DEFAULT_ATT_MTU
    val packets = try {
      encodeChunks(data, negotiatedMtu)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to encode notification chunks", e)
      return
    }

    txMutex.withLock {
      for ((index, packet) in packets.withIndex()) {
        char.value = packet
        val sent = kotlinx.coroutines.CompletableDeferred<Int>()
        notifySent = sent
        val notified = try {
          gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
        } catch (e: Exception) {
          Log.w(TAG, "Exception during notifyCharacteristicChanged: ${e.message}")
          false
        }
        if (!notified) {
          Log.w(TAG, "notifyCharacteristicChanged returned false (buffer full), retrying after 25ms...")
          delay(25)
          val retried = try {
            gattServer?.notifyCharacteristicChanged(device, char, false) ?: false
          } catch (e: Exception) {
            Log.w(TAG, "Exception during retry notifyCharacteristicChanged: ${e.message}")
            false
          }
          if (!retried) {
            Log.w(TAG, "GATT notification packet dropped after retry for ${device.address}")
            continue
          }
        }
        // Like muse-gadget-everywhere's BleTransport: don't send the next packet until Android
        // confirms this one, or the stack silently drops it and the phone's reassembly stalls.
        val status = kotlinx.coroutines.withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { sent.await() }
        when {
          status == null -> Log.w(TAG, "No onNotificationSent for packet ${index + 1}/${packets.size} within ${NOTIFY_TIMEOUT_MS}ms")
          status != BluetoothGatt.GATT_SUCCESS -> Log.w(TAG, "Notification ${index + 1}/${packets.size} failed: status=$status")
        }
        if (index < packets.lastIndex) delay(CHUNK_STAGGER_MS)
      }
    }
  }

  fun resetPairing() {
    synchronized(pairingLock) {
      pairingWatchdogJob?.cancel()
      pairingWatchdogJob = null
      shutdownJob?.cancel()
      shutdownJob = null
      activePairingDevice = null
      ecdhSecret = null
      rxKey = null
      txKey = null
      sessionIdB64 = ""
      rxCounter.set(0)
      txCounter.set(0)
      pairingState = PairingState.IDLE
    }
    BridgeRepository.dismissPairingPrompt()
  }

  fun scheduleShutdownForTesting() {
    synchronized(pairingLock) {
      shutdownJob?.cancel()
      shutdownJob = scope.launch {
        delay(1500)
        stopAdvertisingInternal()
        synchronized(pairingLock) {
          shutdownJob = null
        }
        resetPairing()
      }
    }
  }

  fun isShutdownScheduledForTesting(): Boolean {
    return synchronized(pairingLock) {
      shutdownJob?.isActive == true
    }
  }

  fun confirmPairingFromUser() {
    scope.launch {
      val dev = synchronized(pairingLock) {
        if (pairingState == PairingState.CONFIRM_REQUIRED) activePairingDevice else null
      } ?: return@launch

      Log.i(TAG, "User confirmed pairing via touchscreen")
      sendPairingConfirmed(dev)
    }
  }

  fun declinePairingFromUser() {
    scope.launch {
      val dev = synchronized(pairingLock) {
        if (pairingState == PairingState.CONFIRM_REQUIRED) {
          pairingState = PairingState.IDLE
          val target = activePairingDevice
          activePairingDevice = null
          pairingWatchdogJob?.cancel()
          pairingWatchdogJob = null
          target
        } else {
          null
        }
      } ?: return@launch

      BridgeRepository.dismissPairingPrompt()
      Log.i(TAG, "User declined pairing via touchscreen")
      sendEncryptedStatus(dev, "error_pairing_rejected")
      resetPairing()
    }
  }

  suspend fun sendPairingConfirmed(device: BluetoothDevice) {
    val shouldSend = synchronized(pairingLock) {
      if ((pairingState == PairingState.CONFIRM_REQUIRED || pairingState == PairingState.READY) && activePairingDevice?.address == device.address) {
        pairingState = PairingState.READY
        true
      } else {
        false
      }
    }
    if (!shouldSend) return

    BridgeRepository.dismissPairingPrompt()

    val conf = JSONObject().apply {
      put("type", "status")
      put("status", "pairing_confirmed")
      val devToken = BridgeRepository.config.value.developerSdkToken.trim()
      // Per Meta Muse Gadget SDK pairing.py: sdk_token is ONLY included if an explicit developer SDK token is configured.
      // OAuth access tokens from mobile provision_v2 are NEVER forwarded back to the client as sdk_token.
      if (com.portal.pebblebridge.model.isValidDeveloperSdkToken(devToken)) {
        put("sdk_token", devToken)
      }
    }
    sendEncryptedJson(device, conf)
    armPairingWatchdog()
    Log.i(TAG, "Sent pairing_confirmed to Muse app (hasDevToken=${conf.has("sdk_token")})")
  }

  fun armPairingWatchdog() {
    synchronized(pairingLock) {
      pairingWatchdogJob?.cancel()
      pairingWatchdogJob = scope.launch {
        delay(PAIRING_TIMEOUT_MS)
        synchronized(pairingLock) {
          if (pairingState != PairingState.IDLE) {
            Log.w(TAG, "Pairing watchdog timed out after $PAIRING_TIMEOUT_MS ms; resetting pairing session")
            resetPairing()
          }
        }
      }
    }
  }

  fun setPairingStateForTesting(state: PairingState) {
    synchronized(pairingLock) {
      pairingState = state
    }
  }

  fun setActivePairingDeviceForTesting(device: BluetoothDevice?) {
    synchronized(pairingLock) {
      activePairingDevice = device
    }
  }

  private fun buildTranscript(
    deviceId: String,
    nodeId: String,
    mac: String,
    firmwareVersion: String,
    mobilePub: String,
    devicePub: String,
    mobileNonce: String,
    deviceNonce: String,
  ): String {
    return listOf(
      "hatch-link-pairing-v5",
      "version=5",
      "initiator_role=mobile",
      "responder_role=link",
      "device_id=$deviceId",
      "node_id=$nodeId",
      "mac=$mac",
      "model=hatch_link",
      "firmware_version=$firmwareVersion",
      "selected_cipher_suite=p256-hkdf-sha256-aes-gcm-v1",
      "pairing_auth=none",
      "pairing_auth_epoch=0",
      "pairing_policy=confirm_app",
      "confirm_timeout_seconds=0",
      "mobile_pub=$mobilePub",
      "device_pub=$devicePub",
      "mobile_nonce=$mobileNonce",
      "device_nonce=$deviceNonce",
    ).joinToString("\n")
  }

  private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    return mac.doFinal(ikm)
  }

  private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    mac.update(info)
    mac.update(1.toByte())
    return mac.doFinal().copyOfRange(0, length)
  }

  private fun base64Url(data: ByteArray): String {
    return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
  }

  private fun ByteArray.stripLeadingZeros(targetLength: Int): ByteArray {
    var start = 0
    while (start < size && this[start] == 0.toByte() && size - start > targetLength) {
      start++
    }
    val res = ByteArray(targetLength)
    val copyLen = minOf(targetLength, size - start)
    System.arraycopy(this, start, res, targetLength - copyLen, copyLen)
    return res
  }
}
