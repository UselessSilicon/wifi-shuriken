# Shuriken Companion v2: design

Status: draft for owner review. Builds on the working prototype in `android/`
(live MTP log tailing, confirmed working on the owner's phone).

## 1. Intent

Turn the prototype into a self-contained, fully offline wardriving companion
for a phone plugged into the WiFi-Shuriken over USB:

- **On the move** (drive, walk, bike): an overhead, follow-me map with live
  counters, so a glance tells you what the Shuriken is catching right now.
- **Parked or at home**: browse every past drive and every network ever seen,
  sort and filter by "nerdy" detail (security version, cipher, band, channel,
  vendor and so on), and open any network to see everywhere it was spotted.

Why: the prototype shows only the current boot's log, in memory, as a list.
The owner wants history, a map and rich stats on the phone itself, while
keeping all data private and on the device.

## 2. Goals

1. Live screen (layout "B1", section 6.1) with an overhead follow-me map and six
   counters for the current trip: 2.4 GHz, 5 GHz, with GPS, no GPS, new, seen before.
2. Permanent on-phone history across all drives; "new" means never seen on any
   earlier trip.
3. Automatic import of finished logs from the Shuriken's SD card over MTP.
4. Phone GPS fed to the Shuriken so rows logged before the Shuriken's own GPS
   gets a fix still receive a position (section 7).
5. Drives, Networks and Network-detail screens with sorting, filtering and stats.
6. Fully offline map from a map file the owner copies onto the phone.
7. Live Shuriken status (GPS fix, satellites) in the Live screen.
8. Modern, sleek UI with light and dark modes.

## 3. Non-goals

- Warnings or alerts of any kind (evil twin, follow detection, watchlists,
  security nags). Stats are informational only.
- Anything related to ATAK or WinTAK (no exports, no data packages, no plugin).
- Online maps or any network access.
- Syncing with any desktop tool. The phone and any desktop tool read the same
  SD card independently; each skips files it has already imported.
- Publishing the app (Play Store etc.). It is installed over USB.
- Drone detection: planned as a separate Part 2 (section 12).

## 4. Anti-goals (failure even if it "works")

- **Any personal data leaving the phone or entering this public repository.**
  No capture data, logs, screenshots, real BSSIDs or SSIDs, device serials,
  coordinates or home paths in code, tests, docs or commit messages. Test
  fixtures use synthetic MACs (`AA:BB:CC:DD:EE:xx` or `00:00:5E:00:53:xx`) and
  coordinates near 0 to 2 degrees.
- **Hard-coding any real place.** The map must not default to, or bundle, any
  particular region. It centres on live GPS or on the data; the map file is chosen
  at run time.
- **Making the Shuriken misbehave.** Never send it anything except NMEA position
  sentences on its NMEA port (section 7). Never change a CDC port's line coding to
  1200 baud (that reboots the RP2350 into its bootloader). Never leave a CDC port
  "connected" (DTR asserted) without reading it: older firmware stalled and was
  reset by its watchdog in exactly that case.
- **Losing or double-counting data.** A drive tailed live and later imported as
  a finished file must count once.

## 5. Constraints

- **Target device:** Samsung Galaxy S23 (USB host/OTG supported). Other phones
  are not a goal.
- **Toolchain (owner asked for latest):** latest stable Kotlin, Android Gradle
  Plugin and Gradle; Jetpack Compose with Material 3; compile against the newest
  stable Android SDK (Android 17 / API 37 at time of writing); JDK 25 for builds.
  All existing code is updated to fit the new stack. minSdk is the builder's call,
  but no lower than what keeps the code simple (the owner only targets the S23).
- **No `INTERNET` permission.** The app has no network access at all, which
  guarantees nothing is uploaded and the map is truly offline.
- **Firmware facts the app relies on** (controller firmware including upstream
  commit `014ed54`, now installed):
  - USB product string `WiFi Shuriken`, VID:PID `2886:0058`. Interfaces are
    identified by their string descriptors `Console`, `MTP` and `NMEA` (interface
    numbers changed between firmware versions, so don't hard-code numbers).
  - **MTP** storage is read-only: GetObjectHandles, GetObjectInfo, GetObject,
    GetPartialObject (32-bit). The active CSV is flushed before it is listed or
    read. The SD lock is released between transfer chunks, so logging continues
    during reads. A transfer that stalls about 5 s is abandoned by the firmware.
  - **Log files** are `shuriken_<YYYYMMDDHHMMSS>[_NN].csv` on the card root, one per
    boot, named from the first GNSS time. The newest (timestamp, then suffix) is the
    one being written. Each starts with a `WigleWifi-1.4,...` pre-header and the
    column header `MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,
    CurrentLongitude,AltitudeMeters,AccuracyMeters,Type`.
  - `AccuracyMeters` is the GNSS HDOP multiplied by 5, not a true accuracy.
  - The firmware deduplicates per boot and resets its dedupe table when it acquires
    a fix, so one BSSID can appear several times in a log.
  - **NMEA port:** carries the GNSS module's own NMEA output (about 5 Hz) to the
    host, and accepts NMEA (`RMC`/`GGA`) from the host. The firmware uses host
    positions only while its own module has no usable fix (fewer than 3 satellites
    or a stale position); its own GNSS always takes priority.
  - **Console port:** human-readable status lines about every second (sweep
    counters, GPS state). Read-only for the app.
- **Volume:** design for at least 1 million stored sightings and 100k networks
  without the UI slowing down.
- **Map file format:** the owner's existing offline map is a PMTiles vector file
  in the **Protomaps basemap schema (v4)**, so the bundled style must target that
  schema. Fonts and sprites come from the Protomaps basemaps-assets set.

**Calls made by the designer (not the owner), recorded so they can be checked:**

- A **trip** is one Shuriken log (one boot). If the phone connects partway through,
  the Live counters still cover the whole current log, because the prototype
  already catches up on existing rows.
- A trip's **track and distance** come from the phone's own GPS when the phone was
  connected for that trip; otherwise (a log imported later) from the log's own row
  positions.
- **Phone GPS feed** has a Settings switch, on by default.
- **Database export** is a plain copy of the SQLite file, saved through the system
  file picker.
- **History starts from the card.** There is no separate "load old logs from files"
  feature: to bring older logs into the app, the owner puts them back on the SD card
  and the normal archive/import step picks them up (the hash list prevents
  duplicates).
- The **archive** (raw files plus hash list) and the database can be rebuilt from
  each other: if the database is lost, re-ingesting the archive restores it.

## 6. Screens

Navigation: a bottom bar with **Live · Drives · Networks · Settings**. Light and dark
follow the system setting. Map dots are coloured by security (green WPA2/WPA3,
blue WPA2/3 mixed, red open, plus distinct colours for WEP, TKIP-era WPA and
enterprise). New-this-trip networks get a violet ring.

### 6.1 Live (layout "B1", chosen by the owner)

- Top ~55%: overhead map that follows the phone, with heading-up or north-up
  toggle and a recentre button. A blue line shows the trip so far. Status chips:
  Shuriken GPS state (e.g. "GPS 9" green, "no fix" amber) and "Phone GPS ✓" while
  feeding.
- Bottom ~45%: six counter tiles for this trip (2.4 GHz, 5 GHz, With GPS, No GPS,
  New, Seen before), a live "networks per minute" graph, and a trip strip (elapsed
  time, distance, speed, open-network count).
- Counters count **unique networks this trip**. "With GPS" means the network has at
  least one positioned sighting this trip; "No GPS" means none yet.
- No alerts or warnings.

### 6.2 Drives

- One row per trip (one per Shuriken boot/log): date and time, duration, distance,
  sightings, unique networks, new networks, % of rows with a position.
- Filters by date range. An optional drive/walk/bike label per trip (set by the
  owner or inferred from speed; builder's call).
- **All on map**: every drive's track and networks together.
- Tapping a drive shows its map, stats and networks.

### 6.3 Networks

- Scope: all drives, or one drive.
- Header stats: totals, security mix bar, % open, % WPA3-capable.
- **Sort:** most seen, newest, strongest signal, name, security, channel, vendor,
  number of drives.
- **Filter:** security class (open/OWE, WEP, WPA/TKIP, WPA2, WPA2/3 mixed, WPA3,
  enterprise), band and channel, vendor, hidden SSID, randomized MAC, date range,
  plus a search box for name or MAC.
- Extra stats views (informational): channel usage per band, top vendors, most
  common names, WPA3 adoption, networks that moved (likely mobile hotspots).

### 6.4 Network detail

Small map with every sighting and the estimated position with its uncertainty
circle; security class and raw AuthMode (cipher CCMP/TKIP); band and channel(s);
vendor; MAC (and whether it's randomized); first and last seen; strongest signal;
number of drives; a sighting-by-sighting history.

### 6.5 Settings

Map file (pick, replace, status), export database (for backup, via the system file
picker), theme follows system, units. Nothing that uploads.

## 7. Phone GPS feed

- During a live session the app claims the `NMEA` interface alongside MTP on the
  same USB connection.
- It forwards the phone's own NMEA (`GGA` and `RMC`, from the platform's NMEA
  listener, about once a second) to the Shuriken.
- It **continuously reads** the port (the firmware writes its module's NMEA there).
  This keeps the firmware from blocking and gives the Live screen the module's
  live fix and satellite count.
- Expected benefit, from the owner's real drives: about 7 to 8% of rows on a drive
  currently have no position, almost all in the first 30 to 170 s after power-on
  before the module's first fix. Mid-drive dropouts are negligible. The feed covers
  that start-up window; it does not change precision once the module has a fix.
- On disconnect or stop, release the interface cleanly (DTR not left asserted).

## 8. Data

### 8.1 Storage and history

On-phone SQLite (Room or equivalent; builder's call) holding trips, sightings,
per-network aggregates, trip tracks (from the phone's own GPS) and imported-file
records. The database is never exported automatically.

### 8.2 Live tailing and import

- **Live:** keep the prototype's tailing of the active log (reads only appended
  bytes) and write rows to the database as they arrive.
- **Import and file archive (owner's design):** the app keeps its **own copy of
  every Shuriken log file** in app-private storage, alongside its database. On each
  USB connection it lists the card and compares it with what it already holds:
  1. Quick match first: a card file whose name and size equal an already-archived
     file is skipped without reading it.
  2. Every other finished log is copied to the phone, hashed with **MD5**, and
     checked against the **imported-hash list**. If the hash is new, the file is kept
     in the archive, ingested into the database, and its hash, name and size are
     appended to the list. If the hash is already listed (same content under another
     name), the copy is discarded.
  3. The hash list is persistent and reused on every connection; it is the single
     source of truth for "already imported". Deconflicting is purely at file level.
  Hold back the active log (the newest file); it is archived once it is finished
  (next connection). Read-only: nothing on the card is changed. Only
  `shuriken_*.csv` logs are archived (designer's call; `RESETLOG.CSV` and firmware
  images are ignored).
- **No double counting:** a log first tailed live and later seen as a finished file
  must end up counted once (e.g. by tracking per-file byte offsets, or by replacing
  that trip's rows with the finished file's). Builder's choice of mechanism.

### 8.3 Parsing and validation

The owner's private desktop pipeline uses these rules on the same logs; match them:

- A row is rejected if its MAC is invalid (or all zeros), FirstSeen is unparseable,
  channel is outside 0 to 196, RSSI is outside -150 to 0, or coordinates are
  invalid. Rows with **empty** coordinates are kept as "no position".
  Coordinates of exactly `0,0` count as no position.
- A truncated final line (power lost mid-write) is dropped, not fatal.
- Band: channels 1 to 14 are 2.4 GHz, 32 to 177 are 5 GHz.
- Security class from AuthMode: contains `EAP` → enterprise; `[WPA2` and `[RSN`
  → WPA2/3 mixed; `[RSN` only → WPA3; `[WPA2` → WPA2; `[WPA` → WPA (TKIP era);
  `[WEP` → WEP; otherwise open/OWE. (The firmware also logs OWE/Enhanced Open as
  `[OPEN]`; label it "open (or Enhanced Open)".)
- Randomized MAC: the locally-administered bit of the first octet is set.

### 8.4 Network position estimate

Same method as the desktop pipeline (median ~12 m from WiGLE's own estimates on
the owner's data):

- Positioned sightings only. If the largest distance between any two sightings is
  over 1,500 m, the network is **mobile**: show the latest position, no averaging.
- One sighting: that point, low confidence, uncertainty 150 m on 2.4 GHz, 80 m on
  5 GHz.
- Several: centroid weighted by `10^(RSSI/20)` (missing RSSI treated as -90 dBm).
  Uncertainty is the weighted mean distance from the centroid, at least 30 m.
  Confidence is high with at least 3 trips and sightings in at least 3 compass
  quadrants (more than 10 m from the centroid), medium with 2 or more trips,
  otherwise low.

### 8.5 Vendor names

Ship the IEEE OUI registry as an app asset (a few MB), refreshed when the app is
rebuilt. No lookups at run time.

## 9. Map

- MapLibre Native for Android, reading a local PMTiles vector file the owner copies
  to the phone and selects in Settings (system file picker; the app keeps a
  persistent read grant).
- Styles, fonts and sprites needed for the basemap ship inside the app, so nothing
  is fetched.
- Without a map file the app still works: tracks and network dots are drawn on a
  plain background.
- Large datasets: cluster or level-of-detail the network dots so "All on map" stays
  smooth.

## 10. Background behaviour

A live session runs in a foreground service (Android's ongoing notification) so
tailing, recording and the GPS feed continue with the screen off. It stops when the
Shuriken is unplugged or the owner ends it.

## 11. Quality

- Plain-Kotlin core (no Android dependencies), unit-tested: parsing and validation,
  security classification, aggregation, position estimate, new/seen logic,
  import de-duplication and no-double-count, NMEA forwarding framing.
- Synthetic fixtures only (section 4).
- Real-hardware testing on the S23 over USB debugging.
- Repository rules: work happens on branches of this fork; pull requests target
  this fork only (see `CLAUDE.md`). Never upstream.

## 12. Later (out of scope now)

**Part 2: drone detection.** Remote ID broadcasts (Bluetooth, which the phone can
receive itself; Wi-Fi beacons and NAN) would give drone ID, position and pilot
location. A cheaper hint from existing data is drone-maker network names and OUIs.
Would appear as a single chip on the Live map.

## 13. Left to the builder

Database library and schema; Compose architecture; exact colours, typography and
animation; chart rendering; clustering approach; the no-double-count mechanism;
drive/walk/bike inference; minSdk; how the map style is packaged; MTP polling
cadence beyond the prototype's.
