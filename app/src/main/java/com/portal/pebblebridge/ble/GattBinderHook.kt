package com.portal.pebblebridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

object GattBinderHook {
  private const val TAG = "GattBinderHook"

  @Volatile
  private var rawBinder: IBinder? = null

  @Volatile
  private var iBluetoothGatt: Any? = null

  @Volatile
  private var isHooked = false

  @Volatile
  private var isConnecting = false

  private var serviceConnection: ServiceConnection? = null
  private var originalManager: Any? = null
  private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

  val isAvailable: Boolean
    get() = iBluetoothGatt != null

  fun getGatt(): Any? = iBluetoothGatt

  /**
   * Binds directly to com.android.bluetooth.gatt.GattService which is exported
   * on Android 9 without permission requirements, bypassing the framework's check
   * for FEATURE_BLUETOOTH_LE.
   */
  @Synchronized
  fun install(context: Context, onReady: (Boolean) -> Unit) {
    if (isHooked && iBluetoothGatt != null) {
      Log.i(TAG, "GattBinderHook already active and hooked")
      onReady(true)
      return
    }

    pendingCallbacks.add(onReady)

    if (isConnecting) {
      Log.i(TAG, "GattBinderHook connection already in flight, enqueued listener (queue size=${pendingCallbacks.size})")
      return
    }

    // Clean up any dangling connection before rebinding
    serviceConnection?.let { oldConn ->
      try {
        context.unbindService(oldConn)
      } catch (_: Exception) {}
      serviceConnection = null
    }

    isConnecting = true
    val intent = Intent("android.bluetooth.IBluetoothGatt").apply {
      component = ComponentName("com.android.bluetooth", "com.android.bluetooth.gatt.GattService")
    }

    val conn = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.i(TAG, "Connected to com.android.bluetooth.gatt.GattService! IBinder: $service")
        val callbacks: List<(Boolean) -> Unit>
        var success = false

        synchronized(GattBinderHook) {
          isConnecting = false
          if (service != null) {
            rawBinder = service
            try {
              val stubClass = Class.forName("android.bluetooth.IBluetoothGatt\$Stub")
              val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
              val gatt = asInterface.invoke(null, service)
              iBluetoothGatt = gatt
              Log.i(TAG, "Obtained IBluetoothGatt interface proxy: $gatt")

              hookBluetoothAdapter()
              isHooked = true
              success = true
            } catch (e: Exception) {
              Log.e(TAG, "Failed to resolve IBluetoothGatt from binder", e)
            }
          } else {
            Log.e(TAG, "Received null binder from GattService")
          }
          callbacks = pendingCallbacks.toList()
          pendingCallbacks.clear()
        }

        callbacks.forEach { it(success) }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.w(TAG, "Disconnected from GattService")
        synchronized(GattBinderHook) {
          isConnecting = false
          rawBinder = null
          iBluetoothGatt = null
          isHooked = false
        }
      }
    }

    serviceConnection = conn
    try {
      val bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
      Log.i(TAG, "context.bindService(GattService) returned: $bound")
      if (!bound) {
        val callbacks: List<(Boolean) -> Unit>
        synchronized(GattBinderHook) {
          isConnecting = false
          callbacks = pendingCallbacks.toList()
          pendingCallbacks.clear()
        }
        callbacks.forEach { it(false) }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Exception binding to GattService", e)
      val callbacks: List<(Boolean) -> Unit>
      synchronized(GattBinderHook) {
        isConnecting = false
        callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
      }
      callbacks.forEach { it(false) }
    }
  }

  @Synchronized
  fun uninstall(context: Context) {
    val callbacks: List<(Boolean) -> Unit>
    synchronized(GattBinderHook) {
      callbacks = pendingCallbacks.toList()
      pendingCallbacks.clear()
    }
    callbacks.forEach { it(false) }

    serviceConnection?.let { conn ->
      try {
        context.unbindService(conn)
        Log.i(TAG, "Successfully unbound from GattService")
      } catch (e: Exception) {
        Log.w(TAG, "Error unbinding GattService: ${e.message}")
      }
    }
    serviceConnection = null
    rawBinder = null
    iBluetoothGatt = null
    isHooked = false
    isConnecting = false

    // Restore original IBluetoothManager in BluetoothAdapter to avoid proxy leak
    originalManager?.let { orig ->
      try {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) {
          val mManagerServiceField = BluetoothAdapter::class.java.getDeclaredField("mManagerService").apply {
            isAccessible = true
          }
          mManagerServiceField.set(adapter, orig)
          Log.i(TAG, "Restored original IBluetoothManager in BluetoothAdapter")
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to restore original IBluetoothManager: ${e.message}")
      }
      originalManager = null
    }
  }

  /**
   * Injects a dynamic proxy for IBluetoothManager into BluetoothAdapter
   * so any framework call to mManagerService.getBluetoothGatt() dynamically resolves our hooked instance.
   */
  @Synchronized
  private fun hookBluetoothAdapter() {
    try {
      val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
      val adapterClass = BluetoothAdapter::class.java

      val mManagerServiceField = adapterClass.getDeclaredField("mManagerService").apply {
        isAccessible = true
      }

      // Preserve root non-proxy manager to prevent unbounded nested proxy chaining
      if (originalManager == null) {
        originalManager = mManagerServiceField.get(adapter)
      }
      val realManager = originalManager ?: return
      val iBluetoothManagerClass = Class.forName("android.bluetooth.IBluetoothManager")

      val proxy = Proxy.newProxyInstance(
        iBluetoothManagerClass.classLoader,
        arrayOf(iBluetoothManagerClass),
        object : InvocationHandler {
          override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.name == "getBluetoothGatt") {
              val currentGatt = getGatt()
              if (currentGatt != null) {
                return currentGatt
              }
            }
            return try {
              if (args == null) {
                method.invoke(realManager)
              } else {
                method.invoke(realManager, *args)
              }
            } catch (e: InvocationTargetException) {
              throw e.targetException ?: e
            } catch (e: Exception) {
              Log.e(TAG, "Error invoking IBluetoothManager.${method.name}", e)
              when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> ' '
                else -> null
              }
            }
          }
        }
      )

      mManagerServiceField.set(adapter, proxy)
      Log.i(TAG, "Successfully injected IBluetoothManager dynamic proxy into BluetoothAdapter")

      // Clear static & instance advertiser caches so they recreate with the proxied manager
      for (fieldName in listOf("sBluetoothLeAdvertiser", "mBluetoothLeAdvertiser")) {
        try {
          val f = adapterClass.getDeclaredField(fieldName).apply { isAccessible = true }
          f.set(if (Modifier.isStatic(f.modifiers)) null else adapter, null)
        } catch (_: Exception) {}
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to hook BluetoothAdapter", e)
    }
  }

  /**
   * Direct fallback to create a BluetoothGattServer using the hooked IBluetoothGatt interface.
   * MUST be called from a background thread to prevent UI thread wait() timeouts.
   */
  fun openGattServerDirect(
    callback: BluetoothGattServerCallback
  ): BluetoothGattServer? {
    val gatt = iBluetoothGatt ?: run {
      Log.e(TAG, "Cannot open GATT server: IBluetoothGatt is null")
      return null
    }

    return try {
      val iBluetoothGattClass = Class.forName("android.bluetooth.IBluetoothGatt")
      val ctor = BluetoothGattServer::class.java.getDeclaredConstructor(
        iBluetoothGattClass,
        Int::class.javaPrimitiveType
      ).apply {
        isAccessible = true
      }

      val transportLe = 2 // BluetoothDevice.TRANSPORT_LE
      val server = ctor.newInstance(gatt, transportLe) as BluetoothGattServer

      val regMethod = BluetoothGattServer::class.java.getDeclaredMethod(
        "registerCallback",
        BluetoothGattServerCallback::class.java
      ).apply {
        isAccessible = true
      }

      val registered = regMethod.invoke(server, callback) as? Boolean ?: false
      if (registered) {
        Log.i(TAG, "Successfully constructed and registered BluetoothGattServer directly via reflection!")
        server
      } else {
        Log.e(TAG, "registerCallback returned false on direct BluetoothGattServer")
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to construct BluetoothGattServer directly", e)
      null
    }
  }
}
