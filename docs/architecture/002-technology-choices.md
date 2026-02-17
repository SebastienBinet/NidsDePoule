# ADR-002: Technology Choices

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Decisions

### Android App: Kotlin

**Chosen:** Kotlin with Jetpack Compose
**Alternatives considered:** Java
**Rationale:**
- Kotlin is Google's recommended language for Android since 2019.
- Coroutines make async sensor reading, GPS tracking, and network I/O clean.
- Jetpack Compose gives declarative UI for the acceleration graph and data
  usage displays.
- `minSdk = 31` (Android 12) lets us use modern sensor APIs and simplified
  permissions. Covers ~60% of devices — acceptable for an initial user base.

### Server: Python with FastAPI

**Chosen:** Python 3 + FastAPI
**Alternatives considered:** Go, Node.js
**Rationale:**
- FastAPI provides async request handling, automatic OpenAPI docs, and Pydantic
  validation — all useful for rapid iteration.
- Python 3.8 is available on macOS 10.15 (Catalina). We may need to install a
  newer version via Homebrew.
- Rich ecosystem for geospatial analysis (shapely, geopandas) and data
  processing (pandas, numpy) for later heatmap/analysis features.
- The expected load (~10 concurrent users) is trivially handled by a single
  FastAPI process with uvicorn.

### Wire Protocol: Protocol Buffers

**Chosen:** Protocol Buffers (protobuf v3)
**Alternatives considered:** JSON + MessagePack, custom binary
**Rationale:**
- Protobuf natively supports both text format (for development/debugging) and
  compact binary format (for production). This directly maps to the
  ASCII/binary dual-mode requirement.
- Schema evolution is built in: fields can be added/deprecated without breaking
  old clients. The `version` field is just a regular int32.
- Very compact encoding. A typical pothole hit report will be ~50-80 bytes in
  binary mode vs ~300-500 bytes in JSON.
- Code generation for both Kotlin (Android) and Python (server).
- Well-documented, battle-tested, widely used.

### Authentication: Anonymous + Device ID (v1)

**Chosen:** Anonymous with device-generated UUID
**Plan for later:** Google Sign-In when friend/social features are added
**Rationale:**
- Minimizes friction for initial adoption.
- A UUID v4 stored in app SharedPreferences serves as the device identity.
- No personal data collected in v1 — privacy-friendly.
- When Google Sign-In is added later, the device UUID can be linked to a Google
  account, preserving historical data.

### Networking: Ngrok / Cloudflare Tunnel

**Chosen:** Tunnel service to expose local Mac server
**Alternatives considered:** Port forwarding, local Wi-Fi only
**Rationale:**
- No router configuration needed (important: home routers vary widely).
- HTTPS out of the box (ngrok provides TLS termination).
- Stable public URL (with ngrok paid plan) or regenerated URL (free plan).
- Cloudflare Tunnel is free and provides a stable hostname with a Cloudflare
  account.
- The Mac's local IP doesn't need to be static.

### Web Dashboard: Deferred

**Decision deferred.** Focus on Android app and server first. Options include
simple HTML+JS with Google Maps API, or a React/Vue SPA for more complex
dashboards.

## Transport Protocol: HTTPS + REST

**Chosen:** HTTPS REST API with protobuf bodies
**Rationale:**
- REST is simple and well-supported on both Kotlin (OkHttp/Retrofit) and
  Python (FastAPI).
- Protobuf can be sent as `application/x-protobuf` (binary) or
  `text/plain` (ASCII text format).
- The `Content-Type` header signals which mode is in use.
- No need for WebSockets or gRPC for v1: pothole reports are fire-and-forget
  POST requests. The app doesn't need server push.
- Later, if real-time map updates are wanted, WebSocket or Server-Sent Events
  can be added alongside REST.
