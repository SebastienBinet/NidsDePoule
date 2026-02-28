"""Pothole cluster and individual hit API endpoints."""

from __future__ import annotations

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse

from server.core.clustering import cluster_hits, clusters_to_geojson

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


def _hit_to_detail(record: dict) -> dict:
    """Convert a raw binpb record to a detailed JSON object for the UI."""
    hit = record.get("hit", {})
    loc = hit.get("location", {})
    pat = hit.get("pattern", {})
    source = record.get("source", "auto")

    lat_microdeg = loc.get("lat_microdeg", 0)
    lon_microdeg = loc.get("lon_microdeg", 0)

    return {
        "record_id": record.get("record_id", 0),
        "server_timestamp_ms": record.get("server_timestamp_ms", 0),
        "device_id": record.get("device_id", "")[:8],
        "source": source,
        "source_label": _SOURCE_LABELS.get(source, source),
        "location": {
            "lat": lat_microdeg / 1_000_000,
            "lon": lon_microdeg / 1_000_000,
            "accuracy_m": loc.get("accuracy_m", 0),
        },
        "trajectory": {
            "bearing_deg": hit.get("bearing_deg", 0),
            "bearing_before_deg": hit.get("bearing_before_deg", 0),
            "bearing_after_deg": hit.get("bearing_after_deg", 0),
            "speed_mps": hit.get("speed_mps", 0),
        },
        "pattern": {
            "severity": pat.get("severity", 0),
            "peak_vertical_mg": pat.get("peak_vertical_mg", 0),
            "peak_lateral_mg": pat.get("peak_lateral_mg", 0),
            "duration_ms": pat.get("duration_ms", 0),
            "baseline_mg": pat.get("baseline_mg", 0),
            "peak_to_baseline_ratio": pat.get("peak_to_baseline_ratio", 0),
            "waveform_samples": len(pat.get("waveform_vertical", [])),
            "waveform_vertical": pat.get("waveform_vertical", []),
            "waveform_lateral": pat.get("waveform_lateral", []),
        },
        "timestamp_ms": hit.get("timestamp_ms", 0),
    }


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

    details = [_hit_to_detail(r) for r in raw_hits]
    return JSONResponse(content={"hits": details, "total": len(details)})
