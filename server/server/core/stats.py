"""Server statistics and active-user tracking.

Tracks in-memory counters and a sliding window of active real-time devices.
No framework dependencies.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field


@dataclass
class DeviceActivity:
    """Tracks a single device's recent activity."""
    last_seen: float          # time.monotonic() timestamp
    reporting_mode: str       # "realtime" or "batch"
    hits_sent: int = 0


class ServerStats:
    """Thread-safe server statistics with active-user tracking.

    A device is considered "real-time active" if:
    1. It sent data with reporting_mode="realtime" (or sent individual hits,
       not batches — individual POSTs imply real-time mode).
    2. Its last activity was within ``active_window_seconds`` (default 120s).
    """

    def __init__(self, active_window_seconds: float = 120.0) -> None:
        self._lock = threading.Lock()
        self._started_at = time.time()
        self._active_window = active_window_seconds

        # Counters
        self.hits_received: int = 0
        self.hits_stored: int = 0
        self.hits_rejected: int = 0
        self.bytes_received: int = 0
        self.bytes_stored: int = 0
        self.batches_received: int = 0
        self.heartbeats_received: int = 0
        self.storage_errors: int = 0
        self.queue_depth: int = 0
        self.queue_max_depth: int = 0

        # Device tracking: device_id → DeviceActivity
        self._devices: dict[str, DeviceActivity] = {}

    def record_hit(self, device_id: str, size_bytes: int, *, is_batch: bool = False) -> None:
        """Record that a hit was received from a device."""
        now = time.monotonic()
        with self._lock:
            self.hits_received += 1
            self.bytes_received += size_bytes
            mode = "batch" if is_batch else "realtime"
            if device_id in self._devices:
                dev = self._devices[device_id]
                dev.last_seen = now
                dev.reporting_mode = mode
                dev.hits_sent += 1
            else:
                self._devices[device_id] = DeviceActivity(
                    last_seen=now, reporting_mode=mode, hits_sent=1,
                )

    def record_batch(self, device_id: str, count: int, size_bytes: int) -> None:
        """Record that a batch of hits was received."""
        now = time.monotonic()
        with self._lock:
            self.hits_received += count
            self.batches_received += 1
            self.bytes_received += size_bytes
            if device_id in self._devices:
                dev = self._devices[device_id]
                dev.last_seen = now
                dev.reporting_mode = "batch"
                dev.hits_sent += count
            else:
                self._devices[device_id] = DeviceActivity(
                    last_seen=now, reporting_mode="batch", hits_sent=count,
                )

    def record_heartbeat(self, device_id: str) -> None:
        """Record a heartbeat from a device."""
        now = time.monotonic()
        with self._lock:
            self.heartbeats_received += 1
            if device_id in self._devices:
                self._devices[device_id].last_seen = now

    def record_stored(self, count: int, size_bytes: int) -> None:
        with self._lock:
            self.hits_stored += count
            self.bytes_stored += size_bytes

    def record_rejected(self, count: int = 1) -> None:
        with self._lock:
            self.hits_rejected += count

    def record_storage_error(self) -> None:
        with self._lock:
            self.storage_errors += 1

    def update_queue_depth(self, depth: int) -> None:
        with self._lock:
            self.queue_depth = depth
            if depth > self.queue_max_depth:
                self.queue_max_depth = depth

    def _prune_stale_devices(self, now: float) -> None:
        """Remove devices not seen within the active window. Caller holds lock."""
        cutoff = now - self._active_window
        stale = [did for did, dev in self._devices.items() if dev.last_seen < cutoff]
        for did in stale:
            del self._devices[did]

    def snapshot(self) -> dict:
        """Return a JSON-serializable snapshot of all stats."""
        now_mono = time.monotonic()
        with self._lock:
            self._prune_stale_devices(now_mono)

            active_realtime = sum(
                1 for dev in self._devices.values()
                if dev.reporting_mode == "realtime"
            )
            active_batch = sum(
                1 for dev in self._devices.values()
                if dev.reporting_mode == "batch"
            )
            active_total = len(self._devices)

            return {
                "uptime_seconds": round(time.time() - self._started_at, 1),
                "hits_received": self.hits_received,
                "hits_stored": self.hits_stored,
                "hits_rejected": self.hits_rejected,
                "bytes_received": self.bytes_received,
                "bytes_stored": self.bytes_stored,
                "batches_received": self.batches_received,
                "heartbeats_received": self.heartbeats_received,
                "storage_errors": self.storage_errors,
                "queue_depth": self.queue_depth,
                "queue_max_depth_ever": self.queue_max_depth,
                "active_devices": {
                    "total": active_total,
                    "realtime": active_realtime,
                    "batch": active_batch,
                    "window_seconds": self._active_window,
                },
            }
