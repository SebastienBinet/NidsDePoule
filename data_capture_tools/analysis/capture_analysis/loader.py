"""Load sensor capture sessions into pandas DataFrames.

Forward-compatible: handles sessions from any app version (v001+).
CSVs have a `# vNNN` comment line (skipped by comment="#").
Meta JSON has `schema_version` (1+) and `app_version` for version detection.
Extra/missing columns and fields are handled gracefully.
"""

import json
import re
from pathlib import Path
from typing import Optional

import pandas as pd

# Current schema version understood by this loader.
# Bump when the loader gains support for new fields/formats.
_LOADER_SCHEMA_VERSION = 1


def load_session(session_dir: str | Path) -> dict:
    """Load all CSV files and metadata from a capture session.

    Returns a dict with keys:
        meta      — parsed JSON metadata (dict)
        accel     — DataFrame (or None) — raw accelerometer (includes gravity)
        lin_accel — DataFrame (or None) — linear acceleration (gravity removed by Android sensor fusion)
        gyro      — DataFrame (or None)
        mag       — DataFrame (or None)
        gps       — DataFrame (or None)
        events    — DataFrame (or None)

    All DataFrames include a `time_s` column (seconds since session start).
    Unknown CSV columns from future versions are preserved as-is.
    """
    d = Path(session_dir)

    # Load metadata
    meta_files = list(d.glob("meta_*.json"))
    if not meta_files:
        raise FileNotFoundError(f"No meta_*.json found in {d}")
    meta = json.loads(meta_files[0].read_text())

    schema = meta.get("schema_version", 0)  # 0 = pre-v005 (no schema_version field)
    if schema > _LOADER_SCHEMA_VERSION:
        import warnings
        warnings.warn(
            f"Session {d.name} has schema_version={schema}, but this loader "
            f"only understands up to {_LOADER_SCHEMA_VERSION}. "
            f"Some fields may not be loaded. Update the analysis tools.",
            stacklevel=2,
        )

    boot_offset_ms = meta.get("boot_to_epoch_offset_ms", 0)
    start_boot_ns = meta.get("start_boot_time_ns", 0)

    # Load sensor CSVs (extra columns from future versions are kept)
    accel = _load_sensor_csv(d, "accel", start_boot_ns)
    lin_accel = _load_sensor_csv(d, "lin_accel", start_boot_ns)
    gyro = _load_sensor_csv(d, "gyro", start_boot_ns)
    mag = _load_sensor_csv(d, "mag", start_boot_ns)

    # Load GPS
    gps = _load_gps_csv(d, boot_offset_ms, start_boot_ns)

    # Load events
    events = _load_events_csv(d, boot_offset_ms, start_boot_ns)

    return {
        "meta": meta, "accel": accel, "lin_accel": lin_accel,
        "gyro": gyro, "mag": mag, "gps": gps, "events": events,
    }


def _read_csv_version(filepath: Path) -> Optional[str]:
    """Read the app version from the first line comment (e.g. '# v004')."""
    with open(filepath) as f:
        first_line = f.readline().strip()
    match = re.match(r"^#\s*(v\d+)", first_line)
    return match.group(1) if match else None


def _load_sensor_csv(
    session_dir: Path, prefix: str, start_boot_ns: int
) -> Optional[pd.DataFrame]:
    files = list(session_dir.glob(f"{prefix}_*.csv"))
    if not files:
        return None
    df = pd.read_csv(files[0], comment="#")
    if df.empty:
        return None
    # Compute time_s from whatever timestamp column exists
    if "timestamp_ns" in df.columns and start_boot_ns > 0:
        df["time_s"] = (df["timestamp_ns"] - start_boot_ns) / 1e9
    elif "timestamp_ns" in df.columns:
        df["time_s"] = (df["timestamp_ns"] - df["timestamp_ns"].iloc[0]) / 1e9
    return df


def _load_gps_csv(
    session_dir: Path, boot_offset_ms: int, start_boot_ns: int
) -> Optional[pd.DataFrame]:
    files = list(session_dir.glob("gps_*.csv"))
    if not files:
        return None
    df = pd.read_csv(files[0], comment="#")
    if df.empty:
        return None
    if "timestamp_ms" in df.columns:
        start_epoch_ms = boot_offset_ms + start_boot_ns / 1e6
        df["time_s"] = (df["timestamp_ms"] - start_epoch_ms) / 1e3
    return df


def _load_events_csv(
    session_dir: Path, boot_offset_ms: int, start_boot_ns: int
) -> Optional[pd.DataFrame]:
    files = list(session_dir.glob("events_*.csv"))
    if not files:
        return None
    df = pd.read_csv(files[0], comment="#")
    if df.empty:
        return None
    if "timestamp_ms" in df.columns:
        start_epoch_ms = boot_offset_ms + start_boot_ns / 1e6
        df["time_s"] = (df["timestamp_ms"] - start_epoch_ms) / 1e3
    return df


def list_sessions(data_dir: str | Path) -> list[dict]:
    """List all session directories under data_dir, returning metadata summaries."""
    data_dir = Path(data_dir)
    sessions = []
    for d in sorted(data_dir.iterdir()):
        if not d.is_dir() or not d.name.startswith("session_"):
            continue
        meta_files = list(d.glob("meta_*.json"))
        if meta_files:
            meta = json.loads(meta_files[0].read_text())
            sessions.append(
                {
                    "session_id": meta.get("session_id", d.name),
                    "path": str(d),
                    "device": meta.get("device_model", "unknown"),
                    "start": meta.get("start_time_iso", ""),
                    "samples": meta.get("sample_counts", {}),
                    "app_version": meta.get("app_version", "unknown"),
                    "schema_version": meta.get("schema_version", 0),
                }
            )
    return sessions
