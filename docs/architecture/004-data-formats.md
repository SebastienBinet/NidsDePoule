# ADR-004: Data Formats — Wire Protocol and Storage

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Wire Protocol: Protocol Buffers

### Schema Design

```protobuf
syntax = "proto3";

package nidsdepoule;

// Envelope for all client-to-server messages
message ClientMessage {
  uint32 protocol_version = 1;  // Schema version (starts at 1)
  string device_id = 2;         // UUID v4 identifying the device
  uint32 app_version = 3;       // App build number

  oneof payload {
    HitReport hit = 10;
    HitBatch batch = 11;
    Heartbeat heartbeat = 12;
  }
}

// A single pothole hit report
message HitReport {
  // When the hit occurred (Unix timestamp in milliseconds)
  int64 timestamp_ms = 1;

  // GPS data at time of hit
  Location location = 2;

  // Vehicle motion at time of hit
  float speed_mps = 3;      // Speed in meters/second
  float bearing_deg = 4;     // Compass bearing in degrees (0-360)

  // Hit pattern data
  HitPattern pattern = 5;
}

// GPS location
message Location {
  // Latitude and longitude in microdegrees (int32).
  // 1 microdegree ≈ 0.11 meters at the equator.
  // This saves ~8 bytes vs two doubles while giving ~11cm precision.
  sfixed32 lat_microdeg = 1;
  sfixed32 lon_microdeg = 2;

  // Horizontal accuracy in meters (0-255 is enough range)
  uint32 accuracy_m = 3;
}

// Hit waveform pattern — designed to evolve
message HitPattern {
  // Classification of hit severity (0=unknown, 1=light, 2=medium, 3=heavy)
  uint32 severity = 1;

  // Peak vertical acceleration in milli-g (1g = 1000)
  int32 peak_vertical_mg = 2;

  // Peak lateral acceleration in milli-g
  int32 peak_lateral_mg = 3;

  // Duration of the impact event in milliseconds
  uint32 duration_ms = 4;

  // Sampled waveform around the peak (vertical axis, in milli-g)
  // Samples taken at 20ms intervals, centered on peak.
  // Typically 10-20 samples (200-400ms window).
  repeated int32 waveform_vertical = 5 [packed = true];

  // Sampled waveform around the peak (lateral axis, in milli-g)
  repeated int32 waveform_lateral = 6 [packed = true];

  // Baseline acceleration magnitude in milli-g at time of hit
  // (rolling average of recent driving)
  int32 baseline_mg = 7;

  // Ratio of peak to baseline (fixed-point: value × 100, so 350 = 3.5×)
  uint32 peak_to_baseline_ratio = 8;

  // Reserved for future hit pattern fields
  // Use field numbers 20+ for new fields to keep wire format stable
}

// Batch of hits (for Wi-Fi upload mode)
message HitBatch {
  repeated HitReport hits = 1;
}

// Periodic heartbeat (for connection health / future use)
message Heartbeat {
  int64 timestamp_ms = 1;
  uint32 pending_hits = 2;  // How many hits are queued for upload
}
```

### Size Estimate

A typical HitReport with 15 waveform samples:
- Timestamp: 5 bytes (varint)
- Location: 10 bytes (2 × sfixed32 + varint accuracy)
- Speed + bearing: 8 bytes (2 × float)
- Pattern: ~45 bytes (severity + peaks + duration + 15 samples packed + baseline + ratio)
- **Total HitReport: ~70 bytes**

With ClientMessage envelope (version + device_id + app_version):
- ~90 bytes total per hit in binary mode
- ~500 bytes in text format (ASCII mode)

This is very efficient. At 10 hits per minute (heavy pothole road), real-time
mode uses ~54 KB/hour.

## Storage Format on Server

### Chosen: Newline-Delimited Protobuf Binary (with JSON index)

**Structure:**

```
data/
├── incoming/                    # Raw incoming data
│   └── 2025/
│       └── 02/
│           └── 17/
│               ├── 14/          # Hour 14 (2pm)
│               │   ├── hits.binpb      # Protobuf binary (length-prefixed records)
│               │   ├── hits.jsonl      # JSON Lines index (for debugging/querying)
│               │   └── stats.json      # Hourly stats (count, unique devices, etc.)
│               └── 15/
│                   ├── hits.binpb
│                   ├── hits.jsonl
│                   └── stats.json
├── processed/                   # After analysis pipeline processes them
│   └── (same date/hour structure)
└── potholes/                    # Clustered pothole entities (future)
    └── potholes.db              # SQLite database of known potholes
```

### Binary File Format (hits.binpb)

Each record in the `.binpb` file is length-prefixed:

```
[4 bytes: little-endian uint32 record length][N bytes: serialized ServerHitRecord]
[4 bytes: little-endian uint32 record length][N bytes: serialized ServerHitRecord]
...
```

The `ServerHitRecord` wraps the client's `HitReport` with server metadata:

```protobuf
// Server-side enriched record
message ServerHitRecord {
  // Server receive timestamp (Unix ms)
  int64 server_timestamp_ms = 1;

  // Original client message metadata
  uint32 protocol_version = 2;
  string device_id = 3;
  uint32 app_version = 4;

  // The original hit report
  HitReport hit = 5;

  // Server-assigned unique ID for this record
  uint64 record_id = 6;
}
```

### JSON Lines Index (hits.jsonl)

One JSON object per line, for easy debugging and ad-hoc querying:

```json
{"id":1,"ts":"2025-02-17T14:32:01.123Z","device":"a1b2c3","lat":45.764043,"lon":4.835659,"severity":2,"peak_mg":4500}
{"id":2,"ts":"2025-02-17T14:32:05.456Z","device":"d4e5f6","lat":45.767891,"lon":4.832145,"severity":1,"peak_mg":2100}
```

### Rationale for This Storage Format

1. **Date/hour directory structure:** Easy to browse, archive, delete old data.
   One hour of data at peak (10 users × 10 hits/min × 60 min × 100 bytes)
   = ~600 KB per hour. Very manageable on any disk.
2. **Binary protobuf for primary storage:** Compact, preserves all fields
   including the waveform. Can be re-read with any future version of the
   protobuf schema.
3. **JSON Lines for debugging:** Human-readable index with key fields only.
   Can be searched with `grep`, `jq`, or loaded into pandas.
4. **Length-prefixed records:** Standard pattern for streaming protobuf files.
   Easy to read sequentially and to append atomically.
5. **Server wraps client data:** The `ServerHitRecord` adds server timestamp
   and a unique ID without modifying the client's data. The original client
   message is preserved verbatim inside.

### Why Not SQLite for Raw Storage?

- SQLite requires schema definition up front. As the hit pattern evolves, we'd
  need migrations.
- Binary protobuf fields (waveform) don't fit well in SQL columns.
- Append-only file writes are simpler and faster than SQLite INSERTs for an
  ingestion queue.
- We use SQLite later for the processed potholes database, where the schema is
  more stable.

### Why Not Pure JSON?

- JSON is verbose (~5× larger than protobuf for this data).
- Parsing JSON is slower than parsing protobuf.
- We keep a JSON Lines index for convenience, but the source of truth is the
  binary protobuf file.

## Version Strategy

- `protocol_version` in `ClientMessage`: incremented when the protobuf schema
  changes in a breaking way. Non-breaking additions don't bump this.
- `app_version` in `ClientMessage`: the Android app build number. Lets the
  server know which client version sent the data.
- Protobuf's native field evolution: new fields can be added without breaking
  old readers (unknown fields are preserved). Fields can be deprecated by
  convention (prefix with `deprecated_`).
