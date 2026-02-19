# ADR-006: Open Questions — Resolved

**Status:** Resolved
**Date:** 2025-02-17
**Updated:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Resolved Questions

### Q1: Accelerometer Sampling Rate
**Answer:** 50 Hz is sufficient.

### Q2: Hit Waveform Detail
**Answer:** Start with 150 samples (~3 seconds at 50 Hz) for initial data
collection and analysis. Will likely reduce to ~15 samples once real data
reveals what's actually needed. The protobuf schema supports variable-length
waveform arrays, so this change requires no schema modification.

**Impact on message size:**
- 150 samples × 2 axes × ~2 bytes/sample (packed varint) = ~600 bytes waveform
- Total hit message with envelope: ~700 bytes binary (vs ~90 bytes with 15 samples)
- At 10 hits/minute real-time: ~420 KB/hour. Still very manageable.

### Q3: GPS Accuracy Requirements
**Answer:** 5m accuracy is fine for v1. See ADR-009 for a detailed analysis of
precision improvement options if needed later. Key alternatives:
1. **Snap-to-road** (cheapest, most practical): post-process GPS points onto
   known road geometry using OpenStreetMap.
2. **Crowdsourced averaging**: average GPS readings from multiple users at the
   same pothole to improve position estimate.
3. **IMU dead reckoning**: use accelerometer/gyroscope to interpolate between
   GPS fixes.

### Q4: Multiple Vehicles / Phones
**Answer:** No deduplication needed. The assumption is that only the driver's
phone in a car mount is reporting. A phone held in hand will produce a
different hit pattern and won't be in "car mount detected" mode. The car mount
detection module acts as a natural filter.

### Q5: Privacy & Data Retention
**Answer:** Two-part strategy:
- **On phone:** Delete hit data immediately after successful upload to server.
  No local accumulation beyond the Wi-Fi batch buffer.
- **On server:** Keep all data. Provide developer tools to:
  - Remove the oldest 90% of data (by date).
  - Remove the weakest 90% of hits (by severity/peak acceleration).
  - These are developer/admin operations, not automatic.

### Q6: City Admin Authentication
**Answer:** City name + employee number combination. Simple and practical.
Implementation details:
- City name from a predefined list (city registers with the platform).
- Employee number as a personal identifier.
- Password or PIN for authentication.
- No need for OAuth or external identity providers.

### Q7: Pothole Identity
**Answer:** Proximity + direction of travel. Specifically:
- Geographic proximity (e.g., hits within 10 meters).
- **AND** similar direction of travel.
- To handle direction changes (turns, roundabouts), capture TWO directions:
  1. **Direction before hit:** bearing from position 20m before the hit to the
     hit position.
  2. **Direction after hit:** bearing from the hit position to the position 20m
     after the hit.
- Two hits are at the "same pothole" if they are geographically close AND their
  direction vectors are compatible (e.g., within 30° of each other).
- This prevents clustering hits from opposite lanes or perpendicular streets.

**Impact on protobuf schema:** Added `bearing_before_deg` and
`bearing_after_deg` fields to `HitReport`.

### Q8: Internationalization
**Answer:** Multilingual from the start. French and English for first iteration.
Implementation: Android string resources (`strings.xml` in `values/` and
`values-fr/`). Server error messages should also be locale-aware.

### Q9: Google Maps API Billing
**Answer:** Yes, willing to set up billing. But concerned about cost explosion
at scale. See ADR-008 for a detailed cost analysis. **Key finding: Google Maps
becomes extremely expensive at scale ($2M+/month at 10M users). The strategy
is to start with Google Maps and switch to OpenStreetMap + Leaflet/MapLibre if
the user base grows beyond ~50,000 users.**

### Q10: Android App Name and Package
**Answer:** Confirmed.
- App name: **NidsDePoule**
- Package name: `fr.nidsdepoule.app`

## Decisions Deferred

| Topic                     | When to decide      | Depends on            |
|---------------------------|---------------------|-----------------------|
| Web dashboard framework   | Phase 3 start       | Complexity of UI needs |
| Google Sign-In details    | Phase 4 start       | Friend feature design  |
| ML-based hit classification | After real data collected | Data quality/quantity |
| Reduce waveform to 15 samples | After real data analysis | What patterns matter |
