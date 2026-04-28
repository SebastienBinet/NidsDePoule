"""Synthetic sensor session generator for pothole detection algorithm development.

Generates realistic multi-sensor data (accel, lin_accel, gyro, mag, GPS) in the
same CSV format as the real Android capture app, so load_session() can load it.

Usage:
    from capture_analysis.synthetic import generate_synthetic_session, generate_and_load
    session_dir = generate_synthetic_session("/tmp")
    # or
    session = generate_and_load("/tmp")  # returns dict like load_session()
"""

import json
import os
from pathlib import Path

import numpy as np
from scipy.signal import butter, filtfilt

# =============================================================================
# Constants
# =============================================================================

ACCEL_HZ = 500
GYRO_HZ = 500
MAG_HZ = 50
GPS_HZ = 1
GRAVITY = 9.81

# Montreal magnetic field (IGRF model, approximate)
# Inclination ~72° (steeply down into earth), declination ~-14° (west of true north)
MAG_FIELD_UT = np.array([19.0, -4.5, 52.0])  # North, East, Down in µT (NED)

# Convert to ENU (East, North, Up) which is our world frame
MAG_FIELD_ENU = np.array([-4.5, 19.0, -52.0])  # East, North, Up

START_LAT = 45.5088
START_LON = -73.5878
BOOT_TIME_NS = 1_000_000_000_000  # 1000s after boot
EPOCH_OFFSET_MS = 1_712_000_000_000


# =============================================================================
# Rotation matrices
# =============================================================================

def _rot_x(angle_rad):
    c, s = np.cos(angle_rad), np.sin(angle_rad)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def _rot_y(angle_rad):
    c, s = np.cos(angle_rad), np.sin(angle_rad)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def _rot_z(angle_rad):
    c, s = np.cos(angle_rad), np.sin(angle_rad)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def phone_rotation_matrix(heading_rad, phone_tilt_forward_rad):
    """Compute world-to-phone rotation matrix.

    Phone convention: X=right, Y=up (screen top), Z=out of screen.
    World convention: X=East, Y=North, Z=Up.

    When phone is vertical (portrait) facing the heading direction:
    - Phone Y aligns with world Z (up)
    - Phone Z aligns with negative heading direction (screen faces user)
    - Phone X aligns with right of heading

    phone_tilt_forward_rad: additional forward tilt (positive = screen tilts toward sky)
    """
    # Start with phone vertical, screen facing opposite to heading
    # 1. Rotate world so heading aligns with -Z_phone direction
    # 2. Swap axes: world Z -> phone Y, world heading -> -phone Z
    R_heading = _rot_z(-heading_rad)  # align north with heading
    # In the heading-aligned frame: X=right, Y=forward, Z=up
    # Phone vertical: phone_X=right, phone_Y=up, phone_Z=backward(-forward)
    # Axis swap: world_right -> phone_X, world_up -> phone_Y, world_backward -> phone_Z
    R_axes = np.array([
        [1, 0, 0],   # phone X = world right (after heading rotation)
        [0, 0, 1],   # phone Y = world up
        [0, -1, 0],  # phone Z = world backward
    ])
    # Forward tilt: rotate around phone X axis
    R_tilt = _rot_x(phone_tilt_forward_rad)
    return R_tilt @ R_axes @ R_heading


# =============================================================================
# Noise generators
# =============================================================================

def _white_noise(n, rms, rng):
    return rng.normal(0, rms, n)


def _blue_noise(n, rms, fs, rng):
    """High-frequency emphasized noise (fresh asphalt texture)."""
    raw = rng.normal(0, 1, n)
    # High-pass at 10 Hz
    b, a = butter(2, 10 / (fs / 2), "high")
    filtered = filtfilt(b, a, raw)
    return filtered * (rms / (np.std(filtered) + 1e-12))


def _brownian_noise(n, rms, fs, rng):
    """Low-frequency emphasized noise (degraded road surface)."""
    raw = rng.normal(0, 1, n)
    # Low-pass at 15 Hz
    b, a = butter(2, 15 / (fs / 2), "low")
    filtered = filtfilt(b, a, raw)
    return filtered * (rms / (np.std(filtered) + 1e-12))


def generate_road_noise(n, noise_type, rms_vertical, fs, rng):
    """Generate 3-axis road noise in world frame (East, North, Up).

    Vertical (Up) is dominant. Horizontal components are ~30% of vertical.
    """
    gen = {"white": _white_noise, "blue": _blue_noise, "brownian": _brownian_noise}[noise_type]

    kwargs = {"rng": rng} if noise_type == "white" else {"fs": fs, "rng": rng}
    noise_up = gen(n, rms_vertical, **kwargs)
    noise_east = gen(n, rms_vertical * 0.3, **kwargs)
    noise_north = gen(n, rms_vertical * 0.3, **kwargs)
    return np.column_stack([noise_east, noise_north, noise_up])


def generate_gyro_noise(n, noise_type, rms_pitch, fs, rng):
    """Generate 3-axis gyroscope noise in world frame.

    Pitch (around East axis) is dominant. Roll and yaw are ~40% of pitch.
    """
    gen = {"white": _white_noise, "blue": _blue_noise, "brownian": _brownian_noise}[noise_type]
    kwargs = {"rng": rng} if noise_type == "white" else {"fs": fs, "rng": rng}

    pitch = gen(n, rms_pitch, **kwargs)        # around East
    roll = gen(n, rms_pitch * 0.4, **kwargs)   # around North (forward)
    yaw = gen(n, rms_pitch * 0.4, **kwargs)    # around Up
    return np.column_stack([pitch, roll, yaw])  # East, North, Up angular velocities


# =============================================================================
# Pothole waveforms
# =============================================================================

def _rect_pulse(n_samples, severity):
    return np.full(n_samples, severity)


def _sinc_pulse(n_samples, severity):
    t = np.linspace(-3, 3, n_samples)
    pulse = np.sinc(t) * severity
    return pulse


def inject_pothole(accel_world, gyro_world, t_center_s, duration_s, waveform, severity_ms2, fs, rng):
    """Inject a pothole impulse into world-frame accel and gyro arrays.

    The pothole primarily affects:
    - Vertical acceleration (Up axis): main impact
    - Forward acceleration (North axis): ~20% of vertical (pitch effect)
    - Lateral acceleration (East axis): ~10% (random asymmetry)
    - Gyro pitch: proportional to vertical accel derivative
    - Gyro roll: ~30% of pitch
    - Gyro yaw: ~15% of pitch
    """
    idx_center = int(t_center_s * fs)
    n_samples = max(2, int(duration_s * fs))
    idx_start = idx_center - n_samples // 2
    idx_end = idx_start + n_samples

    if idx_start < 0 or idx_end > len(accel_world):
        return

    if waveform == "rect":
        pulse = _rect_pulse(n_samples, severity_ms2)
    elif waveform == "sinc":
        pulse = _sinc_pulse(n_samples, severity_ms2)
    else:
        raise ValueError(f"Unknown waveform: {waveform}")

    # Directional coupling
    lateral_sign = rng.choice([-1, 1])
    accel_world[idx_start:idx_end, 2] += pulse                            # Up (main)
    accel_world[idx_start:idx_end, 1] += pulse * 0.20                     # North (forward pitch)
    accel_world[idx_start:idx_end, 0] += pulse * 0.10 * lateral_sign      # East (asymmetry)

    # Gyro: derivative of the acceleration pulse → angular impulse
    dpulse = np.gradient(pulse, 1.0 / fs)
    scale = 0.02  # rad/s per m/s³ — empirical coupling factor
    gyro_world[idx_start:idx_end, 0] += dpulse * scale                     # Pitch (around East)
    gyro_world[idx_start:idx_end, 1] += dpulse * scale * 0.30              # Roll (around North)
    gyro_world[idx_start:idx_end, 2] += dpulse * scale * 0.15 * lateral_sign  # Yaw


# =============================================================================
# Route / vehicle dynamics
# =============================================================================

def generate_route(duration_s, fs):
    """Generate vehicle speed and heading profiles for the test scenario.

    Returns:
        speed_mps: array at fs Hz
        heading_rad: array at fs Hz (0=North, π/2=East)
        events: list of (time_s, event_type, source) tuples
    """
    n = int(duration_s * fs)
    t = np.arange(n) / fs
    speed = np.zeros(n)
    heading = np.full(n, np.deg2rad(45))  # start heading NE

    events = []

    # 0-10s: stopped
    # 10-30s: accelerate to 50 km/h (13.9 m/s) over 5s, then cruise
    for i in range(n):
        ti = t[i]
        if ti < 10:
            speed[i] = 0
        elif ti < 15:
            speed[i] = 13.9 * (ti - 10) / 5  # smooth acceleration
        elif ti < 30:
            speed[i] = 13.9
        elif ti < 40:
            speed[i] = 13.9  # cruising with potholes
        elif ti < 45:
            # Braking to stop: decelerate over 5s
            speed[i] = 13.9 * (1 - (ti - 40) / 5)
        elif ti < 55:
            speed[i] = 0  # stopped at stop sign
        elif ti < 60:
            speed[i] = 10.0 * (ti - 55) / 5  # accelerate to 36 km/h
        elif ti < 65:
            speed[i] = 10.0  # turning speed
        elif ti < 70:
            speed[i] = 10.0 + 3.9 * (ti - 65) / 5  # accelerate back to ~50
        elif ti <= duration_s:
            speed[i] = 13.9

    # Heading: 90° right turn between 60-65s
    for i in range(n):
        ti = t[i]
        if ti < 60:
            heading[i] = np.deg2rad(45)  # NE
        elif ti < 65:
            frac = (ti - 60) / 5
            # Smooth S-curve turn
            s = 3 * frac**2 - 2 * frac**3
            heading[i] = np.deg2rad(45 + 90 * s)
        else:
            heading[i] = np.deg2rad(135)  # SE after turn

    speed = np.clip(speed, 0, None)

    # Define potholes as events
    pothole_times = [
        # Easy potholes (low noise segment 30-40s) — each has 1x, 2x, 4x faster variants
        (31.0, "rect", 0.050, 10.0),   # rect 50ms
        (31.5, "rect", 0.025, 10.0),   # rect 25ms (2x faster)
        (32.0, "rect", 0.0125, 10.0),  # rect 12.5ms (4x faster)
        (33.0, "rect", 0.100, 8.0),    # rect 100ms
        (33.5, "rect", 0.050, 8.0),    # rect 50ms (2x faster)
        (34.0, "rect", 0.025, 8.0),    # rect 25ms (4x faster)
        (35.0, "sinc", 0.050, 12.0),   # sinc 50ms
        (35.5, "sinc", 0.025, 12.0),   # sinc 25ms (2x faster)
        (36.0, "sinc", 0.0125, 12.0),  # sinc 12.5ms (4x faster)
        (37.0, "sinc", 0.100, 7.0),    # sinc 100ms
        (37.5, "sinc", 0.050, 7.0),    # sinc 50ms (2x faster)
        (38.0, "sinc", 0.025, 7.0),    # sinc 25ms (4x faster)
        # Hard potholes (high noise segment 80-90s) — same with speed variants
        (81.0, "rect", 0.050, 10.0),
        (81.5, "rect", 0.025, 10.0),
        (82.0, "rect", 0.0125, 10.0),
        (83.0, "rect", 0.100, 8.0),
        (83.5, "rect", 0.050, 8.0),
        (84.0, "rect", 0.025, 8.0),
        (85.0, "sinc", 0.050, 12.0),
        (85.5, "sinc", 0.025, 12.0),
        (86.0, "sinc", 0.0125, 12.0),
        (87.0, "sinc", 0.100, 7.0),
        (87.5, "sinc", 0.050, 7.0),
        (88.0, "sinc", 0.025, 7.0),
        # Tilted phone potholes (100-120s) — same with speed variants
        (102.0, "rect", 0.050, 10.0),
        (102.5, "rect", 0.025, 10.0),
        (103.0, "rect", 0.0125, 10.0),
        (105.0, "rect", 0.100, 8.0),
        (105.5, "rect", 0.050, 8.0),
        (106.0, "rect", 0.025, 8.0),
        (108.0, "sinc", 0.050, 12.0),
        (108.5, "sinc", 0.025, 12.0),
        (109.0, "sinc", 0.0125, 12.0),
        (112.0, "sinc", 0.100, 7.0),
        (112.5, "sinc", 0.050, 7.0),
        (113.0, "sinc", 0.025, 7.0),
    ]

    for pt_time, wf, dur, sev in pothole_times:
        events.append((pt_time, "pothole", "synthetic"))

    return t, speed, heading, pothole_times, events


def generate_phone_tilt(t, fs):
    """Generate phone tilt angle over time.

    0-90s: vertical (0 rad tilt)
    90-100s: gradual tilt from 0 to 45° forward
    100-120s: 45° forward tilt
    """
    n = len(t)
    tilt = np.zeros(n)
    for i in range(n):
        ti = t[i]
        if ti < 90:
            tilt[i] = 0
        elif ti < 100:
            frac = (ti - 90) / 10
            tilt[i] = np.deg2rad(45) * (3 * frac**2 - 2 * frac**3)
        else:
            tilt[i] = np.deg2rad(45)
    return tilt


# =============================================================================
# GPS generation
# =============================================================================

def generate_gps(t_highres, speed, heading, fs_highres, rng):
    """Generate GPS data at 1 Hz from high-res speed/heading."""
    # Integrate position at high res
    dt = 1.0 / fs_highres
    n = len(t_highres)
    lat = np.zeros(n)
    lon = np.zeros(n)
    lat[0] = START_LAT
    lon[0] = START_LON

    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * np.cos(np.radians(START_LAT))

    for i in range(1, n):
        vn = speed[i] * np.cos(heading[i])  # North velocity
        ve = speed[i] * np.sin(heading[i])  # East velocity
        lat[i] = lat[i - 1] + vn * dt / m_per_deg_lat
        lon[i] = lon[i - 1] + ve * dt / m_per_deg_lon

    # Downsample to 1 Hz
    step = fs_highres // GPS_HZ
    indices = np.arange(0, n, step)

    gps_t_ms = (EPOCH_OFFSET_MS + (BOOT_TIME_NS / 1e6) + t_highres[indices] * 1000).astype(np.int64)

    # Add GPS jitter (~2m)
    jitter_lat = rng.normal(0, 2.0 / m_per_deg_lat, len(indices))
    jitter_lon = rng.normal(0, 2.0 / m_per_deg_lon, len(indices))

    bearing_deg = np.degrees(heading[indices]) % 360

    return {
        "timestamp_ms": gps_t_ms,
        "lat_deg": lat[indices] + jitter_lat,
        "lon_deg": lon[indices] + jitter_lon,
        "altitude_m": np.full(len(indices), 45.0),
        "speed_mps": speed[indices] + rng.normal(0, 0.3, len(indices)),
        "bearing_deg": bearing_deg,
        "accuracy_m": 3.0 + rng.exponential(1, len(indices)),
        "vertical_accuracy_m": np.full(len(indices), 5.0),
        "speed_accuracy_mps": np.full(len(indices), 0.5),
        "bearing_accuracy_deg": np.full(len(indices), 5.0),
    }


# =============================================================================
# Main generator
# =============================================================================

def generate_synthetic_session(output_dir, scenario="full_test", seed=42):
    """Generate a synthetic sensor session and write CSV/JSON files.

    Args:
        output_dir: directory to create the session folder in
        scenario: scenario name (used in filenames)
        seed: random seed for reproducibility

    Returns:
        Path to the created session directory.
    """
    rng = np.random.default_rng(seed)
    duration_s = 120
    session_id = f"synthetic_{scenario}"

    print(f"Generating synthetic session: {session_id} ({duration_s}s)")

    # --- Route ---
    print("  Route & vehicle dynamics...")
    t, speed, heading, pothole_defs, events = generate_route(duration_s, ACCEL_HZ)
    n_accel = len(t)
    phone_tilt = generate_phone_tilt(t, ACCEL_HZ)

    # Vehicle dynamics in world frame (ENU)
    dt = 1.0 / ACCEL_HZ
    d_speed = np.gradient(speed, dt)          # longitudinal acceleration
    d_heading = np.gradient(heading, dt)      # heading rate
    a_lateral = speed * d_heading             # centripetal acceleration

    # World-frame vehicle acceleration (ENU)
    cos_h = np.cos(heading)
    sin_h = np.sin(heading)
    # Forward = North*cos(h) + East*sin(h) direction
    accel_world = np.zeros((n_accel, 3))
    accel_world[:, 0] = d_speed * sin_h + a_lateral * cos_h   # East
    accel_world[:, 1] = d_speed * cos_h - a_lateral * sin_h   # North
    # Vertical: only from potholes + noise (no vehicle dynamics)

    # World-frame gyro baseline (from heading change = yaw)
    gyro_world = np.zeros((n_accel, 3))
    gyro_world[:, 2] = d_heading  # Yaw = heading change rate
    # Phone tilt change = pitch
    d_tilt = np.gradient(phone_tilt, dt)
    gyro_world[:, 0] += d_tilt  # Pitch around East axis

    # --- Road noise ---
    print("  Road noise...")
    # Segment-specific noise
    noise_accel = np.zeros((n_accel, 3))
    noise_gyro = np.zeros((n_accel, 3))

    noise_segments = [
        (0, 10, "white", 0.02, 0.001),      # stopped: very low noise
        (10, 30, "white", 0.10, 0.005),      # cruising, smooth road
        (30, 40, "white", 0.08, 0.004),      # smooth road (easy potholes)
        (40, 55, "white", 0.05, 0.002),      # braking/stopped
        (55, 70, "white", 0.12, 0.006),      # turning
        (70, 90, "brownian", 0.50, 0.025),   # degraded road (hard potholes)
        (90, 100, "white", 0.15, 0.008),     # orientation change
        (100, 120, "white", 0.15, 0.008),    # tilted phone potholes
    ]

    for t_start, t_end, ntype, rms_a, rms_g in noise_segments:
        i0 = int(t_start * ACCEL_HZ)
        i1 = min(int(t_end * ACCEL_HZ), n_accel)
        seg_len = i1 - i0
        if seg_len <= 0:
            continue
        noise_accel[i0:i1] = generate_road_noise(seg_len, ntype, rms_a, ACCEL_HZ, rng)
        noise_gyro[i0:i1] = generate_gyro_noise(seg_len, ntype, rms_g, ACCEL_HZ, rng)

    accel_world += noise_accel
    gyro_world += noise_gyro

    # --- Potholes ---
    print(f"  Injecting {len(pothole_defs)} potholes...")
    for pt_time, waveform, dur, severity in pothole_defs:
        inject_pothole(accel_world, gyro_world, pt_time, dur, waveform, severity, ACCEL_HZ, rng)

    # --- Transform to phone frame ---
    print("  Phone-frame transformation...")
    accel_phone = np.zeros_like(accel_world)
    lin_accel_phone = np.zeros_like(accel_world)
    gyro_phone = np.zeros_like(gyro_world)
    mag_phone_full = np.zeros_like(accel_world)

    gravity_world = np.array([0, 0, GRAVITY])  # Up in ENU

    # Compute per-sample (heading and tilt can change)
    # Batch by segments where orientation is constant for efficiency
    prev_h, prev_tilt = None, None
    R = None
    for i in range(n_accel):
        h = heading[i]
        tlt = phone_tilt[i]
        # Recompute rotation matrix only when orientation changes significantly
        if R is None or abs(h - prev_h) > 1e-6 or abs(tlt - prev_tilt) > 1e-6:
            R = phone_rotation_matrix(h, tlt)
            prev_h, prev_tilt = h, tlt

        # Raw accel = world_to_phone @ (vehicle_accel + gravity) + noise (already in accel_world)
        accel_phone[i] = R @ (accel_world[i] + gravity_world)
        # Linear accel = world_to_phone @ vehicle_accel (no gravity)
        lin_accel_phone[i] = R @ accel_world[i]
        # Gyro in phone frame
        gyro_phone[i] = R @ gyro_world[i]
        # Magnetometer in phone frame
        mag_phone_full[i] = R @ MAG_FIELD_ENU

    # Add sensor noise
    accel_phone += rng.normal(0, 0.01, accel_phone.shape)
    lin_accel_phone += rng.normal(0, 0.01, lin_accel_phone.shape)
    gyro_phone += rng.normal(0, 0.001, gyro_phone.shape)
    mag_phone_full += rng.normal(0, 0.5, mag_phone_full.shape)

    # --- Timestamps ---
    ts_accel_ns = BOOT_TIME_NS + (t * 1e9).astype(np.int64)

    # Gyro at same rate as accel for simplicity
    ts_gyro_ns = ts_accel_ns.copy()

    # Mag at 50 Hz
    mag_step = ACCEL_HZ // MAG_HZ
    mag_indices = np.arange(0, n_accel, mag_step)
    ts_mag_ns = ts_accel_ns[mag_indices]
    mag_phone = mag_phone_full[mag_indices]

    # --- GPS ---
    print("  GPS data...")
    gps_data = generate_gps(t, speed, heading, ACCEL_HZ, rng)

    # --- Events ---
    event_rows = []
    for ev_time, ev_type, ev_source in events:
        ev_ms = int(EPOCH_OFFSET_MS + (BOOT_TIME_NS / 1e6) + ev_time * 1000)
        event_rows.append((ev_ms, ev_type, ev_source))

    # --- Write files ---
    session_dir = Path(output_dir) / f"session_{session_id}"
    session_dir.mkdir(parents=True, exist_ok=True)
    print(f"  Writing to {session_dir}/")

    def write_csv(filename, header, rows_func):
        path = session_dir / filename
        with open(path, "w") as f:
            f.write("# synthetic\n")
            f.write(header + "\n")
            rows_func(f)
        return path

    # Accel
    write_csv(
        f"accel_{session_id}.csv",
        "timestamp_ns,x_ms2,y_ms2,z_ms2",
        lambda f: np.savetxt(f, np.column_stack([ts_accel_ns, accel_phone]),
                             fmt="%d,%.6f,%.6f,%.6f"),
    )
    print(f"    accel: {n_accel:,} samples")

    # Lin accel
    write_csv(
        f"lin_accel_{session_id}.csv",
        "timestamp_ns,x_ms2,y_ms2,z_ms2",
        lambda f: np.savetxt(f, np.column_stack([ts_accel_ns, lin_accel_phone]),
                             fmt="%d,%.6f,%.6f,%.6f"),
    )
    print(f"    lin_accel: {n_accel:,} samples")

    # Gyro
    write_csv(
        f"gyro_{session_id}.csv",
        "timestamp_ns,x_rads,y_rads,z_rads",
        lambda f: np.savetxt(f, np.column_stack([ts_gyro_ns, gyro_phone]),
                             fmt="%d,%.6f,%.6f,%.6f"),
    )
    print(f"    gyro: {n_accel:,} samples")

    # Mag
    write_csv(
        f"mag_{session_id}.csv",
        "timestamp_ns,x_ut,y_ut,z_ut",
        lambda f: np.savetxt(f, np.column_stack([ts_mag_ns, mag_phone]),
                             fmt="%d,%.6f,%.6f,%.6f"),
    )
    print(f"    mag: {len(mag_indices):,} samples")

    # GPS
    write_csv(
        f"gps_{session_id}.csv",
        "timestamp_ms,lat_deg,lon_deg,altitude_m,speed_mps,bearing_deg,accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg",
        lambda f: np.savetxt(
            f,
            np.column_stack([
                gps_data["timestamp_ms"], gps_data["lat_deg"], gps_data["lon_deg"],
                gps_data["altitude_m"], gps_data["speed_mps"], gps_data["bearing_deg"],
                gps_data["accuracy_m"], gps_data["vertical_accuracy_m"],
                gps_data["speed_accuracy_mps"], gps_data["bearing_accuracy_deg"],
            ]),
            fmt="%d,%.8f,%.8f,%.2f,%.2f,%.1f,%.1f,%.1f,%.2f,%.1f",
        ),
    )
    print(f"    gps: {len(gps_data['timestamp_ms']):,} fixes")

    # Events
    write_csv(
        f"events_{session_id}.csv",
        "timestamp_ms,event_type,source",
        lambda f: f.writelines(f"{ts},{et},{src}\n" for ts, et, src in event_rows),
    )
    print(f"    events: {len(event_rows)} markers")

    # Meta
    meta = {
        "schema_version": 1,
        "session_id": session_id,
        "device_model": "Synthetic",
        "android_version": "N/A",
        "app_version": "synthetic",
        "start_time_iso": "2026-04-20T10:00:00.000Z",
        "start_time_epoch_ms": int(EPOCH_OFFSET_MS + BOOT_TIME_NS / 1e6),
        "start_boot_time_ns": int(BOOT_TIME_NS),
        "boot_to_epoch_offset_ms": int(EPOCH_OFFSET_MS),
        "sensors": {
            "accelerometer": {"name": "Synthetic", "vendor": "Test", "resolution": 0.001, "max_range": 40.0, "requested_delay_us": 0},
            "linear_acceleration": {"name": "Synthetic", "vendor": "Test", "resolution": 0.001, "max_range": 40.0, "requested_delay_us": 0},
            "gyroscope": {"name": "Synthetic", "vendor": "Test", "resolution": 0.0001, "max_range": 10.0, "requested_delay_us": 0},
            "magnetometer": {"name": "Synthetic", "vendor": "Test", "resolution": 0.1, "max_range": 100.0, "requested_delay_us": 0},
        },
        "sample_counts": {
            "accel": n_accel,
            "lin_accel": n_accel,
            "gyro": n_accel,
            "mag": len(mag_indices),
            "gps": len(gps_data["timestamp_ms"]),
            "events": len(event_rows),
        },
        "synthetic": {
            "scenario": scenario,
            "seed": seed,
            "duration_s": duration_s,
            "accel_hz": ACCEL_HZ,
            "description": (
                "0-10s stopped | 10-30s cruise 50km/h low noise | "
                "30-40s 4 potholes easy | 40-55s braking+stop | "
                "55-70s accel+turn | 70-90s high brownian noise + 4 potholes hard | "
                "90-100s phone tilt 0→45° | 100-120s 4 potholes tilted phone"
            ),
            "potholes": [
                {"time_s": pt, "waveform": wf, "duration_s": dur, "severity_ms2": sev}
                for pt, wf, dur, sev in pothole_defs
            ],
        },
    }

    meta_path = session_dir / f"meta_{session_id}.json"
    meta_path.write_text(json.dumps(meta, indent=2))
    print(f"    meta: {meta_path.name}")

    print(f"Done — {session_dir}")
    return str(session_dir)


def generate_and_load(output_dir="/tmp", scenario="full_test", seed=42):
    """Generate a synthetic session and load it using the standard loader.

    Returns the same dict format as load_session().
    """
    session_dir = generate_synthetic_session(output_dir, scenario, seed)

    # Use the loader from this package
    from .loader import load_session
    return load_session(session_dir)
