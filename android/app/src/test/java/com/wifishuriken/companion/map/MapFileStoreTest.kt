package com.wifishuriken.companion.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapFileStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun store(free: () -> Long, reserve: Long = 100, checkEvery: Long = 10) =
    MapFileStore(tmp.newFolder(), freeBytes = free, reserveBytes = reserve, checkEveryBytes = checkEvery)

  @Test
  fun importChecksSpaceAndRenames() = runBlocking {
    val data = ByteArray(1000) { it.toByte() }
    val ok = store(free = { 1_000_000 })
    assertTrue(ok.importFrom({ data.inputStream() }, data.size.toLong()) { _, _ -> }.isSuccess)
    assertArrayEquals(data, ok.file.readBytes())
    assertTrue(ok.dir.listFiles()!!.none { it.name.endsWith(".part") })

    val tight = store(free = { 1050 }) // needs size + reserve = 1100
    assertTrue(tight.importFrom({ data.inputStream() }, data.size.toLong()) { _, _ -> }.isFailure)
    assertFalse(tight.file.exists())
  }

  @Test
  fun unknownSizeAbortsWhenSpaceRunsOut() = runBlocking {
    var free = 1_000L
    val s = store(free = { free -= 100; free }, reserve = 500, checkEvery = 10)
    // Deliver 10 bytes per read so the free-space check runs repeatedly.
    val trickle = object : java.io.InputStream() {
      var left = 1000
      override fun read(): Int = if (left-- > 0) 0 else -1
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = minOf(10, len, left)
        left -= n
        return n
      }
    }
    val result = s.importFrom({ trickle }, size = null) { _, _ -> }
    assertTrue(result.isFailure)
    assertFalse(s.file.exists())
    assertTrue(s.dir.listFiles()!!.none { it.name.endsWith(".part") })
  }

  @Test
  fun failedImportKeepsPreviousMap() = runBlocking {
    val s = store(free = { 1_000_000 })
    s.importFrom({ "old".byteInputStream() }, 3) { _, _ -> }
    val result = s.importFrom({ throw java.io.IOException("unreadable") }, 3) { _, _ -> }
    assertTrue(result.isFailure)
    assertTrue(s.file.readText() == "old")
  }
}
