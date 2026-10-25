package com.wifishuriken.companion.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.wifishuriken.companion.core.Band
import com.wifishuriken.companion.core.SecurityClass

data class NetworkFilter(
  val tripId: Long? = null,
  val security: Set<SecurityClass> = emptySet(),
  val band: Band? = null,
  val channel: Int? = null,
  val vendor: String? = null,
  val hiddenOnly: Boolean = false,
  val randomizedOnly: Boolean = false,
  val fromUtc: String? = null,
  val toUtc: String? = null,
  val query: String? = null,
)

enum class NetworkSort { MOST_SEEN, NEWEST, STRONGEST, NAME, SECURITY, CHANNEL, VENDOR, MOST_TRIPS }

class NetworksQuery(val sql: String, val args: Array<Any?>) {
  fun toSupport() = SimpleSQLiteQuery(sql, args)
}

/** The WHERE clause (with leading " WHERE ", or empty) and its arguments for [filter] over `networks n`. */
fun networksWhere(filter: NetworkFilter): Pair<String, List<Any?>> {
  val where = ArrayList<String>()
  val args = ArrayList<Any?>()
  filter.tripId?.let {
    where += "EXISTS (SELECT 1 FROM sightings s WHERE s.mac = n.mac AND s.tripId = ?)"
    args += it
  }
  if (filter.security.isNotEmpty()) {
    where += "n.security IN (${filter.security.joinToString(",") { "?" }})"
    args.addAll(filter.security.map { it.name })
  }
  filter.band?.let { where += "n.band = ?"; args += it.name }
  filter.channel?.let { where += "(',' || n.channels || ',') LIKE ?"; args += "%,$it,%" }
  filter.vendor?.takeIf { it.isNotBlank() }?.let { where += "n.vendor LIKE ?"; args += "%$it%" }
  if (filter.hiddenOnly) where += "n.ssid = ''"
  if (filter.randomizedOnly) where += "n.randomized = 1"
  filter.fromUtc?.let { where += "n.lastSeenUtc >= ?"; args += it }
  filter.toUtc?.let { where += "n.firstSeenUtc <= ?"; args += it }
  filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
    where += "(n.ssid LIKE ? OR n.mac LIKE ?)"
    args += "%$it%"
    args += "%${it.uppercase()}%"
  }
  return (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) to args
}

/** Builds the Networks screen query; every ORDER BY has a stable tiebreak (mac) so paging is stable. */
fun buildNetworksQuery(filter: NetworkFilter, sort: NetworkSort, limit: Int, offset: Int): NetworksQuery {
  val (whereSql, whereArgs) = networksWhere(filter)
  val args = ArrayList(whereArgs)
  val order = when (sort) {
    NetworkSort.MOST_SEEN -> "n.sightings DESC, n.mac"
    NetworkSort.NEWEST -> "n.lastSeenUtc DESC, n.mac"
    NetworkSort.STRONGEST -> "n.bestRssi IS NULL, n.bestRssi DESC, n.mac"
    NetworkSort.NAME -> "n.ssid COLLATE NOCASE, n.mac"
    NetworkSort.SECURITY -> "n.security, n.mac"
    NetworkSort.CHANNEL -> "n.channel IS NULL, n.channel, n.mac"
    NetworkSort.VENDOR -> "n.vendor IS NULL, n.vendor COLLATE NOCASE, n.mac"
    NetworkSort.MOST_TRIPS -> "n.trips DESC, n.mac"
  }
  args += limit
  args += offset
  return NetworksQuery("SELECT n.* FROM networks n$whereSql ORDER BY $order LIMIT ? OFFSET ?", args.toTypedArray())
}

/** Security class counts for the networks matching [filter] (header stats). */
fun buildSecurityMixQuery(filter: NetworkFilter): NetworksQuery {
  val (whereSql, args) = networksWhere(filter)
  return NetworksQuery("SELECT n.security AS security, COUNT(*) AS n FROM networks n$whereSql GROUP BY n.security", args.toTypedArray())
}
