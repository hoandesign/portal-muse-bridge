package com.portal.pebblebridge

import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.model.LinkState
import com.portal.pebblebridge.model.NoteStatus
import com.portal.pebblebridge.model.VoiceNote
import com.portal.pebblebridge.muse.ApplicationRequest
import com.portal.pebblebridge.muse.ApplicationResponse
import com.portal.pebblebridge.muse.BodyChunk
import com.portal.pebblebridge.muse.CipherState
import com.portal.pebblebridge.muse.MuseDeliveryClient
import com.portal.pebblebridge.muse.MuseLinkClient
import com.portal.pebblebridge.muse.NoiseHeader
import com.portal.pebblebridge.muse.NoiseProtocolException
import com.portal.pebblebridge.muse.NoiseTransport
import com.portal.pebblebridge.muse.NoiseTransportFrame
import com.portal.pebblebridge.muse.NoiseXXInitiator
import com.portal.pebblebridge.muse.ProtoWire
import com.portal.pebblebridge.muse.ServiceFrame
import com.portal.pebblebridge.muse.ServiceFramePayload
import com.portal.pebblebridge.muse.ServiceRequest
import com.portal.pebblebridge.state.BridgeRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MuseNoiseProtocolTest {

  @Before
  fun setUp() {
    BridgeRepository.resetForTesting()
  }

  @Test
  fun testProtoWireVarintRoundtrip() {
    val testValues = longArrayOf(0L, 1L, 127L, 128L, 300L, 16384L, 2097151L, 0x7FFFFFFFFFFFFFFFL)
    for (v in testValues) {
      val encoded = ProtoWire.encodeVarint(v)
      val (decoded, readBytes) = ProtoWire.readVarint(encoded, 0)
      assertEquals(v, decoded)
      assertEquals(encoded.size, readBytes)
    }
  }

  @Test
  fun testProtoWireDelimitedFieldRoundtrip() {
    val payload = "Hello Meta Muse AI via Pebble Index Ring".toByteArray(StandardCharsets.UTF_8)
    val field = ProtoWire.delimitedField(1, payload)
    val (fNum, wType, nextOffset) = ProtoWire.readKey(field, 0)
    assertEquals(1, fNum)
    assertEquals(2, wType)
    val (decoded, endOffset) = ProtoWire.readDelimited(field, nextOffset)
    assertArrayEquals(payload, decoded)
    assertEquals(field.size, endOffset)
  }

  @Test
  fun testCipherStateEncryptionDecryptionAndTamperRejection() {
    val key = ByteArray(32) { (it + 7).toByte() }
    val cipherEnc = CipherState().apply { initializeKey(key) }
    val cipherDec = CipherState().apply { initializeKey(key) }

    val ad = "ad-authenticated-data".toByteArray(StandardCharsets.UTF_8)
    val plaintext = "Secret message payload to leased VM".toByteArray(StandardCharsets.UTF_8)

    val ciphertext = cipherEnc.encryptWithAd(ad, plaintext)
    val decrypted = cipherDec.decryptWithAd(ad, ciphertext)
    assertArrayEquals(plaintext, decrypted)

    // Verify tamper rejection
    val tampered = ciphertext.copyOf()
    tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
    val corruptDec = CipherState().apply { initializeKey(key) }
    try {
      corruptDec.decryptWithAd(ad, tampered)
      fail("Decryption should fail on tampered ciphertext tag")
    } catch (_: NoiseProtocolException) {
      // Expected
    }
  }

  @Test
  fun testNoiseTransportLengthPrefixedFraming() {
    val payload = JSONObject().apply {
      put("method", "link.register")
      put("node_id", "homelink-e1890c")
    }.toString().toByteArray(StandardCharsets.UTF_8)

    val framed = NoiseTransport.encodeLengthPrefixedMessage(payload)
    assertEquals(4 + payload.size, framed.size)

    val bb = ByteBuffer.wrap(framed).order(ByteOrder.LITTLE_ENDIAN)
    val length = bb.getInt()
    assertEquals(payload.size, length)

    val unframed = NoiseTransport.decodeLengthPrefixedMessage(framed)
    assertNotNull(unframed)
    assertArrayEquals(payload, unframed!!)

    // Invalid length prefix bounds
    val corruptedFramed = ByteArray(2)
    assertNull(NoiseTransport.decodeLengthPrefixedMessage(corruptedFramed))
  }

  @Test
  fun testApplicationRequestResponseSerialization() {
    val headers = listOf(
      NoiseHeader("Content-Type", "application/json"),
      NoiseHeader("x-app-id", "musegadget")
    )
    val body = "{\"status\":\"ok\"}".toByteArray(StandardCharsets.UTF_8)
    val appReq = ApplicationRequest("POST", "/chat/stream", headers, body, endBody = true)
    val encoded = appReq.encode()

    val decoded = ApplicationRequest.decode(encoded)
    assertEquals("POST", decoded.verb)
    assertEquals("/chat/stream", decoded.path)
    assertEquals(2, decoded.headers.size)
    assertEquals("Content-Type", decoded.headers[0].key)
    assertEquals("application/json", decoded.headers[0].value)
    assertEquals("x-app-id", decoded.headers[1].key)
    assertEquals("musegadget", decoded.headers[1].value)
    assertArrayEquals(body, decoded.body)
    assertTrue(decoded.endBody)
  }

  @Test
  fun testNoiseTransportFrameChunkingAndReassembly() {
    val key1 = ByteArray(32) { 0x11.toByte() }
    val key2 = ByteArray(32) { 0x22.toByte() }

    val encTransport = NoiseTransport(CipherState().apply { initializeKey(key1) }, CipherState().apply { initializeKey(key2) })
    val decTransport = NoiseTransport(CipherState().apply { initializeKey(key2) }, CipherState().apply { initializeKey(key1) })

    val testData = "Testing body chunk encryption in Noise transport stream".toByteArray(StandardCharsets.UTF_8)
    val frames = encTransport.encryptBodyChunk(streamId = 42L, data = testData, endBody = true)
    assertEquals(1, frames.size)

    val decryptedServiceFrame = decTransport.decryptFrame(frames[0])
    assertNotNull(decryptedServiceFrame)
    assertEquals(42L, decryptedServiceFrame!!.streamId)
    assertTrue(decryptedServiceFrame.payload is ServiceFramePayload.Chunk)

    val chunk = (decryptedServiceFrame.payload as ServiceFramePayload.Chunk).chunk
    assertArrayEquals(testData, chunk.data)
    assertTrue(chunk.endBody)
  }

  @Test
  fun testMuseDeliveryClientOfflineHandling() = runTest {
    val client = MuseDeliveryClient(linkClientProvider = { null })
    val note = VoiceNote(
      id = "test-1",
      timestampEpochMs = 123456789L,
      text = "Voice note test",
      title = "Test",
      status = NoteStatus.PENDING
    )

    // With blank token, logs locally
    val emptyConfig = BridgeConfig(museAccessToken = "", developerSdkToken = "")
    val resLocal = client.deliverNote(note, emptyConfig)
    assertTrue(resLocal.isSuccess)
    assertTrue(resLocal.getOrThrow().contains("Received & logged on Portal"))

    // With token but link client null, fails gracefully informing offline
    val tokenConfig = BridgeConfig(museAccessToken = "hatch-device-token:xyz123")
    val resOffline = client.deliverNote(note, tokenConfig)
    assertFalse(resOffline.isSuccess)
    assertTrue(resOffline.exceptionOrNull()?.message?.contains("not connected") == true)
  }

  @Test
  fun testBridgeRepositoryNodeIdAndLinkStateFlow() {
    assertEquals(LinkState.DISCONNECTED, BridgeRepository.museLinkState.value)
    BridgeRepository.updateMuseLinkState(LinkState.CONNECTED_ONLINE)
    assertEquals(LinkState.CONNECTED_ONLINE, BridgeRepository.museLinkState.value)

    BridgeRepository.nodeId = "homelink-test99"
    assertEquals("homelink-test99", BridgeRepository.nodeId)

    BridgeRepository.resetForTesting()
    assertEquals(LinkState.DISCONNECTED, BridgeRepository.museLinkState.value)
    assertEquals("homelink-e1890c", BridgeRepository.nodeId)
  }

  @Test
  fun testNoiseXXFullHandshakeAndBidirectionalTransport() {
    val initiator = NoiseXXInitiator()
    initiator.initialize()

    val responder = com.portal.pebblebridge.muse.NoiseXXResponder()
    responder.initialize()

    // Step 1: Initiator -> Responder (msg 1)
    val msg1 = initiator.writeMessage1()
    assertEquals(32, msg1.size)
    responder.readMessage1(msg1)

    // Step 2: Responder -> Initiator (msg 2)
    val msg2 = responder.writeMessage2()
    assertEquals(32 + 48 + 16, msg2.size) // e(32) + s(48) + payload(16) = 96
    initiator.readMessage2(msg2)

    // Step 3: Initiator -> Responder (msg 3)
    val msg3 = initiator.writeMessage3()
    assertEquals(48 + 16, msg3.size) // s(48) + payload(16) = 64
    responder.readMessage3(msg3)

    // Split into transport cipher states
    val (initSend, initRecv) = initiator.split()
    val (respSend, respRecv) = responder.split()

    // Test Initiator -> Responder transport message
    val ad = "transport-ad".toByteArray(StandardCharsets.UTF_8)
    val initPlaintext = "Hello from Meta Portal to Leased VM".toByteArray(StandardCharsets.UTF_8)
    val initCiphertext = initSend.encryptWithAd(ad, initPlaintext)
    val respDecrypted = respRecv.decryptWithAd(ad, initCiphertext)
    assertArrayEquals(initPlaintext, respDecrypted)

    // Test Responder -> Initiator transport message
    val respPlaintext = "Hello from Leased VM to Meta Portal".toByteArray(StandardCharsets.UTF_8)
    val respCiphertext = respSend.encryptWithAd(ad, respPlaintext)
    val initDecrypted = initRecv.decryptWithAd(ad, respCiphertext)
    assertArrayEquals(respPlaintext, initDecrypted)
  }

  @Test
  fun testNoiseXXTamperedHandshakeRejection() {
    val initiator = NoiseXXInitiator()
    initiator.initialize()

    val responder = com.portal.pebblebridge.muse.NoiseXXResponder()
    responder.initialize()

    val msg1 = initiator.writeMessage1()
    responder.readMessage1(msg1)

    val msg2 = responder.writeMessage2()
    val tamperedMsg2 = msg2.copyOf()
    tamperedMsg2[tamperedMsg2.size - 1] = (tamperedMsg2[tamperedMsg2.size - 1].toInt() xor 0x01).toByte()

    try {
      initiator.readMessage2(tamperedMsg2)
      fail("Initiator should reject tampered Message 2")
    } catch (_: NoiseProtocolException) {
      // Expected
    }
  }

  @Test
  fun testProtoWireDelimitedFieldIntegerOverflowProtection() {
    // Malicious or corrupted varint claiming huge length that could overflow 32-bit int
    val maliciousVarint = ProtoWire.encodeVarint(0x7FFFFFFFL)
    val payload = maliciousVarint + byteArrayOf(1, 2, 3)

    try {
      ProtoWire.readDelimited(payload, 0)
      fail("readDelimited should reject length exceeding remaining buffer")
    } catch (e: NoiseProtocolException) {
      assertTrue(e.message?.contains("Truncated or overflowed") == true)
    }
  }

  @Test
  fun testServiceResponseParseIgnoresVarintStatus() {
    // Construct a payload where field 1 is varint status 200, and field 2 is delimited bytes "test"
    val baos = java.io.ByteArrayOutputStream()
    baos.write(ProtoWire.int64Field(1, 200L)) // status = 200 (wire type 0 varint)
    baos.write(ProtoWire.delimitedField(2, "cloud-response-payload".toByteArray(StandardCharsets.UTF_8)))
    val raw = baos.toByteArray()

    val decTransport = NoiseTransport(
      CipherState().apply { initializeKey(ByteArray(32) { 1 }) },
      CipherState().apply { initializeKey(ByteArray(32) { 2 }) }
    )

    // DecryptFrame uses parseServiceResponse internally:
    // Let's verify by testing decryptFrame roundtrip with a ServiceResponse containing varint status
    val framePayload = ServiceRequest(0, raw).encode()
    val frame = NoiseTransportFrame(1L, 0, 1, framePayload)
    val encCipher = CipherState().apply { initializeKey(ByteArray(32) { 2 }) }
    val encrypted = encCipher.encryptWithAd(ByteArray(0), frame.encode())

    val serviceFrame = decTransport.decryptFrame(encrypted)
    assertNotNull(serviceFrame)
  }
}
