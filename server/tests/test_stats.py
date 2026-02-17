"""Tests for ServerStats and active device tracking."""

from __future__ import annotations

import time

from server.core.stats import ServerStats


def test_initial_stats():
    stats = ServerStats()
    snap = stats.snapshot()
    assert snap["hits_received"] == 0
    assert snap["active_devices"]["total"] == 0
    assert snap["active_devices"]["realtime"] == 0
    assert snap["active_devices"]["batch"] == 0


def test_record_hit_realtime():
    stats = ServerStats()
    stats.record_hit("device-a", 700)
    stats.record_hit("device-b", 700)

    snap = stats.snapshot()
    assert snap["hits_received"] == 2
    assert snap["bytes_received"] == 1400
    assert snap["active_devices"]["total"] == 2
    assert snap["active_devices"]["realtime"] == 2
    assert snap["active_devices"]["batch"] == 0


def test_record_batch():
    stats = ServerStats()
    stats.record_batch("device-c", count=10, size_bytes=7000)

    snap = stats.snapshot()
    assert snap["hits_received"] == 10
    assert snap["batches_received"] == 1
    assert snap["active_devices"]["total"] == 1
    assert snap["active_devices"]["realtime"] == 0
    assert snap["active_devices"]["batch"] == 1


def test_device_mode_updates():
    """A device that switches from batch to realtime should be tracked correctly."""
    stats = ServerStats()
    stats.record_batch("device-d", count=5, size_bytes=3500)

    snap = stats.snapshot()
    assert snap["active_devices"]["batch"] == 1
    assert snap["active_devices"]["realtime"] == 0

    # Same device now sends in real-time
    stats.record_hit("device-d", 700)

    snap = stats.snapshot()
    assert snap["active_devices"]["batch"] == 0
    assert snap["active_devices"]["realtime"] == 1
    assert snap["active_devices"]["total"] == 1


def test_stale_devices_pruned():
    """Devices older than the active window should be pruned from stats."""
    stats = ServerStats(active_window_seconds=0.1)
    stats.record_hit("device-e", 700)

    # Should be active immediately
    snap = stats.snapshot()
    assert snap["active_devices"]["total"] == 1

    # Wait for the window to expire
    time.sleep(0.15)

    snap = stats.snapshot()
    assert snap["active_devices"]["total"] == 0


def test_queue_depth_tracking():
    stats = ServerStats()
    stats.update_queue_depth(50)
    stats.update_queue_depth(100)
    stats.update_queue_depth(30)

    snap = stats.snapshot()
    assert snap["queue_depth"] == 30
    assert snap["queue_max_depth_ever"] == 100


def test_stored_and_rejected_counters():
    stats = ServerStats()
    stats.record_stored(5, 3500)
    stats.record_rejected(2)
    stats.record_storage_error()

    snap = stats.snapshot()
    assert snap["hits_stored"] == 5
    assert snap["bytes_stored"] == 3500
    assert snap["hits_rejected"] == 2
    assert snap["storage_errors"] == 1


def test_multiple_devices_mixed():
    """Test a realistic mix of real-time and batch devices."""
    stats = ServerStats()

    # 3 real-time devices
    for i in range(3):
        stats.record_hit(f"rt-{i}", 700)

    # 2 batch devices
    for i in range(2):
        stats.record_batch(f"batch-{i}", count=20, size_bytes=14000)

    snap = stats.snapshot()
    assert snap["active_devices"]["total"] == 5
    assert snap["active_devices"]["realtime"] == 3
    assert snap["active_devices"]["batch"] == 2
    assert snap["hits_received"] == 3 + 40  # 3 individual + 2×20 batch
