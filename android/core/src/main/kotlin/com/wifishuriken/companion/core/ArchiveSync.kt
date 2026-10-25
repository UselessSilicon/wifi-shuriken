package com.wifishuriken.companion.core

import java.io.OutputStream

/** A file in the root of the Shuriken's SD card as seen over MTP. */
data class CardFile(val name: String, val size: Long, val handle: Int)

/** Read-only access to the card (implemented over MTP in the app). */
interface CardSource {
  fun listCard(): List<CardFile>
  fun copyTo(handle: Int, size: Long, out: OutputStream)
}

data class SyncPlan(val toFetch: List<CardFile>, val heldBack: CardFile?, val skipped: List<CardFile>)

/**
 * Decides which card files to copy (design §8.2): only Shuriken logs; the newest log is being
 * written so it is held back; a file whose name and size are already in the index is skipped
 * without reading it.
 */
fun planSync(card: List<CardFile>, index: HashIndex): SyncPlan {
  val logs = card.mapNotNull { f -> ShurikenLogName.parse(f.name)?.let { it to f } }
  val newest = logs.maxByOrNull { it.first }?.second
  val rest = logs.map { it.second }.filter { it != newest }
  val (skipped, toFetch) = rest.partition { index.hasNameAndSize(it.name, it.size) }
  return SyncPlan(toFetch = toFetch, heldBack = newest, skipped = skipped)
}

sealed interface FetchedDecision {
  data object Archive : FetchedDecision
  data object DuplicateContent : FetchedDecision
}

/** After copying and hashing: keep it, or it's the same content as a file already imported. */
fun decideFetched(md5: String, index: HashIndex): FetchedDecision =
  if (index.containsMd5(md5)) FetchedDecision.DuplicateContent else FetchedDecision.Archive
