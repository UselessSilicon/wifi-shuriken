// wifi_relog_gate.h
#pragma once

#include <stdint.h>
#include "wifi_dedupe.h"

// Spatial re-log gate. Decides per sighting whether a repeat network becomes a
// new log row: first sight; a fix arrived for a positionless entry; moved at
// least distance_m; or signal at least rssi_gain_db above the stored value.
// Pure logic, no platform headers, so it builds natively for unit tests.

static constexpr uint16_t RELOG_GATE_EMPTY_SLOT = 0xFFFF;

struct RelogGateEntry {
  WiFiDedupeHash key;
  int32_t lat_e7;
  int32_t lon_e7;
  int8_t rssi;
  uint8_t has_pos;
};

// - entries[capacity]: ring in insertion order; head is the oldest.
// - slots[table_size]: open addressing over entry indices, 0xFFFF = empty.
struct RelogGate {
  RelogGateEntry* entries;
  uint16_t* slots;
  uint16_t table_size;  // power of two, >= 2 * capacity
  uint16_t capacity;    // < 0xFFFF
  uint16_t count;
  uint16_t head;        // oldest entry index
};

struct RelogGateConfig {
  uint16_t distance_m;   // 0 disables the distance rule
  uint8_t rssi_gain_db;  // 0 disables the signal rule
};

enum class RelogDecision : uint8_t {
  LogFirstSight,
  LogFixArrived,
  LogMoved,
  LogStronger,
  Suppress
};

void relogGateInit(RelogGate* gate, RelogGateEntry* entries, uint16_t capacity,
                   uint16_t* slots, uint16_t table_size);
void relogGateReset(RelogGate* gate);

// On any Log* result the entry's position and RSSI are updated to the
// sighting; on Suppress nothing changes.
RelogDecision relogGateDecide(RelogGate* gate, const WiFiDedupeHash& key, bool has_pos,
                              int32_t lat_e7, int32_t lon_e7, int8_t rssi,
                              const RelogGateConfig& cfg);
