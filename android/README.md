# Shuriken Companion (Android prototype)

> **Disclaimer:** Written entirely by an AI (Claude, by Anthropic), not by the repository owner, who did not
> write or design it. Unofficial: not associated with or endorsed by the WiFi-Shuriken project; a personal
> one-off.


Live view of a WiFi-Shuriken scan log on an Android phone over USB, with no
firmware changes. The phone acts as USB host, opens the controller's read-only
MTP interface, and tails the active `shuriken_<timestamp>.csv` on the SD card.

## How it works

1. The phone needs USB host (OTG) support. Connect it to the controller's USB-C port.
2. Android matches the MTP interface (`res/xml/device_filter.xml`) and offers to
   open the app. Accepting grants USB permission. You can also start the app by
   hand and tap **Connect**.
3. Every 2 s the app:
   - lists the SD card root (`GetObjectHandles`),
   - picks the newest `shuriken_YYYYMMDDHHMMSS[_NN].csv`, which is the file the
     controller is writing,
   - reads only the bytes appended since the last poll (`GetPartialObject`).

   The controller flushes the active CSV before it answers either request, so
   every row logged so far is visible.
4. Rows are parsed as WiGLE CSV 1.4 and grouped by BSSID. For each AP the app
   keeps the last and best RSSI, a sighting count, and the position of the
   strongest sighting.

On first connect the app catches up on the existing log, at most 256 KiB per
poll. During catch-up it polls about every 50 ms.

## Layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/.../core/` | Plain Kotlin with no Android dependencies: CSV record splitting, WiGLE row parsing, log selection, AP aggregation, tail/poll logic. Covered by unit tests. |
| `app/src/main/java/.../usb/` | `MtpRootFileSource` (Android `MtpDevice` adapter) and USB device matching. |
| `ShurikenViewModel.kt` | USB/MTP session and poll loop. Survives rotation. |
| `MainActivity.kt` | Permission flow, attach/detach handling, UI. |

## Build

- Android Studio: open the `android/` directory.
- Command line: run `./gradlew testDebugUnitTest assembleDebug` (needs JDK 17+ and the Android SDK).
- CI: the **Android Companion** workflow builds `app-debug.apk` and uploads it as
  the `shuriken-companion-debug-apk` artifact.

## Known limits of the prototype

- **Files app or MTP provider conflicts.** On some phones the system MTP handler
  claims the device first, and `MtpDevice.open` then fails. If that happens,
  unplug, plug back in, and choose this app in the prompt.
- **Requires an SD card and GNSS time.** The firmware creates the log only once
  it has both, so the app shows "No scan log yet" until then.
- **Per-boot view.** When the controller starts a new log file, the app resets
  its tables to match.
- **Polling shares the SD card.** The controller releases its SD lock between
  transfer chunks, so logging continues during reads. A transfer that stalls for
  5 s is abandoned by the firmware; the app treats that as a transient error and
  retries on the next poll.
- **Power.** If the phone powers the Shuriken over OTG, the phone battery runs
  down faster.
- **Not tested on hardware yet.** The core logic is unit-tested; the USB/MTP path
  still needs a real device.
