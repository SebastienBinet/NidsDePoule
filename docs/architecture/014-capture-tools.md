# ADR-014: Sensor Capture Tools for Detection Algorithm Research

**Status:** Accepted
**Date:** 2026-04-08
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Context

The NidsDePoule app detects potholes at ~50 Hz using `TYPE_LINEAR_ACCELERATION`
(gravity removed by Android). To develop better detection algorithms, we need
raw, high-fidelity sensor data captured at maximum rate, with ground-truth
labels indicating where real potholes are.

## Decision

Build a **separate capture app** (`data_capture_tools/`) and **Jupyter analysis
tools** — fully independent from the NidsDePoule production app.

### Key technical choices

| Choice | Rationale |
|--------|-----------|
| `TYPE_ACCELEROMETER` (raw, gravity included) | Maximum fidelity; gravity removal is an algorithm decision, not a capture decision |
| `SENSOR_DELAY_FASTEST` (~500 Hz) | Captures full frequency spectrum; can always downsample in post-processing |
| Separate CSV per sensor type | Sensors run at different rates; avoids NaN pollution; easy `pd.read_csv()` |
| Magnetometer included | Nearly free; enables orientation reconstruction in post-analysis |
| Continuous audio recording (16 kHz mono WAV) | Captures voice labels ("nid!") and impact sounds for verification |
| Boot-time nanosecond timestamps | Monotonic, jitter-free; `meta.json` provides epoch offset for GPS alignment |

### Ground-truth labeling: Combo C (Buttons + Voice + Audio)

After evaluating ~20 labeling methods, we chose a combination optimized for
a solo driver doing research drives:

1. **Bluetooth clicker** — press when hitting a pothole. No sensor interference
   (separate device). Precise timestamp.
2. **Voice commands** — say "nid!", "gros nid!", "crack" for severity. Captured
   in the continuous audio stream.
3. **Continuous audio** — records both voice labels and impact sounds. Used for
   post-drive verification.

Events are stored in `events_{session}.csv` with timestamp, type, and source.

### Data transfer: Google Drive via Android Share

Sessions are shared as zip files via Android's share sheet to Google Drive.
Colab mounts Drive natively — zero extra infrastructure, zero cost.

## Session file format

Each session produces a directory:
```
session_20260408_143025_a1b2c[_RouteAbbrev]/
  accel_*.csv      # ~77 MB/hour, TYPE_ACCELEROMETER raw, 3-axis m/s²
  gyro_*.csv       # ~86 MB/hour, TYPE_GYROSCOPE, 3-axis rad/s
  mag_*.csv         # variable, TYPE_MAGNETIC_FIELD, 3-axis µT
  gps_*.csv        # ~0.3 MB/hour, lat/lon/alt/speed/bearing/accuracy
  events_*.csv     # BT button / screen button timestamps + type
  audio_*.wav      # ~115 MB/hour, mono 16-bit PCM 16 kHz
  meta_*.json      # device, sensors, route, labeling, comment, counts
```

CSV files start with `# vNNN` comment line (app version).

Session directory suffix: last 5 chars of `ANDROID_ID` (stable per device).
After annotation, `_RouteAbbrev` (e.g. `_Sher2SDen`) is appended.

## Post-capture annotation

When recording stops, the app shows a dialog with:
- Route: origin / destination (auto-suggested via reverse geocoding)
- Labeling method: Boutons / Boutons+Voix / Voix
- Reliability: Boutons très fiable / Voix très fiable / Fiable si combiné / Peu fiable
- Optional comment (free text: "en vélo", "téléphone dans la poche", etc.)

All saved to `meta_{session}.json`.

## Version history

| Version | Changes |
|---------|---------|
| v001 | Version tracking, build date, version in all outputs |
| v002 | ANR fix (IO dispatcher), route auto-suggest, abbreviation in filenames |
| v003 | CI artifact fix |
| v004 | Share to Drive, optional comment, Delete fix, device ID suffix |

## Analysis tools

Jupyter notebook (`01_explore_session.ipynb`) runs in Google Colab:
- Accelerometer 3-axis + gravity-removed + magnitude plots
- Gyroscope 3-axis plots
- Speed profile, spectrogram, GPS track map (folium)
- Audio waveform with playback
- Event markers (dashed lines) on all plots
- Drill-down zoom function
- Synthetic data generator for demo without real captures
