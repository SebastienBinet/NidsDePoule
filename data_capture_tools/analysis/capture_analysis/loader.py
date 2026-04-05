"""Load sensor capture sessions into pandas DataFrames."""

import json
from pathlib import Path
from typing import Optional

import pandas as pd


def load_session(session_dir: str | Path) -> dict:
    """Load all CSV files and metadata from a capture session.

    Returns a dict with keys:
        meta  — parsed JSON metadata (dict)
        accel — DataFrame with columns: timestamp_ns, x_ms2, y_ms2, z_ms2, time_s
        gyro  — DataFrame with columns: timestamp_ns, x_rads, y_rads, z_rads, time_s
        mag   — DataFrame (or None if file missing)
        gps   — DataFrame with columns: timestamp_ms, lat_deg, lon_deg, ..., time_s
    """
    d = Path(session_dir)

    # Load metadata
    meta_files = list(d.glob("meta_*.json"))
    if not meta_files:
        raise FileNotFoundError(f"No meta_*.json found in {d}")
    meta = json.loads(meta_files[0].read_text())

    boot_offset_ms = meta.get("boot_to_epoch_offset_ms", 0)
    start_boot_ns = meta.get("start_boot_time_ns", 0)

    # Load sensor CSVs
    accel = _load_sensor_csv(d, "accel", start_boot_ns)
    gyro = _load_sensor_csv(d, "gyro", start_boot_ns)
    mag = _load_sensor_csv(d, "mag", start_boot_ns)

    # Load GPS
    gps = _load_gps_csv(d, boot_offset_ms, start_boot_ns)

    return {"meta": meta, "accel": accel, "gyro": gyro, "mag": mag, "gps": gps}


def _load_sensor_csv(
    session_dir: Path, prefix: str, start_boot_ns: int
) -> Optional[pd.DataFrame]:
    files = list(session_dir.glob(f"{prefix}_*.csv"))
    if not files:
        return None
    df = pd.read_csv(files[0])
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
    df = pd.read_csv(files[0])
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
                }
            )
    return sessions
