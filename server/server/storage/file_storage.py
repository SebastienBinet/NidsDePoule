"""File-based storage implementation.

Stores hit records as:
- Length-prefixed binary protobuf in .binpb files (source of truth)
- JSON Lines index in .jsonl files (for debugging / quick queries)

Directory structure: base_dir/YYYY/MM/DD/HH/
"""

from __future__ import annotations

import json
import struct
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

import structlog

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData

log = structlog.get_logger()


class FileHitStorage:
    """HitStorage backed by date/hour partitioned files on disk."""

    def __init__(self, base_dir: str | Path) -> None:
        self._base_dir = Path(base_dir)
        self._base_dir.mkdir(parents=True, exist_ok=True)

    def _hour_dir(self, timestamp_ms: int) -> Path:
        """Return the directory for a given timestamp."""
        dt = datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc)
        path = self._base_dir / f"{dt.year:04d}" / f"{dt.month:02d}" / f"{dt.day:02d}" / f"{dt.hour:02d}"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _serialize_record(self, record: ServerHitRecordData) -> bytes:
        """Serialize a record to binary protobuf format.

        For Phase 1, we use a simple JSON-based binary format since we don't
        have compiled protobuf yet. The structure is:
        [4-byte little-endian length][JSON bytes]

        This will be replaced with actual protobuf serialization when we add
        the compiled proto dependency.
        """
        data = {
            "server_timestamp_ms": record.server_timestamp_ms,
            "protocol_version": record.protocol_version,
            "device_id": record.device_id,
            "app_version": record.app_version,
            "record_id": record.record_id,
            "hit": {
                "timestamp_ms": record.hit.timestamp_ms,
                "location": {
                    "lat_microdeg": record.hit.location.lat_microdeg,
                    "lon_microdeg": record.hit.location.lon_microdeg,
                    "accuracy_m": record.hit.location.accuracy_m,
                },
                "speed_mps": record.hit.speed_mps,
                "bearing_deg": record.hit.bearing_deg,
                "bearing_before_deg": record.hit.bearing_before_deg,
                "bearing_after_deg": record.hit.bearing_after_deg,
                "pattern": {
                    "severity": record.hit.pattern.severity,
                    "peak_vertical_mg": record.hit.pattern.peak_vertical_mg,
                    "peak_lateral_mg": record.hit.pattern.peak_lateral_mg,
                    "duration_ms": record.hit.pattern.duration_ms,
                    "waveform_vertical": list(record.hit.pattern.waveform_vertical),
                    "waveform_lateral": list(record.hit.pattern.waveform_lateral),
                    "baseline_mg": record.hit.pattern.baseline_mg,
                    "peak_to_baseline_ratio": record.hit.pattern.peak_to_baseline_ratio,
                },
            },
        }
        payload = json.dumps(data, separators=(",", ":")).encode("utf-8")
        return struct.pack("<I", len(payload)) + payload

    def _to_jsonl_entry(self, record: ServerHitRecordData) -> str:
        """Create a compact JSON Lines entry for the index."""
        dt = datetime.fromtimestamp(
            record.hit.timestamp_ms / 1000, tz=timezone.utc
        )
        entry = {
            "id": record.record_id,
            "ts": dt.isoformat(),
            "device": record.device_id[:8],
            "lat": record.hit.location.lat / 1_000_000 if isinstance(record.hit.location.lat_microdeg, int) else record.hit.location.lat,
            "lon": record.hit.location.lon / 1_000_000 if isinstance(record.hit.location.lon_microdeg, int) else record.hit.location.lon,
            "severity": record.hit.pattern.severity,
            "peak_mg": record.hit.pattern.peak_vertical_mg,
            "speed": round(record.hit.speed_mps, 1),
        }
        return json.dumps(entry, separators=(",", ":"))

    async def store(self, record: ServerHitRecordData) -> None:
        """Store a single hit record to disk."""
        hour_dir = self._hour_dir(record.server_timestamp_ms)

        # Append to binary file
        binpb_path = hour_dir / "hits.binpb"
        binary_data = self._serialize_record(record)
        with open(binpb_path, "ab") as f:
            f.write(binary_data)

        # Append to JSON Lines index
        jsonl_path = hour_dir / "hits.jsonl"
        jsonl_entry = self._to_jsonl_entry(record)
        with open(jsonl_path, "a") as f:
            f.write(jsonl_entry + "\n")

        log.debug("hit_written", record_id=record.record_id,
                  path=str(hour_dir))

    async def store_batch(self, records: list[ServerHitRecordData]) -> None:
        """Store a batch of hit records."""
        for record in records:
            await self.store(record)
