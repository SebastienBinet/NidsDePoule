"""Tests for the hit report API endpoints."""

from __future__ import annotations

import json
import time

import pytest


@pytest.mark.asyncio
async def test_health(client):
    resp = await client.get("/api/v1/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "uptime_seconds" in data
    assert "disk_free_gb" in data


@pytest.mark.asyncio
async def test_stats_empty(client):
    resp = await client.get("/api/v1/stats")
    assert resp.status_code == 200
    data = resp.json()
    assert data["active_devices"]["total"] == 0
    assert data["active_devices"]["realtime"] == 0
    assert data["active_devices"]["batch"] == 0


@pytest.mark.asyncio
async def test_config_endpoint(client):
    resp = await client.get("/api/v1/config")
    assert resp.status_code == 200
    data = resp.json()
    assert "max_waveform_samples" in data
    assert "max_hits_per_hour" in data
    assert "min_protocol_version" in data


@pytest.mark.asyncio
async def test_submit_single_hit(client):
    hit_payload = {
        "protocol_version": 1,
        "device_id": "test-device-001",
        "app_version": 1,
        "hit": {
            "timestamp_ms": int(time.time() * 1000),
            "location": {
                "lat_microdeg": 45764043,
                "lon_microdeg": 4835659,
                "accuracy_m": 5,
            },
            "speed_mps": 13.5,
            "bearing_deg": 270.0,
            "bearing_before_deg": 268.0,
            "bearing_after_deg": 272.0,
            "pattern": {
                "severity": 2,
                "peak_vertical_mg": 4500,
                "peak_lateral_mg": 800,
                "duration_ms": 120,
                "waveform_vertical": [1000, 1200, 2000, 4500, 3000, 1500, 1000],
                "waveform_lateral": [100, 200, 800, 500, 200, 100, 50],
                "baseline_mg": 1050,
                "peak_to_baseline_ratio": 428,
            },
        },
    }

    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(hit_payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True
    assert data["hits_stored"] == 1


@pytest.mark.asyncio
async def test_submit_batch(client):
    now_ms = int(time.time() * 1000)
    batch_payload = {
        "protocol_version": 1,
        "device_id": "test-device-002",
        "app_version": 1,
        "batch": {
            "hits": [
                {
                    "timestamp_ms": now_ms - 60000,
                    "location": {"lat_microdeg": 45764000, "lon_microdeg": 4835600, "accuracy_m": 8},
                    "speed_mps": 10.0,
                    "bearing_deg": 90.0,
                    "pattern": {"severity": 1, "peak_vertical_mg": 2100, "baseline_mg": 1000, "peak_to_baseline_ratio": 210},
                },
                {
                    "timestamp_ms": now_ms - 30000,
                    "location": {"lat_microdeg": 45764100, "lon_microdeg": 4835700, "accuracy_m": 5},
                    "speed_mps": 12.0,
                    "bearing_deg": 92.0,
                    "pattern": {"severity": 2, "peak_vertical_mg": 3500, "baseline_mg": 1000, "peak_to_baseline_ratio": 350},
                },
            ],
        },
    }

    resp = await client.post(
        "/api/v1/hits",
        content=json.dumps(batch_payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True
    assert data["hits_stored"] == 2


@pytest.mark.asyncio
async def test_realtime_active_devices(client):
    """Test that devices sending individual hits show up as real-time active."""
    now_ms = int(time.time() * 1000)
    for i in range(3):
        payload = {
            "protocol_version": 1,
            "device_id": f"realtime-device-{i:03d}",
            "app_version": 1,
            "hit": {
                "timestamp_ms": now_ms,
                "location": {"lat_microdeg": 45764043, "lon_microdeg": 4835659},
                "speed_mps": 10.0,
                "bearing_deg": 90.0,
                "pattern": {"severity": 1, "peak_vertical_mg": 2000},
            },
        }
        resp = await client.post(
            "/api/v1/hits",
            content=json.dumps(payload),
            headers={"content-type": "application/json"},
        )
        assert resp.status_code == 200

    # Check stats
    resp = await client.get("/api/v1/stats")
    data = resp.json()
    assert data["active_devices"]["realtime"] >= 3
    assert data["active_devices"]["total"] >= 3


@pytest.mark.asyncio
async def test_reject_empty_device_id(client):
    payload = {
        "protocol_version": 1,
        "device_id": "",
        "app_version": 1,
        "hit": {
            "timestamp_ms": int(time.time() * 1000),
            "location": {"lat_microdeg": 45764043, "lon_microdeg": 4835659},
            "speed_mps": 10.0,
            "bearing_deg": 90.0,
            "pattern": {"severity": 1, "peak_vertical_mg": 2000},
        },
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
async def test_heartbeat(client):
    payload = {
        "protocol_version": 1,
        "device_id": "heartbeat-device-001",
        "app_version": 1,
        "heartbeat": {
            "timestamp_ms": int(time.time() * 1000),
            "pending_hits": 5,
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


@pytest.mark.asyncio
async def test_invalid_json(client):
    resp = await client.post(
        "/api/v1/hits",
        content=b"not json at all",
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 400
