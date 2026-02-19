# ADR-009: GPS Precision — Current Limits and Future Alternatives

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Current Situation

Standard phone GPS accuracy: **3-10 meters** horizontal. This means:
- A pothole reported at position P is actually somewhere in a ~10m circle
  around P.
- Two users hitting the same pothole may report positions 5-15 meters apart.
- This is **sufficient for clustering** (10m radius catches the same pothole)
  but **not sufficient for lane-level precision**.

**Decision for v1:** Accept 5m accuracy. Use Fused Location Provider on
Android (combines GPS, Wi-Fi, cell, and sensors) for the best available fix.

## Alternatives for Better Precision (If Needed Later)

### 1. Snap-to-Road (Recommended First Improvement)

**How:** After receiving GPS coordinates, snap them to the nearest road segment
using road network data.

**Precision gain:** Eliminates off-road scatter. Puts all hits exactly on the
road centerline. Effective precision: ~2-3m along the road.

**Implementation options:**

| Option                    | Cost         | Latency   | Quality |
|---------------------------|-------------|-----------|---------|
| Google Roads API          | $10/1K req  | ~100ms    | Excellent |
| OSRM (self-hosted)       | $50/month VM | ~10ms    | Very good |
| Valhalla (self-hosted)    | $50/month VM | ~10ms    | Very good |

**Recommendation:** Self-hosted OSRM. Free, fast, and produces results
comparable to Google Roads. Can be run on the same server as the main
application at startup scale.

**How OSRM snap-to-road works:**
1. Download OpenStreetMap extract for your region (France: ~4 GB).
2. Pre-process with OSRM: generates a routing graph.
3. Query: `GET /nearest/v1/driving/{lon},{lat}` → returns snapped point on
   nearest road.
4. Can also snap a series of points (trace) to a road path.

### 2. Crowdsourced Averaging (Recommended Second Improvement)

**How:** When multiple users report hits near the same location, average their
GPS positions. With N independent readings, accuracy improves by √N.

**Precision gain:**
- 5 users at same pothole: accuracy improves from 5m to ~2.2m
- 20 users: ~1.1m
- 100 users: ~0.5m

**Implementation:** Part of the pothole clustering algorithm. When grouping
hits into a pothole entity, the pothole's position is the centroid of all hits
(optionally weighted by GPS accuracy).

**Cost:** Zero. This is purely algorithmic.

**Caveat:** Only works for potholes hit by multiple users. Isolated hits from a
single user stay at raw GPS accuracy.

### 3. IMU Dead Reckoning (Advanced)

**How:** Use the phone's accelerometer and gyroscope to track position changes
between GPS fixes. GPS updates typically arrive at 1 Hz; the IMU runs at 50+
Hz. Between GPS fixes, integrate accelerometer readings to estimate position
delta.

**Precision gain:** Smooths the trajectory. Doesn't improve absolute accuracy
but gives better relative positioning of hits along the route (e.g., "this hit
was 15m after the turn" rather than "somewhere in this 10m circle").

**Implementation:** Complex. Requires sensor fusion (Extended Kalman Filter or
similar). Android's Fused Location Provider already does some of this
internally.

**Recommendation:** Don't implement this ourselves. Rely on Android's Fused
Location Provider, which already integrates IMU data. If more precision is
needed, explore the GNSS raw measurements API (below).

### 4. GNSS Raw Measurements (Android 7+)

**How:** Android exposes raw GNSS satellite measurements (pseudorange, carrier
phase, Doppler). Post-processing these with correction data can achieve
sub-meter accuracy.

**Precision gain:** 0.5-2 meters with satellite-based augmentation (SBAS).
Potentially sub-meter with post-processing.

**Implementation:** Very complex. Requires GNSS signal processing expertise.
Google provides a sample app (GnssLogger) for raw measurement collection.

**Recommendation:** Not worth the complexity for pothole detection. The
combination of snap-to-road + crowdsourced averaging gives sufficient
precision.

### 5. Road Lane Detection (Theoretical)

**How:** Determine which lane the car is in by analyzing:
- Distance from road centerline.
- Direction of travel (distinguishes opposite lanes).
- Relative GPS offset patterns across multiple users.

**Precision gain:** Lane-level (each lane is ~3.5m wide).

**Implementation:** Would require road width data (available in some OSM data)
and statistical analysis of GPS offset patterns.

**Recommendation:** A "nice to have" for Phase 4+. Not needed for MVP. The
bearing_before/bearing_after approach already distinguishes travel direction,
which separates opposite lanes.

## Recommended Implementation Order

1. **v1 (now):** Raw GPS from Fused Location Provider. ~5m accuracy. Sufficient
   for detecting pothole areas.

2. **v2 (Phase 3):** Add snap-to-road via self-hosted OSRM. Server-side
   post-processing. ~2-3m along-road accuracy.

3. **v3 (Phase 3):** Add crowdsourced averaging in pothole clustering. For
   popular potholes, accuracy reaches ~1m.

4. **Future (if needed):** Explore GNSS raw measurements or lane detection for
   sub-meter precision.

## Impact on Pothole Clustering

The clustering algorithm (proximity + direction) works at each precision level:

| Precision | Cluster radius | Correctly separates          |
|-----------|---------------|------------------------------|
| 5m (v1)   | 10m           | Opposite lanes, perpendicular streets |
| 2-3m (v2) | 5m            | Adjacent potholes on same road |
| 1m (v3)   | 3m            | Individual potholes in cluster |
