package com.wifishuriken.companion.core

sealed interface ReadAction {
  data object Continue : ReadAction
  data class Sleep(val ms: Long) : ReadAction
  data object Stop : ReadAction
}

/**
 * What a USB reader loop does after each read. A read that waited out its timeout is normal (no
 * data yet); a read that fails at once means the device or interface is gone, so the loop backs off
 * instead of spinning and stops after [maxInstantFailures] in a row.
 */
class ReadFailurePolicy(
  private val timeoutMs: Long,
  private val maxInstantFailures: Int = 20,
  private val backoffMs: Long = 100,
) {
  private var instantFailures = 0

  fun onResult(bytes: Int, elapsedMs: Long): ReadAction {
    if (bytes >= 0 || elapsedMs >= timeoutMs / 2) {
      if (bytes > 0) instantFailures = 0
      return ReadAction.Continue
    }
    instantFailures++
    return if (instantFailures >= maxInstantFailures) ReadAction.Stop else ReadAction.Sleep(backoffMs)
  }
}
