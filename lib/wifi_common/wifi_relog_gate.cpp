// wifi_relog_gate.cpp
#include "wifi_relog_gate.h"

#include <math.h>
#include <string.h>

static uint16_t keyToSlot(const WiFiDedupeHash& h, uint16_t table_size) {
  uint16_t idx = 0;
  memcpy(&idx, h.bytes, sizeof(idx));
  return (uint16_t)(idx & (uint16_t)(table_size - 1u));
}

// Removes the slot at `removed` and shifts later entries of the probe chain
// back toward their natural positions. Leaves no tombstones.
static void slotBackwardShiftDelete(RelogGate* gate, uint16_t removed) {
  const uint16_t mask = (uint16_t)(gate->table_size - 1u);
  gate->slots[removed] = RELOG_GATE_EMPTY_SLOT;
  uint16_t gap = removed;
  uint16_t scan = (uint16_t)((removed + 1u) & mask);
  while (gate->slots[scan] != RELOG_GATE_EMPTY_SLOT) {
    const uint16_t natural = keyToSlot(gate->entries[gate->slots[scan]].key, gate->table_size);
    const uint16_t dist_to_gap = (uint16_t)((gap - natural + gate->table_size) & mask);
    const uint16_t dist_to_scan = (uint16_t)((scan - natural + gate->table_size) & mask);
    if (dist_to_gap <= dist_to_scan) {
      gate->slots[gap] = gate->slots[scan];
      gate->slots[scan] = RELOG_GATE_EMPTY_SLOT;
      gap = scan;
    }
    scan = (uint16_t)((scan + 1u) & mask);
  }
}

// Returns the slot holding `key`, or table_size when absent.
static uint16_t findSlot(const RelogGate* gate, const WiFiDedupeHash& key) {
  const uint16_t mask = (uint16_t)(gate->table_size - 1u);
  uint16_t slot = keyToSlot(key, gate->table_size);
  while (gate->slots[slot] != RELOG_GATE_EMPTY_SLOT) {
    if (wifiDedupeHashEquals(gate->entries[gate->slots[slot]].key, key)) return slot;
    slot = (uint16_t)((slot + 1u) & mask);
  }
  return gate->table_size;
}

static void store(RelogGateEntry& e, bool has_pos, int32_t lat_e7, int32_t lon_e7, int8_t rssi) {
  e.has_pos = has_pos ? 1 : 0;
  e.lat_e7 = lat_e7;
  e.lon_e7 = lon_e7;
  e.rssi = rssi;
}

static bool movedAtLeast(const RelogGateEntry& e, int32_t lat_e7, int32_t lon_e7,
                         uint32_t distance_m) {
  const float meters_per_deg = 111320.0f;
  const float dlat_deg = (float)(lat_e7 - e.lat_e7) * 1e-7f;
  int64_t dlon_e7 = (int64_t)lon_e7 - (int64_t)e.lon_e7;
  if (dlon_e7 > 1800000000LL) dlon_e7 -= 3600000000LL;
  else if (dlon_e7 < -1800000000LL) dlon_e7 += 3600000000LL;
  const float stored_lat_rad = (float)e.lat_e7 * 1e-7f * (acosf(-1.0f) / 180.0f);
  const float dx = (float)dlon_e7 * 1e-7f * cosf(stored_lat_rad) * meters_per_deg;
  const float dy = dlat_deg * meters_per_deg;
  return dx * dx + dy * dy >= (float)distance_m * (float)distance_m;
}

void relogGateInit(RelogGate* gate, RelogGateEntry* entries, uint16_t capacity,
                   uint16_t* slots, uint16_t table_size) {
  if (!gate) return;
  gate->entries = entries;
  gate->slots = slots;
  gate->table_size = table_size;
  gate->capacity = capacity;
  relogGateReset(gate);
}

void relogGateReset(RelogGate* gate) {
  if (!gate) return;
  gate->count = 0;
  gate->head = 0;
  if (gate->slots) {
    for (uint16_t i = 0; i < gate->table_size; i++) gate->slots[i] = RELOG_GATE_EMPTY_SLOT;
  }
}

RelogDecision relogGateDecide(RelogGate* gate, const WiFiDedupeHash& key, bool has_pos,
                              int32_t lat_e7, int32_t lon_e7, int8_t rssi,
                              uint16_t accuracy_m, const RelogGateConfig& cfg) {
  const uint16_t found = findSlot(gate, key);
  if (found == gate->table_size) {
    uint16_t idx;
    if (gate->count < gate->capacity) {
      idx = (uint16_t)((gate->head + gate->count) % gate->capacity);
      gate->count++;
    } else {
      idx = gate->head;
      const uint16_t old = findSlot(gate, gate->entries[idx].key);
      if (old != gate->table_size) slotBackwardShiftDelete(gate, old);
      gate->head = (uint16_t)((gate->head + 1u) % gate->capacity);
    }
    gate->entries[idx].key = key;
    store(gate->entries[idx], has_pos, lat_e7, lon_e7, rssi);
    const uint16_t mask = (uint16_t)(gate->table_size - 1u);
    uint16_t slot = keyToSlot(key, gate->table_size);
    while (gate->slots[slot] != RELOG_GATE_EMPTY_SLOT) slot = (uint16_t)((slot + 1u) & mask);
    gate->slots[slot] = idx;
    return RelogDecision::LogFirstSight;
  }

  RelogGateEntry& e = gate->entries[gate->slots[found]];
  if (cfg.distance_m == 0 && cfg.rssi_gain_db == 0) return RelogDecision::Suppress;
  if (has_pos && !e.has_pos) {
    store(e, true, lat_e7, lon_e7, rssi);
    return RelogDecision::LogFixArrived;
  }
  uint32_t threshold_m = cfg.distance_m;
  const uint32_t accuracy_floor_m = (uint32_t)cfg.accuracy_factor * (uint32_t)accuracy_m;
  if (accuracy_floor_m > threshold_m) threshold_m = accuracy_floor_m;
  if (has_pos && e.has_pos && cfg.distance_m > 0 &&
      movedAtLeast(e, lat_e7, lon_e7, threshold_m)) {
    store(e, true, lat_e7, lon_e7, rssi);
    return RelogDecision::LogMoved;
  }
  if (has_pos && cfg.rssi_gain_db > 0 &&
      (int16_t)rssi >= (int16_t)e.rssi + (int16_t)cfg.rssi_gain_db) {
    store(e, true, lat_e7, lon_e7, rssi);
    return RelogDecision::LogStronger;
  }
  return RelogDecision::Suppress;
}
