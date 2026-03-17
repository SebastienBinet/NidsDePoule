"""Smoke tests — hit a live deployed server to verify deployment.

Controlled by the SMOKE_TEST_URL environment variable.
Default: tests are skipped unless SMOKE_TEST_URL is set.

Usage:
    SMOKE_TEST_URL=https://nidsdepoule.onrender.com pytest tests/test_smoke.py -v
"""

from __future__ import annotations

import json
import os
import uuid

import httpx
import pytest

SMOKE_URL = os.environ.get("SMOKE_TEST_URL", "")

pytestmark = [
    pytest.mark.smoke,
    pytest.mark.skipif(not SMOKE_URL, reason="SMOKE_TEST_URL not set"),
]


@pytest.fixture
def http():
    """Synchronous httpx client for the live server."""
    with httpx.Client(base_url=SMOKE_URL, timeout=15) as c:
        yield c


# ---------- Health & reachability ----------

def test_server_reachable(http):
    resp = http.get("/api/v1/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"


def test_server_version(http):
    resp = http.get("/api/v1/health")
    data = resp.json()
    assert "version" in data


# ---------- Diagnostic endpoint ----------

def test_storage_diagnostic(http):
    resp = http.get("/api/v1/debug/storage")
    assert resp.status_code == 200
    data = resp.json()
    assert "storage_backend_config" in data
    assert "storage_backend_class" in data
    assert "storage_details" in data


def test_storage_backend_is_expected(http):
    resp = http.get("/api/v1/debug/storage")
    data = resp.json()
    backend = data["storage_backend_config"]
    assert backend in ("firebase", "firestore"), (
        f"Expected firebase or firestore, got '{backend}' — "
        "hits may be going to local files!"
    )


# ---------- Read-only API endpoints ----------

def test_config_endpoint_live(http):
    resp = http.get("/api/v1/config")
    assert resp.status_code == 200
    data = resp.json()
    assert "min_protocol_version" in data
    assert "max_batch_size" in data


def test_stats_endpoint_live(http):
    resp = http.get("/api/v1/stats")
    assert resp.status_code == 200
    data = resp.json()
    assert "hits_received" in data
    assert "active_devices" in data


def test_potholes_endpoint_live(http):
    resp = http.get("/api/v1/potholes")
    assert resp.status_code == 200
    data = resp.json()
    assert data["type"] == "FeatureCollection"
    assert "features" in data


def test_dashboard_loads(http):
    resp = http.get("/")
    assert resp.status_code == 200
    assert "html" in resp.headers.get("content-type", "").lower()


# ---------- Write test ----------

def test_submit_hit_live(http):
    """POST a hit with smoke-test device_id and verify acceptance."""
    device_id = f"smoke-test-{uuid.uuid4().hex[:12]}"
    payload = {
        "protocol_version": 1,
        "device_id": device_id,
        "app_version": 1,
        "source": "auto",
        "hit": {
            "timestamp_ms": int(__import__("time").time() * 1000),
            "location": {
                "lat_microdeg": 45500000,
                "lon_microdeg": -73600000,
                "accuracy_m": 10,
            },
            "speed_mps": 8.0,
            "bearing_deg": 90.0,
            "pattern": {
                "severity": 1,
                "peak_vertical_mg": 2000,
                "duration_ms": 80,
                "baseline_mg": 1000,
                "peak_to_baseline_ratio": 200,
            },
        },
    }
    resp = http.post(
        "/api/v1/hits",
        content=json.dumps(payload),
        headers={"content-type": "application/json"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] is True
    assert data["hits_stored"] == 1
