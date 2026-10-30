# WiFi-Shuriken (unofficial personal fork)

> **Fork note:** This is an unofficial personal fork of
> [CoD-Segfault/wifi-shuriken](https://github.com/CoD-Segfault/wifi-shuriken), not associated with or
> endorsed by the WiFi-Shuriken project; a personal one-off. The Android companion app (`android/`), the
> documents in `docs/superpowers/`, and the fork-specific parts of this README (this note, "What's in this
> fork" and Part 2) were written entirely by an AI (Claude, by Anthropic), not by the fork owner, who did
> not write or design them. Part 1 is the upstream project's text.

## What's in this fork

The hardware and case files are unchanged from upstream. The firmware is upstream plus one AI-written
change, the spatial re-log (see the pointer below). This fork also adds an Android app and its documents.

| Path | Origin | Contents |
| --- | --- | --- |
| `src/`, `include/`, `lib/`, `platformio.ini`, `scripts/`, `test/` | Upstream, modified by the re-log change | Controller (XIAO RP2350) and scanner (XIAO ESP32-C5) firmware |
| `lib/wifi_common/wifi_relog_gate.*`, the dedupe TTL change, `RELOG_*` settings | Fork only (AI-written) | Spatial re-log: logs an access point again after moving 25 m or a 6 dB signal gain |
| `docs/spatial-relog.md` | Fork only (AI-written) | Re-log behaviour, size impact and the safe flash procedure |
| `case_stl/` | Upstream | Case models |
| `docs/sd-card-updates.md` | Upstream | SD card firmware update instructions |
| `.github/workflows/build-firmware.yml` | Upstream | Firmware build (`firmware-artifacts`) |
| `android/` | Fork only (AI-written) | Shuriken Companion Android app, see Part 2 |
| `docs/superpowers/` | Fork only (AI-written) | The app's design and implementation plan |
| `.github/workflows/android-companion.yml` | Fork only (AI-written) | App build and tests (`shuriken-companion-debug-apk`) |
| `CLAUDE.md` | Fork only (AI-written) | Working rules for the AI assistant in this repository |

**Spatial re-log:** this fork's firmware logs an access point more than once per drive (default 25 m or
6 dB, on by default, `0.2-relog1`). See [docs/spatial-relog.md](docs/spatial-relog.md) for behaviour, how to
turn it off, and the safe flash procedure.

---

# Part 1: The WiFi-Shuriken device (upstream)

*Text from the upstream project's README. Only the headings and section order were rearranged so that
device use comes before the technical details.*

## Overview

WiFi-Shuriken is a distributed WiFi scanning and logging system built around two roles, controller and scanner. An RP2350-based controller manages the overall system, while one or more ESP32-C5 scanner nodes perform channel-specific WiFi scans and report results back to the controller.

The controller handles functions such as USB serial, GNSS, SD logging, and scan scheduling. It talks to the scanner nodes over SPI, with optional shift-register based chip select fanout for multiple scanners. The controller firmware splits work across cores so scanner scheduling and transport can run independently from logging and GNSS processing.

Each ESP32-C5 scanner runs as an SPI slave and exposes a small command protocol for identity, scan requests, result counts, and result retrieval. Scan results are deduplicated and buffered on the scanner, then deduplicated again at the controller before being written to WiGLE CSV format with timestamp and position data from the GNSS subsystem.

## Using the Shuriken

### Status & Indicators

- RED LED = No SD Card, No SD Card Detected
- AMBER LED = No GPS Lock / No Location Lock
- GREEN LED = GO Go Go - Device operating as expected

Inside the case - AMBER LEDs are twinkling fast last stars at night on a wonderful drive to WarDrive. 
These are flickering because they're scanning for the WiFi Beacons

### Get Out & Go
Apply Power via USB-C next to the SD Card Slot.
Green LED means you're GPS Locked and SD Card Detected

When your drive is complete or you want the files;
- Remove the USB-C Power
- Grab the SD Card
- Upload the files to WiGLE for Free Internet Points

## Firmware downloads

The GitHub Actions `Build Firmware` workflow builds only the XIAO RP2350 controller
and XIAO ESP32-C5 scanner. Download its `firmware-artifacts` archive:

- `xiao_rp2350_controller.uf2`: RP2350 USB BOOTSEL flashing.
- `xiao_esp32c5_scanner-merged.bin`: ESP32-C5 USB flashing image.
- `sd-card-updates/controller.bin`: RP2350 SD card update image.
- `sd-card-updates/scanner.bin`: ESP32-C5 SPI update image, read from the SD card.
- `sd-card-updates/README.md`: instructions included with the update images.

For SD updates, copy the desired binaries to the card's **root**, keeping the
`controller.bin` and `scanner.bin` filenames. The installed firmware must support
SD updates. See [SD card update instructions](docs/sd-card-updates.md).

## USB SD card access

The controller exposes the SD card automatically as a read-only MTP device while
logging continues. MTP performs file-level reads through the firmware rather than
sharing raw FAT sectors, so the host cannot corrupt the card. Root-directory files
are refreshed when the host requests an object listing; the active CSV is flushed
before it is listed or read. Root object handles are streamed across as many MTP
packets as needed; nested directory contents and uploads/deletes are not exposed.
Directories are omitted from the listing. Downloads use the file size captured
at the start, allowing the logger to append between transfer chunks. Files too
large for a 32-bit MTP container are rejected instead of wrapping the transfer
length; partial reads remain available within the protocol's 32-bit offset range.
File timestamps come from the card, with 1980-01-01 used when none is recorded.
The read-only store reports zero space available for host writes, without scanning
the entire FAT for physical free space. Abandoned transfers time out after five
seconds without USB progress; hosts can recover with MTP reset or endpoint
clear-halt, and the CDC console/GNSS interfaces remain available.
Some hosts label the device as a camera because MTP uses the USB Still Image class.
The host also constructs the `camera:/...@usb:.../store_...` URI; firmware-provided
device, interface, and storage labels are kept short but cannot replace that URI.
KDE's camera backend may also show synthetic `about.txt` and `summary.txt` files
next to the `store_...` directory. Those files are generated by the host backend,
not by the controller or the SD card; the SD contents are inside the storage
directory.

---

# Part 2: Shuriken Companion Android app (this fork only)

*Written entirely by an AI (Claude, by Anthropic). Not part of, or endorsed by, the WiFi-Shuriken
project.*

An optional Android app for the device above. The Shuriken works exactly as described in Part 1 without
it. With it, the phone replaces the "grab the SD card" step: plug the phone into the Shuriken's USB-C
port and the app reads the card over the read-only MTP interface described in
[USB SD card access](#usb-sd-card-access). The firmware and the SD card are not modified.

What it does:

- **Live:** an overhead map that follows you, with network counters (2.4 GHz, 5 GHz, with GPS, no GPS,
  new, seen before) while the Shuriken logs.
- **Phone GPS feed:** the phone sends its position to the Shuriken, which uses it only when its own GNSS
  has no lock.
- **Archive:** every log on the card is copied into the app and imported once.
- **Drives and Networks:** browse past drives and every network seen, with sorting, filters and stats.
- **Offline:** the app has no internet permission. You copy a map file (PMTiles) to the phone.
  Nothing is uploaded; WiGLE uploads stay a manual step, as in Part 1.

Requirements: an Android phone with USB OTG (Android 13 or newer), and Shuriken controller firmware that
includes upstream commit `014ed54` (MTP compatibility fix). This fork's firmware already includes it.

Quick start:

1. Get the APK: the `shuriken-companion-debug-apk` artifact of the **Android Companion** workflow, or
   build it from `android/`.
2. Install it on the phone, then copy a `.pmtiles` map of your area to the phone and choose it in
   **Settings**.
3. Power the Shuriken (Part 1, "Get Out & Go") and plug the phone into its USB-C port. Accept the USB
   prompt and allow location.

Status: working in a first real-world test (2026-10-25, Samsung Galaxy S23). It is a debug build of a
personal project.

More: [android/README.md](android/README.md) (features, map files, privacy, hardware notes, build) and
the design and plan in [docs/superpowers/](docs/superpowers/).
