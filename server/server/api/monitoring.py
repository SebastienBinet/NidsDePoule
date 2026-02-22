"""Health check and monitoring endpoints."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

from fastapi import APIRouter

router = APIRouter(prefix="/api/v1")

# Load build info once at import time.
_BUILD_INFO_PATH = Path(__file__).parent.parent / "build_info.json"
_BUILD_INFO: dict = {}
if _BUILD_INFO_PATH.exists():
    try:
        _BUILD_INFO = json.loads(_BUILD_INFO_PATH.read_text())
    except (json.JSONDecodeError, OSError):
        pass


@router.get("/health")
async def health() -> dict:
    """Basic health check."""
    from server.main import get_stats, get_config

    stats = get_stats()
    config = get_config()

    storage_path = Path(config.storage.base_dir)
    try:
        disk = shutil.disk_usage(storage_path if storage_path.exists() else ".")
        disk_free_gb = round(disk.free / (1024 ** 3), 1)
        storage_writable = True
    except OSError:
        disk_free_gb = -1
        storage_writable = False

    snapshot = stats.snapshot()
    result = {
        "status": "ok",
        "version": "0.1.0",
        "uptime_seconds": snapshot["uptime_seconds"],
        "queue_depth": snapshot["queue_depth"],
        "storage_writable": storage_writable,
        "disk_free_gb": disk_free_gb,
    }
    result.update(_BUILD_INFO)
    return result


@router.get("/stats")
async def stats() -> dict:
    """Detailed server statistics including active device counts.

    The ``active_devices`` section shows:
    - ``total``: devices seen in the last N seconds (configurable window)
    - ``realtime``: devices currently sending hits in real-time
    - ``batch``: devices that last sent a batch upload
    - ``window_seconds``: the time window used for "active" calculation
    """
    from server.main import get_stats

    return get_stats().snapshot()


@router.get("/config")
async def get_client_config() -> dict:
    """Configuration endpoint for the Android app.

    The app calls this on startup to get server-controlled parameters.
    """
    from server.main import get_config

    config = get_config()
    return {
        "min_app_version": 1,
        "latest_app_version": 1,
        "min_protocol_version": 1,
        "update_urgency": "none",
        "max_waveform_samples": config.limits.max_waveform_samples,
        "max_hits_per_hour": config.limits.max_hits_per_device_per_hour,
        "max_batch_size": config.limits.max_batch_size,
    }
