"""Pothole cluster and individual hit API endpoints."""

from __future__ import annotations

import json as json_mod
import traceback

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


def _num(val, default=0):
    """Coerce *val* to a number.  Handles None and Firestore special types."""
    if val is None:
        return default
    try:
        return float(val) if isinstance(val, float) else int(val)
    except (TypeError, ValueError):
        return default


def _int(val, default=0):
    """Coerce *val* to int."""
    if val is None:
        return default
    try:
        return int(val)
    except (TypeError, ValueError):
        return default


def _list(val):
    """Coerce *val* to a plain Python list (handles Firestore repeated fields)."""
    if val is None:
        return []
    try:
        return list(val)
    except (TypeError, ValueError):
        return []


@router.get("/potholes")
async def get_potholes() -> JSONResponse:
    """Return clustered potholes as a GeoJSON FeatureCollection.

    Reads all stored hits, clusters them spatially, and returns the result.
    """
    from server.main import get_storage

    try:
        raw_hits = get_storage().read_all_hits()
        clusters = cluster_hits(raw_hits)
        geojson = clusters_to_geojson(clusters)
    except Exception as exc:
        log.error("potholes_failed", exc_info=True)
        return JSONResponse(
            status_code=500,
            content={"error": f"{type(exc).__name__}: {exc}",
                     "type": "FeatureCollection", "features": []},
        )
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
    Every field is explicitly coerced to plain Python types so that
    Firestore-specific objects (DatetimeWithNanoseconds,
    RepeatedScalarContainer, etc.) never leak into the JSON response.
    """
    try:
        hit = record.get("hit") or {}
        loc = hit.get("location") or {}
        pat = hit.get("pattern") or {}
        source = str(record.get("source") or "auto")

        lat_microdeg = _int(loc.get("lat_microdeg"))
        lon_microdeg = _int(loc.get("lon_microdeg"))

        return {
            "record_id": _int(record.get("record_id")),
            "server_timestamp_ms": _int(record.get("server_timestamp_ms")),
            "device_id": str(record.get("device_id") or "")[:8],
            "source": source,
            "source_label": _SOURCE_LABELS.get(source, source),
            "location": {
                "lat": lat_microdeg / 1_000_000,
                "lon": lon_microdeg / 1_000_000,
                "accuracy_m": _int(loc.get("accuracy_m")),
            },
            "trajectory": {
                "bearing_deg": _num(hit.get("bearing_deg")),
                "bearing_before_deg": _num(hit.get("bearing_before_deg")),
                "bearing_after_deg": _num(hit.get("bearing_after_deg")),
                "speed_mps": _num(hit.get("speed_mps")),
            },
            "pattern": {
                "severity": _int(pat.get("severity")),
                "peak_vertical_mg": _int(pat.get("peak_vertical_mg")),
                "peak_lateral_mg": _int(pat.get("peak_lateral_mg")),
                "duration_ms": _int(pat.get("duration_ms")),
                "baseline_mg": _int(pat.get("baseline_mg")),
                "peak_to_baseline_ratio": _int(pat.get("peak_to_baseline_ratio")),
                "waveform_samples": len(_list(pat.get("waveform_vertical"))),
                "waveform_vertical": _list(pat.get("waveform_vertical")),
                "waveform_lateral": _list(pat.get("waveform_lateral")),
            },
            "timestamp_ms": _int(hit.get("timestamp_ms")),
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

    try:
        raw_hits = get_storage().read_all_hits()
    except Exception as exc:
        log.error("read_all_hits_failed", exc_info=True)
        return JSONResponse(
            status_code=500,
            content={"error": f"read_all_hits failed: {exc}",
                     "hits": [], "total": 0, "skipped": 0},
        )

    # Sort by server timestamp descending (most recent first).
    try:
        raw_hits.sort(
            key=lambda r: _int(r.get("server_timestamp_ms") if isinstance(r, dict) else 0),
            reverse=True,
        )
    except Exception as exc:
        log.error("sort_hits_failed", exc_info=True)
        # Continue with unsorted data rather than crashing.

    raw_hits = raw_hits[:limit]

    details = []
    skipped = 0
    for r in raw_hits:
        d = _hit_to_detail(r)
        if d is not None:
            details.append(d)
        else:
            skipped += 1

    # Final safety: make sure the response is actually JSON-serializable.
    try:
        json_mod.dumps({"hits": details, "total": len(details), "skipped": skipped})
    except (TypeError, ValueError) as exc:
        log.error("hits_json_serialize_failed", exc_info=True)
        return JSONResponse(
            status_code=500,
            content={"error": f"JSON serialization failed: {exc}",
                     "hits": [], "total": 0, "skipped": skipped},
        )

    return JSONResponse(content={"hits": details, "total": len(details),
                                 "skipped": skipped})


@router.get("/debug/hits")
async def debug_hits(
    limit: int = Query(default=5, ge=1, le=20),
) -> JSONResponse:
    """Return raw storage records for debugging (no transformation).

    Uses repr() for non-serializable Firestore types so this endpoint
    never returns a 500 — it always shows you what's in storage.
    """
    from server.main import get_storage

    try:
        raw_hits = get_storage().read_all_hits()
    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={"error": f"read_all_hits failed: {type(exc).__name__}: {exc}",
                     "traceback": traceback.format_exc()},
        )

    raw_hits.sort(
        key=lambda r: _int(r.get("server_timestamp_ms") if isinstance(r, dict) else 0),
        reverse=True,
    )
    raw_hits = raw_hits[:limit]

    # Sanitize: convert every value to JSON-safe types using repr() as fallback.
    def sanitize(obj):
        if obj is None or isinstance(obj, (bool, int, float, str)):
            return obj
        if isinstance(obj, dict):
            return {str(k): sanitize(v) for k, v in obj.items()}
        if isinstance(obj, (list, tuple)):
            items = [sanitize(v) for v in obj]
            if len(items) > 10:
                return items[:5] + [f"...{len(items)} total"]
            return items
        # Firestore special type — show type name + repr
        return f"<{type(obj).__name__}>: {repr(obj)}"

    sanitized = [sanitize(r) for r in raw_hits]
    return JSONResponse(content={"raw_records": sanitized, "count": len(sanitized)})
