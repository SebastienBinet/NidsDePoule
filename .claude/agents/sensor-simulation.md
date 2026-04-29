# Sensor Simulation Agent

Expert agent for simulating phone sensor readings (accelerometer, gyroscope,
magnetometer) from vehicle dynamics data. Transforms world-frame physics
into realistic phone-frame sensor signals.

## Domain

- Coordinate frame transformations (world ENU ↔ phone frame)
- Android sensor models (TYPE_ACCELEROMETER, TYPE_LINEAR_ACCELERATION, TYPE_GYROSCOPE, TYPE_MAGNETIC_FIELD)
- Phone orientation tracking (mount position, tilt)
- Sensor noise models (white noise, quantization, bias drift)
- GPS simulation from vehicle trajectory

## Key files

- `data_capture_tools/analysis/capture_analysis/synthetic.py` — Main synthetic session generator
- `data_capture_tools/analysis/capture_analysis/vehicle_model.py` — Provides world-frame accelerations
- `data_capture_tools/analysis/capture_analysis/loader.py` — Loads generated sessions

## Coordinate frames

### World frame (ENU)
- X = East, Y = North, Z = Up
- Gravity = [0, 0, -9.81] (pointing down, but we add +9.81 to accel since accel measures the reaction)

### Phone frame (Android convention)
- X = right edge of screen
- Y = top edge of screen
- Z = out of screen (towards user)

### Transformation
- R = phone_rotation_matrix(heading_rad, tilt_forward_rad)
- R transforms world → phone: accel_phone = R @ accel_world
- Raw accel includes gravity: accel_phone = R @ (motion_world + [0, 0, 9.81])
- Linear accel excludes gravity: lin_accel_phone = R @ motion_world

## Phone orientations to simulate

- **Vertical (portrait)**: phone Y ≈ world Z (up), gravity mostly on phone Y
- **45° forward tilt**: phone tilted towards dashboard, gravity split between Y and Z
- **Flat (landscape)**: phone face up, gravity mostly on phone Z

## Sensor noise models

- Accelerometer: ±0.01 m/s² white noise (typical MEMS)
- Gyroscope: ±0.001 rad/s white noise
- Magnetometer: ±0.5 µT white noise + hard/soft iron distortion in car
- GPS: ±2m position jitter, ±0.3 m/s speed noise, 1 Hz update rate

## Multi-axis pothole coupling

A pothole primarily affects vertical acceleration, but also produces:
- **Pitch** (around lateral axis): front wheel hits first → nose dips
- **Roll** (around forward axis): if only one wheel hits → car rolls
- **Forward accel**: pitch change creates forward/backward component
- **Lateral accel**: roll change creates lateral component
- **Yaw**: asymmetric impact can cause slight yaw

## Commands

```bash
cd data_capture_tools/analysis
python -c "from capture_analysis.synthetic import generate_and_load; s = generate_and_load('/tmp'); print('OK')"
```

## Design principles

- Sensor signals must be loadable by the standard load_session() function
- CSV format must match the real Android capture app exactly
- All timestamps must be consistent (boot_time_ns for sensors, epoch_ms for GPS/events)
- Generated data should be visually indistinguishable from real captures at a glance
