package com.portal.pebblebridge.muse

import kotlin.math.max
import kotlin.math.min

/**
 * Paces `/device_token/refresh` so a dead or rate-limited credential cannot be hammered.
 *
 * Mirrors upstream muse-gadget-sdk: Linux `TOKEN_RETRY_S = 300` between attempts, ESP32
 * `count_vm_auth_refusal_unlocked()` doubling after each refusal up to a cap. A successful refresh
 * also arms the base interval, because the API can return 200 with tokens every endpoint rejects.
 * A server `Retry-After` (HTTP 429) is honoured when longer than our own interval.
 */
object RefreshGate {
  const val BASE_INTERVAL_MS = 5 * 60_000L
  const val MAX_INTERVAL_MS = 60 * 60_000L
  private const val MAX_DOUBLINGS = 4

  @Volatile var clockMs: () -> Long = { System.currentTimeMillis() }

  @Volatile private var nextAllowedAtMs = 0L
  @Volatile private var failures = 0

  fun remainingMs(): Long = max(0L, nextAllowedAtMs - clockMs())

  /**
   * Returns 0 and arms the base interval if an attempt may go out now, else the ms still to wait.
   * Atomic, so two concurrent callers cannot both send a refresh.
   */
  @Synchronized
  fun tryAcquire(): Long {
    val wait = remainingMs()
    if (wait == 0L) nextAllowedAtMs = clockMs() + BASE_INTERVAL_MS
    return wait
  }

  @Synchronized
  fun onSuccess() {
    failures = 0
    nextAllowedAtMs = clockMs() + BASE_INTERVAL_MS
  }

  @Synchronized
  fun onFailure(retryAfterMs: Long? = null) {
    failures = min(failures + 1, MAX_DOUBLINGS + 1)
    val interval = min(MAX_INTERVAL_MS, BASE_INTERVAL_MS shl (failures - 1))
    nextAllowedAtMs = clockMs() + max(interval, min(retryAfterMs ?: 0L, MAX_INTERVAL_MS))
  }

  /** A fresh pairing brings new credentials, so they may be used at once. */
  @Synchronized
  fun reset() {
    failures = 0
    nextAllowedAtMs = 0L
  }

  /** Parses a delta-seconds `Retry-After` header; HTTP-date values are ignored. */
  fun parseRetryAfterMs(header: String?): Long? =
    header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000L }
}
