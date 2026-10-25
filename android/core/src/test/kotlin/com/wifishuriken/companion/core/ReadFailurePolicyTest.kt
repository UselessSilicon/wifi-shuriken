package com.wifishuriken.companion.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadFailurePolicyTest {
  @Test
  fun timeoutsKeepReading() {
    val p = ReadFailurePolicy(timeoutMs = 500)
    repeat(100) { assertEquals(ReadAction.Continue, p.onResult(-1, elapsedMs = 500)) }
  }

  @Test
  fun instantFailuresBackOffThenStop() {
    val p = ReadFailurePolicy(timeoutMs = 500, maxInstantFailures = 5, backoffMs = 100)
    repeat(4) { assertEquals(ReadAction.Sleep(100), p.onResult(-1, elapsedMs = 0)) }
    assertEquals(ReadAction.Stop, p.onResult(-1, elapsedMs = 1))
  }

  @Test
  fun dataResetsTheCount() {
    val p = ReadFailurePolicy(timeoutMs = 500, maxInstantFailures = 2, backoffMs = 100)
    assertEquals(ReadAction.Sleep(100), p.onResult(-1, elapsedMs = 0))
    assertEquals(ReadAction.Continue, p.onResult(42, elapsedMs = 3))
    assertEquals(ReadAction.Sleep(100), p.onResult(-1, elapsedMs = 0))
  }
}
