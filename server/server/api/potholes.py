"""Pothole cluster and individual hit API endpoints."""

from __future__ import annotations

import structlog
from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse

from server.core.clustering import cluster_hits, clusters_to_geojson

log = structlog.get_logger()

router = APIRouter(prefix="/api/v1")

# Source labels for human-readable display.
_SOURCE_LABELS = {
    "almost": "iiiiiiiii !!!",
    "hit": "AYOYE !?!#$!",
    # Legacy source values (pre-v6 clients).
    "auto": "Auto",
    "visual_small": "Hiii !",
    "visual_big": "HIIIIIII !!!",
    "impact_small": "Ouch !",
    "impact_big": "AYOYE !?!#$!",
}


def _safe(val, default=0):
    """Return *val* if it is not None, otherwise *default*."""
    return val if val is not None else default


@router.get("/potholes")
async def get_potholes() -> JSONResponse:
    """Return clustered potholes as a GeoJSON FeatureCollection.

    Reads all stored hits, clusters them spatially, and returns the result.
    """
    from server.main import get_storage

    raw_hits = get_storage().read_all_hits()
    clusters = cluster_hits(raw_hits)
    geojson = clusters_to_geojson(clusters)
    return JSONResponse(content=geojson, media_type="application/geo+json")


@router.delete("/hits")
async def delete_hits(request: Request) -> JSONResponse:
    """Delete hit records by their record IDs.

    Body: {"record_ids": [1, 2, 3]}
    Used by the dashboard eraser tool.
    """
    import json as json_mod

    from server.main import get_storage

    body = json_mod.loads(await request.body())
    record_ids = set(body.get("record_ids", []))
    if not record_ids:
        return JSONResponse(content={"deleted": 0})

    deleted = get_storage().delete_hits(record_ids)
    return JSONResponse(content={"deleted": deleted})


def _hit_to_detail(record: dict) -> dict | None:
    """Convert a raw storage record to a detailed JSON object for the UI.

    Returns None if the record is too malformed to display.
    """
    try:
        hit = record.get("hit") or {}
        loc = hit.get("location") or {}
        pat = hit.get("pattern") or {}
        source = record.get("source") or "auto"

        lat_microdeg = _safe(loc.get("lat_microdeg"), 0)
        lon_microdeg = _safe(loc.get("lon_microdeg"), 0)

        return {
            "record_id": _safe(record.get("record_id"), 0),
            "server_timestamp_ms": _safe(record.get("server_timestamp_ms"), 0),
            "device_id": (record.get("device_id") or "")[:8],
            "source": source,
            "source_label": _SOURCE_LABELS.get(source, source),
            "location": {
                "lat": lat_microdeg / 1_000_000,
                "lon": lon_microdeg / 1_000_000,
                "accuracy_m": _safe(loc.get("accuracy_m"), 0),
            },
            "trajectory": {
                "bearing_deg": _safe(hit.get("bearing_deg"), 0),
                "bearing_before_deg": _safe(hit.get("bearing_before_deg"), 0),
                "bearing_after_deg": _safe(hit.get("bearing_after_deg"), 0),
                "speed_mps": _safe(hit.get("speed_mps"), 0),
            },
            "pattern": {
                "severity": _safe(pat.get("severity"), 0),
                "peak_vertical_mg": _safe(pat.get("peak_vertical_mg"), 0),
                "peak_lateral_mg": _safe(pat.get("peak_lateral_mg"), 0),
                "duration_ms": _safe(pat.get("duration_ms"), 0),
                "baseline_mg": _safe(pat.get("baseline_mg"), 0),
                "peak_to_baseline_ratio": _safe(pat.get("peak_to_baseline_ratio"), 0),
                "waveform_samples": len(pat.get("waveform_vertical") or []),
                "waveform_vertical": pat.get("waveform_vertical") or [],
                "waveform_lateral": pat.get("waveform_lateral") or [],
            },
            "timestamp_ms": _safe(hit.get("timestamp_ms"), 0),
        }
    except Exception:
        log.warning("hit_to_detail_failed", record_id=record.get("record_id"),
                    exc_info=True)
        return None


@router.get("/hits/recent")
async def get_recent_hits(
    limit: int = Query(default=100, ge=1, le=1000),
) -> JSONResponse:
    """Return the most recent individual hits with full detail.

    Includes position, trajectory (bearings), accelerometer pattern,
    and waveform data — everything needed to inspect what was received.
    """
    from server.main import get_storage

    raw_hits = get_storage().read_all_hits()

    # Sort by server timestamp descending (most recent first).
    raw_hits.sort(key=lambda r: r.get("server_timestamp_ms", 0), reverse=True)
    raw_hits = raw_hits[:limit]

    details = []
    skipped = 0
    for r in raw_hits:
        d = _hit_to_detail(r)
        if d is not None:
            details.append(d)
        else:
            skipped += 1

    return JSONResponse(content={"hits": details, "total": len(details),
                                 "skipped": skipped})


@router.get("/debug/hits")
async def debug_hits(
    limit: int = Query(default=5, ge=1, le=20),
) -> JSONResponse:
    """Return raw storage records for debugging (no transformation)."""
    from server.main import get_storage

    raw_hits = get_storage().read_all_hits()
    raw_hits.sort(key=lambda r: r.get("server_timestamp_ms", 0), reverse=True)
    raw_hits = raw_hits[:limit]

    # Truncate waveforms to keep response small.
    for r in raw_hits:
        hit = r.get("hit") or {}
        pat = hit.get("pattern") or {}
        for key in ("waveform_vertical", "waveform_lateral"):
            wf = pat.get(key)
            if isinstance(wf, list) and len(wf) > 5:
                pat[key] = wf[:5] + [f"...{len(wf)} total"]

    return JSONResponse(content={"raw_records": raw_hits, "count": len(raw_hits)})
