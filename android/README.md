# Shuriken Companion (Android)

> **Disclaimer:** Written entirely by an AI (Claude, by Anthropic), not by the repository owner, who did not
> write or design it. Unofficial: not associated with or endorsed by the WiFi-Shuriken project; a personal
> one-off.

An Android companion for a WiFi-Shuriken wardriving rig. The phone connects to the
controller over USB. While you drive, walk or bike, it shows the scan live on an
offline map, archives the Shuriken's log files, and keeps its own database for
browsing later. It needs no firmware changes beyond upstream commit `014ed54`. That
commit fixes MTP (see Hardware notes).

## Features

- **Live:** a map that follows you overhead, with your track and network dots coloured
  by security class. Counters show 2.4 GHz, 5 GHz, with GPS, no GPS, new (never seen
  before) and seen before. It also shows networks per minute and a trip summary. It
  shows no alerts or warnings. Before the Shuriken is plugged in, the map already
  follows the phone's own GPS, and a chip says whether the phone has a fix.
- **Phone GPS feed:** the phone sends its own GGA/RMC sentences to the Shuriken's NMEA
  port about once a second. The firmware uses them only when its own GNSS module has
  no fix. The phone produces these sentences only once it has a fix of its own, so Live
  shows "Phone GPS: waiting for fix" until then and "Phone GPS on" while feeding. The
  Shuriken's LED reflects only its own GNSS module, so it can stay amber while the phone
  feed is in use. You can turn the feed off in Settings.
- **Archive:** every `shuriken_*.csv` on the SD card is copied into app storage and
  imported once, streamed in chunks so long logs never have to fit in memory. Files are
  matched by name and size, then MD5. A file that cannot be imported is retried on the
  next sessions (beside the live view, never before it) and set aside after three
  failures, so one bad file cannot stop recording. The log being written
  is tailed live, record by record.
- **Import logs:** Settings > Import logs > Choose CSV files adds Shuriken CSV logs kept
  elsewhere (older logs, copies on a computer). File names may carry a prefix
  (`<anything>shuriken_YYYYMMDDHHMMSS.csv`); files without a log name are named from
  their first row. Files already imported (same MD5, or an older partial copy) are
  skipped, a longer copy of an existing log adds only its new rows, and a file with the
  same name but different content is skipped. Only the Shuriken's 11-column WiGLE 1.4
  layout is accepted; WiGLE app exports and KML are refused.
- **Drives:** a list of drives with distance, duration, sightings, unique networks,
  new networks, percentage with GPS and mode (drive, walk or bike: inferred, or set by
  hand). Filter by mode and date. You can show every drive on one map.
- **Networks:** all drives or one drive. Search by name or MAC. Eight sort orders.
  Filters: security class, band, channel, vendor, hidden SSID, randomized MAC and date.
  Header stats show the security mix, percentage open and percentage WPA3-capable. A
  stats view shows channel usage per band, top vendors, common names and networks that
  moved.
- **Network detail:** a map of every sighting with the estimated position and its
  uncertainty circle. Shows security and the raw AuthMode, channels, vendor (IEEE OUI),
  first and last seen, strongest signal, number of drives and the full sighting history.
- **Settings:** the offline map file, log import, database export (system file picker),
  phone GPS feed, units, and an About section.

## Privacy

- The merged manifest has **no network permission** (`INTERNET`,
  `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are removed). The
  `verifyNoNetworkPermission` Gradle check fails the build if any library adds one back.
- The map is fully offline. You copy a regional PMTiles file to the phone and pick it
  in Settings. The app copies it into its own storage. Map fonts and sprites are
  bundled in the APK at build time.
- Scan data, the archive and the database stay on the phone. The only way out is the
  database export, which you start yourself through the system file picker. Backup and
  device-to-device transfer are disabled for all app data (`allowBackup="false"` plus
  data extraction rules that exclude everything).
- Diagnostics are minimal: the app logs session steps and errors to logcat under the tag
  `ShurikenDiag` (connected, sync counts, failures, first GPS sentence written). It never
  logs positions, MAC addresses or network names.
- Use a release build on the phone (`assembleRelease`, below): it is not debuggable and
  carries no developer tooling. A debug build's data can be read over adb while USB
  debugging is on. Either way, turn USB/wireless debugging off when not developing.

## Getting a map file

Build a regional extract with the `pmtiles` CLI from the Protomaps daily build.
Choose a bounding box for your own area; about 1 GB covers a large region.

```
pmtiles extract https://build.protomaps.com/<YYYYMMDD>.pmtiles region.pmtiles --bbox=<minLon>,<minLat>,<maxLon>,<maxLat>
```

Copy `region.pmtiles` to the phone (USB or SD card). Then open Settings, tap
**Choose map file** and pick it. Map data © OpenStreetMap contributors.

## Hardware notes

- Controller firmware must include upstream commit `014ed54` (the MTP fix).
- USB device `2886:0058` ("WiFi Shuriken"). The app uses the MTP interface (read only)
  and the CDC data interface that follows the control interface named `NMEA`. It never
  uses the console port, never sets DTR, and never changes the baud rate. Setting
  1200 baud would reboot the controller into its bootloader.
- MTP and NMEA share one USB connection. NMEA uses blocking bulk transfers on its own
  thread only.
- The phone usually powers the Shuriken over OTG. A session also works if the Shuriken
  runs on its own power and is plugged in later: the archive catches up.
- The firmware creates a log only once it has an SD card and GNSS time.

## Build

Requirements: JDK 25 and an Android SDK with platform `android-37.2` and build-tools
`37.0.0`. Point `ANDROID_HOME` (or `local.properties`) at the SDK.

```
./gradlew test :app:verifyNoNetworkPermission assembleDebug
```

The first build downloads the IEEE OUI list and the pinned
`protomaps/basemaps-assets` fonts and sprites (checked against a SHA-256) into
`build/generated`. Later builds use the cached copies.

For the phone, build a release:

```
./gradlew :app:verifyNoNetworkPermission assembleRelease
```

It is signed only if your own `~/.gradle/gradle.properties` (never committed) sets
`companionStoreFile`, `companionStorePassword`, `companionKeyAlias` and
`companionKeyPassword`; otherwise the release APK is unsigned. Sign with the same key as
the app already installed: Android only updates an app in place when the key matches,
and a different key means uninstalling it, which deletes its database and archive
(backup is disabled on purpose).

The **Android Companion** CI workflow builds `app-debug.apk` (signed with a throwaway CI
key, so it cannot update an app installed from your own builds).

## Layout

| Path | Contents |
| --- | --- |
| `core/` | Plain Kotlin, no Android: WiGLE CSV parsing and validation, security and band classification, position estimate, NMEA framing, archive sync planning, trip counters, trip mode, USB interface layout, OUI table. |
| `app/.../data/` | Room database, ingest, network queries and stats, settings, database export. |
| `app/.../usb/` | MTP card source, NMEA port, connection lifecycle, debug self-test. |
| `app/.../live/` | Foreground live session service, archive sync, active-log tracking, phone location. |
| `app/.../map/` | Offline MapLibre map, map file store. |
| `app/.../ui/` | Compose screens: Live, Drives, Networks, Stats, Network detail, Settings. |
| `tools/gen-map-style.mjs` | Regenerates the bundled light/dark map styles from `@protomaps/basemaps`. |

## Known limits

- The app opens only for the Shuriken (USB 2886:0058); other MTP devices such as cameras
  and phones are ignored.
- On some phones the system MTP handler claims the device first. If the app cannot
  open it, unplug, plug back in, and choose this app when asked.
- Tested on a Samsung Galaxy S23 (Android 17) on 2026-10-25: connection, archive sync,
  live tailing and the phone GPS feed work. Longer drives and walks are still being
  tested.
