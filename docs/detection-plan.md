# Automatic Pothole Detection Strategy — NidsDePoule

## Context

The app currently relies on **manual button presses** (AYOYE = hit, "iiiiiiiii" = almost) to report potholes. The accelerometer captures a 30-second magnitude-only buffer and finds a single peak. There is no automatic detection, no per-axis data, no frequency analysis, no adaptive thresholds. This plan adds all of these while preserving manual reporting.

---

## Answers to User Questions

### What is "z_score"?
`z_score = (sample - mean) / standard_deviation`. Measures how many standard deviations above average a sample is. z=3.5 means 3.5 std devs above normal — self-adapting to noise level. On rough road (high stddev), a bigger spike is needed to trigger; on smooth road, a modest spike stands out.

### Is "peak_to_baseline_ratio" equivalent to "median"?
Yes, nearly. Current code: `baseline = median(30s readings)`, `ratio = peak / baseline * 100`. Your idea of "cross y*median for x samples" is a refinement: requiring sustained elevation filters phone taps (5ms) from potholes (50-200ms).

### "speed_mps" units?
Already **meters per second** (from Android `Location.getSpeed()`). Not miles. All metric already.

### GPS accuracy already sent?
**Confirmed.** `accuracyM` exists in `LocationReading`, `HitReportData`, and `LocationData` on server. Already transmitted. Server clustering just doesn't use it yet.

---

## Bugs Found During Investigation

### BUG 1: `duration_ms` reports 30s buffer span, not waveform duration
**File:** `MainViewModel.kt:404`
`duration_ms` = `readings.last().timestamp - readings.first().timestamp` (the full 30s buffer). Should be `waveform.last().timestamp - waveform.first().timestamp` (the 150-sample window, ~3s). This explains Firestore showing 150 entries with duration_ms 25239.

### BUG 2: Multiple AYOYE presses send identical data
**File:** `MainViewModel.kt:351-371`, `AccelerationBuffer.kt:66-96`
`isPeakSent` flag is visual-only. Neither `findAndMarkPeak()` nor `AccelRecorder` exclude previously-sent peaks. Two presses within 30s → exact same peak/waveform sent twice.

### BUG 3: No direction filtering on pothole display
**Files:** `RouteMapWidget.kt`, `HitReporter.kt`, `MainViewModel.kt`, `clustering.py`
App shows ALL potholes regardless of travel direction. Bearing data IS captured and stored for both AYOYE and iiiiiiiii, but: server clustering ignores bearing, GeoJSON includes no bearing, app displays all markers without direction check.

---

## A. Status Widget at Top of App

### Current StatusBar
`MainScreen.kt:326-395` — Row of chips: GPS, CONNECTED, MIC, DEV, SIM

### New Status Chips to Add

**File: `MainScreen.kt`** — Extend `StatusBar` parameters and add three new chip groups:

```kotlin
@Composable
private fun StatusBar(
    // existing
    hasGpsFix: Boolean, isConnected: Boolean, devModeEnabled: Boolean,
    isSimulating: Boolean, isListening: Boolean,
    // NEW
    mountType: MountType,      // CAR_MOUNT, HANDHELD, UNKNOWN
    movingType: MovingType,    // DRIVING, JUST_PARKED, UNKNOWN
    dataUsageMode: DataUsageMode,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // existing chips...

        // Mount type chip
        StatusChip(label = when(mountType) {
            MountType.CAR_MOUNT -> "MOUNT"
            MountType.HANDHELD -> "HAND"
            MountType.UNKNOWN  -> "HOLD?"
        }, active = mountType == MountType.CAR_MOUNT,
           color = when(mountType) {
            MountType.CAR_MOUNT -> Color(0xFF4CAF50)  // green
            MountType.HANDHELD -> Color(0xFFFF9800)    // orange
            MountType.UNKNOWN  -> Color.Gray
        })

        // Moving type chip
        StatusChip(label = when(movingType) {
            MovingType.DRIVING     -> "DRIVE"
            MovingType.JUST_PARKED -> "PARK"
            MovingType.NOT_IN_CAR  -> "WALK"
            MovingType.UNKNOWN     -> "MOVE?"
        }, active = movingType == MovingType.DRIVING)

        // Data usage mode chip (tappable to change)
        StatusChip(label = when(dataUsageMode) {
            DataUsageMode.WIFI_ONLY  -> "WiFi"
            DataUsageMode.MB_10      -> "10M"
            DataUsageMode.MB_100     -> "100M"
            DataUsageMode.GB_1       -> "1G"
            DataUsageMode.UNLIMITED  -> "UNL"
        }, active = true)
    }
}
```

**New enums in `detection/AutoDetector.kt`:**
```kotlin
enum class MountType { CAR_MOUNT, HANDHELD, UNKNOWN }
enum class MovingType { DRIVING, JUST_PARKED, NOT_IN_CAR, UNKNOWN }
enum class DataUsageMode { WIFI_ONLY, MB_10, MB_100, GB_1, UNLIMITED }
```

**Mount type detection** (from accel baseline stability — already in AutoDetector):
- CAR_MOUNT: stddev < 80mg over 10s
- HANDHELD: stddev > 150mg
- UNKNOWN: in between or insufficient data

**Moving type detection**:
- DRIVING: speed > 2 m/s for >5s
- JUST_PARKED: speed dropped from >5 m/s to <1 m/s within last 60s
- NOT_IN_CAR: speed < 0.5 m/s for >120s AND stddev pattern changes (no vehicle vibration)
- UNKNOWN: insufficient data

**Data usage mode**: stored in SharedPreferences, user selects from settings. Affects:
- WIFI_ONLY: batch all hits, upload only on WiFi
- MB_10..UNLIMITED: progressively allow more cellular data (waveform detail, polling frequency)

---

## B. Data Capture: Per-Axis Storage + Position Pairing

### Changes

**File: `ThresholdHitDetector.kt`** — Extend `AccelReading`:
```kotlin
data class AccelReading(val timestamp: Long, val magnitudeMg: Int,
    val xMg: Int = 0, val yMg: Int = 0, val zMg: Int = 0)
```

**File: `MainViewModel.kt:196-209`** — Pass per-axis to `AccelRecorder`:
```kotlin
accelRecorder.addReading(timestamp, magnitudeMg, x, y, z)
```

**File: `MainViewModel.kt:374-418`** — In `buildHitEvent()`, populate:
- `waveformVertical` = z-axis samples (approx vertical when phone in holder)
- `waveformLateral` = x-axis samples
- Keep magnitude waveform as primary (orientation-independent)
- **FIX `duration_ms`**: compute from waveform window, not full 30s buffer

**GPS pairing** — No new structure needed. Existing `interpolateLocation(timestampMs)` already works for any accel timestamp.

---

## C. Multi-Peak Detection + Duplicate Prevention (BUG FIX)

### New file: `detection/PeakFinder.kt`

```
fun findPeaks(readings, minProminence, minSeparationMs, excludeTimestamps): List<PeakResult>
  1. Compute running median over 1s window as local baseline
  2. Find local maxima (higher than both neighbors)
  3. Filter: prominence >= minProminence above local baseline
  4. Exclude peaks within 100ms of any timestamp in excludeTimestamps
  5. Merge: if two peaks < minSeparationMs apart, keep the taller one
  6. Return list sorted by timestamp
```

### Fix for multiple AYOYE presses (Request 1)

**File: `MainViewModel.kt`** — Add a set of already-reported peak timestamps:
```kotlin
private val reportedPeakTimestamps = mutableSetOf<Long>()
```

In `onReportHit()`:
1. Use `PeakFinder.findPeaks(excludeTimestamps = reportedPeakTimestamps)`
2. Take the first (largest remaining) peak
3. Add its timestamp to `reportedPeakTimestamps`
4. Clear timestamps older than 30s

This guarantees each AYOYE press finds a **different** local maximum.

---

## D. Accumulated-G Detection Criteria (NEW)

### Concept
In addition to z-score peak detection, add: "within time window T, the total accumulated G exceeds threshold A."

```
accumulatedG(windowMs) = sum(abs(magnitudeMg[i])) for all samples in last windowMs
```

This catches **bad road segments** where no single spike exceeds threshold but the road is clearly terrible.

### Implementation in `AutoDetector`

```kotlin
// Accumulated G over sliding windows
private val ACCUMULATION_WINDOWS = longArrayOf(100, 500, 1000, 2000) // ms

fun computeAccumulatedG(readings: List<AccelReading>, windowMs: Long): Int {
    val cutoff = readings.last().timestamp - windowMs
    return readings.filter { it.timestamp >= cutoff }.sumOf { it.magnitudeMg }
}

// Baseline: minimum accumulated-G over last 10 minutes (represents "calm road")
private var minAccumG_10min: Map<Long, Int> = mapOf()  // windowMs -> minValue

// Detection: if current accumulatedG > baselineAccumG * RATIO_THRESHOLD
// then flag as "bad road segment"
```

**Use cases**:
- Single sharp pothole: caught by z-score (existing)
- Terrible road surface: caught by accumulated-G (new)
- Sustained vibration: accumulated-G rises even though no single spike is large
- Still car (speed ~0): accumulated-G baseline ≈ 0, so even small values during parking trigger — filtered by speed check

### Thresholds (initial, tunable)

| Window | Ratio to trigger |
|---|---|
| 100ms | > 4x baseline |
| 500ms | > 3x baseline |
| 1000ms | > 2.5x baseline |
| 2000ms | > 2x baseline |

---

## E. Frequency Domain Analysis

### Feasibility
- Android HW does **NOT** output frequency data — all FFT must be in software
- **Existing FFT**: `MfccExtractor.kt:165` has Cooley-Tukey radix-2 FFT (pure Kotlin)
- CPU cost: 256-point FFT = ~0.2ms per call = negligible
- At 50 Hz, 256 samples = 5.12s window, resolution = 0.195 Hz/bin

### New file: `detection/FrequencyAnalyzer.kt`
- Band energies: infra (0-1 Hz), low (1-5 Hz), mid (5-15 Hz), high (15-25 Hz)
- Key discriminator: potholes → dominant mid-band; speed bumps → dominant low-band

---

## F. Adaptive Threshold Strategy

### Core Algorithm (AutoDetector.kt)

- **Sliding 60-second statistics window** (median, stddev), reset on mount type change
- **Z-score threshold**: MOUNTED z>=3.5, HANDHELD z>=5.0, UNKNOWN z>=4.0
- **Duration filter**: reject events <20ms (phone tap) or >500ms (braking)
- **Frequency check**: reject if dominantHz < 2Hz AND lowEnergy > midEnergy
- **Accumulated-G check**: flag bad road segments (see section D)
- **Debounce**: 2-second cooldown between auto-detections
- **Speed gate**: skip if speed < 2 m/s (5 km/h)
- **Parking suppression**: suppress for 30s when parking maneuver detected
- **Not-in-car detection**: suppress when NOT_IN_CAR state detected

---

## G. Test Vector Generation

### User preference: Real-World Recorded Traces (PRIMARY)

**Add recording mode to the app:**
1. New `RecordingAccelerometerSource` wrapping `AndroidAccelerometer`
2. Writes CSV: `timestamp_ms,x_mg,y_mg,z_mg,magnitude_mg,lat_microdeg,lon_microdeg,speed_mps,accuracy_m`
3. Toggle via debug menu
4. Drive over known potholes at multiple speeds
5. Manually annotate: pothole timestamps, type, depth estimate

**Replay in tests:**
1. New `CsvReplayAccelerometerSource` implementing `AccelerometerSource`
2. Replays at original timing (integration) or fast (unit tests)
3. Store annotated CSVs in `tools/test_vectors/`

**Secondary**: Synthetic `TestSignalGenerator` for edge cases not easily captured in real driving (two potholes 100ms apart, phone drop during pothole, etc.)

---

## H. Debug Mode: Baseline G-Records (Request 2)

### Concept
When debug mode is enabled AND a hit is reported, start a 5-minute timer. During those 5 minutes, track the 30-second window with the **minimum accumulated G**. Send that "quiet" window to the server as a calibration baseline.

### Implementation

**File: `MainViewModel.kt`** — After sending a hit report in debug mode:
```kotlin
if (debugFlags.isDevMode) {
    startBaselineCapture(durationMinutes = 5)
}
```

**New method `startBaselineCapture()`:**
- Every 30 seconds for the next 5 minutes, compute accumulated-G for the current buffer
- Track the 30-second window with the minimum accumulated-G
- When the 5-minute timer expires, send that quiet window as a special report:
  ```json
  {
    "source": "baseline",
    "hit": {
      "waveform_vertical": [150 samples from quietest window],
      "baseline_mg": <median of quiet window>,
      "duration_ms": <actual waveform duration>,
      "severity": 0
    }
  }
  ```

**Server**: Store with `source="baseline"`, exclude from clustering. Available for offline threshold calibration.

---

## I. Direction Filtering for Pothole Display (BUG FIX)

### Problem
Potholes from opposite lane shown to user. Bearing data captured but not used.

### Fix — Server Side

**File: `clustering.py`** — Include aggregate bearing in GeoJSON output:
```python
# In PotholeCluster, track bearings
self.bearings: list[float] = []  # all report bearings

# In to_geojson_feature(), add:
"bearing_avg": mean(self.bearings) if self.bearings else None,
"bearing_spread": std(self.bearings) if len(self.bearings) > 1 else 360,
```

### Fix — Client Side

**File: `MainViewModel.kt:467-506`** — In `checkPotholeProximity()`, add bearing filter:
```kotlin
// Only warn about potholes in our direction of travel (±90 degrees)
val bearingDiff = abs(normalizeAngle(currentBearing - marker.bearingAvg))
if (bearingDiff > 90) continue  // opposite direction, skip alert
```

**For iiiiiiiii reports**: These are visual sightings — the user may see a pothole in the opposite lane. The bearing of the reporter matters: if the reporter was going the same direction as the current user, it's likely same-lane. If opposite direction, the pothole could be in either lane. **Show with a "?" marker** for ambiguous-direction potholes.

**For AYOYE reports**: The user physically hit it. It's definitely in their lane. Use bearing to filter confidently.

---

## J. Server-Side Changes

### J1. Device Reputation (persistent ID, not daily rotation)
Keep persistent device_id (default). Privacy rotation opt-in in settings.
Reputation: `(confirmed + 5) / (total + 10)`, starts at 0.5. Weight centroid contribution.

### J2. Real-Time Propagation to Other Smartphones
- Polling: 10s when driving, 60s when stopped
- New endpoint: `GET /api/v1/potholes/since?timestamp_ms=...` (incremental)
- Journalist two-phone test: ~11s total latency

### J3. GPS Accuracy in Clustering
`effective_radius = radius_m + accuracy_m * 0.5`. Weight centroid by `1/max(accuracy_m, 3)`.

### J4. Speed Bump Classification (NOT suppression)
Classify as `"infrastructure_or_severe"`, don't suppress. Use g-profile signature (down-first vs up-first) when waveform data available. Different icon in app/dashboard. Only count mounted devices at speed > 20 km/h toward classification.

---

## K. Edge Cases

### K1. Snow/Parking + Not-in-Car Detection
- JUST_PARKED: speed drop from >5 to <1 m/s + lateral steering pattern → suppress 30s
- NOT_IN_CAR: speed < 0.5 m/s for >120s + no vehicle vibration signature → suppress entirely
- Shown in status widget as PARK or WALK chip

### K2. Suspicious-But-Below-Threshold Upload (Phase 2)
Add `ReportSource.SUSPICIOUS("suspicious")`. Opt-in "research mode", reduced waveform.

### K3. Information Compaction (acknowledged, future effort)

---

## Implementation Phases

### Phase 1 Sprint 1 (Foundation + Bug Fixes)
1. **FIX**: `duration_ms` to use waveform timestamps, not 30s buffer
2. **FIX**: Multiple AYOYE → find different peaks (PeakFinder + reportedPeakTimestamps)
3. Per-axis storage in `AccelReading` (x, y, z)
4. Populate `waveformLateral` in `buildHitEvent()`
5. `PeakFinder` with multi-peak detection
6. Real-world recording mode (CSV export in debug menu)

### Phase 1 Sprint 2 (Auto-Detection + Status Widget)
7. `AutoDetector` with z-score, mount detection, parking suppression
8. `FrequencyAnalyzer` using existing FFT
9. Accumulated-G criteria (section D)
10. Status widget: mount type, moving type, data usage mode
11. `ReportSource.AUTO` in protocol
12. Integration into MainViewModel

### Phase 1 Sprint 3 (Server + Direction)
13. GPS accuracy weighting in clustering
14. Bearing in GeoJSON + client-side direction filtering (BUG FIX)
15. Incremental potholes endpoint (`/since`)
16. Dynamic polling interval (10s driving / 60s idle)
17. Cluster classification (not suppression)

### Phase 1 Sprint 4 (Calibration + Reputation)
18. Debug mode baseline g-records (Request 2)
19. Device reputation model
20. Device ID persistence setting
21. CsvReplayAccelerometerSource for test replay

### Phase 2 (Future)
- SSE/WebSocket push propagation
- Vehicle type learning
- Road roughness profiling
- Suspicious event upload / research mode
- Orientation-aware axis decomposition
- Information compaction
- Lane-level pothole discrimination (when GPS accuracy < 5m)

---

## Critical Files

| File | Changes |
|------|---------|
| `ui/MainScreen.kt:326-395` | Status widget: mount, moving, data usage chips |
| `detection/ThresholdHitDetector.kt` | Extend AccelReading with x/y/z |
| `detection/HitDetectionStrategy.kt` | Add AUTO to ReportSource, frequency fields to HitEvent |
| `detection/PeakFinder.kt` | **NEW** — multi-peak detection with exclusion |
| `detection/FrequencyAnalyzer.kt` | **NEW** — FFT band analysis |
| `detection/AutoDetector.kt` | **NEW** — adaptive thresholds, mount/moving detection, accumulated-G |
| `MainViewModel.kt:195-209` | Pass per-axis, wire AutoDetector, fix duration_ms |
| `MainViewModel.kt:351-418` | Multi-peak in onReportHit, duplicate prevention |
| `MainViewModel.kt:467-506` | Direction filtering in checkPotholeProximity |
| `reporting/HitReporter.kt` | Dynamic polling interval, CSV recording mode |
| `reporting/HitReportData.kt` | Add mount_type, frequency_profile, source=baseline |
| `server/core/models.py` | Frequency fields, DeviceReputation, baseline source |
| `server/core/clustering.py` | GPS accuracy weighting, classification, bearing in GeoJSON |
| `server/core/reputation.py` | **NEW** — device reputation scoring |
| `server/api/potholes.py` | Add `/since` endpoint, bearing in response |
