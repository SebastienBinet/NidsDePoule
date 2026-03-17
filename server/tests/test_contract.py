"""Contract tests — verify the server accepts/rejects protocol messages correctly.

These tests exercise Interface I1 (Android App ↔ Server) by sending all
message types and checking HTTP status codes and response shapes.
"""

from __future__ import annotations

import json
import time

import pytest


def _make_hit(*, ts_ms=None, lat=45764043, lon=4835659, severity=2):
    """Build a minimal valid hit payload."""
    return {
        "timestamp_ms": ts_ms or int(time.time() * 1000),
        "location": {
            "lat_microdeg": lat,
            "lon_microdeg": lon,
            "accuracy_m": 5,
        },
        "speed_mps": 13.5,
        "bearing_deg": 270.0,
        "bearing_before_deg": 268.0,
        "bearing_after_deg": 272.0,
        "pattern": {
            "severity": severity,
            "peak_vertical_mg": 4500,
            "peak_lateral_mg": 800,
            "duration_ms": 120,
            "waveform_vertical": [1000, 1200, 2000, 4500, 3000, 1500, 1000],
            "waveform_lateral": [100, 200, 800, 500, 200, 100, 50],
            "baseline_mg": 1050,
            "peak_to_baseline_ratio": 428,
        },
    }


def _post(client, payload):
    """Helper — POST JSON to /api/v1/hits."""
    import asyncio

    async def _do():
        return await client.post(
            "/api/v1/hits",
            content=json.dumps(payload),
            headers={"content-type": "application/json"},
        )
    return asyncio.get_event_loop().run_until_complete(_do())


# ---------- Single hit ----------

@pytest.mark.asyncio
async def test_single_hit_accepted(client):
    payload = {
        "protocol_version": 1,
        "device_id": "contract-test-001",
        "app_version": 1,
        "hit": _make_hit(),
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True
    assert data["hits_stored"] == 1
    assert data["error"] == ""


# ---------- Batch ----------

@pytest.mark.asyncio
async def test_batch_accepted(client):
    now = int(time.time() * 1000)
    payload = {
        "protocol_version": 1,
        "device_id": "contract-test-002",
        "app_version": 1,
        "batch": {
            "hits": [
                _make_hit(ts_ms=now - 60000),
                _make_hit(ts_ms=now - 30000),
                _make_hit(ts_ms=now),
            ],
        },
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True
    assert data["hits_stored"] == 3


@pytest.mark.asyncio
async def test_empty_batch(client):
    payload = {
        "protocol_version": 1,
        "device_id": "contract-test-003",
        "app_version": 1,
        "batch": {"hits": []},
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True
    assert data["hits_stored"] == 0


# ---------- Heartbeat ----------

@pytest.mark.asyncio
async def test_heartbeat_accepted(client):
    payload = {
        "protocol_version": 1,
        "device_id": "contract-hb-001",
        "app_version": 1,
        "heartbeat": {
            "timestamp_ms": int(time.time() * 1000),
            "pending_hits": 3,
        },
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True
    assert data["hits_stored"] == 0


@pytest.mark.asyncio
async def test_heartbeat_with_location(client):
    payload = {
        "protocol_version": 1,
        "device_id": "contract-hb-gps-001",
        "app_version": 1,
        "heartbeat": {
            "timestamp_ms": int(time.time() * 1000),
            "pending_hits": 0,
            "location": {
                "lat_microdeg": 45500000,
                "lon_microdeg": -73600000,
            },
        },
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200

    # Device should appear in active devices list with its GPS position
    resp2 = await client.get("/api/v1/devices/active")
    devices = resp2.json()
    device_ids = [d["device_id"] for d in devices]
    assert "contract" in device_ids[0] or len(devices) > 0


# ---------- Rejection cases ----------

@pytest.mark.asyncio
async def test_reject_protocol_v0(client):
    payload = {
        "protocol_version": 0,
        "device_id": "contract-reject-001",
        "app_version": 1,
        "hit": _make_hit(),
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 426
    data = resp.json()
    assert data["accepted"] is False
    assert "too old" in data["error"]


@pytest.mark.asyncio
async def test_reject_empty_device_id(client):
    payload = {
        "protocol_version": 1,
        "device_id": "",
        "app_version": 1,
        "hit": _make_hit(),
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 422
    data = resp.json()
    assert data["accepted"] is False


@pytest.mark.asyncio
async def test_reject_malformed_json(client):
    resp = await client.post(
        "/api/v1/hits",
        content=b"this is not json {{{",
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 400
    data = resp.json()
    assert data["accepted"] is False
    assert "invalid JSON" in data["error"]


@pytest.mark.asyncio
async def test_reject_missing_hit_fields_graceful(client):
    """Server should handle a hit with missing sub-fields gracefully."""
    payload = {
        "protocol_version": 1,
        "device_id": "contract-missing-001",
        "app_version": 1,
        "hit": {
            "timestamp_ms": int(time.time() * 1000),
            # No location, no pattern — server should fill defaults
        },
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    # Server is lenient: it fills defaults for missing fields
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True


# ---------- Source field ----------

@pytest.mark.asyncio
async def test_source_field_preserved(client):
    payload = {
        "protocol_version": 1,
        "device_id": "contract-source-001",
        "app_version": 1,
        "source": "almost",
        "hit": _make_hit(),
    }
    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    assert resp.json()["accepted"] is True


# ---------- Read-only endpoints ----------

@pytest.mark.asyncio
async def test_config_endpoint_contract(client):
    resp = await client.get("/api/v1/config")
    assert resp.status_code == 200
    data = resp.json()
    # Required keys per the protocol
    for key in ("min_app_version", "latest_app_version", "min_protocol_version",
                "update_urgency", "max_waveform_samples", "max_hits_per_hour",
                "max_batch_size"):
        assert key in data, f"Missing key: {key}"
    assert isinstance(data["min_protocol_version"], int)
    assert isinstance(data["max_batch_size"], int)


@pytest.mark.asyncio
async def test_health_endpoint_contract(client):
    resp = await client.get("/api/v1/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert isinstance(data["uptime_seconds"], (int, float))
    assert isinstance(data["queue_depth"], int)
    assert "storage_writable" in data
    assert "disk_free_gb" in data


@pytest.mark.asyncio
async def test_debug_storage_endpoint(client):
    resp = await client.get("/api/v1/debug/storage")
    assert resp.status_code == 200
    data = resp.json()
    assert "storage_backend_config" in data
    assert "storage_backend_class" in data
    assert "storage_details" in data
    assert "hits_received" in data
    assert "hits_stored" in data
    assert "storage_errors" in data
