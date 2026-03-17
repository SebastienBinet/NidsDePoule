"""Integration tests — verify the full pipeline from HTTP to storage.

Uses a SpyHitStorage to verify that hits flow through the processor,
queue, and consumer all the way to the storage layer.
"""

from __future__ import annotations

import asyncio
import json
import time

import pytest

import server.main as main_module


def _make_hit_payload(device_id="integ-device-001", source="auto"):
    """Build a valid single-hit payload."""
    return {
        "protocol_version": 1,
        "device_id": device_id,
        "app_version": 1,
        "source": source,
        "hit": {
            "timestamp_ms": int(time.time() * 1000),
            "location": {
                "lat_microdeg": 45500000,
                "lon_microdeg": -73600000,
                "accuracy_m": 5,
            },
            "speed_mps": 12.0,
            "bearing_deg": 180.0,
            "pattern": {
                "severity": 2,
                "peak_vertical_mg": 3500,
                "peak_lateral_mg": 600,
                "duration_ms": 100,
                "baseline_mg": 1000,
                "peak_to_baseline_ratio": 350,
            },
        },
    }


async def _post_hit(client, payload):
    return await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )


async def _drain_queue(timeout=2.0):
    """Run the storage consumer briefly to drain the queue."""
    processor = main_module._processor
    task = asyncio.create_task(processor.run_storage_consumer())
    # Give it time to process all items
    await asyncio.sleep(0.1)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


@pytest.mark.asyncio
async def test_hit_reaches_storage(client, spy_storage):
    """Send a hit and verify store() is called with correct record."""
    resp = await _post_hit(client, _make_hit_payload())
    assert resp.status_code == 200

    await _drain_queue()

    assert len(spy_storage.stored_records) == 1
    record = spy_storage.stored_records[0]
    assert record.device_id == "integ-device-001"
    assert record.hit.location.lat_microdeg == 45500000


@pytest.mark.asyncio
async def test_batch_all_hits_stored(client, spy_storage):
    """Send a batch of N hits and verify all N reach storage."""
    now = int(time.time() * 1000)
    payload = {
        "protocol_version": 1,
        "device_id": "integ-batch-001",
        "app_version": 1,
        "batch": {
            "hits": [
                {
                    "timestamp_ms": now - i * 10000,
                    "location": {"lat_microdeg": 45500000 + i, "lon_microdeg": -73600000},
                    "speed_mps": 10.0,
                    "bearing_deg": 90.0,
                    "pattern": {"severity": 1, "peak_vertical_mg": 2000},
                }
                for i in range(5)
            ],
        },
    }
    resp = await _post_hit(client, payload)
    assert resp.status_code == 200
    assert resp.json()["hits_stored"] == 5

    await _drain_queue()

    assert len(spy_storage.stored_records) == 5


@pytest.mark.asyncio
async def test_storage_error_recorded(client, spy_storage):
    """When storage raises, storage_errors counter increments."""
    spy_storage.fail_next = True

    resp = await _post_hit(client, _make_hit_payload())
    assert resp.status_code == 200

    await _drain_queue()

    stats_resp = await client.get("/api/v1/stats")
    stats = stats_resp.json()
    assert stats["storage_errors"] >= 1


@pytest.mark.asyncio
async def test_stats_updated_after_hit(client):
    """After sending a hit, /stats shows hits_received incremented."""
    resp = await _post_hit(client, _make_hit_payload())
    assert resp.status_code == 200

    stats_resp = await client.get("/api/v1/stats")
    stats = stats_resp.json()
    assert stats["hits_received"] >= 1


@pytest.mark.asyncio
async def test_device_appears_active(client):
    """After sending a hit with GPS, device appears in active list."""
    resp = await _post_hit(client, _make_hit_payload(device_id="integ-active-001"))
    assert resp.status_code == 200

    devices_resp = await client.get("/api/v1/devices/active")
    devices = devices_resp.json()
    device_ids = [d["device_id"] for d in devices]
    assert any("integ-ac" in did for did in device_ids)


@pytest.mark.asyncio
async def test_potholes_endpoint_after_hit(client, spy_storage):
    """After sending a hit and draining the queue, /potholes returns data."""
    resp = await _post_hit(client, _make_hit_payload())
    assert resp.status_code == 200

    await _drain_queue()

    potholes_resp = await client.get("/api/v1/potholes")
    assert potholes_resp.status_code == 200
    geojson = potholes_resp.json()
    assert geojson["type"] == "FeatureCollection"


@pytest.mark.asyncio
async def test_hits_recent_after_hit(client, spy_storage):
    """After sending a hit and draining, /hits/recent returns it."""
    resp = await _post_hit(client, _make_hit_payload())
    assert resp.status_code == 200

    await _drain_queue()

    recent_resp = await client.get("/api/v1/hits/recent")
    assert recent_resp.status_code == 200
    data = recent_resp.json()
    assert data["total"] >= 1
    assert len(data["hits"]) >= 1
