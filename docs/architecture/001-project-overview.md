# ADR-001: Project Overview and Goals

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Context

NidsDePoule ("Potholes") is a crowdsourced pothole detection system. The goal
is to let citizens passively detect potholes while driving, aggregate the data
on a server, and make it useful to both drivers and city administrations.

## Goals

1. **Passive detection:** The Android app detects potholes via accelerometer
   while the phone sits in a car mount. No user interaction needed after setup.
2. **Low friction:** Anonymous usage with device IDs. No account required to
   start reporting.
3. **Dual reporting modes:** Real-time upload or Wi-Fi-only batch upload, at
   the user's choice.
4. **Compact data:** Minimize cellular data usage. Protocol Buffers with both
   ASCII (dev) and binary (production) modes.
5. **Simple server:** Python server on an old Mac (macOS 10.15.8), reachable
   via tunnel (ngrok/Cloudflare). Handles ~100 users, ~10 simultaneous peak.
6. **Progressive features:** Start simple, add social features, city admin
   portal, and heatmap visualization over time.

## Target Users

| User type        | Needs                                              |
|------------------|----------------------------------------------------|
| Driver (citizen) | Passive detection, see potholes on map, data usage control |
| Developer        | ASCII data mode, simulation tools, reset capability |
| City admin       | View pothole severity/impact, acknowledge, mark as repaired |
| Friends group    | See each other's pothole hits on a shared map      |

## Non-Goals (for v1)

- iOS app
- Machine learning-based pothole classification (may come later)
- Payment or monetization
- Multi-server deployment

## Consequences

- Starting anonymous means we need a device ID scheme that survives app
  reinstalls (use Android `ANDROID_ID` or generate a UUID stored in shared
  preferences with backup).
- Protocol Buffers add a compilation step but give us schema evolution,
  compact binary, and text representation for free.
- Running on an old Mac limits server resources but the expected load (10
  simultaneous users) is very manageable.
