# ADR-012: Observability, Modularity, Developer Tools, and Extensibility

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Design Principles

This is a hobby project. The architecture should be:
- **Simple first.** No over-engineering. Only add abstraction when there's a
  concrete second use case.
- **Debuggable.** When something goes wrong at 2am, you should be able to
  figure out what happened in 5 minutes by reading logs.
- **Replaceable.** No module should require rewriting more than one other
  module to swap out.
- **Testable.** Every module can be tested in isolation with fake inputs.

---

## 1. OBSERVABILITY

### The Problem
You're running a server on a free Oracle Cloud VM. You're the only operator.
You need to know: Is it working? How fast? Any errors? What happened yesterday?

### Strategy: Structured Logging as the Foundation

Heavy monitoring frameworks (Prometheus, Grafana, Datadog) are overkill for a
hobby project. Instead, **structured logging covers 90% of observability needs
at zero cost.**

#### Structured Logging (Server)

Every log line is a JSON object. This makes logs greppable, parseable, and
queryable.

```python
# What we want every log line to look like:
{"ts": "2025-02-17T14:32:01.123Z", "level": "info", "module": "api",
 "event": "hit_received", "device": "a1b2...", "lat": 45.76, "lon": 4.83,
 "size_bytes": 712, "duration_ms": 3.2}
```

**Implementation:** Python `structlog` library.

```python
import structlog

log = structlog.get_logger()

# Anywhere in the code:
log.info("hit_received", device=device_id, lat=lat, lon=lon,
         size_bytes=len(data), duration_ms=elapsed)
log.warning("queue_high", depth=queue.qsize(), max=10000)
log.error("storage_write_failed", path=path, error=str(e))
```

**Why structlog:**
- JSON output by default. Greppable.
- Context binding: attach `device_id` once, it appears in all subsequent logs.
- Zero-cost in production (no sampling needed at this scale).
- Human-readable output in development, JSON in production (configurable).
- If we ever add Loki, Elasticsearch, or CloudWatch, structured logs feed
  directly into them.

#### Key Events to Log

| Event                  | Level | Fields                                    |
|------------------------|-------|-------------------------------------------|
| `hit_received`         | info  | device, lat, lon, severity, size_bytes    |
| `batch_received`       | info  | device, count, total_bytes                |
| `hit_stored`           | debug | record_id, path, write_ms                 |
| `queue_depth`          | info  | depth, max (periodic, every 30s)          |
| `queue_high`           | warn  | depth, max                                |
| `queue_overflow`       | error | depth, dropped_count                      |
| `storage_write_failed` | error | path, error                               |
| `request_rejected`     | warn  | device, reason, status_code               |
| `server_started`       | info  | host, port, version                       |
| `purge_executed`       | info  | mode, deleted_count, freed_bytes          |

#### Log Files and Rotation

```
logs/
├── server.log          # Current log (JSON lines)
├── server.log.1        # Previous rotation
├── server.log.2
└── ...
```

- Rotate daily or at 100 MB (whichever comes first).
- Keep 7 days of logs.
- Use Python `logging.handlers.RotatingFileHandler`.
- Total log disk usage: ~200 MB max (well within free tier).

#### Metrics: Lightweight In-Process Counters

Instead of Prometheus, maintain simple in-memory counters that are exposed via
a `/api/v1/stats` endpoint:

```python
@dataclass
class ServerStats:
    started_at: datetime
    hits_received: int = 0
    hits_stored: int = 0
    hits_rejected: int = 0
    bytes_received: int = 0
    bytes_stored: int = 0
    queue_depth: int = 0
    queue_max_depth: int = 0
    storage_errors: int = 0
    active_devices_1h: set = field(default_factory=set)

    def snapshot(self) -> dict:
        """Return a JSON-serializable snapshot."""
        return {
            "uptime_seconds": (datetime.now() - self.started_at).total_seconds(),
            "hits_received": self.hits_received,
            "hits_stored": self.hits_stored,
            "hits_rejected": self.hits_rejected,
            "bytes_received": self.bytes_received,
            "queue_depth": self.queue_depth,
            "queue_max_depth_ever": self.queue_max_depth,
            "storage_errors": self.storage_errors,
            "active_devices_1h": len(self.active_devices_1h),
        }
```

**Accessible via:**
- `GET /api/v1/stats` — returns JSON. Can be polled by a cron job, a
  dashboard, or just curl.
- Optionally: write a stats snapshot to a file every 5 minutes for historical
  data.

**Why not Prometheus/Grafana:**
- Prometheus needs a scraper process + storage. Grafana needs a UI server.
  That's 2 extra services to maintain.
- At this scale, `curl /stats` and `grep` on log files give you everything.
- If we ever need Prometheus, the counter pattern maps directly to Prometheus
  counters. The migration is mechanical.

#### Android App Observability

- **Local logcat:** Standard Android logging. Filtered by tag `NidsDePoule`.
- **Data usage counters:** Already in the UI (KB/min, MB/hour, MB/month).
- **Hit counter:** Number of hits detected in the current session.
- **Connection status:** Last successful upload time, pending queue size.
- **No crash reporting service for now.** If needed later, Firebase Crashlytics
  is free.

#### Health Check

`GET /api/v1/health` returns:

```json
{
  "status": "ok",
  "version": "1.0.0",
  "uptime_seconds": 86400,
  "queue_depth": 42,
  "storage_writable": true,
  "disk_free_gb": 150.3
}
```

A simple cron job or UptimeRobot (free, 5-minute checks) can ping this and
alert you by email if it's down.

---

## 2. MODULARITY

### The Problem
The system has many parts (sensor reading, hit detection, reporting, storage,
analysis). They need to evolve independently and be testable in isolation.

### Strategy: Ports and Adapters (Hexagonal Architecture), Kept Simple

The core idea: **business logic doesn't know about frameworks.** Each module
defines an interface (port). The framework plugs in via an adapter.

But we won't use the word "hexagonal" or build an abstract factory factory.
We'll just follow one rule:

> **Every module depends on interfaces (Python Protocol / Kotlin interface),
> not on concrete implementations.**

#### Server Module Boundaries

```
┌──────────────────────────────────────────────────┐
│ CORE (no framework dependencies)                 │
│                                                  │
│  ┌──────────────┐  ┌──────────────┐              │
│  │ HitProcessor │  │ StatsTracker │              │
│  │ (validates,  │  │ (counters,   │              │
│  │  enriches)   │  │  snapshots)  │              │
│  └──────┬───────┘  └──────────────┘              │
│         │                                        │
│  ┌──────▼───────┐  ┌──────────────┐              │
│  │ HitQueue     │  │ PurgeManager │              │
│  │ (interface)  │  │ (interface)  │              │
│  └──────────────┘  └──────────────┘              │
└──────────────────────────────────────────────────┘
         │                    │
         │ adapters           │ adapters
         ▼                    ▼
┌──────────────┐    ┌──────────────┐
│ AsyncioQueue │    │ FileStorage  │
│ (in-memory)  │    │ (disk files) │
└──────────────┘    └──────────────┘
       OR                  OR
┌──────────────┐    ┌──────────────┐
│ RedisQueue   │    │ S3Storage    │
└──────────────┘    └──────────────┘
```

#### Concrete Interfaces (Python)

```python
from typing import Protocol

class HitQueue(Protocol):
    """Port: accepts hits and delivers them to consumers."""

    async def put(self, record: ServerHitRecord) -> None: ...
    async def get(self) -> ServerHitRecord: ...
    def qsize(self) -> int: ...

class HitStorage(Protocol):
    """Port: persists hit records."""

    async def store(self, record: ServerHitRecord) -> None: ...
    async def store_batch(self, records: list[ServerHitRecord]) -> None: ...

class HitPurger(Protocol):
    """Port: removes hit records by criteria."""

    async def purge_by_age(self, keep_percent: float) -> int: ...
    async def purge_by_severity(self, keep_percent: float) -> int: ...
    async def purge_by_device(self, device_id: str) -> int: ...
```

**Key rule:** The `HitProcessor` (core business logic) depends on `HitQueue`
and `HitStorage` protocols, never on `AsyncioQueue` or `FileStorage` directly.

**Wiring:** Done in `main.py` at startup:

```python
# main.py — this is the only place that knows concrete types
from server.queue.asyncio_queue import AsyncioHitQueue
from server.storage.file_storage import FileHitStorage
from server.core.processor import HitProcessor

queue = AsyncioHitQueue(max_size=10_000)
storage = FileHitStorage(base_dir="data/incoming")
processor = HitProcessor(queue=queue, storage=storage)
```

To switch to Redis + S3 later:

```python
from server.queue.redis_queue import RedisHitQueue
from server.storage.s3_storage import S3HitStorage

queue = RedisHitQueue(redis_url="redis://localhost")
storage = S3HitStorage(bucket="nidsdepoule-hits")
processor = HitProcessor(queue=queue, storage=storage)  # unchanged
```

#### Android Module Boundaries

```
┌──────────────────────────────────────────────────┐
│ CORE (pure Kotlin, no Android dependencies)      │
│                                                  │
│  ┌──────────────┐  ┌──────────────┐              │
│  │ HitDetector  │  │ CarMount     │              │
│  │ (algorithm)  │  │ Detector     │              │
│  └──────────────┘  └──────────────┘              │
│  ┌──────────────┐  ┌──────────────┐              │
│  │ DataUsage    │  │ Protobuf     │              │
│  │ Tracker      │  │ Serializer   │              │
│  └──────────────┘  └──────────────┘              │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│ PLATFORM (Android-specific)                      │
│                                                  │
│  ┌──────────────┐  ┌──────────────┐              │
│  │ SensorReader │  │ GpsReader    │              │
│  │ (accel API)  │  │ (location)   │              │
│  └──────────────┘  └──────────────┘              │
│  ┌──────────────┐  ┌──────────────┐              │
│  │ HttpReporter │  │ LocalBuffer  │              │
│  │ (OkHttp)     │  │ (Room/SQLite)│              │
│  └──────────────┘  └──────────────┘              │
└──────────────────────────────────────────────────┘
```

**The critical boundary:** `HitDetector` is pure Kotlin. No `import android.*`.
This means:
- It can be unit-tested on JVM without an Android emulator.
- Test data is just arrays of floats (simulated accelerometer readings).
- The detection algorithm can be iterated rapidly.

The platform layer implements interfaces that the core defines:

```kotlin
// Core defines this interface
interface AccelerometerSource {
    fun registerListener(callback: (timestamp: Long, x: Float, y: Float, z: Float) -> Unit)
    fun unregister()
}

// Platform implements it
class AndroidAccelerometer(context: Context) : AccelerometerSource {
    // Uses SensorManager internally
}

// Tests use a fake
class FakeAccelerometer : AccelerometerSource {
    fun simulateReading(timestamp: Long, x: Float, y: Float, z: Float)
}
```

#### What This Gives Us

| Benefit                | How                                           |
|------------------------|-----------------------------------------------|
| Test hit detection     | Feed synthetic data to `HitDetector` on JVM   |
| Test server logic      | Use `FakeQueue` + `FakeStorage`               |
| Swap queue backend     | Implement `HitQueue` protocol, change 1 line  |
| Swap storage backend   | Implement `HitStorage` protocol, change 1 line|
| Swap HTTP framework    | Only `api/` package changes                   |
| Run server without disk| Use `InMemoryStorage` for testing              |

---

## 3. FRAMEWORK REPLACEABILITY

### The Problem
FastAPI, OkHttp, Jetpack Compose — any of these could become unmaintained,
change licensing, or not fit future needs. How do we minimize lock-in?

### Strategy: Thin Framework Layer, Fat Core

The framework is an adapter, not the application. Framework-specific code
should be a thin shell around framework-agnostic core logic.

#### Server: FastAPI Is an Adapter

```
server/
├── server/
│   ├── core/                  # Framework-free business logic
│   │   ├── processor.py       # HitProcessor: validate, enrich, enqueue
│   │   ├── stats.py           # ServerStats: counters, snapshots
│   │   └── models.py          # Dataclasses (not Pydantic, not protobuf)
│   ├── api/                   # FastAPI-specific (THIN)
│   │   ├── app.py             # FastAPI app setup, middleware
│   │   └── hits.py            # Route handlers (parse request → call core)
│   ├── queue/                 # Queue adapters
│   │   ├── base.py            # HitQueue protocol
│   │   ├── asyncio_queue.py   # asyncio.Queue implementation
│   │   └── redis_queue.py     # Redis implementation (future)
│   ├── storage/               # Storage adapters
│   │   ├── base.py            # HitStorage protocol
│   │   ├── file_storage.py    # Disk file implementation
│   │   └── s3_storage.py      # S3/R2 implementation (future)
│   └── proto/                 # Generated protobuf code (adapter)
│       └── nidsdepoule_pb2.py
```

**Replacing FastAPI with Flask, Starlette, or anything else:**
1. Rewrite `api/app.py` and `api/hits.py` (~100-200 lines).
2. Everything in `core/`, `queue/`, `storage/` is untouched.

**Replacing protobuf with something else:**
1. Write a new serializer that produces the same internal models.
2. Change `api/hits.py` to use the new serializer.
3. Core logic uses `models.py` dataclasses, not protobuf objects directly.

#### Android: Keep Activities Thin

Activities and Composables are UI adapters. They call into core services:

```kotlin
// Activity does NOT contain business logic
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val hitDetector = HitDetector(config)     // core
        val reporter = HitReporter(httpClient)     // core
        val sensorReader = AndroidAccelerometer(this) // platform adapter

        setContent {
            NidsDePouleApp(hitDetector, reporter, sensorReader)
        }
    }
}
```

**Replacing Jetpack Compose with XML layouts:**
- Rewrite the UI layer. Core logic (`HitDetector`, `HitReporter`) is untouched.

**Replacing OkHttp with Ktor or another HTTP client:**
- `HitReporter` depends on a `HttpClient` interface, not OkHttp directly.
- Swap the implementation.

#### Protobuf Isolation

Protobuf generated code is an adapter, not a core type. The core uses plain
data classes:

```python
# core/models.py — no protobuf imports
@dataclass
class HitData:
    timestamp_ms: int
    lat_microdeg: int
    lon_microdeg: int
    speed_mps: float
    bearing_deg: float
    bearing_before_deg: float
    bearing_after_deg: float
    severity: int
    peak_vertical_mg: int
    peak_lateral_mg: int
    duration_ms: int
    waveform_vertical: list[int]
    waveform_lateral: list[int]
    baseline_mg: int
```

```python
# proto/converter.py — the only file that imports protobuf
def from_protobuf(msg: HitReport) -> HitData:
    """Convert protobuf message to internal data class."""
    ...

def to_protobuf(data: HitData) -> HitReport:
    """Convert internal data class to protobuf message."""
    ...
```

**This means:** If protobuf is replaced with FlatBuffers, MessagePack, or
anything else, only `proto/converter.py` changes.

---

## 4. DEVELOPMENT TOOLS

### Hit Simulator

A Python tool that generates realistic traffic for testing:

```bash
# Simulate 5 devices driving around Lyon for 10 minutes
python -m tools.simulator.simulate \
    --server http://localhost:8000 \
    --devices 5 \
    --duration 600 \
    --center 45.764,4.835 \
    --radius-km 5

# Replay a recorded drive (real sensor data captured to file)
python -m tools.simulator.replay \
    --server http://localhost:8000 \
    --file recorded_drive.binpb

# Stress test: simulate 100 devices sending at max rate
python -m tools.simulator.simulate \
    --server http://localhost:8000 \
    --devices 100 \
    --hits-per-minute 60 \
    --mode binary
```

**Features:**
- Generates GPS coordinates along realistic road paths (using random walks
  along a grid, or replaying real GPX tracks).
- Generates accelerometer data with configurable noise and pothole frequency.
- Supports both binary and text protobuf modes.
- Reports throughput statistics (hits/sec, bytes/sec, error rate).
- Can save generated data to files for reproducible tests.

### Data Inspector

A CLI tool to examine stored data:

```bash
# Show summary of stored data
python -m tools.scripts.inspect_data --summary

# Output:
# Total records: 14,523
# Date range: 2025-02-10 to 2025-02-17
# Unique devices: 47
# Severity distribution: light=8901, medium=4322, heavy=1300
# Total size: 12.3 MB

# Show the last 10 hits
python -m tools.scripts.inspect_data --tail 10

# Show hits from a specific device
python -m tools.scripts.inspect_data --device a1b2c3

# Export to GeoJSON (for visualization in QGIS or geojson.io)
python -m tools.scripts.inspect_data --export geojson --output hits.geojson

# Export to CSV
python -m tools.scripts.inspect_data --export csv --output hits.csv
```

### Server Dev Mode

When running in development mode, the server provides extra features:

```python
# Enabled by: uvicorn server.main:app --reload (or SERVER_ENV=dev)

# Extra endpoints in dev mode:
GET  /api/v1/debug/last-hits?n=10    # Last 10 received hits (in-memory ring buffer)
GET  /api/v1/debug/queue             # Current queue contents
POST /api/v1/debug/reset             # Clear all stored data
GET  /api/v1/debug/config            # Current server configuration
POST /api/v1/debug/config            # Update configuration on-the-fly
```

**These endpoints are disabled in production** (controlled by environment
variable or config file).

### Android Dev Mode

A hidden developer screen accessible via long-press on the version number:

- **Raw sensor view:** Live accelerometer values (x, y, z) + gravity.
- **Detection threshold slider:** Adjust the hit detection multiplier in
  real time and see which bumps would trigger.
- **Force send mode:** Send every bump (not just detected hits) for data
  collection.
- **Server URL override:** Point to localhost/ngrok/production.
- **ASCII mode toggle:** Switch between binary and text protobuf.
- **Mock GPS:** Enable fake GPS for indoor testing.

### Configuration Management

Server configuration via a single YAML file + environment variable overrides:

```yaml
# config.yaml
server:
  host: 0.0.0.0
  port: 8000
  env: dev  # dev | prod

queue:
  backend: asyncio  # asyncio | redis
  max_size: 10000
  redis_url: redis://localhost:6379  # only if backend=redis

storage:
  backend: file  # file | s3
  base_dir: data/incoming
  s3_bucket: ""  # only if backend=s3

limits:
  max_hits_per_device_per_hour: 600
  max_waveform_samples: 150
  max_batch_size: 100

logging:
  level: info  # debug | info | warning | error
  format: json  # json | console
  file: logs/server.log
```

Environment variables override YAML values:

```bash
SERVER_ENV=prod
QUEUE_BACKEND=redis
QUEUE_REDIS_URL=redis://myhost:6379
STORAGE_BACKEND=s3
STORAGE_S3_BUCKET=nidsdepoule-hits
```

**Why YAML + env vars:**
- YAML for the full config with comments (developer-friendly).
- Env vars for deployment overrides (12-factor app, works in containers).
- No framework dependency (just PyYAML, or even plain `dict` if we drop YAML).

---

## 5. TESTING ARCHITECTURE

### Test Pyramid

```
        ╱  E2E tests  ╲           Few, slow, fragile
       ╱  (simulator → ╲          Real HTTP + disk
      ╱   server → disk) ╲
     ╱─────────────────────╲
    ╱  Integration tests     ╲    Moderate number
   ╱  (FastAPI TestClient,    ╲   In-memory queue + storage
  ╱   Room in-memory DB)       ╲
 ╱──────────────────────────────╲
╱  Unit tests (pure logic)       ╲  Many, fast, reliable
╱  HitDetector, HitProcessor,    ╲ No I/O, no framework
╱  CarMountDetector, Serializer    ╲
╱───────────────────────────────────╲
```

### Fake Implementations for Testing

Every interface (port) gets a fake implementation used in tests:

```python
class InMemoryHitQueue:
    """Fake queue for testing. Stores hits in a list."""
    def __init__(self):
        self._items = []
    async def put(self, record):
        self._items.append(record)
    async def get(self):
        return self._items.pop(0)
    def qsize(self):
        return len(self._items)

class InMemoryHitStorage:
    """Fake storage for testing. Stores hits in memory."""
    def __init__(self):
        self.records = []
    async def store(self, record):
        self.records.append(record)
    async def store_batch(self, records):
        self.records.extend(records)
```

Tests use these fakes:

```python
async def test_hit_processor_stores_valid_hit():
    queue = InMemoryHitQueue()
    storage = InMemoryHitStorage()
    processor = HitProcessor(queue=queue, storage=storage)

    hit = make_test_hit(lat=45764043, lon=4835659, severity=2)
    await processor.process(hit)

    assert len(storage.records) == 1
    assert storage.records[0].hit.pattern.severity == 2
```

---

## 6. EXTENSIBILITY POINTS

### Plugin-Like Extension Without a Plugin System

We don't need a plugin registry or dynamic loading. We need clearly defined
places where new behavior can be added:

| Extension point           | How to extend                                | Example                            |
|---------------------------|----------------------------------------------|------------------------------------|
| New hit pattern fields    | Add field to protobuf + `HitData` dataclass | Road surface type, tire pressure   |
| New storage backend       | Implement `HitStorage` protocol              | PostgreSQL, ClickHouse, MongoDB    |
| New queue backend         | Implement `HitQueue` protocol                | Kafka, SQS, ZeroMQ                |
| New analysis module       | Read from storage, write results to new dir  | ML classifier, seasonal trends    |
| New API endpoint          | Add route in `api/` package                  | City admin API, friend API         |
| New export format         | Add to `inspect_data` tool                   | KML, Shapefile, Parquet           |
| New detection algorithm   | Implement `HitDetectionStrategy` interface   | FFT-based, ML-based               |

### Hit Detection Strategy (Android)

The hit detection algorithm will evolve significantly. Make it swappable:

```kotlin
interface HitDetectionStrategy {
    /**
     * Process a new accelerometer reading.
     * Returns a HitEvent if a pothole is detected, null otherwise.
     */
    fun processReading(
        timestamp: Long,
        verticalAccel: Float,   // milli-g, gravity-compensated
        lateralAccel: Float,    // milli-g
        speed: Float            // m/s
    ): HitEvent?

    /**
     * Reset the baseline and internal state.
     */
    fun reset()
}

// v1: Simple threshold over rolling average
class ThresholdHitDetector(
    private val windowSize: Int = 3000,      // 60 seconds at 50 Hz
    private val thresholdFactor: Float = 3.0f
) : HitDetectionStrategy { ... }

// Future: FFT-based frequency analysis
class FrequencyHitDetector(...) : HitDetectionStrategy { ... }

// Future: ML model
class ModelHitDetector(modelPath: String) : HitDetectionStrategy { ... }
```

The app can switch strategies via configuration without code changes.

---

## 7. SUMMARY: WHAT TO BUILD INTO THE CODE FROM DAY 1

| Concern          | Day 1 implementation                  | Swap path                  |
|------------------|---------------------------------------|----------------------------|
| Logging          | `structlog` → JSON files              | Any log aggregator (Loki, ELK) |
| Metrics          | In-memory counters → `/stats`         | Prometheus counters         |
| Health check     | `GET /health` endpoint                | Any uptime monitor          |
| Queue            | `asyncio.Queue` + `HitQueue` protocol| Redis, Kafka               |
| Storage          | File-based + `HitStorage` protocol   | S3, PostgreSQL              |
| Hit detection    | Threshold + `HitDetectionStrategy`    | FFT, ML                    |
| Serialization    | Protobuf + converter layer            | FlatBuffers, MessagePack   |
| HTTP framework   | FastAPI (thin adapter)                | Flask, Starlette           |
| HTTP client      | OkHttp + `HttpClient` interface       | Ktor, Volley               |
| Configuration    | YAML + env vars                       | Any config source          |
| Testing          | Fakes for every interface             | No change needed           |

**Total overhead of doing this right: ~50-100 extra lines of interface
definitions.** This is not over-engineering — it's the minimum structure
needed to avoid a rewrite when the first component needs to change.
