package com.portal.pebblebridge.muse

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.math.ec.rfc7748.X25519

class NoiseProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class CipherState {
  private var key: ByteArray? = null
  private var nonce: Long = 0
  private var poisoned: Boolean = false
  private val lock = Any()

  fun initializeKey(keyBytes: ByteArray) = synchronized(lock) {
    if (poisoned) throw NoiseProtocolException("CipherState: poisoned")
    if (keyBytes.size != 32) throw NoiseProtocolException("Key must be 32 bytes")
    this.key = keyBytes.copyOf()
    this.nonce = 0
  }

  fun hasKey(): Boolean = key != null

  fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray = synchronized(lock) {
    if (poisoned) throw NoiseProtocolException("CipherState: poisoned")
    val currentKey = key ?: return plaintext.copyOf()
    if (nonce >= (1L shl 53) - 1) {
      poisoned = true
      throw NoiseProtocolException("Nonce exhausted")
    }
    val currentNonce = nonce++
    try {
      val iv = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        .putInt(0)
        .putLong(currentNonce)
        .array()

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(currentKey, "AES"), GCMParameterSpec(128, iv))
      cipher.updateAAD(ad)
      cipher.doFinal(plaintext)
    } catch (e: Exception) {
      poisoned = true
      throw NoiseProtocolException("Encrypt failed", e)
    }
  }

  fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray = synchronized(lock) {
    if (poisoned) throw NoiseProtocolException("CipherState: poisoned")
    val currentKey = key ?: return ciphertext.copyOf()
    if (nonce >= (1L shl 53) - 1) {
      poisoned = true
      throw NoiseProtocolException("Nonce exhausted")
    }
    val currentNonce = nonce++
    try {
      val iv = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        .putInt(0)
        .putLong(currentNonce)
        .array()

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(currentKey, "AES"), GCMParameterSpec(128, iv))
      cipher.updateAAD(ad)
      cipher.doFinal(ciphertext)
    } catch (e: Exception) {
      poisoned = true
      throw NoiseProtocolException("Decrypt failed", e)
    }
  }
}

class NoiseXXInitiator {
  companion object {
    internal val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.UTF_8)
    internal val EMPTY_BYTES = ByteArray(0)
    internal val SECURE_RANDOM = SecureRandom()

    private val LOW_ORDER_POINTS = listOf(
      ByteArray(32),
      ByteArray(32).apply { this[0] = 1 }
    )

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(key, "HmacSHA256"))
      return mac.doFinal(data)
    }

    fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
      val tempKey = hmacSha256(chainingKey, ikm)
      val out1 = hmacSha256(tempKey, byteArrayOf(1))
      val out2 = hmacSha256(tempKey, out1 + byteArrayOf(2))
      return Pair(out1, out2)
    }
  }

  enum class Phase { CREATED, INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

  private var phase = Phase.CREATED
  private var ck = ByteArray(32)
  private var h = ByteArray(32)
  private val cipher = CipherState()

  // Ephemeral keypair e
  private var ePrivate = ByteArray(32)
  var ePublic = ByteArray(32)
    private set

  // Static keypair s
  private var sPrivate = ByteArray(32)
  var sPublic = ByteArray(32)
    private set

  // Remote ephemeral re and static rs
  private var remoteE = ByteArray(32)
  private var remoteS = ByteArray(32)

  fun initialize() {
    if (phase != Phase.CREATED) throw NoiseProtocolException("Wrong phase for initialize: $phase")
    val padded = ByteArray(32)
    System.arraycopy(PROTOCOL_NAME, 0, padded, 0, PROTOCOL_NAME.size)
    h = padded
    ck = padded.copyOf()
    mixHash(EMPTY_BYTES)
    phase = Phase.INITIALIZED
  }

  private fun mixHash(data: ByteArray) {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(h)
    md.update(data)
    h = md.digest()
  }

  private fun mixKey(ikm: ByteArray) {
    val (newCk, tempK) = hkdf2(ck, ikm)
    ck = newCk
    cipher.initializeKey(tempK)
  }

  private fun encryptAndHash(plaintext: ByteArray): ByteArray {
    val ciphertext = cipher.encryptWithAd(h, plaintext)
    mixHash(ciphertext)
    return ciphertext
  }

  private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
    val plaintext = cipher.decryptWithAd(h, ciphertext)
    mixHash(ciphertext)
    return plaintext
  }

  private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    if (publicKey.size != 32) throw NoiseProtocolException("Invalid public key length")
    for (lowOrder in LOW_ORDER_POINTS) {
      if (MessageDigest.isEqual(publicKey, lowOrder)) {
        throw NoiseProtocolException("Rejected low-order point")
      }
    }
    val shared = ByteArray(32)
    X25519.calculateAgreement(privateKey, 0, publicKey, 0, shared, 0)
    if (MessageDigest.isEqual(shared, ByteArray(32))) {
      throw NoiseProtocolException("DH produced all-zero output")
    }
    return shared
  }

  fun writeMessage1(): ByteArray {
    if (phase != Phase.INITIALIZED) throw NoiseProtocolException("Wrong phase for writeMessage1: $phase")
    try {
      X25519.generatePrivateKey(SECURE_RANDOM, ePrivate)
      X25519.generatePublicKey(ePrivate, 0, ePublic, 0)

      mixHash(ePublic)
      encryptAndHash(EMPTY_BYTES)
      phase = Phase.MSG1_SENT
      return ePublic.copyOf()
    } catch (e: Exception) {
      phase = Phase.DEAD
      throw NoiseProtocolException("writeMessage1 failed", e)
    }
  }

  fun readMessage2(msg: ByteArray): ByteArray {
    if (phase != Phase.MSG1_SENT) throw NoiseProtocolException("Wrong phase for readMessage2: $phase")
    if (msg.size < 32 + (32 + 16) + 16) {
      phase = Phase.DEAD
      throw NoiseProtocolException("Message 2 too short: ${msg.size}")
    }
    try {
      var offset = 0
      remoteE = msg.copyOfRange(offset, offset + 32)
      mixHash(remoteE)
      offset += 32

      val ee = dh(ePrivate, remoteE)
      mixKey(ee)

      val encRs = msg.copyOfRange(offset, offset + 32 + 16)
      remoteS = decryptAndHash(encRs)
      offset += 32 + 16

      val es = dh(ePrivate, remoteS)
      mixKey(es)

      val payload = decryptAndHash(msg.copyOfRange(offset, msg.size))
      phase = Phase.MSG2_READ
      return payload
    } catch (e: Exception) {
      phase = Phase.DEAD
      throw NoiseProtocolException("readMessage2 failed", e)
    }
  }

  fun writeMessage3(): ByteArray {
    if (phase != Phase.MSG2_READ) throw NoiseProtocolException("Wrong phase for writeMessage3: $phase")
    try {
      X25519.generatePrivateKey(SECURE_RANDOM, sPrivate)
      X25519.generatePublicKey(sPrivate, 0, sPublic, 0)

      val encS = encryptAndHash(sPublic)
      val se = dh(sPrivate, remoteE)
      mixKey(se)

      val encPayload = encryptAndHash(EMPTY_BYTES)
      phase = Phase.MSG3_SENT
      return encS + encPayload
    } catch (e: Exception) {
      phase = Phase.DEAD
      throw NoiseProtocolException("writeMessage3 failed", e)
    }
  }

  fun split(): Pair<CipherState, CipherState> {
    if (phase != Phase.MSG3_SENT) throw NoiseProtocolException("Wrong phase for split: $phase")
    phase = Phase.SPLIT
    val (k1, k2) = hkdf2(ck, EMPTY_BYTES)
    ck = ByteArray(32)
    h = ByteArray(32)

    val sendCipher = CipherState()
    sendCipher.initializeKey(k1)
    val recvCipher = CipherState()
    recvCipher.initializeKey(k2)

    k1.fill(0)
    k2.fill(0)
    ePrivate.fill(0)
    sPrivate.fill(0)

    return Pair(sendCipher, recvCipher)
  }
}

class NoiseXXResponder {
  private enum class Phase {
    UNINITIALIZED,
    INITIALIZED,
    MSG1_READ,
    MSG2_SENT,
    MSG3_READ,
    SPLIT,
    DEAD,
  }

  private var phase = Phase.UNINITIALIZED
  private var h = ByteArray(32)
  private var ck = ByteArray(32)
  private val cipherState = CipherState()

  private val sPrivate = ByteArray(32)
  val sPublic = ByteArray(32)
  private val ePrivate = ByteArray(32)
  val ePublic = ByteArray(32)

  private var remoteE = ByteArray(32)
  var remoteS = ByteArray(32)
    private set

  fun initialize(staticPrivate: ByteArray? = null, ephemeralPrivate: ByteArray? = null) {
    if (staticPrivate != null) {
      System.arraycopy(staticPrivate, 0, sPrivate, 0, 32)
    } else {
      NoiseXXInitiator.SECURE_RANDOM.nextBytes(sPrivate)
    }
    X25519.generatePublicKey(sPrivate, 0, sPublic, 0)

    if (ephemeralPrivate != null) {
      System.arraycopy(ephemeralPrivate, 0, ePrivate, 0, 32)
    } else {
      NoiseXXInitiator.SECURE_RANDOM.nextBytes(ePrivate)
    }
    X25519.generatePublicKey(ePrivate, 0, ePublic, 0)

    val padded = ByteArray(32)
    System.arraycopy(NoiseXXInitiator.PROTOCOL_NAME, 0, padded, 0, NoiseXXInitiator.PROTOCOL_NAME.size)
    h = padded
    ck = padded.copyOf()
    mixHash(NoiseXXInitiator.EMPTY_BYTES)
    phase = Phase.INITIALIZED
  }

  private fun mixHash(data: ByteArray) {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(h)
    md.update(data)
    h = md.digest()
  }

  private fun mixKey(ikm: ByteArray) {
    val (newCk, tempKey) = NoiseXXInitiator.hkdf2(ck, ikm)
    ck = newCk
    cipherState.initializeKey(tempKey)
    tempKey.fill(0)
  }

  private fun encryptAndHash(plaintext: ByteArray): ByteArray {
    val ciphertext = cipherState.encryptWithAd(h, plaintext)
    mixHash(ciphertext)
    return ciphertext
  }

  private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
    val plaintext = cipherState.decryptWithAd(h, ciphertext)
    mixHash(ciphertext)
    return plaintext
  }

  fun readMessage1(msg1: ByteArray) {
    if (phase != Phase.INITIALIZED) throw NoiseProtocolException("Wrong phase for readMessage1: $phase")
    if (msg1.size < 32) throw NoiseProtocolException("Message 1 too short")
    remoteE = msg1.copyOfRange(0, 32)
    mixHash(remoteE)
    val encPayload = msg1.copyOfRange(32, msg1.size)
    decryptAndHash(encPayload)
    phase = Phase.MSG1_READ
  }

  fun writeMessage2(): ByteArray {
    if (phase != Phase.MSG1_READ) throw NoiseProtocolException("Wrong phase for writeMessage2: $phase")
    try {
      mixHash(ePublic)

      val ee = ByteArray(32)
      X25519.calculateAgreement(ePrivate, 0, remoteE, 0, ee, 0)
      mixKey(ee)
      ee.fill(0)

      val encS = encryptAndHash(sPublic)

      val es = ByteArray(32)
      X25519.calculateAgreement(sPrivate, 0, remoteE, 0, es, 0)
      mixKey(es)
      es.fill(0)

      val encPayload = encryptAndHash(NoiseXXInitiator.EMPTY_BYTES)
      phase = Phase.MSG2_SENT
      return ePublic + encS + encPayload
    } catch (e: Exception) {
      phase = Phase.DEAD
      throw NoiseProtocolException("writeMessage2 failed", e)
    }
  }

  fun readMessage3(msg3: ByteArray) {
    if (phase != Phase.MSG2_SENT) throw NoiseProtocolException("Wrong phase for readMessage3: $phase")
    if (msg3.size < 48) throw NoiseProtocolException("Message 3 too short: ${msg3.size}")
    try {
      val encS = msg3.copyOfRange(0, 48)
      remoteS = decryptAndHash(encS)

      val se = ByteArray(32)
      X25519.calculateAgreement(ePrivate, 0, remoteS, 0, se, 0)
      mixKey(se)
      se.fill(0)

      val encPayload = msg3.copyOfRange(48, msg3.size)
      decryptAndHash(encPayload)
      phase = Phase.MSG3_READ
    } catch (e: Exception) {
      phase = Phase.DEAD
      throw NoiseProtocolException("readMessage3 failed", e)
    }
  }

  fun split(): Pair<CipherState, CipherState> {
    if (phase != Phase.MSG3_READ) throw NoiseProtocolException("Wrong phase for split: $phase")
    phase = Phase.SPLIT
    val (k1, k2) = NoiseXXInitiator.hkdf2(ck, NoiseXXInitiator.EMPTY_BYTES)
    ck = ByteArray(32)
    h = ByteArray(32)

    val sendCipher = CipherState()
    sendCipher.initializeKey(k2)
    val recvCipher = CipherState()
    recvCipher.initializeKey(k1)

    k1.fill(0)
    k2.fill(0)
    ePrivate.fill(0)
    sPrivate.fill(0)

    return Pair(sendCipher, recvCipher)
  }
}
