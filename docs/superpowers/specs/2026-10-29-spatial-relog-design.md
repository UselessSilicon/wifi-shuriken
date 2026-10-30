# Spatial Re-log: Design

> **Disclaimer:** Written entirely by an AI (Claude, by Anthropic), not by the repository owner, who did not
> write or design it. Unofficial: not associated with or endorsed by the WiFi-Shuriken project; a personal
> one-off on the fork `UselessSilicon/wifi-shuriken`.

- **Date:** 2026-10-29
- **Status:** Design agreed with the owner (section 12). No product code on this branch yet.
- **Scope:** controller (RP2350) and scanner (ESP32-C5) firmware, native unit tests, docs.

## 1. Intent and why

The log is meant to support locating access points afterwards (WiGLE-style weighted averaging of the
positions an AP was heard from). That works best with several rows per AP per pass: approaching, passing,
leaving. Today the device writes about one row per AP per power-on.

Cause: two identity dedupe tables drop every repeat of an AP (key = xxHash of SSID, BSSID, channel, band,
capabilities; RSSI excluded), one on each scanner (`src/scanner_main.cpp:406`) and one shared on controller
core1 (`src/controller_scanner_runtime.cpp:1355`). Each keeps the last 4000 identities with FIFO eviction
(`lib/wifi_common/wifi_dedupe.h`). The header comment states the original intent: "small enough that APs
fall off as you move away so re-observation from a different direction still produces a new log entry".
That only happens where a drive sees more than 4000 distinct APs; in lower-density areas nothing is ever
evicted, so an AP is logged once until power-off (plus once more after the first GNSS fix, via
`CONTROLLER_DEDUPE_RESET_ON_FIX_ACQUIRE`). This design makes the re-log spatial explicitly instead of
relying on table pressure.

## 2. Goals, non-goals, anti-goals

Goals
- Re-log an AP when the device has moved far enough or the signal got clearly stronger since its last row.
- Defaults: 25 m and 6 dB. Both configurable; 0 and 0 restore today's behaviour exactly.
- Bounded memory and SD traffic; no change to the CSV format.

Non-goals
- Changing the CSV columns, WiGLE header, file naming, or the companion app.
- Any on-device position estimation.

Anti-goals (a failure even if it "works")
- A parked or slow-moving device filling the card with rows of the same APs.
- New queue or SD drops on a normal drive (watch the existing counters, section 7).
- Behaviour change when the feature is configured off.

## 3. The rule

For each AP that reaches the SD-write step (core0, `Logger::appendCsvRow`, `lib/pico_logging/pico_logging.cpp:612`):

1. Not in the gate (first sight, or evicted): **log**, remember it.
2. In the gate, current row has a usable position, stored entry has none: **log** (this is today's
   "log again once a fix exists" outcome, now per AP instead of a global reset).
3. In the gate, both have positions, distance from the stored position >= `RELOG_DISTANCE_M`: **log**.
4. In the gate, `rssi >= stored_rssi + RELOG_RSSI_GAIN_DB`: **log**.
5. Otherwise: **suppress** (count it).

"Usable position" is exactly the logger's existing `have_location` test (location valid and younger than
`gps_field_max_age_ms`), so the gate and the written row always agree.

On every logged row the entry stores that row's position (or "none") and its RSSI. So after a distance
re-log the RSSI baseline resets to the new row's RSSI; a stronger reading later at the same place still
triggers rule 4, and moving on triggers rule 3.

No usable fix: rules 3 and 4 are not evaluated for positionless rows (rule 4 would spam blank-coordinate
rows when stationary indoors). The AP is logged once without a position, as today, and again by rule 2 when
a fix arrives.

Identity key: reuse `wifiDedupeHashFromResult` material (SSID, BSSID, channel, band, capabilities). An AP
that changes channel or security is a new identity, as today. Core0 only has a `QueuedScanResult`
(`lib/pico_logging/pico_logging.h:14`), which carries all five fields, so the gate recomputes the hash there;
add a small helper that builds the hash from those fields so both paths share one definition (call).

Position source: whatever `Logger` is currently pointed at (`setGpsSource`, hardware module first, phone
NMEA as fallback, `src/controller_main.cpp:478`). The gate reads the same `gps_ptr_` fix as the row, so a
source switch cannot make them disagree (call).

Threading: the gate is only touched on core0 (the drain loop and the fix-acquire hook both run there), so
it needs no locking (call).

Distance: equirectangular approximation in single-precision float,
`dx = dlon * cos(lat) * 111320`, `dy = dlat * 111320`, compare `dx*dx + dy*dy` against
`RELOG_DISTANCE_M^2` (no sqrt). Error is far below 1 m at 25 m. The RP2350 Cortex-M33 has a single-precision FPU.

## 4. Where it runs

Three places change:

| Place | Today | Change |
|---|---|---|
| Scanner table (ESP32-C5) | forget only on reset or FIFO pressure | entries also expire `WIFI_DEDUPE_TTL_MS` after insertion |
| Controller core1 master table | same | same expiry |
| Controller core0, SD-write step | nothing | new spatial gate (section 3) |

Why expiry upstream instead of removing the tables: they still do useful work. They collapse duplicate
results inside one scan and across scanners on overlapping channels, which keeps SPI and the core1 to core0
queue light. With expiry, an AP passes each table at most once per TTL, the gate decides whether that
pass becomes a row.

TTL default: 1000 ms when the feature is on, 0 (never expire, today) when it is off. Rationale: re-log
spacing can never be finer than one pass per TTL; at 50 km/h (about 14 m/s) a 25 m spacing needs at most
about 1.8 s between passes, and the real floor is the sweep time (`last_sweep_ms` in the periodic status
line), which is the builder's tuning reference.

Expiry implementation (no change to the slot array or backward-shift delete): add a parallel
`uint32_t fifo_ms[capacity]` holding each FIFO entry's insertion time. At the start of
`wifiDedupeTableRemember(table, hash, now_ms)`, pop and delete from the FIFO head while
`ttl_ms != 0 && now_ms - fifo_ms[head] >= ttl_ms` (millis is monotonic and the FIFO is insertion-ordered,
so the head is always the oldest). A hit does not refresh the timestamp, so a continuously heard AP passes
once per TTL. Memory: +16 KB per table at capacity 4000. Existing behaviour and tests are preserved when
`ttl_ms == 0`; the existing wrapper signature stays for callers that pass no time.

Both firmwares must carry the change: if the scanners keep never-expiring tables, repeats never reach the
controller and the gate is inert (safe, just ineffective). The scanner TTL lives in
`include/ScannerConfiguration.h`, the controller TTL and gate settings in `include/Configuration.h`.

Considered and rejected: having the controller send `CMD_DEDUPE_RESET` periodically instead of a scanner
TTL (no scanner reflash). The scanner handler calls `resetDedupeAndBuffers()`, which aborts the in-progress
scan and drops buffered results (`src/scanner_main.cpp:227`), so periodic resets would lose data.

## 5. Gate data structure and memory

New pure module `lib/wifi_common/wifi_relog_gate.{h,cpp}` (no Arduino, no TinyGPS; testable natively):

```
struct RelogGateEntry {        // 20 bytes at 64-bit hashes (with padding)
  WiFiDedupeHash key;          // 8
  int32_t lat_e7;              // 4, degrees * 1e7 (matches the %.7f CSV output)
  int32_t lon_e7;              // 4
  int8_t  rssi;                // 1
  uint8_t has_pos;             // 1
};
```

- Index: open addressing over `uint16_t` entry indices (8192 slots, 16 KB), FIFO eviction over the entry
  array (insertion order), same pattern and probe rules as `WiFiDedupeTable`.
- Capacity `RELOG_GATE_CAPACITY` default 4000 (entries 80 KB). Evicting an AP only means its next sighting
  counts as a first sight and is logged: one extra row, never a lost one.
- API sketch: `RelogDecision relogGateDecide(gate, key, has_pos, lat_e7, lon_e7, rssi, cfg)` returning
  log/suppress and updating the entry on log; `relogGateReset(gate)`.

Budget against RP2350 (520 KB SRAM): gate about 96 KB plus 16 KB for the master table's `fifo_ms`, about
112 KB added on the controller. Scanner: +16 KB for `fifo_ms` against the ESP32-C5's on-chip SRAM.
Not measured on this branch (PlatformIO is not installed in this environment). Builder: record
`pio run -e pico2_controller` and `pio run -e xiao_esp32c5_scanner` "RAM:" lines before and after; if the
controller margin is tight, halve `RELOG_GATE_CAPACITY` (eviction cost is one extra row).

## 6. Configuration (include/Configuration.h, include/ScannerConfiguration.h)

```
RELOG_DISTANCE_M        25    // 0 disables the distance rule
RELOG_RSSI_GAIN_DB       6    // 0 disables the signal rule
RELOG_GATE_CAPACITY   4000
CONTROLLER_DEDUPE_TTL_MS     (RELOG_DISTANCE_M || RELOG_RSSI_GAIN_DB) ? 1000 : 0
SCANNER_DEDUPE_TTL_MS 1000    // ScannerConfiguration.h; 0 = today
```

With `RELOG_DISTANCE_M == 0 && RELOG_RSSI_GAIN_DB == 0` the gate is compiled out and the controller TTL is
0: byte-for-byte today's behaviour on the controller. A scanner built with TTL > 0 against a disabled
controller only adds repeats that the never-expiring master table drops, so output is still unchanged.

## 7. Throughput

- Expected rows: an AP audible along about 100 to 200 m of road gives about 4 to 8 rows per pass instead of
  1; overall about 3 to 6 times more rows, less when stationary (rules 3 and 4 do not fire).
- SD volume: a row is about 120 to 150 bytes. Even 50 rows/s is under 8 KB/s, well within SD throughput;
  flush policy (`csv_flush_interval_ms`, every 25 rows) is unchanged.
- Queues: steady state per sweep resembles today's start-of-drive burst (every in-range AP is "new"), which
  the pipeline already handles: `SCAN_RESULT_QUEUE_DEPTH` 1024, `CONTROLLER_SCAN_RESULT_DRAIN_PER_LOOP` 64,
  `PROTO_MAX_RESULTS` 127 per scanner transfer. The gate runs before `appendCsvRow` and costs one hash
  probe plus a few float operations per row.
- Counters to watch on a test drive (periodic status line, `src/controller_main.cpp:214`): `Queue drops`,
  `serial_drop`, `logged`, `blank_gps`, `sweeps`, `last_sweep_ms`. Add `relog_rows` (rows written by rules
  2 to 4) and `relog_suppressed` (rule 5) so the effect is visible.
- `dedupe_drop` changes meaning: it now counts repeats within the TTL window, so it will be much lower than
  today on long drives and is no longer "everything seen before". Document this in the status line help.

## 8. Interactions

- `CONTROLLER_DEDUPE_RESET_ON_FIX_ACQUIRE`: keep. `maybeRequestDedupeResetOnFixAcquire` runs on core0
  (`src/controller_main.cpp:261`), so it calls `relogGateReset` directly next to the existing core1 request
  (call). Its reset also clears the gate, so 0/0 stays exact and,
  with the feature on, every AP gets one fresh positioned row after a fix is regained (rule 2 already covers
  most of this; the reset adds nothing harmful).
- Companion Android app: `ApTable` (`android/core/.../ApTable.kt`) already accumulates sightings by BSSID
  and its comment expects "the same BSSID can appear many times in one log". No app change needed.
- WiGLE: the WigleWifi CSV format allows many rows per BSSID (the WiGLE Android app produces them).
  Uploads stay compatible; WiGLE's own placement gets more points.
- Downstream tools that assumed one row per AP per log (none in this repo) would see more rows.

## 9. Testing

Native unit tests (`test/test_wifi_common`, all three hash-width envs), pure logic:

- `test_relogGate_first_sight_logs`
- `test_relogGate_same_spot_suppressed` (0 m, same RSSI)
- `test_relogGate_24m_suppressed_25m_logs` (synthetic coordinates near 1.0, 1.0)
- `test_relogGate_rssi_plus5_suppressed_plus6_logs`
- `test_relogGate_rssi_baseline_resets_after_distance_relog`
- `test_relogGate_no_fix_logs_once_then_suppresses`
- `test_relogGate_fix_arrival_relogs_positionless_entry`
- `test_relogGate_eviction_relogs_as_first_sight`
- `test_relogGate_zero_zero_disables` (every repeat suppressed, matching today)
- `test_relogGate_reset_clears`
- `test_relogGate_longitude_scaling_at_high_latitude` (25 m east-west at a synthetic high latitude)
- `test_wifiDedupeTable_ttl_expires_entry` / `_ttl_zero_never_expires` / `_hit_does_not_refresh_ttl` /
  `_millis_wraparound` (now_ms near 0xFFFFFFFF)

Only the owner can verify on hardware: build and flash controller and scanners, then one test drive with
the defaults. Compare against a drive of the same route on current firmware: rows per AP per drive
(expect about 3 to 6), queue and serial drops (expect unchanged, ideally 0), `blank_gps` share, and that a
long stop does not grow the log.

## 10. Left to the builder

- Exact function signatures, file split, and how `appendCsvRow` receives the decision (gate call in
  `controller_main.cpp` before `appendCsvRow`, or inside the logger with an injected gate).
- Whether `wifiDedupeTableRemember` gains a `now_ms` overload or a table-level clock callback.
- Status-line wording for the new counters.

## 11. Calls made by Claude (owner did not decide these)

- Gate placement at core0 SD-write; upstream tables kept with time expiry rather than removed.
- TTL default 1000 ms (feature on), scanner TTL in `ScannerConfiguration.h`, expiry via FIFO timestamps
  with hits not refreshing the timer.
- No-fix behaviour: log once without position, re-log when a fix arrives; RSSI rule ignored without a fix.
- RSSI baseline resets to the last logged row's RSSI after any re-log.
- Fixed-point 1e-7 degree storage; equirectangular distance; gate capacity 4000 with FIFO eviction.
- Keep `CONTROLLER_DEDUPE_RESET_ON_FIX_ACQUIRE` and have it clear the gate.
- New counters `relog_rows` and `relog_suppressed`; `dedupe_drop` meaning documented as changed.
- Rejected periodic `CMD_DEDUPE_RESET` because it aborts in-progress scans.
- Gate key recomputed on core0 from `QueuedScanResult` via a shared helper; position from the logger's
  current GPS source; no locking (core0 only); gate reset called directly from the core0 fix-acquire hook.

## 12. Owner decisions (2026-10-29)

1. **Larger logs: accepted**, on condition that nothing conflicts with WiGLE's rules. Findings: WiGLE
   publishes no rule against several rows per network; its own Android app writes repeated observations of
   a network as the device moves, and WiGLE staff describe duplicate uploads as safely ignored. Re-logged
   rows here are genuine new observations (new time, new position or stronger signal), not copies, and
   the CSV header and row format stay unchanged. Residual risk: WiGLE's policies are not formally
   documented for this; if WiGLE ever objects, setting 0/0 restores current behaviour.
2. **Reflashing scanners: accepted**, on condition of a safe, verified flash process (section 13).
3. **On by default** in every build from this fork: 25 m / 6 dB, no build flags needed. The owner
   wants to flash and keep driving as before, only with better data. Status/serial output must not
   require any new action from the owner.

## 13. Safe flash procedure (required before release)

Facts from the code: scanner SD/SPI updates use the ESP32 `Update` library (write to the inactive
partition, switch only after `Update.end(true)` succeeds, so a failed update keeps the old image); the
controller stages the image from SD into LittleFS and applies it through the OTA bootloader with a
CRC-checked command page (`src/controller_update_runtime.cpp`). Both chips have ROM recovery that no
firmware can overwrite: RP2350 USB BOOTSEL (UF2) and ESP32-C5 USB download mode (merged image).

Procedure the release must document (in the fork README section for this feature):

1. Build `xiao_rp2350_controller` and `xiao_esp32c5_scanner` from the tagged commit; native tests green;
   size report within limits; publish SHA-256 of `controller.bin`, `scanner.bin`, the UF2 and the
   merged scanner image next to the files.
2. Keep the currently installed release's images (SD and USB variants) as the rollback set.
3. Bench first, not on a drive: copy `controller.bin` and `scanner.bin` to the SD card root, power on
   with stable power, wait for both to be renamed `*.applied.bin`; confirm the new version string on the
   USB serial status line and that every scanner slot reports the new version.
4. Bench smoke test: a few minutes walking or idling with a fix; confirm rows are written, drop counters
   stay at 0, and a stationary period does not grow the log.
5. Rollback: copy the previous release's `controller.bin`/`scanner.bin` to the SD root the same way; if a
   board will not boot, recover over USB (BOOTSEL + UF2 for the controller; download mode + merged image
   for each scanner).
6. Mixed versions (new controller, old scanners or the reverse) must keep logging exactly as today; the
   implementation must be tested for that, so a partially applied update is never harmful.

## 14. Amendment after final review (accuracy-scaled distance)

Rule: when both the stored entry and the sighting have positions, the distance rule fires at
`max(RELOG_DISTANCE_M, RELOG_ACCURACY_FACTOR x accuracy_m)`, where `accuracy_m` is HDOP x 5 m rounded to
whole metres, taken from the same GPS fields and validity/age test as the AccuracyMeters column. Unknown
accuracy (0) keeps the plain distance; `RELOG_ACCURACY_FACTOR=0` ignores accuracy. Default factor: 2. The
rest of the decision order is unchanged.

Why: the plain 25 m rule ignored fix quality. A parked device under poor sky or on a coarse phone fix can
wander more than 25 m every minute, which would re-log every access point in view each time and reset its
RSSI baseline, so the log would grow with time at one spot. That is the anti-goal of this design. Scaling
the threshold by accuracy keeps 25 m under a good fix and requires a move well beyond the fix's own error
under a poor one.

The same review added compile-time checks on the gate sizes (table size a power of two, at least twice the
capacity, at most 65535; capacity between 1 and 65534), since bad overrides would make the probe loop spin
forever.
