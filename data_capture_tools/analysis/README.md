# Sensor Capture Analysis Tools

Python tools for analyzing raw sensor data captured by the Android Sensor Capture app.

## Planned Tools

- **loader.py** — Load session CSVs into pandas DataFrames
- **alignment.py** — Time-align accel/gyro/mag/GPS into a single DataFrame
- **filters.py** — Bandpass, lowpass, highpass wrappers (scipy.signal)
- **visualization.py** — Reusable plotting functions
- **features.py** — Feature extraction (RMS, peak-to-peak, zero-crossing, etc.)

## Planned Notebooks

- `01_explore_session.ipynb` — Load and visualize a session
- `02_frequency_analysis.ipynb` — Spectrograms, FFT, frequency signatures
- `03_detection_algorithms.ipynb` — Test detection approaches

## CSV Formats

Each recording session produces a directory with 4 CSV files + 1 JSON metadata file.

### accel_{session}.csv
```
timestamp_ns,x_ms2,y_ms2,z_ms2
```
- `timestamp_ns`: boot-time nanoseconds
- Values in m/s² (raw TYPE_ACCELEROMETER, gravity included)

### gyro_{session}.csv
```
timestamp_ns,x_rads,y_rads,z_rads
```
- Values in rad/s

### mag_{session}.csv
```
timestamp_ns,x_ut,y_ut,z_ut
```
- Values in microtesla

### gps_{session}.csv
```
timestamp_ms,lat_deg,lon_deg,altitude_m,speed_mps,bearing_deg,accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg
```
- Wall-clock milliseconds, full double precision lat/lon

### events_{session}.csv
```
timestamp_ms,event_type,source
```
- Ground-truth labels from BT remote button presses or on-screen taps
- `event_type`: "pothole", "crack", "rough", "other"
- `source`: "bt_button", "screen_button"

### audio_{session}.wav
- Continuous microphone recording (mono, 16-bit PCM, 16 kHz)
- ~1.9 MB/minute (~115 MB/hour)
- Captures voice labels ("nid!", "gros nid!") and road impact sounds

### meta_{session}.json
Contains device model, sensor hardware info, `boot_to_epoch_offset_ms` for
converting sensor timestamps to wall-clock time, sample counts, and file sizes.
