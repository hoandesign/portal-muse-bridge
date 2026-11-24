package com.portal.pebblebridge

import com.portal.pebblebridge.model.BridgeConfig
import com.portal.pebblebridge.muse.MuseDeliveryClient
import com.portal.pebblebridge.muse.RefreshGate
import org.junit.After
import com.portal.pebblebridge.state.BridgeRepository
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MuseTokenRefreshTest {

  private val captured = mutableListOf<Request>()

  private fun clientReturning(code: Int, body: String) = OkHttpClient.Builder()
    .addInterceptor(Interceptor { chain ->
      captured += chain.request()
      Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
    })
    .build()

  private fun bodyOf(req: Request): JSONObject {
    val buf = Buffer()
    req.body!!.writeTo(buf)
    return JSONObject(buf.readUtf8())
  }

  private val config = BridgeConfig(
    developerSdkToken = "",
    museAccessToken = "old-access",
    museRefreshToken = "hatch_refresh:raw-r",
    museApiUrl = "https://api.muse.ai",
  )

  @Before
  fun setUp() {
    BridgeRepository.resetForTesting()
    RefreshGate.reset()
    RefreshGate.clockMs = { now }
    captured.clear()
  }

  @After
  fun tearDown() {
    RefreshGate.reset()
    RefreshGate.clockMs = { System.currentTimeMillis() }
  }

  private var now = 1_000_000L

  @Test
  fun refreshSendsNodeIdAsDeviceIdAndRefreshBearer() = runTest {
    BridgeRepository.nodeId = "homelink-e1890c"
    BridgeRepository.deviceId = "hatch-link:02:aa:bb:e1:89:0c"
    val client = MuseDeliveryClient(linkClientProvider = { null },
      httpClient = clientReturning(200, """{"access_token":"new-a","refresh_token":"new-r"}"""))

    val result = client.refreshAccessToken(config)

    assertTrue(result.isSuccess)
    val req = captured.single()
    assertEquals("https://api.muse.ai/device_token/refresh", req.url.toString())
    assertEquals("Bearer hatch_refresh:raw-r", req.header("Authorization"))
    assertEquals("homelink-e1890c", bodyOf(req).getString("device_id"))
    assertEquals("new-a", BridgeRepository.config.value.museAccessToken)
    assertEquals("new-r", BridgeRepository.config.value.museRefreshToken)
  }

  @Test
  fun rejectedRefreshKeepsStoredTokens() = runTest {
    BridgeRepository.updateConfig(config)
    val client = MuseDeliveryClient(linkClientProvider = { null },
      httpClient = clientReturning(401, """{"type":"session_invalid","status":401}"""))

    val result = client.refreshAccessToken(config)

    assertFalse(result.isSuccess)
    assertEquals("old-access", BridgeRepository.config.value.museAccessToken)
    assertEquals("hatch_refresh:raw-r", BridgeRepository.config.value.museRefreshToken)
  }

  @Test
  fun failedRefreshIsPacedWithoutTouchingNetwork() = runTest {
    val client = MuseDeliveryClient(linkClientProvider = { null },
      httpClient = clientReturning(429, """{"status":429}"""))

    assertFalse(client.refreshAccessToken(config).isSuccess)
    assertEquals(1, captured.size)

    // Immediate retries (the link loop polls every 2-30 s) must not hit the server.
    now += 30_000L
    assertFalse(client.refreshAccessToken(config).isSuccess)
    assertEquals(1, captured.size)

    // After the base interval an attempt goes out again; a second failure doubles the wait.
    now += RefreshGate.BASE_INTERVAL_MS
    client.refreshAccessToken(config)
    assertEquals(2, captured.size)
    assertEquals(2 * RefreshGate.BASE_INTERVAL_MS, RefreshGate.remainingMs())
  }

  @Test
  fun gateHonoursLongerRetryAfterAndCapsBackoff() {
    RefreshGate.onFailure(RefreshGate.parseRetryAfterMs("1800"))
    assertEquals(1_800_000L, RefreshGate.remainingMs())

    RefreshGate.reset()
    repeat(10) { RefreshGate.onFailure() }
    assertEquals(RefreshGate.MAX_INTERVAL_MS, RefreshGate.remainingMs())

    assertEquals(null, RefreshGate.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    RefreshGate.reset()
    assertEquals(0L, RefreshGate.tryAcquire())
    assertTrue("tryAcquire arms the gate", RefreshGate.tryAcquire() > 0)
  }
}
