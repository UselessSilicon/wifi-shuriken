package com.wifishuriken.companion.core

import java.io.InputStream
import java.security.MessageDigest

data class IndexEntry(val md5: String, val name: String, val size: Long)

/**
 * The persistent list of imported log files (owner's design, §8.2): the single source of truth for
 * "already imported". One line per entry, `md5<TAB>size<TAB>name`, lower-case hex MD5.
 */
class HashIndex(entries: List<IndexEntry>) {
  val entries: List<IndexEntry> = entries.toList()
  private val md5s = this.entries.map { it.md5 }.toHashSet()
  private val nameSizes = this.entries.map { it.name to it.size }.toHashSet()

  fun containsMd5(md5: String): Boolean = md5.lowercase() in md5s

  fun hasNameAndSize(name: String, size: Long): Boolean = (name to size) in nameSizes

  fun plus(e: IndexEntry): HashIndex = HashIndex(entries + e.copy(md5 = e.md5.lowercase()))

  fun serialize(): String = entries.joinToString("") { "${it.md5}\t${it.size}\t${it.name}\n" }

  companion object {
    fun parse(text: String): HashIndex = HashIndex(
      text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        val size = f.getOrNull(1)?.toLongOrNull()
        if (f.size == 3 && f[0].isNotEmpty() && size != null && f[2].isNotEmpty()) IndexEntry(f[0].lowercase(), f[2], size) else null
      }.toList(),
    )
  }
}

object Md5 {
  fun hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).toHex()

  fun hexOfStream(input: InputStream): String {
    val md = MessageDigest.getInstance("MD5")
    val buf = ByteArray(64 * 1024)
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      md.update(buf, 0, n)
    }
    return md.digest().toHex()
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
