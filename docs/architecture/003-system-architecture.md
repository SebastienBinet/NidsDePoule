# ADR-003: System Architecture

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## High-Level Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Android App                        │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌────────────────────┐ │
│  │Sensor    │  │Hit       │  │Reporting           │ │
│  │Module    │──▶Detection │──▶Module              │ │
│  │(accel,   │  │Module    │  │(real-time / batch) │ │
│  │ GPS)     │  │          │  │                    │ │
│  └──────────┘  └──────────┘  └────────┬───────────┘ │
│  ┌──────────┐  ┌──────────┐           │             │
│  │Car Mount │  │UI Module │           │             │
│  │Detection │  │(graph,   │           │             │
│  │Module    │  │ stats)   │           │             │
│  └──────────┘  └──────────┘           │             │
└───────────────────────────────────────┼─────────────┘
                                        │ HTTPS (protobuf)
                                        │ via ngrok/CF tunnel
┌───────────────────────────────────────┼─────────────┐
│                Mac Server             ▼             │
│  ┌──────────────────────────────────────────┐       │
│  │  FastAPI  (REST endpoint)                │       │
│  └─────────────┬────────────────────────────┘       │
│                │                                    │
│  ┌─────────────▼────────────────────────────┐       │
│  │  Ingestion Queue  (in-memory + overflow) │       │
│  └─────────────┬────────────────────────────┘       │
│                │                                    │
│  ┌─────────────▼────────────────────────────┐       │
│  │  Storage Writer  (disk files)            │       │
│  └─────────────┬────────────────────────────┘       │
│                │                                    │
│  ┌─────────────▼────────────────────────────┐       │
│  │  Analysis Pipeline  (future)             │       │
│  └──────────────────────────────────────────┘       │
│                                                      │
│  ┌──────────────────────────────────────────┐       │
│  │  Web Dashboard  (future)                 │       │
│  └──────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────┘
```

## Android App Modules

### 1. Sensor Module
- Reads accelerometer data at a configurable rate (default: 50 Hz).
- Reads GPS location, speed, and bearing.
- Maintains a 60-second circular buffer of acceleration data for the graph.
- Provides acceleration data in device-local coordinates (up-down = Z axis,
  left-right = X axis after rotation compensation).

### 2. Car Mount Detection Module
- Detects when the phone is placed in a car mount (relatively stable
  orientation with characteristic vibration pattern).
- Uses a combination of:
  - Gravity sensor to detect stable orientation.
  - Low-frequency vibration pattern typical of a car engine/road.
  - Optional: connection to car Bluetooth as a hint.
- When car mount is detected, hit detection module activates.
- When car mount is not detected, the app conserves battery.

### 3. Hit Detection Module
- Filters accelerometer data to isolate pothole-like impacts.
- Maintains a running baseline (rolling average or median) of acceleration
  magnitude during normal driving.
- A "hit" is detected when acceleration exceeds baseline × configurable factor
  (e.g., 3×).
- Classifies hits by severity based on peak acceleration and duration.
- **This module must be well-tested** — unit tests with simulated data.
- The hit pattern includes: peak acceleration, duration, waveform signature
  (a few sample points around the peak).

### 4. Reporting Module
- Two modes:
  - **Real-time:** sends each hit immediately via HTTPS POST.
  - **Wi-Fi batch:** stores hits in a local SQLite database, sends them when
    Wi-Fi is available.
- Tracks data usage: bytes sent in last minute, last hour, last month.
- Serializes hit reports as protobuf (binary or text format).
- Handles retries with exponential backoff on network failures.

### 5. UI Module
- Acceleration graph (last 60 seconds): two traces (up-down, left-right).
- Data usage display (KB/min, MB/hour, MB/month).
- Reporting mode toggle (real-time vs Wi-Fi-only).
- Status indicators: car mount detected, GPS fix, connection status.
- Future: Google Maps view with potholes.

## Server Components

### 1. FastAPI REST Endpoint
- `POST /api/v1/hits` — receive hit reports (single or batch).
- `GET /api/v1/health` — health check.
- Accepts `Content-Type: application/x-protobuf` (binary) or
  `Content-Type: text/plain` (ASCII protobuf text format).
- Validates protobuf schema version.

### 2. Ingestion Queue
- In-process async queue (Python `asyncio.Queue`).
- Decouples HTTP response time from disk writes.
- Capacity: configurable (default 10,000 items). If full, applies backpressure
  (HTTP 503 with Retry-After header).
- An overflow file backs the queue if it exceeds memory limits.

### 3. Storage Writer
- Consumes from the ingestion queue.
- Writes to disk in an append-friendly format (see ADR-004).
- Organizes files by date and hour for easy browsing and cleanup.

### 4. Analysis Pipeline (future)
- Reads from disk storage.
- Clusters nearby hits into "pothole" entities.
- Computes severity scores based on hit count and intensity.
- Feeds the web dashboard.

### 5. Web Dashboard (future)
- Google Maps-based heatmap.
- City admin interface for commenting on potholes.
- User view of their own hits.

## Data Flow

1. Phone accelerometer → Sensor Module (50 Hz)
2. Sensor Module → Hit Detection Module (continuous filtering)
3. Hit detected → create HitReport protobuf message
4. HitReport → Reporting Module → HTTPS POST to server
5. Server receives → validates → enqueues
6. Queue consumer → writes to disk
7. (Future) Analysis reads disk → clusters potholes → updates dashboard
