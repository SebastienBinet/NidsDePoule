# Plan: Simplify to Almost/Hit with voice recognition

## Naming convention
- **Almost** = "iiiiiiiii !!!" — "Il y en a un proche !" — geo info only
- **Hit** = "AYOYE !?!#$!" — "Je viens d'en pogner un !" — geo + 5s accel data

## Changes by file

### 1. `detection/HitDetectionStrategy.kt` — Simplify ReportSource
- Replace enum values: keep only `ALMOST("almost")` and `HIT("hit")`
- Keep `HitEvent` data class as-is (still needed for Hit waveform capture)
- Keep `HitDetectionStrategy` interface (still used for accel buffer)

### 2. `detection/ThresholdHitDetector.kt` — Strip to accel buffer only
- Remove mutable `thresholdFactor`, `minMagnitudeMg`, `currentBaselineMg`
- Remove `companion object` with defaults
- `processReading()` → only buffers readings, always returns null (no auto-detection)
- Keep `recentReadings()` for Hit waveform capture
- Keep `reset()`

### 3. `detection/CarMountDetector.kt` — Delete file
### 4. `detection/CarMountDetectorTest.kt` — Delete file

### 5. `MainViewModel.kt` — Core logic simplification
- Remove: `carMountDetector`, `isMounted` state
- Remove: `thresholdFactor`, `minMagnitudeMg`, `currentBaselineMg` state + update methods
- Remove: auto-detection in accel callback (`if (event != null)` block), `onHitDetected()`
- Remove: `onReportVisualSmall()`, `onReportImpactSmall()`
- Rename: `onReportVisualBig()` → `onReportAlmost()` — source=ALMOST, severity=2, no accel
- Rename: `onReportImpactBig()` → `onReportHit()` — source=HIT, severity=3, 5s accel
- Remove: `updateServerUrl()` (server URL becomes read-only)
- Remove: `updateThresholdFactor()`, `updateMinMagnitudeMg()`
- Add: `VoiceCommandListener` — start/stop with lifecycle

### 6. `ui/MainScreen.kt` — UI overhaul
- Remove parameters: `isMounted`, `onVisualSmall`, `onImpactSmall`, `thresholdFactor`, `onThresholdFactorChanged`, `minMagnitudeMg`, `onMinMagnitudeChanged`, `currentBaselineMg`, `onServerUrlChanged`
- Remove "Support auto" status chip from StatusBar
- Remove `SensitivitySliders` composable entirely
- `ReportButtonsPanel`: 2 buttons side by side (remove small variants)
  - Left: "iiiiiiiii !!!" (amber) — Almost
  - Right: "AYOYE !?!#$!" (red) — Hit
- Server URL (dev mode): replace `OutlinedTextField` with read-only `Text`, click copies to clipboard

### 7. `ui/AccelerationGraph.kt` — Reduce height
- `.height(120.dp)` → `.height(60.dp)`

### 8. New: `sensor/VoiceCommandListener.kt` — Voice recognition
- Use Android `SpeechRecognizer` in continuous listening mode
- Match keywords: "iiiii"/"attention"/"il y en a" → Almost, "ayoye"/"ouch"/"aille" → Hit
- Callbacks: `onAlmostDetected()`, `onHitDetected()`
- Requires `RECORD_AUDIO` permission

### 9. `ui/VoiceFeedback.kt` — Update phrases
- Almost → "Attention !", Hit → "AYOYE !"

### 10. `MainActivity.kt` — Rewire
- Remove slider/mount/small-button/serverUrlChanged parameters
- Add RECORD_AUDIO permission request
- Wire Almost/Hit callbacks

### 11. String resources (both EN and FR)
- Remove: btn_visual_small, btn_visual_small_hint, btn_impact_small, btn_impact_small_hint, status_car_mount
- Rename: btn_visual_big → btn_almost, btn_impact_big → btn_hit
- Update hints: EN "There's one near me!" / "Just hit one!", FR "Il y en a un proche !" / "Je viens d'en pogner un !"

### 12. Tests
- `ThresholdHitDetectorTest.kt` — simplify (remove auto-detection tests, keep buffer tests)
- `CarMountDetectorTest.kt` — delete

### 13. Server — Update source validation
- Accept "almost" and "hit" as valid source values (check `server/` code)

## Execution order
1. Delete CarMountDetector + test
2. Strip ThresholdHitDetector to buffer-only
3. Simplify ReportSource enum (ALMOST, HIT)
4. Update MainViewModel (remove auto-detect, rename to Almost/Hit, remove sliders)
5. Update MainScreen (2 buttons, remove sliders, remove mount chip, read-only URL)
6. Reduce graph height
7. Add VoiceCommandListener
8. Update VoiceFeedback phrases
9. Update string resources
10. Update MainActivity wiring
11. Update/delete tests
12. Update server-side validation
