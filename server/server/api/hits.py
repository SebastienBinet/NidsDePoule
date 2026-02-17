"""Hit report API endpoints.

This is the thin FastAPI adapter. It parses HTTP requests, converts protobuf
(or JSON) to internal models, and calls the processor.
"""

from __future__ import annotations

from fastapi import APIRouter, Request, Response

from server.core.models import (
    ClientMessageData,
    HitData,
    HitPatternData,
    LocationData,
)

router = APIRouter(prefix="/api/v1")


def _parse_json_hit(data: dict) -> HitData:
    """Parse a hit from JSON (for development/ASCII mode)."""
    loc = data.get("location", {})
    pat = data.get("pattern", {})
    return HitData(
        timestamp_ms=data.get("timestamp_ms", 0),
        location=LocationData(
            lat_microdeg=loc.get("lat_microdeg", 0),
            lon_microdeg=loc.get("lon_microdeg", 0),
            accuracy_m=loc.get("accuracy_m", 0),
        ),
        speed_mps=data.get("speed_mps", 0.0),
        bearing_deg=data.get("bearing_deg", 0.0),
        bearing_before_deg=data.get("bearing_before_deg", 0.0),
        bearing_after_deg=data.get("bearing_after_deg", 0.0),
        pattern=HitPatternData(
            severity=pat.get("severity", 0),
            peak_vertical_mg=pat.get("peak_vertical_mg", 0),
            peak_lateral_mg=pat.get("peak_lateral_mg", 0),
            duration_ms=pat.get("duration_ms", 0),
            waveform_vertical=tuple(pat.get("waveform_vertical", [])),
            waveform_lateral=tuple(pat.get("waveform_lateral", [])),
            baseline_mg=pat.get("baseline_mg", 0),
            peak_to_baseline_ratio=pat.get("peak_to_baseline_ratio", 0),
        ),
    )


def _parse_json_message(body: dict) -> ClientMessageData:
    """Parse a ClientMessage from JSON."""
    hit = None
    hits = []
    heartbeat_ts = None
    heartbeat_pending = 0

    if "hit" in body:
        hit = _parse_json_hit(body["hit"])
    if "batch" in body:
        hits = [_parse_json_hit(h) for h in body["batch"].get("hits", [])]
    if "heartbeat" in body:
        hb = body["heartbeat"]
        heartbeat_ts = hb.get("timestamp_ms", 0)
        heartbeat_pending = hb.get("pending_hits", 0)

    return ClientMessageData(
        protocol_version=body.get("protocol_version", 1),
        device_id=body.get("device_id", ""),
        app_version=body.get("app_version", 0),
        hit=hit,
        hits=hits,
        heartbeat_timestamp_ms=heartbeat_ts,
        heartbeat_pending_hits=heartbeat_pending,
    )


@router.post("/hits")
async def receive_hits(request: Request) -> Response:
    """Receive hit reports from the Android app.

    Accepts:
    - application/json: JSON format (development/ASCII mode)
    - application/x-protobuf: binary protobuf (future, production mode)
    """
    from server.main import get_processor

    processor = get_processor()
    body_bytes = await request.body()
    content_type = request.headers.get("content-type", "application/json")

    if "protobuf" in content_type:
        # TODO: Parse protobuf binary when compiled proto is available.
        # For now, return an error suggesting JSON mode.
        return Response(
            content='{"accepted": false, "error": "protobuf not yet supported, use application/json"}',
            status_code=415,
            media_type="application/json",
        )

    # JSON mode (default for Phase 1)
    import json

    try:
        body = json.loads(body_bytes)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return Response(
            content='{"accepted": false, "error": "invalid JSON"}',
            status_code=400,
            media_type="application/json",
        )

    msg = _parse_json_message(body)
    accepted, error, hits_stored = await processor.process_message(msg, len(body_bytes))

    status = 200 if accepted else 422
    if error and "too old" in error:
        status = 426

    result = {"accepted": accepted, "error": error, "hits_stored": hits_stored}
    return Response(
        content=json.dumps(result),
        status_code=status,
        media_type="application/json",
    )
