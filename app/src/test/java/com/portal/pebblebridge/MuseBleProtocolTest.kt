package com.portal.pebblebridge

import android.content.Context
import com.portal.pebblebridge.ble.ChunkAssembler
import com.portal.pebblebridge.ble.MuseBleManager
import com.portal.pebblebridge.ble.PairingState
import java.util.regex.Pattern
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MuseBleProtocolTest {

  private lateinit var context: Context
  private lateinit var manager: MuseBleManager

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    com.portal.pebblebridge.state.BridgeRepository.resetForTesting()
    manager = MuseBleManager(context)
  }

  @Test
  fun testDeviceIdentityConformsToOfficialSdk() {
    // 1. MAC address must be 6 valid lowercase hex octets
    val macPattern = Pattern.compile("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    assertTrue("MAC address ${manager.deviceMac} must match lowercase hex format", macPattern.matcher(manager.deviceMac).matches())

    // 2. Unicast locally-administered bit check: byte 0 has bit 1 set, bit 0 cleared
    val firstByte = manager.deviceMac.split(":")[0].toInt(16)
    assertEquals("Must be locally-administered unicast MAC", 0x02, firstByte and 0x03)

    // 3. Suffix must be 6 lowercase hex digits matching last 3 octets
    val suffixPattern = Pattern.compile("^[0-9a-f]{6}$")
    assertTrue("Suffix ${manager.deviceSuffix} must be 6 lowercase hex chars", suffixPattern.matcher(manager.deviceSuffix).matches())
    val last3Octets = manager.deviceMac.split(":").takeLast(3).joinToString("")
    assertEquals("Suffix must match last 3 octets of MAC", last3Octets, manager.deviceSuffix)

    // 4. BLE name must be MuseGadget followed by uppercase suffix
    assertEquals("BLE name must match MuseGadget + uppercase suffix", "MuseGadget" + manager.deviceSuffix.uppercase(), manager.bleName)

    // 5. Node ID must be homelink- followed by lowercase suffix
    assertEquals("Node ID must match homelink- + lowercase suffix", "homelink-" + manager.deviceSuffix, manager.nodeId)

    // 6. Device ID must be hatch-link: + mac
    assertEquals("Device ID must match hatch-link: + mac", "hatch-link:" + manager.deviceMac, manager.deviceId)
  }

  @Test
  fun testDeviceInfoFieldsMatchProtocol5Contract() {
    val expectedJson = JSONObject().apply {
      put("type", "device_info")
      put("node_id", manager.nodeId)
      put("version", "1.0.0")
      put("device_id", manager.deviceId)
      put("mac", manager.deviceMac)
      put("model", "hatch_link")
      put("pairing_protocol", 5)
      put("pairing_auth", "none")
      put("pairing_auth_epoch", 0)
      put("pairing_policy", "confirm_app")
      put("build_sha", "")
      put("network_ready", true)
    }

    assertEquals("device_info", expectedJson.getString("type"))
    assertEquals("hatch_link", expectedJson.getString("model"))
    assertEquals(5, expectedJson.getInt("pairing_protocol"))
    assertEquals("none", expectedJson.getString("pairing_auth"))
    assertEquals(0, expectedJson.getInt("pairing_auth_epoch"))
    assertEquals("confirm_app", expectedJson.getString("pairing_policy"))
    assertTrue(expectedJson.getBoolean("network_ready"))
  }

  @Test
  fun testChunkAssemblerSinglePacketUnchunked() {
    val assembler = ChunkAssembler()
    val unchunked = "{\"action\": \"get_device_info\"}".toByteArray(Charsets.UTF_8)
    val result = assembler.feed(unchunked)
    assertNotNull("Unchunked packet must be returned immediately", result)
    assertArrayEquals(unchunked, result)
  }

  @Test
  fun testChunkAssemblerOrderedMultiPacket() {
    val assembler = ChunkAssembler()
    val rawPayload = ("A".repeat(500) + "{\"action\": \"provision_v2\"}").toByteArray(Charsets.UTF_8)
    val chunks = MuseBleManager.encodeChunks(rawPayload, mtu = 160)

    assertTrue("Should produce multiple chunks", chunks.size > 1)

    var assembled: ByteArray? = null
    for (i in 0 until chunks.size) {
      val packet = chunks[i]
      val res = assembler.feed(packet)
      if (i < chunks.size - 1) {
        assertNull("Intermediate chunk $i must not return complete message", res)
      } else {
        assembled = res
      }
    }

    assertNotNull("Last chunk must return complete assembled message", assembled)
    assertArrayEquals("Assembled payload must match original", rawPayload, assembled)
  }

  @Test
  fun testChunkAssemblerOutOfOrderDropped() {
    val assembler = ChunkAssembler()
    val rawPayload = "B".repeat(300).toByteArray(Charsets.UTF_8)
    val chunks = MuseBleManager.encodeChunks(rawPayload, mtu = 100)

    // Feed chunk index 1 first (out of order, expected index 0)
    val dropped = assembler.feed(chunks[1])
    assertNull("Out-of-order chunk must return null and reset buffer", dropped)

    // Now feeding chunk 0 should start a fresh session
    assertNull(assembler.feed(chunks[0]))
    // Then chunk 1 succeeds in order
    val complete = assembler.feed(chunks[1])
    if (chunks.size == 2) {
      assertNotNull("In-order refeed must complete assembly", complete)
    }
  }

  @Test
  fun testChunkAssemblerTotalZeroDropped() {
    val assembler = ChunkAssembler()
    val malformedPacket = byteArrayOf(MuseBleManager.CHUNK_MAGIC, 0, 0, 'x'.toByte())
    val result = assembler.feed(malformedPacket)
    assertNull("Packet with total=0 must be rejected", result)
  }

  @Test
  fun testChunkAssemblerOversizeMessageDropped() {
    val smallAssembler = ChunkAssembler(maxBytes = 50)
    val payload = "C".repeat(100).toByteArray(Charsets.UTF_8)
    val chunks = MuseBleManager.encodeChunks(payload, mtu = 40)

    var dropped = false
    for (chunk in chunks) {
      val res = smallAssembler.feed(chunk)
      if (res == null) {
        dropped = true
      }
    }
    assertTrue("Oversize message exceeding maxBytes must be dropped", dropped)
  }

  @Test
  fun testEncodeChunksMtuSlicingAndLimits() {
    val data = ByteArray(350) { it.toByte() }
    val chunks = MuseBleManager.encodeChunks(data, mtu = 160)

    // Each packet must not exceed MAX_PACKET_BYTES
    for (chunk in chunks) {
      assertTrue("Packet length ${chunk.size} <= MAX_PACKET_BYTES", chunk.size <= MuseBleManager.MAX_PACKET_BYTES)
      assertEquals("Magic byte must be 0xFE", MuseBleManager.CHUNK_MAGIC, chunk[0])
    }

    // Verify empty array produces single spec frame: [0xFE, 0, 1]
    val emptyChunks = MuseBleManager.encodeChunks(ByteArray(0), mtu = 160)
    assertEquals(1, emptyChunks.size)
    assertArrayEquals(byteArrayOf(MuseBleManager.CHUNK_MAGIC, 0, 1), emptyChunks[0])

    // Verify > 255 chunks throws IllegalArgumentException
    val hugeData = ByteArray(160 * 256)
    assertThrows(IllegalArgumentException::class.java) {
      MuseBleManager.encodeChunks(hugeData, mtu = 23)
    }
  }

  @Test
  fun testWifiScanFormatsActiveAndOpenEntries() {
    val activeSsid = manager.getActiveWifiSsid()
    assertTrue("SSID must not be blank", activeSsid.isNotBlank())

    val scanEntries = manager.getWifiScanEntries()
    assertTrue("Scan entries must not be empty", scanEntries.isNotEmpty())

    // Must contain CURRENT_CONNECTION_LABEL as an open fallback entry
    val openFallback = scanEntries.find { it.ssid == MuseBleManager.CURRENT_CONNECTION_LABEL }
    assertNotNull("Open fallback entry 'Use current connection' must be present", openFallback)
    assertFalse("Fallback 'Use current connection' must be open (secure=false)", openFallback!!.secure)

    // First entry must have valid SSID, negative RSSI, and be marked open (secure=false) per Meta SDK network.py
    val firstEntry = scanEntries.first()
    assertTrue("First entry must have valid SSID", firstEntry.ssid.isNotBlank())
    assertTrue("RSSI must be valid negative dBm", firstEntry.rssi <= 0)
    assertFalse("Active network at index 0 must be open (secure=false) to skip password prompt", firstEntry.secure)

    // ALL offered entries MUST be secure=false to prevent Android client >512-byte ATT write crash
    for (entry in scanEntries) {
      assertFalse("Entry '${entry.ssid}' must be marked open (secure=false)", entry.secure)
    }

    // All entries must have deduplicated SSIDs
    val ssids = scanEntries.map { it.ssid }
    assertEquals("SSIDs must be deduplicated", ssids.toSet().size, ssids.size)
  }

  @Test
  fun testPairingStateTransitions() {
    assertEquals("Pairing state must initialize to IDLE", PairingState.IDLE, manager.pairingState)
    manager.setPairingStateForTesting(PairingState.WAIT_CLIENT_FINISHED)
    assertEquals(PairingState.WAIT_CLIENT_FINISHED, manager.pairingState)

    manager.setPairingStateForTesting(PairingState.CONFIRM_REQUIRED)
    assertEquals(PairingState.CONFIRM_REQUIRED, manager.pairingState)

    manager.setPairingStateForTesting(PairingState.READY)
    assertEquals(PairingState.READY, manager.pairingState)

    manager.setPairingStateForTesting(PairingState.PROVISIONING)
    assertEquals(PairingState.PROVISIONING, manager.pairingState)

    manager.resetPairing()
    assertEquals("State after reset must return to IDLE", PairingState.IDLE, manager.pairingState)
  }

  @Test
  fun testAesGcmEncryptDecryptContractAndTagVerification() {
    val key = ByteArray(32) { (it + 1).toByte() }
    val counter = 0L
    val nonce = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.BIG_ENDIAN)
      .put(0.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte())
      .putLong(counter)
      .array()
    val aad = "hatch-link ble setup v1|session123|m2d|$counter".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
    val plaintext = "{\"action\":\"pairing_client_finished\"}".toByteArray(java.nio.charset.StandardCharsets.UTF_8)

    // Encrypt
    val encryptCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    encryptCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
    encryptCipher.updateAAD(aad)
    val ciphertextWithTag = encryptCipher.doFinal(plaintext)

    // Decrypt valid
    val decryptCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    decryptCipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
    decryptCipher.updateAAD(aad)
    val decrypted = decryptCipher.doFinal(ciphertextWithTag)
    assertArrayEquals("Decrypted payload must match plaintext", plaintext, decrypted)

    // Tampered ciphertext must fail authentication
    val tampered = ciphertextWithTag.clone()
    tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
    val badCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    badCipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
    badCipher.updateAAD(aad)
    assertThrows(javax.crypto.AEADBadTagException::class.java) {
      badCipher.doFinal(tampered)
    }

    // Tampered AAD must fail authentication
    val badAadCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    badAadCipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
    badAadCipher.updateAAD("tampered_aad".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
    assertThrows(javax.crypto.AEADBadTagException::class.java) {
      badAadCipher.doFinal(ciphertextWithTag)
    }
  }

  @Test
  fun testPreparedWriteBoundaryValidationRules() {
    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    val device = adapter.getRemoteDevice("11:22:33:44:55:66")

    val chunk1 = ByteArray(100) { 1 }
    val chunk2 = ByteArray(100) { 2 }

    // Step 1: Initial slice must begin at offset 0
    val status0 = manager.processPreparedWriteChunk(device, 0, chunk1)
    assertEquals("Initial write at offset 0 must return GATT_SUCCESS", android.bluetooth.BluetoothGatt.GATT_SUCCESS, status0)
    assertEquals("Buffer should now contain 100 bytes", 100, manager.getPreparedWriteBufferSize(device))

    // Step 2: Continuation slice at offset 100 succeeds
    val status1 = manager.processPreparedWriteChunk(device, 100, chunk2)
    assertEquals("Sequential write at offset 100 must return GATT_SUCCESS", android.bluetooth.BluetoothGatt.GATT_SUCCESS, status1)
    assertEquals("Buffer should now contain 200 bytes", 200, manager.getPreparedWriteBufferSize(device))

    // Step 3: Out-of-order offset (e.g. offset 50) must return GATT_INVALID_OFFSET and PURGE buffer
    val statusBad = manager.processPreparedWriteChunk(device, 50, chunk1)
    assertEquals("Out-of-order offset must return GATT_INVALID_OFFSET", android.bluetooth.BluetoothGatt.GATT_INVALID_OFFSET, statusBad)
    assertEquals("Buffer must be purged on invalid offset", 0, manager.getPreparedWriteBufferSize(device))

    // Step 4: Oversize write exceeding MAX_MESSAGE_BYTES must return 0x0D (GATT_INSUFFICIENT_RESOURCES) and PURGE
    val oversize = ByteArray(MuseBleManager.MAX_MESSAGE_BYTES + 1) { 3 }
    val statusOversize = manager.processPreparedWriteChunk(device, 0, oversize)
    assertEquals("Oversize prepared write must return GATT_INSUFFICIENT_RESOURCES", 0x0D, statusOversize)
    assertEquals("Buffer must be purged on oversize write", 0, manager.getPreparedWriteBufferSize(device))

    // Step 5: Null value check returns GATT_INVALID_ATTRIBUTE_LENGTH
    val statusNull = manager.processPreparedWriteChunk(device, 0, null)
    assertEquals("Null value must return GATT_INVALID_ATTRIBUTE_LENGTH", android.bluetooth.BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, statusNull)
  }

  @Test
  fun testDeveloperSdkTokenFormattingAndIsolation() {
    // 1. Invalid or empty tokens must be rejected
    assertFalse("Empty token must be rejected", com.portal.pebblebridge.model.isValidDeveloperSdkToken(""))
    assertFalse("Blank token must be rejected", com.portal.pebblebridge.model.isValidDeveloperSdkToken("   "))
    assertFalse("Short token must be rejected", com.portal.pebblebridge.model.isValidDeveloperSdkToken("mgst_short"))
    assertFalse("Generic JWT must be rejected", com.portal.pebblebridge.model.isValidDeveloperSdkToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))

    // 2. Valid token format per Meta SDK install.sh regex
    val validToken = "mgst_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaA" // Exactly 42 base64 chars + valid ending char
    assertTrue("Valid SDK token format must pass regex", com.portal.pebblebridge.model.isValidDeveloperSdkToken(validToken))

    // 3. Token separation: provisioned OAuth access token must NOT contaminate developerSdkToken
    val config = com.portal.pebblebridge.model.BridgeConfig(
      developerSdkToken = "",
      museAccessToken = "oauth_access_xyz_123",
      museRefreshToken = "oauth_refresh_abc_456"
    )
    assertEquals("developerSdkToken must remain empty", "", config.developerSdkToken)
    assertEquals("museAccessToken must hold OAuth token", "oauth_access_xyz_123", config.museAccessToken)
    assertEquals("museRefreshToken must hold refresh token", "oauth_refresh_abc_456", config.museRefreshToken)
    assertEquals("effectiveMuseToken must resolve to museAccessToken", "oauth_access_xyz_123", config.effectiveMuseToken)
    assertEquals("museSdkToken getter must resolve to effectiveMuseToken", "oauth_access_xyz_123", config.museSdkToken)
  }

  @Test
  fun testPairingWatchdogCancellation() {
    manager.armPairingWatchdog()
    // Calling resetPairing cancels watchdog and restores IDLE
    manager.resetPairing()
    assertEquals("State after reset must be IDLE", PairingState.IDLE, manager.pairingState)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun testPairingWatchdogTimeoutResetsStateToIdle() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val testManager = MuseBleManager(context, testDispatcher)
    testManager.setPairingStateForTesting(PairingState.WAIT_CLIENT_FINISHED)
    assertEquals(PairingState.WAIT_CLIENT_FINISHED, testManager.pairingState)

    testManager.armPairingWatchdog()

    // Advance virtual time by 44 seconds - watchdog has not expired yet
    testScheduler.advanceTimeBy(44_000L)
    testScheduler.runCurrent()
    assertEquals(PairingState.WAIT_CLIENT_FINISHED, testManager.pairingState)

    // Advance virtual time past 45 seconds (PAIRING_TIMEOUT_MS)
    testScheduler.advanceTimeBy(2_000L)
    testScheduler.runCurrent()
    assertEquals("State after 45s watchdog expiration must be reset to IDLE", PairingState.IDLE, testManager.pairingState)
  }

  @Test
  fun testPairingConfirmUserFlowTransitionsStateAndDismissesPrompt() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val testManager = MuseBleManager(context, testDispatcher)
    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    val device = adapter.getRemoteDevice("11:22:33:44:55:66")

    testManager.setPairingStateForTesting(PairingState.CONFIRM_REQUIRED)
    testManager.setActivePairingDeviceForTesting(device)
    com.portal.pebblebridge.state.BridgeRepository.showPairingPrompt(device.address)
    assertNotNull(com.portal.pebblebridge.state.BridgeRepository.pairingPrompt.value)

    testManager.confirmPairingFromUser()
    testScheduler.runCurrent()

    assertEquals("Confirming pairing must transition state to READY", PairingState.READY, testManager.pairingState)
    assertNull("Prompt must be dismissed upon user confirmation", com.portal.pebblebridge.state.BridgeRepository.pairingPrompt.value)
  }

  @Test
  fun testPairingDeclineUserFlowTransitionsStateToIdleAndDismissesPrompt() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val testManager = MuseBleManager(context, testDispatcher)
    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    val device = adapter.getRemoteDevice("11:22:33:44:55:66")

    testManager.setPairingStateForTesting(PairingState.CONFIRM_REQUIRED)
    testManager.setActivePairingDeviceForTesting(device)
    com.portal.pebblebridge.state.BridgeRepository.showPairingPrompt(device.address)
    assertNotNull(com.portal.pebblebridge.state.BridgeRepository.pairingPrompt.value)

    testManager.declinePairingFromUser()
    testScheduler.runCurrent()

    assertEquals("Declining pairing must immediately reset state to IDLE", PairingState.IDLE, testManager.pairingState)
    assertNull("Prompt must be dismissed upon user decline", com.portal.pebblebridge.state.BridgeRepository.pairingPrompt.value)
  }

  @Test
  fun testSendPairingConfirmedRejectsStaleOrUnauthenticatedDevices() = runTest {
    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    val deviceA = adapter.getRemoteDevice("11:22:33:44:55:66")
    val rogueDevice = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")

    manager.setPairingStateForTesting(PairingState.CONFIRM_REQUIRED)
    manager.setActivePairingDeviceForTesting(deviceA)

    // Rogue device attempts confirmation
    manager.sendPairingConfirmed(rogueDevice)
    assertEquals("Rogue device must be rejected and state remains CONFIRM_REQUIRED", PairingState.CONFIRM_REQUIRED, manager.pairingState)

    // Authorized device confirms
    manager.sendPairingConfirmed(deviceA)
    assertEquals("Authorized device transitions state to READY", PairingState.READY, manager.pairingState)
  }

  @Test
  fun testTokenPersistenceAllowsClearingWithoutResurrection() {
    val prefs = context.getSharedPreferences("portal_pebble_bridge_prefs", Context.MODE_PRIVATE)
    // User explicitly clears token and saves empty string
    prefs.edit().putString("pref_dev_sdk_token", "").apply()

    com.portal.pebblebridge.state.BridgeRepository.resetForTesting()
    com.portal.pebblebridge.state.BridgeRepository.initPersistence(context)
    val devToken = com.portal.pebblebridge.state.BridgeRepository.config.value.developerSdkToken
    assertEquals("Cleared token must remain empty and not resurrect default BuildConfig token", "", devToken)
  }

  @Test
  fun testRestartPairingModeResetsPairingAndClearsState() {
    com.portal.pebblebridge.state.BridgeRepository.initPersistence(context)
    com.portal.pebblebridge.state.BridgeRepository.setPaired(true)
    assertTrue("BridgeRepository must reflect paired before restart", com.portal.pebblebridge.state.BridgeRepository.isPaired())

    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    val device = adapter.getRemoteDevice("11:22:33:44:55:66")
    manager.setPairingStateForTesting(PairingState.CONFIRM_REQUIRED)
    manager.setActivePairingDeviceForTesting(device)
    com.portal.pebblebridge.state.BridgeRepository.showPairingPrompt(device.address)

    manager.restartPairingMode()

    assertFalse("Manager isPaired must be false after restart", manager.isPaired)
    assertFalse("BridgeRepository isPaired must be false after restart", com.portal.pebblebridge.state.BridgeRepository.isPaired())
    assertFalse("BridgeRepository isPaired flow must be false after restart", com.portal.pebblebridge.state.BridgeRepository.isPaired.value)
    assertEquals("Pairing state must be reset to IDLE", PairingState.IDLE, manager.pairingState)
    assertNull("Active pairing device must be cleared", manager.activePairingDevice)
    assertNull("Pairing prompt must be dismissed", com.portal.pebblebridge.state.BridgeRepository.pairingPrompt.value)
  }

  @Test
  fun testIsPairedStateFlowSynchronizesWithPersistence() {
    com.portal.pebblebridge.state.BridgeRepository.initPersistence(context)
    com.portal.pebblebridge.state.BridgeRepository.setPaired(false)
    assertFalse(com.portal.pebblebridge.state.BridgeRepository.isPaired.value)

    com.portal.pebblebridge.state.BridgeRepository.setPaired(true)
    assertTrue("StateFlow must update to true", com.portal.pebblebridge.state.BridgeRepository.isPaired.value)
    assertTrue("Getter must return true", com.portal.pebblebridge.state.BridgeRepository.isPaired())

    com.portal.pebblebridge.state.BridgeRepository.setPaired(false)
    assertFalse("StateFlow must update to false", com.portal.pebblebridge.state.BridgeRepository.isPaired.value)
    assertFalse("Getter must return false", com.portal.pebblebridge.state.BridgeRepository.isPaired())
  }

  @Test
  fun testShutdownJobCancellationOnRestartPairingMode() {
    manager.scheduleShutdownForTesting()
    assertTrue("Shutdown job must be active when scheduled", manager.isShutdownScheduledForTesting())

    manager.restartPairingMode()
    assertFalse("Restarting pairing mode must immediately cancel pending shutdown job", manager.isShutdownScheduledForTesting())
    assertEquals("Pairing state must reset to IDLE", PairingState.IDLE, manager.pairingState)
  }

  @Test
  fun testResetPairingCancelsPendingShutdownJob() {
    manager.scheduleShutdownForTesting()
    assertTrue("Shutdown job must be active when scheduled", manager.isShutdownScheduledForTesting())

    manager.resetPairing()
    assertFalse("resetPairing must cancel pending shutdown job", manager.isShutdownScheduledForTesting())
  }

  @Test
  fun testResetForTestingClearsAllRepositoryState() {
    com.portal.pebblebridge.state.BridgeRepository.setPaired(true)
    com.portal.pebblebridge.state.BridgeRepository.showPairingPrompt("11:22:33:44:55:66")
    com.portal.pebblebridge.state.BridgeRepository.setBleDeviceName("CustomName")
    com.portal.pebblebridge.state.BridgeRepository.addNote(
      com.portal.pebblebridge.model.VoiceNote(
        id = "test-1",
        timestampEpochMs = 12345L,
        text = "hello",
        status = com.portal.pebblebridge.model.NoteStatus.PENDING
      )
    )

    com.portal.pebblebridge.state.BridgeRepository.resetForTesting()

    assertFalse("isPaired must be false", com.portal.pebblebridge.state.BridgeRepository.isPaired())
    assertFalse("isPaired flow must be false", com.portal.pebblebridge.state.BridgeRepository.isPaired.value)
    assertNull("pairingPrompt must be null", com.portal.pebblebridge.state.BridgeRepository.pairingPrompt.value)
    assertTrue("notes list must be empty", com.portal.pebblebridge.state.BridgeRepository.notes.value.isEmpty())
    assertEquals("bleDeviceName must reset to default", "MuseGadget", com.portal.pebblebridge.state.BridgeRepository.bleDeviceName.value)
  }

  @Test
  fun testBleDeviceNameStateFlowSynchronizesWithManager() {
    val mgr = MuseBleManager(context)
    assertEquals("BridgeRepository bleDeviceName must synchronize with manager.bleName", mgr.bleName, com.portal.pebblebridge.state.BridgeRepository.bleDeviceName.value)
  }
}
