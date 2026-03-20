"""Health check and monitoring endpoints."""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path

from fastapi import APIRouter

router = APIRouter(prefix="/api/v1")

# Read version label from the main module at request time (not import time).
_VERSION_LABEL_PATH = Path(__file__).parent.parent.parent.parent / "VERSION_LABEL"


def _read_version_label() -> str:
    try:
        return _VERSION_LABEL_PATH.read_text().strip()
    except OSError:
        return "unknown"


_VERSION_LABEL = _read_version_label()

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


@router.get("/devices/active")
async def active_devices() -> list:
    """Return active devices with their last known GPS position.

    Used by the dashboard to show green dots on the map.
    """
    from server.main import get_stats

    return get_stats().active_devices_with_locations()


@router.get("/debug/storage")
async def debug_storage() -> dict:
    """Diagnostic endpoint: reveals which storage backend is active."""
    from server.main import get_storage, get_config, get_stats

    storage = get_storage()
    config = get_config()
    stats = get_stats()
    snapshot = stats.snapshot()

    cls_name = type(storage).__name__

    details: dict = {}
    if cls_name == "FileHitStorage":
        base = Path(config.storage.base_dir)
        details["base_dir"] = str(base)
        details["dir_exists"] = base.exists()
        details["dir_writable"] = os.access(base, os.W_OK) if base.exists() else False
    elif cls_name == "FirebaseHitStorage":
        details["bucket_name"] = getattr(storage, "_bucket", None) and storage._bucket.name
        details["has_credentials"] = True
    elif cls_name == "FirestoreHitStorage":
        details["cache_size"] = len(getattr(storage, "_cache", {}))
        details["buffer_size"] = len(getattr(storage, "_buffer", []))
        details["write_count"] = getattr(storage, "_write_count", 0)
        details["max_writes"] = getattr(storage, "_max_writes", 0)
        details["doc_count"] = getattr(storage, "_doc_count", 0)
        details["flush_interval_s"] = getattr(storage, "_flush_interval_s", 0)

    return {
        "version": _VERSION_LABEL,
        "storage_backend_config": config.storage.backend,
        "storage_backend_class": cls_name,
        "storage_details": details,
        "queue_depth": snapshot["queue_depth"],
        "hits_received": snapshot["hits_received"],
        "hits_stored": snapshot["hits_stored"],
        "storage_errors": snapshot["storage_errors"],
        "last_storage_error": snapshot["last_storage_error"],
        "last_storage_error_time": snapshot["last_storage_error_time"],
    }


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
