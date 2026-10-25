package com.wifishuriken.companion.live

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionShutdownTest {
  @Test
  fun connectionClosesOnSessionThreadAfterBlockingCallReturns() {
    val started = CountDownLatch(1)
    var closedOn: Thread? = null
    val job = CoroutineScope(Dispatchers.IO).launch {
      try {
        started.countDown()
        Thread.sleep(300) // a blocking MTP call that cancellation cannot interrupt
      } finally {
        closedOn = Thread.currentThread() // ShurikenConnection.close() inside the session's use {}
      }
    }
    started.await(1, TimeUnit.SECONDS)
    val sends = Executors.newSingleThreadExecutor()
    assertTrue(shutdownSession(sends, job, timeoutMs = 2_000))
    assertTrue(sends.isShutdown)
    assertTrue(closedOn != null)
    assertNotSame(Thread.currentThread(), closedOn)
  }

  @Test
  fun shutdownGivesUpWaitingAfterTimeout() {
    val started = CountDownLatch(1)
    val job = CoroutineScope(Dispatchers.IO).launch {
      started.countDown()
      Thread.sleep(3_000)
    }
    started.await(1, TimeUnit.SECONDS)
    val t0 = System.nanoTime()
    assertFalse(shutdownSession(Executors.newSingleThreadExecutor(), job, timeoutMs = 100))
    assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
  }

  @Test
  fun notificationHasStopAction() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val n = LiveSessionService.buildNotification(context)
    assertEquals(1, n.actions.size)
    assertEquals("Stop", n.actions[0].title.toString())
    assertEquals(LiveSessionService.ACTION_STOP, shadowOf(n.actions[0].actionIntent).savedIntent.action)
  }
}
