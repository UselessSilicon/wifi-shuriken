package com.wifishuriken.companion.live

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Ends a live session without touching the USB connection from the calling (main) thread: stops
 * NMEA sends first, then cancels the session and waits up to [timeoutMs] for it to close the
 * connection itself, on its own thread, once any blocking MTP call has returned. Closing the
 * MtpDevice while another thread is inside a native call would free it under that call.
 * Returns false if the session was still blocked when the wait ended; it closes the connection later.
 */
fun shutdownSession(sendExecutor: ExecutorService, job: Job?, timeoutMs: Long): Boolean {
  sendExecutor.shutdownNow()
  sendExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
  if (job == null) return true
  job.cancel()
  return runBlocking { withTimeoutOrNull(timeoutMs) { job.join() } } != null
}
