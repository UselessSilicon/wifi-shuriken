# Shuriken Companion v2 Implementation Plan

> **Disclaimer:** Written entirely by an AI (Claude, by Anthropic), not by the repository owner, who did not
> write or design it. Unofficial: not associated with or endorsed by the WiFi-Shuriken project; a personal
> one-off.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the `android/` prototype into a fully offline wardriving companion: live follow-me map with trip counters, phone GPS fed to the Shuriken, an on-phone log archive and database, and browse/sort/filter screens.

**Architecture:** Two Gradle modules. `:core` is plain Kotlin/JVM (no Android dependencies) holding parsing, validation, classification, geometry, NMEA, archive-sync planning and counters, all unit-tested on the JVM. `:app` is the Android app: Room database (tested with Robolectric), USB/MTP/NMEA I/O, a foreground live-session service, MapLibre offline map, and Jetpack Compose UI.

**Tech Stack:** Gradle 9.8.2 · AGP 9.4.1 (built-in Kotlin) · Kotlin 2.4.21 + Compose compiler plugin 2.4.21 · KSP 2.3.12 · Compose BOM 2026.09.00, Material 3 · Room 2.8.5 · Lifecycle 2.11.0 · Activity-compose 1.13.0 · Navigation-compose 2.10.2 · Coroutines 1.11.0 · MapLibre Android SDK 13.7.1 · JUnit 4.13.2 · Robolectric 4.17 · androidx.test core 1.7.0 / ext-junit 1.3.0 · JDK 25 (`~/.local/jdk/temurin-25`) · Android SDK at `~/Android/sdk` (platform `android-37.2`, build-tools 37.0.0) · Node 22 (build-time map style generation only).

**Spec:** `docs/superpowers/specs/2026-10-24-shuriken-companion-app-design.md`

## Global Constraints

- No personal data in the repo: synthetic fixtures only (MACs `AA:BB:CC:DD:EE:xx` or `00:00:5E:00:53:xx`, coordinates near 0–2°); never hard-code a real place.
- The app has no `INTERNET` or `ACCESS_NETWORK_STATE` permission; a build check fails if either appears in the merged manifest.
- Never assert DTR on any CDC port; never open the Console port; never set 1200 baud. Only NMEA `GGA`/`RMC` sentences are ever sent to the Shuriken.
- Nothing on the SD card is ever modified (MTP is read-only anyway).
- Target device Samsung Galaxy S23; minSdk 33; compile against API 37 (37.2 platform installed); Java/Kotlin bytecode target 17.
- No alerts or warnings anywhere in the UI; nothing ATAK/WinTAK related.
- Package `com.wifishuriken.companion`; application ID unchanged.
- Repo rules: commits only on branch `companion-app-v2` of the fork; never push upstream; local hooks (data-guard) must never be bypassed.
- Every task ends with `./gradlew test` (and from Task 1 on, `:app:verifyNoNetworkPermission`) green.

Build environment for every command below: `source ~/.local/bin/android-env.sh && cd android`.

## Review Focus

1. A log file that ends mid-row (power loss) or is a zero-row file (header only) → importer keeps all complete rows, records the file, never crashes. Test: Task 8 `ingestTruncatedAndHeaderOnlyFiles`.
2. The same log tailed live, then imported finished, then the app reinstalled-free re-import of the archive → each sighting counted once. Test: Task 8 `liveThenFinishedCountsOnce`.
3. An older log imported after newer trips → "new" credit moves to the older trip. Test: Task 8 `olderLogImportedLaterShiftsNewCredit`.
4. MTP "busy" / stalled transfer during sync → that file is retried next connection, no partial file archived, hash list unchanged. Test: Task 10 `syncOnceFailedCopyLeavesNothing` (fake source throwing mid-copy) and Task 9/10 hardware checks.
5. A network whose sightings span > 1,500 m (phone hotspot) → classified mobile, latest position, no averaging. Test: Task 3 `spreadOver1500mIsMobile`.
6. Plugging in powers the Shuriken on, so the newest card file is the *previous* trip's finished log until the new log appears → it must not be shown as live. Test: Task 10 `newestFileIsLiveOnlyOnceItGrows`.
7. Database lost but archive intact → app re-ingests the archive. Test: Task 10 `archivedFilesWithoutTripAreReingested`.

---

### Task 1: Toolchain upgrade and `:core` module split

**Files:**
- Modify: `android/gradle/wrapper/gradle-wrapper.properties` (Gradle 9.8.2), `android/settings.gradle.kts` (include `:core`), `android/build.gradle.kts`, `android/app/build.gradle.kts`, `android/gradle.properties`
- Create: `android/gradle/libs.versions.toml` (all versions from Tech Stack), `android/core/build.gradle.kts`
- Move: `android/app/src/main/java/com/wifishuriken/companion/core/*.kt` → `android/core/src/main/kotlin/com/wifishuriken/companion/core/`; `android/app/src/test/.../core/CoreTest.kt` → `android/core/src/test/kotlin/com/wifishuriken/companion/core/CoreTest.kt`
- Modify: `.github/workflows/android-companion.yml` (JDK 25, Gradle 9.8.2, same artifact name)

**Interfaces:**
- Produces: `:core` (plugin `org.jetbrains.kotlin.jvm` 2.4.21, `jvmToolchain(25)`, `jvmTarget` 17, JUnit 4) depended on by `:app` via `implementation(project(":core"))`; Gradle task `:app:verifyNoNetworkPermission` wired into `check`.

- [ ] **Step 1:** Regenerate the wrapper: `./gradlew wrapper --gradle-version 9.8.2` (run with the old wrapper first if needed). Expected: `gradle-wrapper.properties` names 9.8.2.
- [ ] **Step 2:** Write the version catalog and convert both build files to it. Root `plugins {}` declares AGP, `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.compose` (2.4.21) and `com.google.devtools.ksp` with `apply false`, so every module resolves Kotlin 2.4.21 from one classloader. App: AGP 9.4.1 with built-in Kotlin (do **not** apply `org.jetbrains.kotlin.android`), compose and KSP plugins; `compileSdk` = API 37 minor 2 via AGP 9's minor-API DSL, or `compileSdk = 37` plus `sdkmanager "platforms;android-37"` if the minor DSL is unavailable; `minSdk = 33`, `targetSdk = 37`; Java 17 compile options; `testOptions.unitTests.isIncludeAndroidResources = true`. `:core`: `kotlin { jvmToolchain(25); compilerOptions.jvmTarget = JVM_17 }` **and** `java { targetCompatibility = JavaVersion.VERSION_17; sourceCompatibility = JavaVersion.VERSION_17 }`. Add `app/src/test/resources/robolectric.properties` with `sdk=36`.
- [ ] **Step 3:** Move the core sources and tests into `:core` unchanged (package stays `com.wifishuriken.companion.core`).
- [ ] **Step 4:** Add `verifyNoNetworkPermission` per variant: in `androidComponents.onVariants`, register a task that takes `variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)` as input and fails if `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE` or `android.permission.ACCESS_WIFI_STATE` appears; an aggregate `verifyNoNetworkPermission` depends on all variants' tasks; `check` depends on it.
- [ ] **Step 5:** Run `./gradlew test :app:verifyNoNetworkPermission assembleDebug`. Expected: BUILD SUCCESSFUL; the 16 existing `CoreTest` tests pass under `:core:test`; `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 6:** Update the CI workflow (setup-java `temurin` 25; install the SDK platform with `sdkmanager "platforms;android-37.2"` or `"platforms;android-37"` and `"build-tools;37.0.0"`; `./gradlew test :app:verifyNoNetworkPermission assembleDebug`).
- [ ] **Step 7:** Commit `build: Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, split :core`.

### Task 2: Row validation, MAC and security classification (`:core`)

**Files:**
- Create: `core/src/main/kotlin/.../core/Mac.kt`, `Security.kt`, `Band.kt` (move `Band` out of `ApTable.kt`)
- Modify: `core/.../WigleCsv.kt`, `core/.../ApTable.kt`, `core/.../LogTailer.kt`, and the old UI that consumes them (`app/.../ApAdapter.kt`, `ShurikenViewModel.kt`) just enough to keep compiling (nullable `Band`, new `Rejected` case counted as unrecognized)
- Test: `core/src/test/kotlin/.../core/ValidationTest.kt`

**Interfaces:**
- Produces:
  - `object Mac { fun normalize(raw: String): String?; fun isRandomized(mac: String): Boolean }`
  - `enum class SecurityClass { OPEN_OR_OWE, WEP, WPA, WPA2, WPA2_WPA3, WPA3, ENTERPRISE }` and `fun classifySecurity(authMode: String): SecurityClass`
  - `enum class Band { GHZ_2_4, GHZ_5; companion object { fun fromChannel(ch: Int?): Band? } }` (1–14 → 2.4, 32–177 → 5, else null)
  - `WigleRecord.Rejected(val line: String, val reason: String)` added; `ApSighting.latitude/longitude` null for empty coordinates; `ApSighting.mac` normalized.

- [ ] **Step 1: Write failing tests** in `ValidationTest`:
  - `normalizesMacForms`: `Mac.normalize("aa-bb-cc-dd-ee-01") == "AA:BB:CC:DD:EE:01"`, `Mac.normalize("aabbccddee01") == "AA:BB:CC:DD:EE:01"`, `Mac.normalize("00:00:00:00:00:00") == null`, `Mac.normalize("zz:bb:cc:dd:ee:01") == null`.
  - `randomizedBit`: `isRandomized("AA:BB:CC:DD:EE:01")` true (0xAA has bit 0x02 set), `isRandomized("00:00:5E:00:53:01")` false.
  - `securityClasses`: `[WPA2-EAP-CCMP][ESS]`→ENTERPRISE, `[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]`→WPA2_WPA3, `[RSN-PSK-CCMP][ESS]`→WPA3, `[WPA2-PSK-CCMP][ESS]`→WPA2, `[WPA-PSK-TKIP][ESS]`→WPA, `[WEP][ESS]`→WEP, `[OPEN][ESS]`→OPEN_OR_OWE, `""`→OPEN_OR_OWE.
  - `rejectsBadRows`: each of channel 197, RSSI 1, RSSI -151, FirstSeen `2026/10/21 13:30:00`, year 1999, lat 91, `0,0` coordinates, MAC `00:00:00:00:00:00` gives `WigleRecord.Rejected`; channel 0 and RSSI -150 are accepted.
  - `emptyCoordinatesKeptWithoutPosition`: empty lat/lon → `Wifi` with null coordinates.
  - `ssidNulRemovedAndEmptyIsHidden`: SSID `"a\u0000b"` → `"ab"`; empty SSID → `""` (UI shows hidden).
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*ValidationTest*'`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement per spec §8.3 (FirstSeen regex `^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$`, parsed as UTC, year 2000–2079; coordinate `0,0` means both |x| < 1e-6).
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: all pass (existing tests updated only where the parser's return type changed).
- [ ] **Step 5:** Commit `feat(core): spec row validation, MAC and security classification`.

### Task 3: Geometry and network position estimate (`:core`)

**Files:** Create `core/.../core/Geo.kt`, `PositionEstimator.kt`; Test `core/src/test/.../PositionEstimatorTest.kt`

**Interfaces:**
- Produces:
  - `object Geo { fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double; fun maxPairwiseM(points: List<Pair<Double, Double>>): Double; fun bearingDeg(...): Double }` (earth radius 6,371,000 m; `maxPairwiseM` uses the bounding-box diagonal above 300 points)
  - `data class SightingPoint(val lat: Double, val lon: Double, val rssi: Int?, val tripId: Long)`
  - `enum class EstimateMethod { NONE, MOBILE, EDGE_OF_RANGE, RSSI_CENTROID }`, `enum class Confidence { NONE, LOW, MEDIUM, HIGH }`
  - `data class PositionEstimate(val lat: Double?, val lon: Double?, val method: EstimateMethod, val confidence: Confidence?, val radiusM: Double?, val maxSpreadM: Double?)`
  - `fun estimatePosition(points: List<SightingPoint>, band: Band?): PositionEstimate` — points in time order.

- [ ] **Step 1: Write failing tests:**
  - `noPoints` → method NONE, lat null.
  - `singleSightingRadiusByBand`: one point, `Band.GHZ_2_4` → EDGE_OF_RANGE, LOW, radius 150.0; `Band.GHZ_5` → radius 80.0; `null` band → 150.0 (same as the desktop pipeline: only 5 GHz gets 80 m).
  - `spreadOver1500mIsMobile`: points (1.0,1.0) and (1.0,1.02) (~2.2 km) → MOBILE, confidence NONE, position equals the **last** point, radius null.
  - `weightedCentroidFavoursStrongSignal`: points at (1.0,1.0) rssi -40 and (1.0,1.001) rssi -80 → centroid longitude < 1.0002; null RSSI weighted as -90.
  - `confidenceRules`: 3 trips with sightings in ≥3 quadrants >10 m from centroid → HIGH; 2 trips → MEDIUM; 1 trip → LOW.
  - `radiusFloor30m`: two very close points → radius 30.0.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*PositionEstimatorTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement per spec §8.4: weight `10^(rssi/20)`; radius = max(30, weighted mean distance to centroid); quadrant = `floor(bearing/90)` for points > 10 m from centroid.
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): network position estimate`.

### Task 4: NMEA forwarding and module status (`:core`)

**Files:** Create `core/.../core/Nmea.kt`; Test `core/src/test/.../NmeaTest.kt`

**Interfaces:**
- Produces:
  - `object Nmea { fun checksumOk(sentence: String): Boolean; fun isForwardable(sentence: String): Boolean; fun frame(sentence: String): ByteArray }` — forwardable = valid checksum and type `GGA` or `RMC` (any talker: `GP`, `GN`, `GL`, ...); `frame` trims and appends `\r\n`, ASCII.
  - `data class ModuleFix(val fix: Boolean, val satellites: Int?, val hdop: Double?)`
  - `class NmeaLineAssembler { fun feed(bytes: ByteArray, len: Int): List<String> }` (splits on `\n`, drops `\r`, discards lines > 120 chars and the first partial line after creation)
  - `fun parseModuleFix(ggaSentence: String): ModuleFix?` (fix = quality field > 0)

- [ ] **Step 1: Write failing tests** (build test sentences with a checksum helper in the test):
  - `onlyGgaAndRmcForwarded`: `$GNGGA…`, `$GPRMC…` true; `$GPGSV…`, `$GPTXT…`, bad checksum false.
  - `frameAddsCrLf`: `frame("$GPRMC,...*hh")` ends with bytes 13,10 and contains no other CR/LF.
  - `assemblerDropsFirstPartialLineAndSplits`: feeding `"PARTIAL\r\n$GNGGA,...\r\n$GN"` then `"RMC,...\r\n"` yields exactly the GGA and RMC lines.
  - `moduleFixFromGga`: quality `1`, sats `09`, hdop `0.9` → `ModuleFix(true, 9, 0.9)`; quality `0`, empty sats → `ModuleFix(false, null, null)` (empty field = null).
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*NmeaTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): NMEA forwarding filter and module fix parsing`.

### Task 5: Archive sync planner and hash index (`:core`)

**Files:** Create `core/.../core/ArchiveSync.kt`, `HashIndex.kt`; Test `core/src/test/.../ArchiveSyncTest.kt`

**Interfaces:**
- Consumes: `ShurikenLogName` (existing, `LogFiles.kt`).
- Produces:
  - `data class CardFile(val name: String, val size: Long, val handle: Int)`
  - `data class IndexEntry(val md5: String, val name: String, val size: Long)`
  - `class HashIndex(entries: List<IndexEntry>) { fun containsMd5(md5: String): Boolean; fun hasNameAndSize(name: String, size: Long): Boolean; fun plus(e: IndexEntry): HashIndex; fun serialize(): String; companion object { fun parse(text: String): HashIndex } }` — one line per entry `md5<TAB>size<TAB>name`, lower-case hex MD5; malformed lines ignored.
  - `data class SyncPlan(val toFetch: List<CardFile>, val heldBack: CardFile?, val skipped: List<CardFile>)`
  - `fun planSync(card: List<CardFile>, index: HashIndex): SyncPlan` — only `ShurikenLogName.parse`-able names; newest log held back; name+size match skipped.
  - `sealed interface FetchedDecision { data object Archive : FetchedDecision; data object DuplicateContent : FetchedDecision }` and `fun decideFetched(md5: String, index: HashIndex): FetchedDecision`. A duplicate is still **recorded** in the index (its own name and size, the existing MD5) so the next connection's name+size check skips it without fetching; the file itself is not kept.
  - `object Md5 { fun hex(bytes: ByteArray): String; fun hexOfStream(input: java.io.InputStream): String }`

- [ ] **Step 1: Write failing tests:**
  - `holdsBackNewestAndIgnoresNonLogs`: card has `RESETLOG.CSV`, `controller.applied.bin`, `shuriken_20260101000000.csv`, `shuriken_20260102000000.csv` → `heldBack.name == "shuriken_20260102000000.csv"`, `toFetch` = only the older log.
  - `suffixOrdering`: `shuriken_20260102000000_02.csv` is newer than `shuriken_20260102000000.csv`.
  - `nameAndSizeMatchSkippedWithoutFetch`.
  - `sameContentDifferentNameIsDuplicate`: `decideFetched(md5 already present)` → DuplicateContent; after `index.plus(IndexEntry(md5, otherName, size))`, `planSync` skips `otherName`.
  - `unchangedIndexReplansSameFile`: planning again with an unchanged index re-lists the file in `toFetch` (pure planner property; the real failure path is tested in Task 10).
  - `indexRoundTripAndMalformedLines`: serialize→parse equality; a line with 2 fields is ignored.
  - `md5KnownValue`: `Md5.hex("abc".toByteArray()) == "900150983cd24fb0d6963f7d28e17f72"`.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*ArchiveSyncTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement (`java.security.MessageDigest`).
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): archive sync planner and MD5 hash index`.

### Task 6: Trip counters and trip stats (`:core`)

**Files:** Create `core/.../core/TripCounters.kt`, `TripStats.kt`; Test `core/src/test/.../TripCountersTest.kt`

**Interfaces:**
- Consumes: `ApSighting`, `Band`, `classifySecurity`, `Geo`.
- Produces:
  - `data class CounterSnapshot(val ghz24: Int, val ghz5: Int, val withGps: Int, val noGps: Int, val new: Int, val seenBefore: Int, val open: Int, val perMinute: List<Int>)`
  - `class TripCounters(private val knownBefore: (mac: String) -> Boolean, private val bucketMs: Long = 60_000) { fun add(s: ApSighting, nowMs: Long); fun snapshot(): CounterSnapshot }` — counts unique MACs; a MAC moves from noGps to withGps on its first positioned sighting; `new` = !knownBefore at first sighting this trip; `perMinute` = new unique MACs per bucket, last 30 buckets.
  - `data class TrackPoint(val tMs: Long, val lat: Double, val lon: Double)` and `fun trackDistanceM(points: List<TrackPoint>): Double` (ignores jumps > 200 m between points 1 s apart as GPS glitches: skip segments implying > 200 km/h).

- [ ] **Step 1: Write failing tests:** `uniqueMacCountedOnce`, `gpsUpgradeMovesBetweenCounters`, `newVsSeenBefore` (knownBefore lambda), `openCountsOpenOrOwe`, `perMinuteBuckets`, `distanceSkipsGlitch` (three points where the middle one implies 1,000 km/h → distance equals the direct first-to-last segment).
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*TripCountersTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): live trip counters and track distance`.

### Task 7: CDC interface pairing and OUI lookup (`:core`) + OUI asset generation

**Files:** Create `core/.../core/UsbLayout.kt`, `Oui.kt`; Test `core/src/test/.../UsbLayoutOuiTest.kt`; Modify `app/build.gradle.kts` (task `generateOuiAsset`)

**Interfaces:**
- Produces:
  - `data class IfaceDesc(val number: Int, val classCode: Int, val name: String?)` (in descriptor order)
  - `data class ShurikenInterfaces(val mtp: Int, val nmeaControl: Int, val nmeaData: Int)`
  - `fun findInterfaces(ifaces: List<IfaceDesc>): ShurikenInterfaces?` — MTP = class 6 named `MTP`; NMEA control = class 2 named `NMEA`; NMEA data = the first class-10 interface after it in order. Never returns the `Console` pair.
  - `class OuiTable(lines: Sequence<String>) { fun vendor(mac: String): String? }` — asset line format `AABBCC<TAB>Vendor`, 24-bit prefixes.
  - Gradle task `generateOuiAsset`: downloads `https://standards-oui.ieee.org/oui/oui.csv` into `build/oui/oui.csv` (cached; if offline and no cache, writes an empty asset and warns), converts to `build/generated/assets/oui/oui.tsv`, registered as a generated assets source for `main`.

- [ ] **Step 1: Write failing tests:** `pairsNmeaDataAfterControl` (layout Console(2)/data(10)/MTP(6)/NMEA(2)/data(10) → nmeaData is the last interface number), `missingNmeaReturnsNull`, `ouiLookupNormalizesMac` (`AA-BB-CC-...` → vendor from a synthetic table line `AABBCC\tExample Vendor`), `ouiUnknownPrefix` → null.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*UsbLayoutOuiTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement; add the Gradle task.
- [ ] **Step 4:** Run `./gradlew :core:test :app:generateOuiAsset`. Expected: PASS; `app/build/generated/assets/oui/oui.tsv` has > 30,000 lines (or is empty with a warning when offline).
- [ ] **Step 5:** Commit `feat: CDC interface pairing, OUI table and build-time OUI asset`.

### Task 8: Room database, ingestion and "new" recomputation (`:app`)

**Files:**
- Create: `app/src/main/java/.../data/Entities.kt`, `Dao.kt`, `CompanionDb.kt`, `Ingestor.kt`, `NetworkQuery.kt`
- Modify: `core/.../CsvRecordSplitter.kt`, `core/.../LogTailer.kt` (record offsets)
- Test: `app/src/test/java/.../data/IngestorTest.kt` (Robolectric, `@Config(sdk = [36])`, in-memory Room); `core/src/test/.../CoreTest.kt` (add `splitterReportsRecordOffsets`)

**Interfaces:**
- Consumes: `WigleCsv`, `CsvRecordSplitter`, `classifySecurity`, `Band`, `Mac`, `estimatePosition`, `ShurikenLogName`.
- Produces:
  - Entities: `TripEntity(id PK auto, logName UNIQUE, startUtc: String, endUtc: String?, rows: Int, rejected: Int)`, `SightingEntity(id PK, tripId FK, byteOffset: Long, mac, ssid, authMode, security: String, channel: Int?, band: String?, rssi: Int?, tsUtc, lat: Double?, lon: Double?, altM: Double?, accuracyM: Double?)` with UNIQUE(`tripId`,`byteOffset`), `NetworkEntity(mac PK, ssid, security, band, channels, firstSeenUtc, lastSeenUtc, firstTripId, sightings, trips, bestRssi, vendor, randomized, estLat, estLon, estMethod, estConfidence, estRadiusM, maxSpreadM)`, `TrackPointEntity(tripId, tMs, lat, lon)`, `ArchivedFileEntity(md5 PK, name, size, path, importedAtUtc)`.
  - `class Ingestor(db: CompanionDb, oui: OuiTable?) { suspend fun ingestFile(logName: String, bytes: ByteArray): IngestResult; suspend fun ingestRecords(logName: String, records: List<CsvRecord>): IngestResult; suspend fun recomputeNetworks(macs: Collection<String>? = null); suspend fun recomputeTrip(tripId: Long) }` — `ingestFile` splits a whole file from offset 0; `ingestRecords` is the live path fed by `LogTailer`. `byteOffset` = offset of the record's first byte in the file, so live-tailed rows and a later full import share keys; inserts use IGNORE. `IngestResult(rowsInserted: Int, rejected: Int, macsTouched: Set<String>)`.
  - Trip totals (`rows`, `rejected`, `uniqueMacs`, `positionedRows`, `newCount`, `endUtc`) are **stored** on `TripEntity` and recomputed by `recomputeTrip` with queries scoped to that trip (never incremented), so re-ingest can't double them and the Drives list never groups over all sightings. Rejected rows are recorded in a small `RejectedRowEntity(tripId, byteOffset)` with UNIQUE(`tripId`,`byteOffset`) so the count is idempotent.
  - `recomputeNetworks` recomputes aggregates and the position estimate, and sets `firstTripId` to the trip with the earliest `startUtc` among the network's sightings (so "new" is chronological).
  - `CsvRecordSplitter.feed` returns `List<CsvRecord>` with `data class CsvRecord(val offset: Long, val text: String)` (offset of the record's first byte from the start of the file, counting from the splitter's initial offset); `LogTailer` passes offsets through to a callback `onRecord(logName: String, record: CsvRecord)`.
  - In `NetworkQuery.kt`: `data class NetworkFilter(val tripId: Long? = null, val security: Set<SecurityClass> = emptySet(), val band: Band? = null, val channel: Int? = null, val vendor: String? = null, val hiddenOnly: Boolean = false, val randomizedOnly: Boolean = false, val fromUtc: String? = null, val toUtc: String? = null, val query: String? = null)` and `enum class NetworkSort { MOST_SEEN, NEWEST, STRONGEST, NAME, SECURITY, CHANNEL, VENDOR, MOST_TRIPS }`; the DAO builds the SQL from them (`RawQuery` or `SupportSQLiteQuery`).
  - `TripEntity` also has `mode: String?` (`DRIVE`, `WALK`, `BIKE`; null = not set).
  - DAO queries used by later tasks: `tripsByStartDesc(): Flow<List<TripSummary>>`, `networksPage(filter: NetworkFilter, sort: NetworkSort, limit: Int, offset: Int)`, `networkDetail(mac)`, `sightingsFor(mac)`, `isKnownBefore(mac: String, tripStartUtc: String): Boolean`, `newCountForTrip(tripId)`, `setTripMode(tripId, mode)`.

- [ ] **Step 1: Write failing tests** (synthetic CSV text built in the test):
  - `splitterReportsRecordOffsets` (core): feeding a pre-header, header and two rows in two chunks yields the rows with offsets equal to their byte positions in the full text, including when a quoted SSID contains a newline.
  - `ingestCountsAndClassifies`: 3 valid rows, 1 rejected → `rowsInserted == 3`, `rejected == 1`, network security `WPA2`.
  - `liveThenFinishedCountsOnce`: feed the file to a `CsvRecordSplitter` in two chunks split **in the middle of a row**, pass the emitted records to `ingestRecords`, then `ingestFile` the whole file → 3 sightings total and trip `rows == 3`.
  - `reimportSameFileNoDuplicates`: ingest twice → sightings and trip `rows`/`rejected` unchanged.
  - `queriesUseIndexes`: `EXPLAIN QUERY PLAN` for `tripsByStartDesc`, `networksPage` (default sort) and `sightingsFor` contains no full `SCAN` of the sightings table.
  - `ingestTruncatedAndHeaderOnlyFiles`: last line without newline is not inserted; header-only file creates a trip with 0 rows and no crash.
  - `olderLogImportedLaterShiftsNewCredit`: ingest trip B (2026-01-02) containing MAC X, then trip A (2026-01-01) containing X → `firstTripId` of X == trip A's id; `newCountForTrip(B) == 0`.
  - `positionEstimateStored`: two positioned sightings → estMethod `RSSI_CENTROID`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*IngestorTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement entities, DAO, DB (indexes on `SightingEntity.mac`, `SightingEntity.tripId`, `NetworkEntity.security`, `NetworkEntity.lastSeenUtc`, `NetworkEntity.firstTripId`, `NetworkEntity.sightings`, `TripEntity.startUtc`), a DAO insert for `TrackPointEntity`, and `Ingestor`. Trip `startUtc` from the log name timestamp; `endUtc` = latest row time. Keep `ShurikenViewModel` compiling against the callback form of `LogTailer`.
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5:** Commit `feat(app): Room database, ingestion with offset de-dup, chronological new`.

### Task 9: USB layer: MTP session and NMEA port (`:app`)

**Files:** Create `app/.../usb/ShurikenConnection.kt`, `MtpCardSource.kt`, `NmeaPort.kt`; Modify `usb/ShurikenUsb.kt`, `usb/MtpRootFileSource.kt`

**Interfaces:**
- Consumes: `findInterfaces`, `IfaceDesc`, `RootFileSource`, `CardFile`, `NmeaLineAssembler`, `Nmea`.
- Produces:
  - `class ShurikenConnection private constructor(...) : AutoCloseable { val mtp: MtpDevice; val storageId: Int; val nmea: NmeaPort?; companion object { fun open(context: Context, device: UsbDevice): ShurikenConnection } }` — one `UsbDeviceConnection`; claims only the NMEA data interface for NMEA; `close()` order: NMEA stop + release → MTP close → connection close.
  - `class MtpCardSource(conn: ShurikenConnection) : RootFileSource { fun listCard(): List<CardFile>; fun copyTo(handle: Int, size: Long, out: java.io.OutputStream) }` — retries a failed call up to 5 times with 200 ms backoff (firmware "busy"); `copyTo` reads in 16 KiB chunks via `getPartialObject`; after 5 consecutive failures it throws `MtpStalledException`, on which the caller closes and reopens the `ShurikenConnection` once (the firmware stalls its endpoints after an abandoned transfer) before giving up for that connection.
  - `class NmeaPort(conn: UsbDeviceConnection, dataIface: UsbInterface) { fun start(onLine: (String) -> Unit); fun send(sentence: String): Boolean; fun stop() }` — dedicated thread, **blocking `bulkTransfer` only** (never `UsbRequest`/`requestWait`), read timeout 500 ms; never sends control requests (no DTR, no line coding).

- [ ] **Step 1:** Implement (Android I/O; no JVM unit tests beyond Task 7's pairing logic). Note `MtpDevice.close()` (and a failed `open()`) also closes the shared `UsbDeviceConnection`, so reopening after a stall means a new `ShurikenConnection` including a new `NmeaPort`. Add a debug-only screen action "Run USB self-test" that, **concurrently**, copies the largest finished log from the card to a temp file while `NmeaPort` reads module NMEA and sends a synthetic `$GPRMC` every second, then reports bytes copied, NMEA lines received and sends accepted.
- [ ] **Step 2:** Run `./gradlew test :app:verifyNoNetworkPermission assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Hardware check (owner present): install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, plug in the Shuriken, run the self-test. Expected: full copy matches the file's MD5 read separately, NMEA GGA lines arrive throughout the copy, no Shuriken reboot (reset log unchanged). Then unplug mid-copy and replug: the app recovers without crashing.
- [ ] **Step 4:** Commit `feat(app): USB connection with MTP card source and blocking NMEA port`.

### Task 10: Live session service: archive sync, live tail, GPS feed (`:app`)

**Files:** Create `app/.../live/LiveSessionService.kt`, `LiveSessionRepository.kt`, `ArchiveStore.kt`, `ArchiveSyncer.kt`, `ActiveLogTracker.kt`, `PhoneLocation.kt`, `data/Settings.kt`; Modify `AndroidManifest.xml`, `MainActivity.kt`; Test `app/src/test/.../live/ArchiveSyncerTest.kt`, `ActiveLogTrackerTest.kt`

**Interfaces:**
- Consumes: Tasks 4–9 (`planSync`, `decideFetched`, `HashIndex`, `Md5`, `Ingestor`, `LogTailer`, `TripCounters`, `MtpCardSource`, `NmeaPort`, `parseModuleFix`, `ShurikenConnection`).
- Produces:
  - `class Settings(context) { var feedPhoneGps: Boolean /* default true */; var units: Units }` (SharedPreferences) — created here because the service reads it; Task 15 only adds the screen.
  - `class ArchiveStore(dir: File) { fun index(): HashIndex; fun archivedFile(name: String): File; fun commitArchive(tmp: File, entry: IndexEntry): File; fun recordDuplicate(entry: IndexEntry) }` — files under `filesDir/archive/`, index at `filesDir/archive/index.tsv`, written atomically (temp + rename).
  - `interface CardSource { fun listCard(): List<CardFile>; fun copyTo(handle: Int, size: Long, out: java.io.OutputStream) }` (implemented by `MtpCardSource`) and `class ArchiveSyncer(store: ArchiveStore, ingestor: Ingestor) { suspend fun syncOnce(source: CardSource, activeLog: String?): SyncReport; suspend fun reingestOrphans(knownTripLogNames: Set<String>): Int }` — `syncOnce` = plan → for each `toFetch`: copy to a temp file in `filesDir/archive/tmp/`, MD5, decide, then `commitArchive` + `ingestFile` + `recomputeNetworks`, or `recordDuplicate`; on any exception for a file: delete its temp file, leave the index unchanged, continue with the next file. Held back = the planner's newest file **and** `activeLog`. `reingestOrphans` ingests every archived file whose name has no `TripEntity` (database lost or wiped).
  - `class ActiveLogTracker { fun observe(newest: CardFile?): String? }` — returns the name of the live log only once the newest file's size has **grown** between two observations (or a file newer than the one seen at connect appears); until then returns null and the UI shows "Waiting for the Shuriken's log…". When the live log changes, the caller resets `TripCounters` and re-runs `syncOnce` (the previous newest file is now finished).
  - `object LiveSessionRepository { val state: StateFlow<LiveState> }` with `data class LiveState(val connected: Boolean, val activeLog: String?, val tripStartMs: Long?, val counters: CounterSnapshot, val moduleFix: ModuleFix?, val feeding: Boolean, val phoneBatteryPct: Int?, val userLocation: TrackPoint?, val bearingDeg: Float?, val speedKmh: Float?, val track: List<TrackPoint>, val networks: List<MapNetwork>, val syncProgress: String?)` (`MapNetwork` is defined in Task 11's file `map/MapNetwork.kt`; create that data class here).
  - Track saving: phone track points are buffered from session start; once `ActiveLogTracker` names the live log, the buffer and all later points are inserted as `TrackPointEntity` rows for that trip (DAO insert from Task 8).
  - Service: manifest permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`; `foregroundServiceType="connectedDevice|location"`; `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or FOREGROUND_SERVICE_TYPE_LOCATION)`. Started only by `MainActivity` (USB attach intent or Connect button) after USB + location + notification permissions. Sequence: open `ShurikenConnection` → `reingestOrphans` → `syncOnce` → poll loop (`LogTailer` → `ingestRecords` → `TripCounters`; `ActiveLogTracker` on each listing) → in parallel `LocationManager.GPS_PROVIDER` every 1 s (track, location, bearing, speed) and `OnNmeaMessageListener` forwarding `Nmea.isForwardable` sentences via `NmeaPort.send` when `Settings.feedPhoneGps`; NMEA reads update `moduleFix`. On `MtpStalledException`: close and reopen the whole `ShurikenConnection` once and restart `NmeaPort` and forwarding. Stops on detach or user action.

- [ ] **Step 1: Write failing tests** (Robolectric/JVM temp dirs, in-memory Room, a fake `CardSource`):
  - `syncOnceArchivesAndIngestsOnce`: two finished logs + newest → both archived and ingested, index has 2 entries; a second `syncOnce` copies nothing (fake source records zero `copyTo` calls).
  - `syncOnceFailedCopyLeavesNothing`: the fake throws halfway through copying one file → no archive file for it, index unchanged, temp dir empty, other files still archived; the next `syncOnce` with a working source fetches it.
  - `duplicateContentRecordedNotKept`: same bytes under two names → one archived file, two index lines.
  - `archivedFilesWithoutTripAreReingested`: archive with 2 files and an empty database → `reingestOrphans` returns 2 and both trips exist.
  - `newestFileIsLiveOnlyOnceItGrows`: `observe(file size 100)` → null; `observe(same size)` → null; `observe(size 140)` → its name; a newer file appearing → the newer name.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*ArchiveSyncerTest*' --tests '*ActiveLogTrackerTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement `Settings`, `ArchiveStore`, `ArchiveSyncer`, `ActiveLogTracker`; run the tests. Expected: PASS.
- [ ] **Step 4:** Read the Android 17 (API 37) behaviour-changes notes for foreground services, USB and location (offline SDK docs if present; otherwise note the gap for the owner); then implement the service, repository, manifest entries (USB attach filter kept) and `MainActivity` wiring.
- [ ] **Step 5:** Run `./gradlew test :app:verifyNoNetworkPermission assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 6:** Hardware check (owner present): finished logs archived once (second connection fetches nothing); on plug-in the previous log is not shown as live and the new log takes over once it grows; counters move; "Phone GPS on" while feeding; rows gain positions before the module's fix (cold start near a window); screen-off recording continues; unplug stops the service.
- [ ] **Step 7:** Commit `feat(app): live session service with archive sync, tail, track and phone GPS feed`.

### Task 11: Offline map component (`:app`)

**Files:** Create `android/tools/gen-map-style.mjs`, `app/src/main/assets/map/style-light.json`, `style-dark.json` (generated, committed), `app/.../map/OfflineMap.kt`, `MapFileStore.kt`; Modify `app/build.gradle.kts` (task `fetchMapAssets`), `AndroidManifest.xml` (`tools:node="remove"` for INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE; MapLibre's location permissions are kept since the app needs them anyway; its Vulkan feature requirement is acceptable on the S23)

**Interfaces:**
- Produces:
  - `gen-map-style.mjs`: uses `@protomaps/basemaps@5.7.2` (`npx`, no package.json committed) to write light and dark styles with source `protomaps` url placeholder `pmtiles://MAP_FILE` and glyph/sprite URLs `asset://map/fonts/{fontstack}/{range}.pbf`, `asset://map/sprites/v4/light|dark`.
  - Gradle `fetchMapAssets`: downloads `protomaps/basemaps-assets` at commit `028c18f` (archive tarball), verifies a pinned SHA-256 recorded in the build file, extracts `fonts/` and `sprites/v4/` into `build/generated/assets/map/` (cached; fails the build with a clear message if offline and uncached).
  - `class MapFileStore(context, freeBytes: () -> Long = { filesDir.usableSpace }) { val file: File /* filesDir/map/basemap.pmtiles */; suspend fun importFrom(uri: Uri, onProgress: (Long, Long?) -> Unit): Result<Unit> }` — if the picked file's size is known, require free space ≥ size + 200 MB before starting; if unknown, check `freeBytes()` every 64 MB copied and abort (deleting the temp file) when it drops below 200 MB; copies to a temp file then renames.
  - `@Composable fun OfflineMap(modifier, followUser: Boolean, headingUp: Boolean, track: List<TrackPoint>, networks: List<MapNetwork>, userLocation: TrackPoint?, bearingDeg: Float?, onNetworkClick: (String) -> Unit)` — MapLibre `MapView` in `AndroidView`; the `Application` calls `MapLibre.getInstance(context)` then `MapLibre.setConnected(false)` before any `MapView` exists; loads style with `MAP_FILE` replaced by `file://` + absolute path; falls back to a background-only style when no map file exists; network dots as a clustered GeoJSON source coloured by security; new-this-trip ring.
  - Uses `MapNetwork` (defined in Task 10).
- [ ] **Step 1:** Run `node tools/gen-map-style.mjs app/src/main/assets/map`. Expected: two JSON files, each containing `pmtiles://MAP_FILE` and no URL starting with `http`.
- [ ] **Step 2:** Add `fetchMapAssets`; extend the local data-guard allowlist (`.git/hooks/data-guard.py`, local only) to accept `android/app/src/main/assets/map/*.json` and `android/tools/*.mjs`.
- [ ] **Step 3:** Implement `MapFileStore` (+ Robolectric tests `importChecksSpaceAndRenames`, `unknownSizeAbortsWhenSpaceRunsOut` using the injected `freeBytes`) and `OfflineMap`.
- [ ] **Step 4:** Run `./gradlew test :app:verifyNoNetworkPermission assembleDebug`. Expected: BUILD SUCCESSFUL; `verifyNoNetworkPermission` passes despite the MapLibre dependency.
- [ ] **Step 5:** Hardware check (owner present): import the owner's map file; map renders with **labels** (proves the `asset://` font URLs, including font names with spaces, resolve), sprites show, and everything still works in airplane mode.
- [ ] **Step 6:** Commit `feat(app): offline MapLibre map from a local PMTiles copy, no network permissions`.

### Task 12: App shell, theme and Live screen (`:app`)

**Files:** Create `app/.../ui/theme/Theme.kt`, `ui/AppNav.kt`, `ui/live/LiveScreen.kt`, `ui/components/CounterTile.kt`, `ui/components/RateGraph.kt`, `ui/components/StatusChip.kt`; Modify `MainActivity.kt` (Compose `setContent`), `app/build.gradle.kts` (`androidx.compose.ui:ui-test-junit4` as testImplementation, `ui-test-manifest` as debugImplementation); Delete `res/layout/*.xml`, `ApAdapter.kt`, `ShurikenViewModel.kt` (replaced by the service and repository)

**Interfaces:**
- Consumes: `LiveSessionRepository.state`, `OfflineMap`, `CounterSnapshot`.
- Produces: `@Composable fun LiveScreen(state: LiveState, map: @Composable (Modifier) -> Unit, onToggleHeading: () -> Unit, onRecentre: () -> Unit)` — stateless, with the map passed in as a slot so tests never create a MapLibre `MapView` (its native library can't load under Robolectric); `@Composable fun CompanionApp()` with bottom navigation `Live · Drives · Networks · Settings`; `CompanionTheme` (Material 3; light/dark from system; fixed brand palette matching spec §6 security colours).
- Live layout per spec §6.1 (B1): map ~55% with chips (Shuriken fix from `moduleFix`, "Phone GPS on" when feeding, phone battery), heading/north toggle and recentre; below, a 3×2 grid of tiles (2.4 GHz, 5 GHz, With GPS, No GPS, New, Seen before), networks-per-minute graph, trip strip (elapsed, distance, speed, open count).
- [ ] **Step 1:** Write a Compose test (Robolectric) `liveScreenShowsSixCounters`: `LiveScreen` with a fixed `LiveState` and an empty map slot shows the six labels and values, the "Phone GPS on" chip when `feeding`, and no text containing "alert" or "warning".
- [ ] **Step 2:** Run it. Expected: FAIL. Implement. Run `./gradlew test`. Expected: PASS.
- [ ] **Step 3:** Run `./gradlew assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit `feat(app): Compose shell, theme and Live screen (B1)`.

### Task 13: Drives screens (`:app`)

**Files:** Create `app/.../ui/drives/DrivesScreen.kt`, `DriveDetailScreen.kt`, `DrivesViewModel.kt`; Modify `data/Dao.kt` (queries)

**Interfaces:**
- Consumes: `tripsByStartDesc` (reads the stored per-trip totals from Task 8; never groups over all sightings), track points, `OfflineMap`.
- Produces: list rows per spec §6.2 (date/time, duration, distance, sightings, unique, new, % positioned); a drive/walk/bike label per trip, inferred from median speed of the trip's track (< 8 km/h WALK, < 30 km/h BIKE, else DRIVE) unless the owner set one via `setTripMode`, with a filter by mode; date-range filter; "All on map" toggle (all tracks + networks, clustered); drive detail (map, stats, networks scoped to that trip → navigates to Networks with `tripId`). Distance: stored phone track if present, else from positioned sightings in time order via `trackDistanceM`.
- [ ] **Step 1:** Robolectric DAO test `tripSummaryFields` (duration, sightings, unique, new, % positioned for a synthetic trip). Run → FAIL → implement queries → PASS.
- [ ] **Step 2:** Implement screens. Run `./gradlew test assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `feat(app): Drives list, all-on-map and drive detail`.

### Task 14: Networks and network detail (`:app`)

**Files:** Create `app/.../ui/networks/NetworksScreen.kt`, `NetworkDetailScreen.kt`, `NetworksViewModel.kt`, `StatsScreen.kt`; Modify `data/Dao.kt`

**Interfaces:**
- Consumes: `NetworkFilter`, `NetworkSort`, `networksPage`, `networkDetail`, `sightingsFor` (Task 8).
- Produces:
  - Screens per spec §6.3/§6.4 with paging (`limit`/`offset`, 100 per page); stats header (totals, security mix bar, % open, % WPA3-capable = WPA3 + WPA2_WPA3); stats view (channel usage per band, top vendors, most common names, mobile networks = estMethod MOBILE); detail (map with all sightings + estimate circle, security + raw AuthMode, band/channels, vendor, MAC + randomized, first/last seen, strongest, trips, sighting history).
- [ ] **Step 1:** Robolectric DAO tests: `filterBySecurityAndBand`, `searchMatchesNameOrMac`, `sortStrongestDescending`, `scopeToTrip`, `pagingStableOrder`. Run → FAIL → implement → PASS.
- [ ] **Step 2:** Implement screens. Run `./gradlew test assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `feat(app): Networks browse/sort/filter, stats and network detail`.

### Task 15: Settings, export and docs (`:app`, docs)

**Files:** Create `app/.../ui/settings/SettingsScreen.kt` (uses `Settings` from Task 10); Modify `android/README.md`, `README.md` (fork section, no personal data)

**Interfaces:**
- Produces: Settings per spec §6.5: map file (pick via `OpenDocument`, progress, replace, status), export database (`CreateDocument("application/vnd.sqlite3")`; produce the copy with `VACUUM INTO` a temp file, then stream it to the chosen URI, so WAL contents are included), phone-GPS feed switch, units (metric/imperial).
- [ ] **Step 1:** Robolectric test `exportContainsRecentRows`: a **file-backed** Room database in WAL mode, insert a trip and 3 sightings without closing, export → open the copy read-only → the 3 sightings are present. Run → FAIL → implement → PASS.
- [ ] **Step 2:** Update `android/README.md`: features, build (`source ~/.local/bin/android-env.sh` is local-only; document generic JDK 25 + SDK 37 requirements), privacy (no network permission, data stays on the phone), hardware notes (firmware with commit `014ed54` required). No personal data.
- [ ] **Step 3:** Run `./gradlew test :app:verifyNoNetworkPermission assembleDebug lint`. Expected: BUILD SUCCESSFUL (lint warnings allowed, no errors).
- [ ] **Step 4:** Commit `feat(app): settings, database export; docs`.

### Task 16: Whole-branch verification and hardware acceptance

- [ ] **Step 1:** `./gradlew clean test :app:verifyNoNetworkPermission assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 2:** `aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk` (build-tools 37.0.0). Expected: no `INTERNET`, no `ACCESS_NETWORK_STATE`.
- [ ] **Step 3:** Hardware acceptance with the owner: Task 9 and 10 checks, plus map file import and offline display, Drives/Networks screens on real history, airplane mode on (app fully functional), Shuriken reset log shows no unexpected resets.
- [ ] **Step 4:** Final review on the most capable model; then the owner decides on pushing the branch to the fork and opening a PR **within the fork** (`gh pr create --repo UselessSilicon/wifi-shuriken --base main --head companion-app-v2`).
