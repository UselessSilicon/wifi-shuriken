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
  shows no alerts or warnings.
- **Phone GPS feed:** the phone sends its own GGA/RMC sentences to the Shuriken's NMEA
  port about once a second. The firmware uses them only when its own GNSS module has
  no fix. You can turn this off in Settings.
- **Archive:** every `shuriken_*.csv` on the SD card is copied into app storage and
  imported once. Files are matched by name and size, then MD5. The log being written
  is tailed live, record by record.
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
- **Settings:** the offline map file, database export (system file picker), phone GPS
  feed, units, and an About section.

## Privacy

- The merged manifest has **no network permission** (`INTERNET`,
  `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are removed). The
  `verifyNoNetworkPermission` Gradle check fails the build if any library adds one back.
- The map is fully offline. You copy a regional PMTiles file to the phone and pick it
  in Settings. The app copies it into its own storage. Map fonts and sprites are
  bundled in the APK at build time.
- Scan data, the archive and the database stay on the phone. The only way out is the
  database export, which you start yourself through the system file picker.

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

The **Android Companion** CI workflow builds `app-debug.apk`.

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

- On some phones the system MTP handler claims the device first. If the app cannot
  open it, unplug, plug back in, and choose this app when asked.
- The hardware path (MTP, NMEA feed) is covered by unit tests and a debug-build USB
  self-test, but has had limited testing on real devices.
