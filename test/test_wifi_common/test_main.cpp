#include <string.h>

#include <unity.h>

#include "wifi_dedupe.h"
#include "wifi_relog_gate.h"
#include "wifi_result_utils.h"

void setUp() {}
void tearDown() {}

static WiFiResult makeValidResult() {
  WiFiResult r = {};
  strncpy(r.ssid, "TestAP", sizeof(r.ssid) - 1);
  const uint8_t bssid[6] = {0x10, 0x20, 0x30, 0x40, 0x50, 0x60};
  memcpy(r.bssid, bssid, sizeof(r.bssid));
  r.rssi = -42;
  r.channel = 36;
  r.band = WIFI_BAND_5_GHZ;
  r.capabilities = CAP_ESS | CAP_WPA2 | CAP_PSK | CAP_CCMP;
  return r;
}

static WiFiResult makeValid24gResult() {
  WiFiResult r = {};
  strncpy(r.ssid, "TwoFour", sizeof(r.ssid) - 1);
  const uint8_t bssid[6] = {0x61, 0x52, 0x43, 0x34, 0x25, 0x16};
  memcpy(r.bssid, bssid, sizeof(r.bssid));
  r.rssi = -55;
  r.channel = 11;
  r.band = WIFI_BAND_24_GHZ;
  r.capabilities = CAP_ESS | CAP_WPA | CAP_PSK | CAP_TKIP;
  return r;
}

static WiFiDedupeHash makeHash(uint8_t seed) {
  WiFiDedupeHash hash = {};
  for (size_t i = 0; i < WIFI_DEDUPE_HASH_SIZE; i++) {
    hash.bytes[i] = (uint8_t)(seed + i);
  }
  return hash;
}

// Helper: initialise a small table for testing. table_size must be a power of
// two and at least 2x capacity; slots and fifo must have table_size and
// capacity entries respectively.
static void initSmallTable(WiFiDedupeTable* table,
                           WiFiDedupeHash* slots, uint16_t table_size,
                           WiFiDedupeHash* fifo, uint16_t capacity) {
  wifiDedupeTableInit(table, slots, table_size, fifo, capacity);
}

void test_wifiBandFromChannel_boundaries() {
  TEST_ASSERT_EQUAL_UINT8(WIFI_BAND_24_GHZ, wifiBandFromChannel(1));
  TEST_ASSERT_EQUAL_UINT8(WIFI_BAND_24_GHZ, wifiBandFromChannel(14));
  TEST_ASSERT_EQUAL_UINT8(WIFI_BAND_5_GHZ, wifiBandFromChannel(36));
  TEST_ASSERT_EQUAL_UINT8(WIFI_BAND_5_GHZ, wifiBandFromChannel(165));
  TEST_ASSERT_EQUAL_UINT8(0, wifiBandFromChannel(0));
  TEST_ASSERT_EQUAL_UINT8(0, wifiBandFromChannel(35));
  TEST_ASSERT_EQUAL_UINT8(0, wifiBandFromChannel(166));
}

void test_wifiBandChannelValid_by_band() {
  TEST_ASSERT_TRUE(wifiBandChannelValid(WIFI_BAND_24_GHZ, 1));
  TEST_ASSERT_TRUE(wifiBandChannelValid(WIFI_BAND_24_GHZ, 14));
  TEST_ASSERT_FALSE(wifiBandChannelValid(WIFI_BAND_24_GHZ, 15));

  TEST_ASSERT_TRUE(wifiBandChannelValid(WIFI_BAND_5_GHZ, 36));
  TEST_ASSERT_TRUE(wifiBandChannelValid(WIFI_BAND_5_GHZ, 165));
  TEST_ASSERT_FALSE(wifiBandChannelValid(WIFI_BAND_5_GHZ, 35));
  TEST_ASSERT_FALSE(wifiBandChannelValid(WIFI_BAND_5_GHZ, 166));

  TEST_ASSERT_FALSE(wifiBandChannelValid(0, 36));
}

void test_wifiBssid_helpers() {
  const uint8_t zero_bssid[6] = {0, 0, 0, 0, 0, 0};
  const uint8_t ff_bssid[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
  const uint8_t mixed_bssid[6] = {0x10, 0x20, 0x30, 0, 0, 0};

  TEST_ASSERT_TRUE(wifiBssidIsZero(zero_bssid));
  TEST_ASSERT_FALSE(wifiBssidIsZero(mixed_bssid));

  TEST_ASSERT_TRUE(wifiBssidIsBroadcast(ff_bssid));
  TEST_ASSERT_FALSE(wifiBssidIsBroadcast(mixed_bssid));
}

void test_wifiBssid_helpers_null_and_zero_length() {
  const uint8_t sample[1] = {0x00};
  TEST_ASSERT_TRUE(wifiBssidIsZero(nullptr));
  TEST_ASSERT_TRUE(wifiBssidIsZero(sample, 0));
  TEST_ASSERT_FALSE(wifiBssidIsBroadcast(nullptr));
  TEST_ASSERT_FALSE(wifiBssidIsBroadcast(sample, 0));
}

void test_wifiResult_validation_rules() {
  WiFiResult valid = makeValidResult();
  TEST_ASSERT_TRUE(wifiResultHasTerminatedSsid(valid));
  TEST_ASSERT_TRUE(wifiResultIsValidForDedupe(valid));

  WiFiResult valid_24g = makeValid24gResult();
  TEST_ASSERT_TRUE(wifiResultHasTerminatedSsid(valid_24g));
  TEST_ASSERT_TRUE(wifiResultIsValidForDedupe(valid_24g));

  WiFiResult bad_band = valid;
  bad_band.band = WIFI_BAND_24_GHZ;
  TEST_ASSERT_FALSE(wifiResultIsValidForDedupe(bad_band));

  WiFiResult zero_bssid = valid;
  memset(zero_bssid.bssid, 0, sizeof(zero_bssid.bssid));
  TEST_ASSERT_FALSE(wifiResultIsValidForDedupe(zero_bssid));

  WiFiResult broadcast_bssid = valid;
  memset(broadcast_bssid.bssid, 0xFF, sizeof(broadcast_bssid.bssid));
  TEST_ASSERT_FALSE(wifiResultIsValidForDedupe(broadcast_bssid));

  WiFiResult no_null_ssid = valid;
  memset(no_null_ssid.ssid, 'A', sizeof(no_null_ssid.ssid));
  TEST_ASSERT_FALSE(wifiResultHasTerminatedSsid(no_null_ssid));
  TEST_ASSERT_FALSE(wifiResultIsValidForDedupe(no_null_ssid));
}

void test_wifiDedupeHash_is_deterministic() {
  WiFiResult r = makeValidResult();
  WiFiDedupeHash a = {};
  WiFiDedupeHash b = {};

  wifiDedupeHashFromResult(r, a);
  wifiDedupeHashFromResult(r, b);

  TEST_ASSERT_TRUE(wifiDedupeHashEquals(a, b));
  b.bytes[WIFI_DEDUPE_HASH_SIZE - 1] ^= 0xA5;
  TEST_ASSERT_FALSE(wifiDedupeHashEquals(a, b));
}

void test_wifiDedupeHash_ignores_rssi_field() {
  WiFiResult a = makeValidResult();
  WiFiResult b = a;
  b.rssi = -5;

  WiFiDedupeHash ha = {};
  WiFiDedupeHash hb = {};
  wifiDedupeHashFromResult(a, ha);
  wifiDedupeHashFromResult(b, hb);
  TEST_ASSERT_TRUE(wifiDedupeHashEquals(ha, hb));

  WiFiDedupeHash slots[16] = {};
  WiFiDedupeHash fifo[8] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 16, fifo, 8);

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &ha));
  TEST_ASSERT_FALSE(wifiDedupeTableRemember(&table, &hb));
}

void test_wifiDedupeHash_changes_when_identity_fields_change() {
  const WiFiResult base = makeValidResult();
  WiFiDedupeHash h_base = {};
  wifiDedupeHashFromResult(base, h_base);

  WiFiResult changed_ssid = base;
  changed_ssid.ssid[0] = (changed_ssid.ssid[0] == 'Z') ? 'Y' : 'Z';
  WiFiDedupeHash h_ssid = {};
  wifiDedupeHashFromResult(changed_ssid, h_ssid);
  TEST_ASSERT_FALSE(wifiDedupeHashEquals(h_base, h_ssid));

  WiFiResult changed_bssid = base;
  changed_bssid.bssid[5] ^= 0x01;
  WiFiDedupeHash h_bssid = {};
  wifiDedupeHashFromResult(changed_bssid, h_bssid);
  TEST_ASSERT_FALSE(wifiDedupeHashEquals(h_base, h_bssid));

  WiFiResult changed_channel = base;
  changed_channel.channel = 44;
  changed_channel.band = WIFI_BAND_5_GHZ;
  WiFiDedupeHash h_channel = {};
  wifiDedupeHashFromResult(changed_channel, h_channel);
  TEST_ASSERT_FALSE(wifiDedupeHashEquals(h_base, h_channel));

  WiFiResult changed_caps = base;
  changed_caps.capabilities ^= CAP_EAP;
  WiFiDedupeHash h_caps = {};
  wifiDedupeHashFromResult(changed_caps, h_caps);
  TEST_ASSERT_FALSE(wifiDedupeHashEquals(h_base, h_caps));
}

void test_wifiDedupeTable_remember_contains_and_duplicate() {
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[4] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 4);

  const WiFiDedupeHash h1 = makeHash(1);
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h1));
  TEST_ASSERT_EQUAL_UINT16(1, table.count);

  TEST_ASSERT_FALSE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_EQUAL_UINT16(1, table.count);
}

void test_wifiDedupeTable_ring_overwrite_when_full() {
  WiFiDedupeHash slots[4] = {};
  WiFiDedupeHash fifo[2] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 4, fifo, 2);

  const WiFiDedupeHash h1 = makeHash(10);
  const WiFiDedupeHash h2 = makeHash(30);
  const WiFiDedupeHash h3 = makeHash(50);

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h3));

  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h3));
  TEST_ASSERT_EQUAL_UINT16(2, table.count);
}

void test_wifiDedupeTable_corrupt_metadata_guard() {
  WiFiDedupeHash slots[4] = {};
  WiFiDedupeHash fifo[2] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 4, fifo, 2);

  // Corrupt both count and FIFO indices beyond capacity.
  table.count = 99;
  table.fifo_head = 99;
  table.fifo_tail = 99;

  const WiFiDedupeHash h = makeHash(90);
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h));
}

void test_wifiDedupeTable_fifo_desync_skips_stale_entries() {
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[3] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 3);

  const WiFiDedupeHash h1 = makeHash(1);
  const WiFiDedupeHash h2 = makeHash(9);
  const WiFiDedupeHash h3 = makeHash(17);
  const WiFiDedupeHash ghost = makeHash(90);
  const WiFiDedupeHash h4 = makeHash(25);

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h3));
  TEST_ASSERT_EQUAL_UINT16(3, table.count);

  // Corrupt only the oldest FIFO entry. Insertion should skip the stale hash
  // and evict the next valid FIFO-backed entry instead of drifting count.
  table.fifo[table.fifo_head] = ghost;

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h4));
  TEST_ASSERT_EQUAL_UINT16(3, table.count);
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h1));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h3));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h4));
}

void test_wifiDedupeTable_fifo_total_desync_resets_table() {
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[3] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 3);

  const WiFiDedupeHash h1 = makeHash(3);
  const WiFiDedupeHash h2 = makeHash(11);
  const WiFiDedupeHash h3 = makeHash(19);
  const WiFiDedupeHash ghost1 = makeHash(90);
  const WiFiDedupeHash ghost2 = makeHash(91);
  const WiFiDedupeHash ghost3 = makeHash(92);
  const WiFiDedupeHash h4 = makeHash(27);

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h3));
  TEST_ASSERT_EQUAL_UINT16(3, table.count);

  // If the FIFO no longer points at any live slot, the corruption guard should
  // reset the table rather than underflow count or leave orphaned entries.
  table.fifo[0] = ghost1;
  table.fifo[1] = ghost2;
  table.fifo[2] = ghost3;

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h4));
  TEST_ASSERT_EQUAL_UINT16(1, table.count);
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h1));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h2));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h3));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h4));
}

void test_wifiDedupeTable_reset_clears_state() {
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[3] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 3);

  const WiFiDedupeHash h1 = makeHash(7);
  const WiFiDedupeHash h2 = makeHash(11);
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h2));
  TEST_ASSERT_EQUAL_UINT16(2, table.count);

  wifiDedupeTableReset(&table);
  TEST_ASSERT_EQUAL_UINT16(0, table.count);
  TEST_ASSERT_EQUAL_UINT16(0, table.fifo_head);
  TEST_ASSERT_EQUAL_UINT16(0, table.fifo_tail);
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h1));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h2));
}

void test_wifiDedupeTable_invalid_inputs() {
  const WiFiDedupeHash h = makeHash(33);
  WiFiDedupeTable invalid = {};

  TEST_ASSERT_FALSE(wifiDedupeTableContains(nullptr, &h));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&invalid, &h));
  TEST_ASSERT_FALSE(wifiDedupeTableRemember(nullptr, &h));
  TEST_ASSERT_FALSE(wifiDedupeTableRemember(&invalid, &h));
}

void test_wifiDedupeTable_zero_hash_uses_reserved_empty_sentinel() {
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[4] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 4);

  const WiFiDedupeHash zero = {};
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &zero));
  TEST_ASSERT_FALSE(wifiDedupeTableRemember(&table, &zero));
  TEST_ASSERT_EQUAL_UINT16(0, table.count);
}

// makeHash(seed) produces bytes {seed, seed+1, seed+2, ...}. On little-endian
// hosts the first two bytes are read as (seed + (seed+1)*256). For seed values
// 0, 8, 16, 24 this index is divisible by 8, so all four map to slot 0 in an
// 8-slot table (mask=7). This exercises linear probing and backward-shift
// deletion with a fully colliding probe chain.
void test_wifiDedupeTable_collision_probe_chain() {
  // All four hashes map to slot 0 in an 8-slot table: on little-endian hosts
  // hashToSlot reads the first two bytes as a uint16_t. For makeHash(N),
  // bytes[0..1] = {N, N+1}, so idx = N + (N+1)*256. For N = 0,8,16,24 this
  // value is always divisible by 8 (mask=7 → slot 0).
  //
  // Capacity=3 means the 4th insert triggers eviction of h1, exercising
  // backward-shift deletion across a fully colliding probe chain.
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[3] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 3);

  const WiFiDedupeHash h1 = makeHash(0);   // natural slot 0
  const WiFiDedupeHash h2 = makeHash(8);   // natural slot 0 (collides)
  const WiFiDedupeHash h3 = makeHash(16);  // natural slot 0 (collides)

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h3));
  TEST_ASSERT_EQUAL_UINT16(3, table.count);

  // h4 triggers eviction of h1 (count == capacity). Backward-shift deletion
  // must repair the probe chain so h2 and h3 remain findable.
  const WiFiDedupeHash h4 = makeHash(24);  // natural slot 0 (collides)
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h4));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h3));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h4));
  TEST_ASSERT_EQUAL_UINT16(3, table.count);
}

void test_wifiDedupeTable_many_insertions_keep_only_most_recent_entries() {
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[3] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 3);

  const WiFiDedupeHash h1 = makeHash(1);
  const WiFiDedupeHash h2 = makeHash(2);
  const WiFiDedupeHash h3 = makeHash(3);
  const WiFiDedupeHash h4 = makeHash(4);
  const WiFiDedupeHash h5 = makeHash(5);

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h3));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h4));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h5));

  TEST_ASSERT_EQUAL_UINT16(3, table.count);
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h1));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h3));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h4));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h5));
}

void test_wifiDedupeTable_evicted_entry_can_be_reinserted() {
  WiFiDedupeHash slots[8] = {};
  WiFiDedupeHash fifo[3] = {};
  WiFiDedupeTable table = {};
  initSmallTable(&table, slots, 8, fifo, 3);

  const WiFiDedupeHash h1 = makeHash(10);
  const WiFiDedupeHash h2 = makeHash(11);
  const WiFiDedupeHash h3 = makeHash(12);
  const WiFiDedupeHash h4 = makeHash(13);

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h3));
  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h4));
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h1));

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h1));
  TEST_ASSERT_EQUAL_UINT16(3, table.count);
  TEST_ASSERT_FALSE(wifiDedupeTableContains(&table, &h2));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h3));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h4));
  TEST_ASSERT_TRUE(wifiDedupeTableContains(&table, &h1));
}

// Helper: capacity 4, size 8 table with a time-to-live.
static void initTtlTable(WiFiDedupeTable* table,
                         WiFiDedupeHash* slots, WiFiDedupeHash* fifo,
                         uint32_t* fifo_ms, uint32_t ttl_ms) {
  wifiDedupeTableInitTtl(table, slots, 8, fifo, fifo_ms, 4, ttl_ms);
}

void test_wifiDedupeTable_ttl_expires_entry() {
  WiFiDedupeTable table;
  WiFiDedupeHash slots[8];
  WiFiDedupeHash fifo[4];
  uint32_t fifo_ms[4];
  initTtlTable(&table, slots, fifo, fifo_ms, 1000);
  const WiFiDedupeHash h = makeHash(5);

  TEST_ASSERT_TRUE(wifiDedupeTableRememberAt(&table, &h, 0));
  TEST_ASSERT_FALSE(wifiDedupeTableRememberAt(&table, &h, 999));
  TEST_ASSERT_TRUE(wifiDedupeTableRememberAt(&table, &h, 1000));
}

void test_wifiDedupeTable_ttl_zero_never_expires() {
  WiFiDedupeTable table;
  WiFiDedupeHash slots[8];
  WiFiDedupeHash fifo[4];
  uint32_t fifo_ms[4];
  initTtlTable(&table, slots, fifo, fifo_ms, 0);
  const WiFiDedupeHash h = makeHash(5);

  TEST_ASSERT_TRUE(wifiDedupeTableRememberAt(&table, &h, 0));
  TEST_ASSERT_FALSE(wifiDedupeTableRememberAt(&table, &h, 1000000));
}

void test_wifiDedupeTable_hit_does_not_refresh_ttl() {
  WiFiDedupeTable table;
  WiFiDedupeHash slots[8];
  WiFiDedupeHash fifo[4];
  uint32_t fifo_ms[4];
  initTtlTable(&table, slots, fifo, fifo_ms, 1000);
  const WiFiDedupeHash h = makeHash(5);

  TEST_ASSERT_TRUE(wifiDedupeTableRememberAt(&table, &h, 0));
  TEST_ASSERT_FALSE(wifiDedupeTableRememberAt(&table, &h, 600));
  TEST_ASSERT_TRUE(wifiDedupeTableRememberAt(&table, &h, 1000));
}

void test_wifiDedupeTable_millis_wraparound() {
  WiFiDedupeTable table;
  WiFiDedupeHash slots[8];
  WiFiDedupeHash fifo[4];
  uint32_t fifo_ms[4];
  initTtlTable(&table, slots, fifo, fifo_ms, 1000);
  const WiFiDedupeHash h = makeHash(5);

  TEST_ASSERT_TRUE(wifiDedupeTableRememberAt(&table, &h, 0xFFFFFF00u));
  TEST_ASSERT_FALSE(wifiDedupeTableRememberAt(&table, &h, 0x000002E7u));
  TEST_ASSERT_TRUE(wifiDedupeTableRememberAt(&table, &h, 0x000002E8u));
}

void test_wifiDedupeTable_remember_without_time_never_expires() {
  WiFiDedupeTable table;
  WiFiDedupeHash slots[8];
  WiFiDedupeHash fifo[4];
  uint32_t fifo_ms[4];
  initTtlTable(&table, slots, fifo, fifo_ms, 1000);
  const WiFiDedupeHash h = makeHash(5);

  TEST_ASSERT_TRUE(wifiDedupeTableRemember(&table, &h));
  TEST_ASSERT_FALSE(wifiDedupeTableRemember(&table, &h));
}

void test_wifiDedupeHash_fields_matches_result() {
  const WiFiResult r = makeValidResult();
  WiFiDedupeHash from_result = {};
  WiFiDedupeHash from_fields = {};
  wifiDedupeHashFromResult(r, from_result);
  wifiDedupeHashFromFields(r.ssid, r.bssid, r.channel, r.band, r.capabilities, from_fields);
  TEST_ASSERT_TRUE(wifiDedupeHashEquals(from_result, from_fields));
}

// ---- Relog gate ----

static constexpr int32_t RG_BASE = 10000000;  // synthetic 1.0 deg
static const RelogGateConfig RG_CFG = {25, 6};

struct RgFixture {
  RelogGateEntry entries[8];
  uint16_t slots[16];
  RelogGate gate;
  explicit RgFixture(uint16_t capacity = 8, uint16_t table_size = 16) {
    relogGateInit(&gate, entries, capacity, slots, table_size);
  }
};

static WiFiDedupeHash rgKey(uint8_t n) {
  char ssid[33] = {};
  const uint8_t bssid[6] = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, n};
  WiFiDedupeHash h;
  wifiDedupeHashFromFields(ssid, bssid, 6, WIFI_BAND_24_GHZ, 0, h);
  return h;
}

static RelogDecision rgAt(RgFixture& f, uint8_t n, int32_t lat, int32_t lon, int8_t rssi,
                          const RelogGateConfig& cfg = RG_CFG) {
  return relogGateDecide(&f.gate, rgKey(n), true, lat, lon, rssi, cfg);
}

static RelogDecision rgNoPos(RgFixture& f, uint8_t n, int8_t rssi,
                             const RelogGateConfig& cfg = RG_CFG) {
  return relogGateDecide(&f.gate, rgKey(n), false, 0, 0, rssi, cfg);
}

void test_relogGate_first_sight_logs() {
  RgFixture f;
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -70) == RelogDecision::LogFirstSight);
}

void test_relogGate_same_spot_suppressed() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, RG_BASE, -70);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -70) == RelogDecision::Suppress);
}

void test_relogGate_24m_suppressed_25m_logs() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, RG_BASE, -70);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 2156, RG_BASE, -70) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 2255, RG_BASE, -70) == RelogDecision::LogMoved);
}

void test_relogGate_rssi_plus5_suppressed_plus6_logs() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, RG_BASE, -70);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -65) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -64) == RelogDecision::LogStronger);
}

void test_relogGate_rssi_baseline_resets_after_distance_relog() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, RG_BASE, -70);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 2695, RG_BASE, -60) == RelogDecision::LogMoved);  // ~30 m
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 2695, RG_BASE, -56) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 2695, RG_BASE, -54) == RelogDecision::LogStronger);
}

void test_relogGate_no_fix_logs_once_then_suppresses() {
  RgFixture f;
  TEST_ASSERT_TRUE(rgNoPos(f, 1, -80) == RelogDecision::LogFirstSight);
  TEST_ASSERT_TRUE(rgNoPos(f, 1, -80) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgNoPos(f, 1, -60) == RelogDecision::Suppress);
}

void test_relogGate_fix_arrival_relogs_positionless_entry() {
  RgFixture f;
  rgNoPos(f, 1, -70);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -70) == RelogDecision::LogFixArrived);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -70) == RelogDecision::Suppress);
}

void test_relogGate_eviction_relogs_as_first_sight() {
  RgFixture f(4, 8);
  for (uint8_t i = 0; i < 5; i++) {
    TEST_ASSERT_TRUE(rgAt(f, i, RG_BASE, RG_BASE, -70) == RelogDecision::LogFirstSight);
  }
  TEST_ASSERT_TRUE(rgAt(f, 0, RG_BASE, RG_BASE, -70) == RelogDecision::LogFirstSight);
  TEST_ASSERT_TRUE(rgAt(f, 4, RG_BASE, RG_BASE, -70) == RelogDecision::Suppress);
}

void test_relogGate_zero_zero_disables() {
  RgFixture f;
  const RelogGateConfig off = {0, 0};
  TEST_ASSERT_TRUE(rgNoPos(f, 1, -70, off) == RelogDecision::LogFirstSight);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -70, off) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 89800, RG_BASE, -70, off) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 89800, RG_BASE, -40, off) == RelogDecision::Suppress);
}

void test_relogGate_reset_clears() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, RG_BASE, -70);
  relogGateReset(&f.gate);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, -70) == RelogDecision::LogFirstSight);
}

void test_relogGate_longitude_scaling_at_high_latitude() {
  RgFixture f;
  const int32_t lat = 700000000;  // synthetic 70.0 deg
  rgAt(f, 1, lat, RG_BASE, -70);
  TEST_ASSERT_TRUE(rgAt(f, 1, lat, RG_BASE + 6000, -70) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgAt(f, 1, lat, RG_BASE + 6571, -70) == RelogDecision::LogMoved);
}

void test_relogGate_stationary_rssi_noise_bounded() {
  RgFixture f;
  int logs = 0;
  for (int i = 0; i < 1000; i++) {
    const int8_t rssi = (int8_t)(-80 + (i % 11));
    if (rgAt(f, 1, RG_BASE, RG_BASE, rssi) != RelogDecision::Suppress) logs++;
  }
  TEST_ASSERT_TRUE(logs <= 3);
}

void test_relogGate_positionless_repeat_after_fix_suppressed() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, RG_BASE, -70);
  TEST_ASSERT_TRUE(rgNoPos(f, 1, -50) == RelogDecision::Suppress);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE + 2695, RG_BASE, -70) == RelogDecision::LogMoved);
}

void test_relogGate_antimeridian_wrap() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, 1799999990, -70);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, -1799999990, -70) == RelogDecision::Suppress);
}

void test_relogGate_rssi_no_overflow() {
  RgFixture f;
  rgAt(f, 1, RG_BASE, RG_BASE, 125);
  TEST_ASSERT_TRUE(rgAt(f, 1, RG_BASE, RG_BASE, 127) == RelogDecision::Suppress);
  rgAt(f, 2, RG_BASE, RG_BASE, -128);
  TEST_ASSERT_TRUE(rgAt(f, 2, RG_BASE, RG_BASE, -122) == RelogDecision::LogStronger);
}

void test_relogGate_churn_keeps_slot_table_consistent() {
  RgFixture f(4, 8);
  for (uint8_t i = 0; i < 60; i++) {
    TEST_ASSERT_TRUE(rgAt(f, i, RG_BASE, RG_BASE, -70) == RelogDecision::LogFirstSight);
    // Checked every step so a leaked slot fails here instead of hanging a later probe.
    uint16_t occupied = 0;
    for (uint16_t s = 0; s < f.gate.table_size; s++) {
      if (f.gate.slots[s] != RELOG_GATE_EMPTY_SLOT) occupied++;
    }
    TEST_ASSERT_EQUAL_UINT16(f.gate.count, occupied);
  }
  for (uint8_t i = 56; i < 60; i++) {
    TEST_ASSERT_TRUE(rgAt(f, i, RG_BASE, RG_BASE, -70) == RelogDecision::Suppress);
  }
  TEST_ASSERT_TRUE(rgAt(f, 10, RG_BASE, RG_BASE, -70) == RelogDecision::LogFirstSight);
}

// Synthetic drive: one AP heard every 500 ms for 20 s, moving 5 m north per
// sample with RSSI rising 1 dB per sample. Scanner table, controller table,
// then (optionally) the re-log gate. Returns the number of resulting rows.
static uint32_t simulateDriveRows(uint32_t scanner_ttl, uint32_t controller_ttl, bool gate_on) {
  WiFiDedupeHash s_slots[8] = {};
  WiFiDedupeHash s_fifo[4] = {};
  uint32_t s_ms[4] = {};
  WiFiDedupeHash c_slots[8] = {};
  WiFiDedupeHash c_fifo[4] = {};
  uint32_t c_ms[4] = {};
  WiFiDedupeTable scanner;
  WiFiDedupeTable controller;
  wifiDedupeTableInitTtl(&scanner, s_slots, 8, s_fifo, s_ms, 4, scanner_ttl);
  wifiDedupeTableInitTtl(&controller, c_slots, 8, c_fifo, c_ms, 4, controller_ttl);
  RelogGateEntry g_entries[4] = {};
  uint16_t g_slots[8];
  RelogGate gate;
  relogGateInit(&gate, g_entries, 4, g_slots, 8);
  const RelogGateConfig cfg = {25, 6};

  const uint8_t bssid[6] = {0x00, 0x00, 0x5E, 0x00, 0x53, 0x01};
  WiFiDedupeHash key;
  wifiDedupeHashFromFields("", bssid, 6, 0, 0x0431, key);

  uint32_t rows = 0;
  for (uint32_t i = 0; i < 40; i++) {
    const uint32_t now = i * 500;
    // 5 m north is about 449 units of 1e-7 degree.
    const int32_t lat_e7 = 10000000 + (int32_t)(i * 449);
    const int32_t lon_e7 = 10000000;
    const int8_t rssi = (int8_t)(-85 + (int)i);
    if (!wifiDedupeTableRememberAt(&scanner, &key, now)) continue;
    if (!wifiDedupeTableRememberAt(&controller, &key, now)) continue;
    if (gate_on) {
      if (relogGateDecide(&gate, key, true, lat_e7, lon_e7, rssi, cfg) ==
          RelogDecision::Suppress) {
        continue;
      }
    }
    rows++;
  }
  return rows;
}

void test_relogPipeline_today_one_row() {
  TEST_ASSERT_EQUAL_UINT32(1, simulateDriveRows(0, 0, false));
}

void test_relogPipeline_new_controller_old_scanner() {
  TEST_ASSERT_EQUAL_UINT32(1, simulateDriveRows(0, 1000, true));
}

void test_relogPipeline_old_controller_new_scanner() {
  TEST_ASSERT_EQUAL_UINT32(1, simulateDriveRows(1000, 0, false));
}

void test_relogPipeline_disabled_build_matches_today() {
  // 0/0 build: controller TTL 0, gate compiled out.
  TEST_ASSERT_EQUAL_UINT32(1, simulateDriveRows(1000, 0, false));
}

void test_relogPipeline_both_new_relogs() {
  const uint32_t rows = simulateDriveRows(1000, 1000, true);
  TEST_ASSERT_GREATER_OR_EQUAL_UINT32(3, rows);
  TEST_ASSERT_LESS_OR_EQUAL_UINT32(40, rows);
}

int main(int argc, char** argv) {
  (void)argc;
  (void)argv;

  UNITY_BEGIN();
  RUN_TEST(test_wifiBandFromChannel_boundaries);
  RUN_TEST(test_wifiBandChannelValid_by_band);
  RUN_TEST(test_wifiBssid_helpers);
  RUN_TEST(test_wifiBssid_helpers_null_and_zero_length);
  RUN_TEST(test_wifiResult_validation_rules);
  RUN_TEST(test_wifiDedupeHash_is_deterministic);
  RUN_TEST(test_wifiDedupeHash_ignores_rssi_field);
  RUN_TEST(test_wifiDedupeHash_changes_when_identity_fields_change);
  RUN_TEST(test_wifiDedupeTable_remember_contains_and_duplicate);
  RUN_TEST(test_wifiDedupeTable_ring_overwrite_when_full);
  RUN_TEST(test_wifiDedupeTable_corrupt_metadata_guard);
  RUN_TEST(test_wifiDedupeTable_fifo_desync_skips_stale_entries);
  RUN_TEST(test_wifiDedupeTable_fifo_total_desync_resets_table);
  RUN_TEST(test_wifiDedupeTable_reset_clears_state);
  RUN_TEST(test_wifiDedupeTable_invalid_inputs);
  RUN_TEST(test_wifiDedupeTable_zero_hash_uses_reserved_empty_sentinel);
  RUN_TEST(test_wifiDedupeTable_collision_probe_chain);
  RUN_TEST(test_wifiDedupeTable_many_insertions_keep_only_most_recent_entries);
  RUN_TEST(test_wifiDedupeTable_evicted_entry_can_be_reinserted);
  RUN_TEST(test_wifiDedupeTable_ttl_expires_entry);
  RUN_TEST(test_wifiDedupeTable_ttl_zero_never_expires);
  RUN_TEST(test_wifiDedupeTable_hit_does_not_refresh_ttl);
  RUN_TEST(test_wifiDedupeTable_millis_wraparound);
  RUN_TEST(test_wifiDedupeTable_remember_without_time_never_expires);
  RUN_TEST(test_wifiDedupeHash_fields_matches_result);
  RUN_TEST(test_relogGate_first_sight_logs);
  RUN_TEST(test_relogGate_same_spot_suppressed);
  RUN_TEST(test_relogGate_24m_suppressed_25m_logs);
  RUN_TEST(test_relogGate_rssi_plus5_suppressed_plus6_logs);
  RUN_TEST(test_relogGate_rssi_baseline_resets_after_distance_relog);
  RUN_TEST(test_relogGate_no_fix_logs_once_then_suppresses);
  RUN_TEST(test_relogGate_fix_arrival_relogs_positionless_entry);
  RUN_TEST(test_relogGate_eviction_relogs_as_first_sight);
  RUN_TEST(test_relogGate_zero_zero_disables);
  RUN_TEST(test_relogGate_reset_clears);
  RUN_TEST(test_relogGate_longitude_scaling_at_high_latitude);
  RUN_TEST(test_relogGate_stationary_rssi_noise_bounded);
  RUN_TEST(test_relogGate_positionless_repeat_after_fix_suppressed);
  RUN_TEST(test_relogGate_antimeridian_wrap);
  RUN_TEST(test_relogGate_rssi_no_overflow);
  RUN_TEST(test_relogGate_churn_keeps_slot_table_consistent);
  RUN_TEST(test_relogPipeline_today_one_row);
  RUN_TEST(test_relogPipeline_new_controller_old_scanner);
  RUN_TEST(test_relogPipeline_old_controller_new_scanner);
  RUN_TEST(test_relogPipeline_disabled_build_matches_today);
  RUN_TEST(test_relogPipeline_both_new_relogs);
  return UNITY_END();
}
