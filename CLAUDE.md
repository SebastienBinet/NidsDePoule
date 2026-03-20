# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NidsDePoule is a crowdsourced pothole detection system. An Android app detects potholes via accelerometer + GPS while driving, reports them to a FastAPI server, which clusters and maps them on a web dashboard.

## Commands

### Server (Python/FastAPI)

```bash
cd server
pip install -r requirements.txt
uvicorn server.main:app --host 0.0.0.0 --port 8000 --reload

# Run all tests
python -m pytest tests/ -v

# Run a single test
python -m pytest tests/test_api.py::test_submit_single_hit -v

# Run smoke tests against live server
SMOKE_TEST_URL=https://nidsdepoule.onrender.com python -m pytest tests/test_smoke.py -v
```

### Android (Kotlin/Gradle)

```bash
cd android
./gradlew assembleDebug                    # Build debug APK
./gradlew testDebugUnitTest                # Run unit tests
./build-and-install.sh install             # Build + install via USB
./build-and-install.sh server URL install  # Set server URL + install
```

## Architecture

### Data Flow

```
Android App → POST /api/v1/hits (JSON) → HitProcessor → AsyncioHitQueue → StorageConsumer → HitStorage backend
                                                                                                    ↓
Dashboard (index.html) ← GET /api/v1/potholes (GeoJSON) ← clustering.py ← storage.read_all_hits()
```

### Server Structure (`server/server/`)

- **`main.py`** — FastAPI app, lifespan management, singleton wiring (`get_processor()`, `get_stats()`, `get_storage()`, `get_config()`)
- **`core/processor.py`** — HitProcessor: validates messages, enqueues hits, runs background storage consumer
- **`core/models.py`** — Immutable dataclasses: `ClientMessageData`, `ServerHitRecordData`, `HitData`, `LocationData`, `HitPatternData`
- **`core/stats.py`** — Thread-safe `ServerStats` with device tracking and active window
- **`core/clustering.py`** — Greedy spatial clustering (15m radius, haversine distance) → GeoJSON output
- **`api/hits.py`** — `POST /api/v1/hits` (single hit, batch, heartbeat)
- **`api/potholes.py`** — `GET /api/v1/potholes`, `GET /api/v1/hits/recent`, `DELETE /api/v1/hits`
- **`api/monitoring.py`** — `/health`, `/stats`, `/devices/active`, `/debug/storage`, `/config`
- **`storage/base.py`** — `HitStorage` protocol: `store()`, `store_batch()`, `read_all_hits()`, `delete_hits()`
- **`storage/`** — Implementations: `file_storage.py` (default dev), `firestore_storage.py` (prod), `firebase_storage.py`, `s3_storage.py`
- **`queue/base.py`** — `HitQueue` protocol; `asyncio_queue.py` implementation
- **`web/index.html`** — Dashboard served at `/`, uses `{{VERSION_LABEL}}` interpolation

### Android Structure (`android/app/src/main/java/fr/nidsdepoule/app/`)

- **`MainViewModel.kt`** — Core logic: accelerometer buffer, GPS interpolation, hit building, report sending
- **`reporting/HitReporter.kt`** — HTTP transport, heartbeat timer (500ms), pothole fetching, batch/realtime modes
- **`detection/`** — `ThresholdHitDetector`, `HitDetectionStrategy`, `ReportSource` enum (`ALMOST`/`HIT`)
- **`ui/MainScreen.kt`** — Jetpack Compose UI with AYOYE (hit) and "iiiiiiiii !!!" (almost) buttons
- **`reporting/HitReportData.kt`** — JSON serialization for server protocol

### Client-Server Protocol (JSON over HTTP)

Hit source values: `"hit"` (AYOYE button, severity 3) and `"almost"` (iiiiiiiii button, severity 2).

Messages: single hit (`{hit: {...}}`), batch (`{batch: {hits: [...]}}`), heartbeat (`{heartbeat: {...}}`). All include `protocol_version`, `device_id`, `app_version`, `source`.

### Configuration

`server/config.yaml` with env var overrides: `NIDS_<SECTION>_<KEY>` (e.g. `NIDS_STORAGE_BACKEND=firestore`). Env vars always win.

Production deployment on Render.com (`render.yaml`), using Firestore backend.

## Version Bumping

When deploying a new version, update **both**:
1. `VERSION_LABEL` (root file)
2. `server/server/main.py` → `_VERSION_LABEL`

These must match. The version appears on the dashboard and in `/api/v1/debug/storage`.

## Testing

Tests use `pytest-asyncio` with `asyncio_mode = "auto"`. Key fixtures in `server/tests/conftest.py`:
- `client` — httpx `AsyncClient` with ASGI transport
- `spy_storage` — `SpyHitStorage` that records all `store()` calls and can simulate failures via `fail_next = True`
- `_init_server` — auto-fixture that wires up server singletons with temp storage per test

Smoke tests (`test_smoke.py`) hit a live server and are skipped unless `SMOKE_TEST_URL` is set.
