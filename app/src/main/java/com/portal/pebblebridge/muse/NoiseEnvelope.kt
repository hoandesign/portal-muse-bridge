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
  data class Reset(val code: Int, val reason: String) : ServiceFramePayload()
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
      is ServiceFramePayload.Chunk -> baos.write(ProtoWire.delimitedField(4, payload.chunk.encode()))
      else -> {}
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
            val (raw, off) = ProtoWire.readDelimited(data, nextOffset)
            // ApplicationRequest
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
            payload = ServiceFramePayload.Reset(0, "Reset")
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
            chunkIndex = raw.toInt()
            offset = off
          }
          3 -> {
            val (raw, off) = ProtoWire.readVarint(data, nextOffset)
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

class NoiseTransport(
  private val sendCipher: CipherState,
  private val recvCipher: CipherState,
) {
  companion object {
    private val EMPTY_AD = ByteArray(0)
    private const val MAX_CHUNK_PAYLOAD = 65489
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
    val totalChunks = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
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

  fun decryptFrame(ciphertext: ByteArray): ServiceFrame? {
    val plaintext = recvCipher.decryptWithAd(EMPTY_AD, ciphertext)
    val transportFrame = NoiseTransportFrame.decode(plaintext)
    val serviceResponse = parseServiceResponse(transportFrame.payload)
    return ServiceFrame.decode(serviceResponse)
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
