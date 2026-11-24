package com.portal.pebblebridge.muse

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ProtoWire {
  const val WIRE_VARINT = 0
  const val WIRE_DELIMITED = 2

  fun encodeVarint(value: Long): ByteArray {
    var v = value
    val baos = ByteArrayOutputStream()
    while (true) {
      val byte = (v and 0x7F).toInt()
      v = v ushr 7
      if (v != 0L) {
        baos.write(byte or 0x80)
      } else {
        baos.write(byte)
        break
      }
    }
    return baos.toByteArray()
  }

  fun readVarint(data: ByteArray, startOffset: Int): Pair<Long, Int> {
    var result = 0L
    var shift = 0
    var offset = startOffset
    while (offset < data.size) {
      val b = data[offset++].toInt() and 0xFF
      result = result or ((b and 0x7F).toLong() shl shift)
      if ((b and 0x80) == 0) {
        return Pair(result, offset)
      }
      shift += 7
      if (shift >= 64) throw NoiseProtocolException("Varint too long")
    }
    throw NoiseProtocolException("Truncated varint")
  }

  fun encodeKey(fieldNumber: Int, wireType: Int): ByteArray =
    encodeVarint(((fieldNumber.toLong() shl 3) or wireType.toLong()))

  fun readKey(data: ByteArray, offset: Int): Triple<Int, Int, Int> {
    val (raw, nextOffset) = readVarint(data, offset)
    val fieldNumber = (raw ushr 3).toInt()
    val wireType = (raw and 0x07).toInt()
    return Triple(fieldNumber, wireType, nextOffset)
  }

  fun int64Field(fieldNumber: Int, value: Long): ByteArray =
    encodeKey(fieldNumber, WIRE_VARINT) + encodeVarint(value)

  fun uint32Field(fieldNumber: Int, value: Int): ByteArray =
    encodeKey(fieldNumber, WIRE_VARINT) + encodeVarint(value.toLong() and 0xFFFFFFFFL)

  fun boolField(fieldNumber: Int, value: Boolean): ByteArray =
    encodeKey(fieldNumber, WIRE_VARINT) + encodeVarint(if (value) 1L else 0L)

  fun bytesField(fieldNumber: Int, value: ByteArray): ByteArray =
    encodeKey(fieldNumber, WIRE_DELIMITED) + encodeVarint(value.size.toLong()) + value

  fun stringField(fieldNumber: Int, value: String): ByteArray =
    bytesField(fieldNumber, value.toByteArray(Charsets.UTF_8))

  fun delimitedField(fieldNumber: Int, payload: ByteArray): ByteArray =
    bytesField(fieldNumber, payload)

  fun readDelimited(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
    val (length, dataOffset) = readVarint(data, offset)
    if (length < 0 || length > (data.size - dataOffset)) {
      throw NoiseProtocolException("Truncated or overflowed delimited field: len=$length")
    }
    val len = length.toInt()
    val bytes = data.copyOfRange(dataOffset, dataOffset + len)
    return Pair(bytes, dataOffset + len)
  }

  fun skipField(data: ByteArray, offset: Int, wireType: Int): Int {
    return when (wireType) {
      WIRE_VARINT -> readVarint(data, offset).second
      WIRE_DELIMITED -> readDelimited(data, offset).second
      1 -> offset + 8 // 64-bit fixed
      5 -> offset + 4 // 32-bit fixed
      else -> throw NoiseProtocolException("Unsupported wire type: $wireType")
    }
  }
}

data class NoiseHeader(val key: String, val value: String) {
  fun encode(): ByteArray {
    val baos = ByteArrayOutputStream()
    if (key.isNotEmpty()) baos.write(ProtoWire.stringField(1, key))
    if (value.isNotEmpty()) baos.write(ProtoWire.stringField(2, value))
    return baos.toByteArray()
  }

  companion object {
    fun decode(data: ByteArray): NoiseHeader {
      var key = ""
      var value = ""
      var offset = 0
      while (offset < data.size) {
        val (fNum, wType, nextOffset) = ProtoWire.readKey(data, offset)
        when (fNum) {
          1 -> {
            val (bytes, off) = ProtoWire.readDelimited(data, nextOffset)
            key = String(bytes, Charsets.UTF_8)
            offset = off
          }
          2 -> {
            val (bytes, off) = ProtoWire.readDelimited(data, nextOffset)
            value = String(bytes, Charsets.UTF_8)
            offset = off
          }
          else -> offset = ProtoWire.skipField(data, nextOffset, wType)
        }
      }
      return NoiseHeader(key, value)
    }
  }
}

data class ApplicationRequest(
  val verb: String,
  val path: String,
  val headers: List<NoiseHeader> = emptyList(),
  val body: ByteArray = ByteArray(0),
  val endBody: Boolean = false,
) {
  fun encode(): ByteArray {
    val baos = ByteArrayOutputStream()
    if (verb.isNotEmpty()) baos.write(ProtoWire.stringField(1, verb))
    if (path.isNotEmpty()) baos.write(ProtoWire.stringField(2, path))
    for (h in headers) {
      baos.write(ProtoWire.delimitedField(3, h.encode()))
    }
    if (body.isNotEmpty()) baos.write(ProtoWire.bytesField(4, body))
    if (endBody) baos.write(ProtoWire.boolField(5, true))
    return baos.toByteArray()
  }

  companion object {
    fun decode(data: ByteArray): ApplicationRequest {
      var verb = ""
      var path = ""
      val headers = mutableListOf<NoiseHeader>()
      var body = ByteArray(0)
      var endBody = false
      var offset = 0
      while (offset < data.size) {
        val (fNum, wType, nextOffset) = ProtoWire.readKey(data, offset)
        when (fNum) {
          1 -> {
            val (b, off) = ProtoWire.readDelimited(data, nextOffset)
            verb = String(b, Charsets.UTF_8)
            offset = off
          }
          2 -> {
            val (b, off) = ProtoWire.readDelimited(data, nextOffset)
            path = String(b, Charsets.UTF_8)
            offset = off
          }
          3 -> {
            val (b, off) = ProtoWire.readDelimited(data, nextOffset)
            headers.add(NoiseHeader.decode(b))
            offset = off
          }
          4 -> {
            val (b, off) = ProtoWire.readDelimited(data, nextOffset)
            body = b
            offset = off
          }
          5 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
            endBody = raw != 0L
            offset = off
          }
          else -> offset = ProtoWire.skipField(data, nextOffset, wType)
        }
      }
      return ApplicationRequest(verb, path, headers, body, endBody)
    }
  }
}

data class ApplicationResponse(
  val status: Int,
  val headers: List<NoiseHeader> = emptyList(),
  val body: ByteArray = ByteArray(0),
  val endBody: Boolean = false,
) {
  fun encode(): ByteArray {
    val baos = ByteArrayOutputStream()
    if (status != 0) baos.write(ProtoWire.uint32Field(1, status))
    for (h in headers) {
      baos.write(ProtoWire.delimitedField(2, h.encode()))
    }
    if (body.isNotEmpty()) baos.write(ProtoWire.bytesField(3, body))
    if (endBody) baos.write(ProtoWire.boolField(4, true))
    return baos.toByteArray()
  }

  companion object {
    fun decode(data: ByteArray): ApplicationResponse {
      var status = 0
      val headers = mutableListOf<NoiseHeader>()
      var body = ByteArray(0)
      var endBody = false
      var offset = 0
      while (offset < data.size) {
        val (fNum, wType, nextOffset) = ProtoWire.readKey(data, offset)
        when (fNum) {
          1 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
            status = raw.toInt()
            offset = off
          }
          2 -> {
            val (hBytes, off) = ProtoWire.readDelimited(data, nextOffset)
            headers.add(NoiseHeader.decode(hBytes))
            offset = off
          }
          3 -> {
            val (bBytes, off) = ProtoWire.readDelimited(data, nextOffset)
            body = bBytes
            offset = off
          }
          4 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
            endBody = raw != 0L
            offset = off
          }
          else -> offset = ProtoWire.skipField(data, nextOffset, wType)
        }
      }
      return ApplicationResponse(status, headers, body, endBody)
    }
  }
}

data class BodyChunk(
  val data: ByteArray,
  val endBody: Boolean = false,
) {
  fun encode(): ByteArray {
    val baos = ByteArrayOutputStream()
    if (data.isNotEmpty()) baos.write(ProtoWire.bytesField(1, data))
    if (endBody) baos.write(ProtoWire.boolField(2, true))
    return baos.toByteArray()
  }

  companion object {
    fun decode(bytes: ByteArray): BodyChunk {
      var data = ByteArray(0)
      var endBody = false
      var offset = 0
      while (offset < bytes.size) {
        val (fNum, wType, nextOffset) = ProtoWire.readKey(bytes, offset)
        when (fNum) {
          1 -> {
            val (b, off) = ProtoWire.readDelimited(bytes, nextOffset)
            data = b
            offset = off
          }
          2 -> {
            val (raw, off) = ProtoWire.readVarint(bytes, nextOffset)
            endBody = raw != 0L
            offset = off
          }
          else -> offset = ProtoWire.skipField(bytes, nextOffset, wType)
        }
      }
      return BodyChunk(data, endBody)
    }
  }
}

sealed class ServiceFramePayload {
  data class Req(val request: ApplicationRequest) : ServiceFramePayload()
  data class Resp(val response: ApplicationResponse) : ServiceFramePayload()
  data class Chunk(val chunk: BodyChunk) : ServiceFramePayload()
  data class Reset(val code: Int, val reason: String) : ServiceFramePayload() {
    fun encode(): ByteArray {
      val baos = ByteArrayOutputStream()
      if (code != 0) baos.write(ProtoWire.uint32Field(1, code))
      if (reason.isNotEmpty()) baos.write(ProtoWire.stringField(2, reason))
      return baos.toByteArray()
    }

    companion object {
      fun decode(data: ByteArray): Reset {
        var code = 0
        var reason = ""
        var offset = 0
        while (offset < data.size) {
          val (fNum, wType, nextOffset) = ProtoWire.readKey(data, offset)
          when (fNum) {
            1 -> {
              val (raw, off) = ProtoWire.readVarint(data, nextOffset)
              code = raw.toInt()
              offset = off
            }
            2 -> {
              val (b, off) = ProtoWire.readDelimited(data, nextOffset)
              reason = String(b, Charsets.UTF_8)
              offset = off
            }
            else -> offset = ProtoWire.skipField(data, nextOffset, wType)
          }
        }
        return Reset(code, reason)
      }
    }
  }
}

data class ServiceFrame(
  val streamId: Long,
  val payload: ServiceFramePayload?,
) {
  fun encode(): ByteArray {
    val baos = ByteArrayOutputStream()
    if (streamId != 0L) baos.write(ProtoWire.int64Field(1, streamId))
    when (payload) {
      is ServiceFramePayload.Req -> baos.write(ProtoWire.delimitedField(2, payload.request.encode()))
      is ServiceFramePayload.Resp -> baos.write(ProtoWire.delimitedField(3, payload.response.encode()))
      is ServiceFramePayload.Chunk -> baos.write(ProtoWire.delimitedField(4, payload.chunk.encode()))
      is ServiceFramePayload.Reset -> baos.write(ProtoWire.delimitedField(5, payload.encode()))
      null -> {}
    }
    return baos.toByteArray()
  }

  companion object {
    fun decode(data: ByteArray): ServiceFrame {
      var streamId = 0L
      var payload: ServiceFramePayload? = null
      var offset = 0
      while (offset < data.size) {
        val (fNum, wType, nextOffset) = ProtoWire.readKey(data, offset)
        when (fNum) {
          1 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
            streamId = raw
            offset = off
          }
          2 -> {
            val (_, off) = ProtoWire.readDelimited(data, nextOffset)
            // ApplicationRequest is client-to-server only; skip if present on inbound frames.
            offset = off
          }
          3 -> {
            val (raw, off) = ProtoWire.readDelimited(data, nextOffset)
            payload = ServiceFramePayload.Resp(ApplicationResponse.decode(raw))
            offset = off
          }
          4 -> {
            val (raw, off) = ProtoWire.readDelimited(data, nextOffset)
            payload = ServiceFramePayload.Chunk(BodyChunk.decode(raw))
            offset = off
          }
          5 -> {
            val (raw, off) = ProtoWire.readDelimited(data, nextOffset)
            payload = ServiceFramePayload.Reset.decode(raw)
            offset = off
          }
          else -> offset = ProtoWire.skipField(data, nextOffset, wType)
        }
      }
      return ServiceFrame(streamId, payload)
    }
  }
}

data class ServiceRequest(val service: Int = 0, val payload: ByteArray) {
  fun encode(): ByteArray {
    val baos = ByteArrayOutputStream()
    if (service != 0) baos.write(ProtoWire.int64Field(1, service.toLong()))
    if (payload.isNotEmpty()) baos.write(ProtoWire.bytesField(2, payload))
    return baos.toByteArray()
  }
}

data class NoiseTransportFrame(
  val chunkId: Long,
  val chunkIndex: Int,
  val totalChunks: Int,
  val payload: ByteArray,
) {
  fun encode(): ByteArray {
    val baos = ByteArrayOutputStream()
    if (chunkId != 0L) baos.write(ProtoWire.int64Field(1, chunkId))
    if (chunkIndex != 0) baos.write(ProtoWire.uint32Field(2, chunkIndex))
    if (totalChunks != 0) baos.write(ProtoWire.uint32Field(3, totalChunks))
    if (payload.isNotEmpty()) baos.write(ProtoWire.bytesField(4, payload))
    return baos.toByteArray()
  }

  companion object {
    fun decode(data: ByteArray): NoiseTransportFrame {
      var chunkId = 0L
      var chunkIndex = 0
      var totalChunks = 1
      var payload = ByteArray(0)
      var offset = 0
      while (offset < data.size) {
        val (fNum, wType, nextOffset) = ProtoWire.readKey(data, offset)
        when (fNum) {
          1 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
            chunkId = raw
            offset = off
          }
          2 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
            if (raw < 0L || raw > Int.MAX_VALUE.toLong()) {
              throw NoiseProtocolException("chunkIndex out of int32 range: $raw")
            }
            chunkIndex = raw.toInt()
            offset = off
          }
          3 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
            if (raw < 0L || raw > Int.MAX_VALUE.toLong()) {
              throw NoiseProtocolException("totalChunks out of int32 range: $raw")
            }
            totalChunks = raw.toInt()
            offset = off
          }
          4 -> {
            val (bytes, off) = ProtoWire.readDelimited(data, nextOffset)
            payload = bytes
            offset = off
          }
          else -> offset = ProtoWire.skipField(data, nextOffset, wType)
        }
      }
      return NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload)
    }
  }
}

/**
 * Reassembles multi-chunk `NoiseTransportFrame` payloads by `chunkId`.
 * Port of `musegadget.noise.framing.NoiseFrameDecoder`.
 */
class NoiseFrameDecoder(
  private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
  companion object {
    const val MAX_CHUNK_PAYLOAD = 65489
    const val MAX_PENDING_ASSEMBLIES = 16
    const val MAX_TOTAL_CHUNKS = 256
    const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024
    const val ASSEMBLY_TTL_MS = 60_000L
  }

  private class Assembly(
    val total: Int,
    val createdAtMs: Long,
    var totalBytes: Int = 0,
    val chunks: MutableMap<Int, ByteArray> = HashMap(),
  )

  private val pending = LinkedHashMap<Long, Assembly>()
  private var poisoned = false

  fun decode(frameBytes: ByteArray): ByteArray? {
    if (poisoned) throw NoiseProtocolException("NoiseFrameDecoder: poisoned after prior failure")
    return try {
      val frame = NoiseTransportFrame.decode(frameBytes)
      if (frame.totalChunks < 1 || frame.totalChunks > MAX_TOTAL_CHUNKS) {
        throw NoiseProtocolException("invalid totalChunks: ${frame.totalChunks}")
      }
      if (frame.chunkIndex < 0 || frame.chunkIndex >= frame.totalChunks) {
        throw NoiseProtocolException("chunkIndex ${frame.chunkIndex} out of range [0, ${frame.totalChunks})")
      }
      if (frame.payload.size > MAX_CHUNK_PAYLOAD) {
        throw NoiseProtocolException("payload too large for noise frame (${frame.payload.size} bytes)")
      }

      evictExpired()

      if (frame.totalChunks == 1 && !pending.containsKey(frame.chunkId)) {
        return frame.payload
      }

      val existing = pending[frame.chunkId]
      val assembly = if (existing == null) {
        if (pending.size >= MAX_PENDING_ASSEMBLIES) {
          throw NoiseProtocolException("too many pending noise frame assemblies")
        }
        Assembly(total = frame.totalChunks, createdAtMs = clockMs()).also { pending[frame.chunkId] = it }
      } else {
        existing
      }

      if (assembly.total != frame.totalChunks) {
        pending.remove(frame.chunkId)
        throw NoiseProtocolException(
          "inconsistent totalChunks for chunkId: expected ${assembly.total}, got ${frame.totalChunks}"
        )
      }
      if (assembly.chunks.containsKey(frame.chunkIndex)) {
        pending.remove(frame.chunkId)
        throw NoiseProtocolException("duplicate chunkIndex ${frame.chunkIndex}")
      }

      assembly.totalBytes += frame.payload.size
      if (assembly.totalBytes > MAX_ASSEMBLY_BYTES) {
        pending.remove(frame.chunkId)
        throw NoiseProtocolException("assembly exceeded byte budget")
      }

      assembly.chunks[frame.chunkIndex] = frame.payload
      if (assembly.chunks.size < assembly.total) {
        return null
      }

      pending.remove(frame.chunkId)
      val out = ByteArray(assembly.totalBytes)
      var offset = 0
      for (i in 0 until assembly.total) {
        val part = assembly.chunks[i] ?: throw NoiseProtocolException("missing chunk $i")
        System.arraycopy(part, 0, out, offset, part.size)
        offset += part.size
      }
      out
    } catch (e: Exception) {
      poisoned = true
      pending.clear()
      throw if (e is NoiseProtocolException) e else NoiseProtocolException(e.message ?: "noise frame decode failed", e)
    }
  }

  private fun evictExpired() {
    val now = clockMs()
    val it = pending.entries.iterator()
    while (it.hasNext()) {
      if (now - it.next().value.createdAtMs > ASSEMBLY_TTL_MS) {
        it.remove()
      }
    }
  }
}

class NoiseTransport(
  private val sendCipher: CipherState,
  private val recvCipher: CipherState,
  private val frameDecoder: NoiseFrameDecoder = NoiseFrameDecoder(),
) {
  companion object {
    private val EMPTY_AD = ByteArray(0)
    private const val MAX_CHUNK_PAYLOAD = NoiseFrameDecoder.MAX_CHUNK_PAYLOAD
    private val SECURE_RANDOM = SecureRandom()

    fun encodeLengthPrefixedMessage(jsonBytes: ByteArray): ByteArray {
      val bb = ByteBuffer.allocate(4 + jsonBytes.size).order(ByteOrder.LITTLE_ENDIAN)
      bb.putInt(jsonBytes.size)
      bb.put(jsonBytes)
      return bb.array()
    }

    fun decodeLengthPrefixedMessage(data: ByteArray): ByteArray? {
      if (data.size < 4) return null
      val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
      val length = bb.getInt()
      if (length < 0 || length > data.size - 4) return null
      val result = ByteArray(length)
      bb.get(result)
      return result
    }
  }

  private val nextStreamId = AtomicLong(1)
  private val recvLock = Any()

  fun startStreamRequest(
    method: String,
    path: String,
    headers: List<NoiseHeader> = emptyList(),
  ): Pair<Long, List<ByteArray>> {
    val streamId = nextStreamId.getAndIncrement()
    val appReq = ApplicationRequest(
      verb = method,
      path = path,
      headers = headers,
      body = ByteArray(0),
      endBody = false,
    )
    val serviceFrame = ServiceFrame(streamId, ServiceFramePayload.Req(appReq))
    val serviceReq = ServiceRequest(0, serviceFrame.encode())
    val frames = encodeFrames(serviceReq.encode())
    return Pair(streamId, frames)
  }

  fun encryptBodyChunk(streamId: Long, data: ByteArray, endBody: Boolean = false): List<ByteArray> {
    val bodyChunk = BodyChunk(data, endBody)
    val serviceFrame = ServiceFrame(streamId, ServiceFramePayload.Chunk(bodyChunk))
    val serviceReq = ServiceRequest(0, serviceFrame.encode())
    return encodeFrames(serviceReq.encode())
  }

  private fun encodeFrames(data: ByteArray): List<ByteArray> {
    val chunkId = SECURE_RANDOM.nextLong()
    val totalChunksLong = maxOf(1L, (data.size.toLong() + MAX_CHUNK_PAYLOAD - 1L) / MAX_CHUNK_PAYLOAD)
    if (totalChunksLong > NoiseFrameDecoder.MAX_TOTAL_CHUNKS.toLong()) {
      throw NoiseProtocolException("payload too large for noise framing (${data.size} bytes, $totalChunksLong chunks)")
    }
    val totalChunks = totalChunksLong.toInt()
    val result = mutableListOf<ByteArray>()

    for (index in 0 until totalChunks) {
      val start = index * MAX_CHUNK_PAYLOAD
      val end = minOf(data.size, start + MAX_CHUNK_PAYLOAD)
      val chunkBytes = if (data.isEmpty()) ByteArray(0) else data.copyOfRange(start, end)
      val frame = NoiseTransportFrame(chunkId, index, totalChunks, chunkBytes)
      val encodedFrame = frame.encode()
      val encrypted = sendCipher.encryptWithAd(EMPTY_AD, encodedFrame)
      result.add(encrypted)
    }
    return result
  }

  fun decryptFrame(ciphertext: ByteArray): ServiceFrame? = synchronized(recvLock) {
    val plaintext = recvCipher.decryptWithAd(EMPTY_AD, ciphertext)
    val reassembled = frameDecoder.decode(plaintext) ?: return null
    val serviceResponse = parseServiceResponse(reassembled)
    ServiceFrame.decode(serviceResponse)
  }

  private fun parseServiceResponse(data: ByteArray): ByteArray {
    var payload = ByteArray(0)
    var offset = 0
    while (offset < data.size) {
      val (fNum, wType, nextOffset) = ProtoWire.readKey(data, offset)
      if (wType == ProtoWire.WIRE_DELIMITED && (fNum == 1 || fNum == 2)) {
        val (bytes, off) = ProtoWire.readDelimited(data, nextOffset)
        payload = bytes
        offset = off
      } else {
        offset = ProtoWire.skipField(data, nextOffset, wType)
      }
    }
    return payload
  }
}
