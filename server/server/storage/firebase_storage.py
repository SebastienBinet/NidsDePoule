"""Firebase Storage implementation.

Stores hit records as individual JSON objects in Firebase Cloud Storage,
keyed by YYYY/MM/DD/HH/<record_id>.json — same layout as S3HitStorage.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import TYPE_CHECKING

import structlog

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData

log = structlog.get_logger()


class FirebaseHitStorage:
    """HitStorage backed by Firebase Cloud Storage."""

    def __init__(self, bucket_name: str, credentials_json: str = "") -> None:
        import os

        import firebase_admin
        from firebase_admin import credentials, storage

        if not firebase_admin._apps:
            if credentials_json:
                cred = credentials.Certificate(json.loads(credentials_json))
            elif os.environ.get("GOOGLE_APPLICATION_CREDENTIALS"):
                cred = credentials.ApplicationDefault()
            else:
                raise RuntimeError(
                    "Firebase credentials not configured. "
                    "Set NIDS_STORAGE_FIREBASE_CREDENTIALS_JSON env var "
                    "(full service-account JSON as a string) or "
                    "GOOGLE_APPLICATION_CREDENTIALS (path to key file)."
                )
            firebase_admin.initialize_app(cred, {
                "storageBucket": bucket_name,
            })

        self._bucket = storage.bucket()

    # ------------------------------------------------------------------
    # Key helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _object_key(timestamp_ms: int, record_id: int) -> str:
        dt = datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc)
        return (
            f"{dt.year:04d}/{dt.month:02d}/{dt.day:02d}/"
            f"{dt.hour:02d}/{record_id}.json"
        )

    # ------------------------------------------------------------------
    # Serialisation (matches FileHitStorage / S3HitStorage JSON format)
    # ------------------------------------------------------------------

    @staticmethod
    def _serialize_record(record: ServerHitRecordData) -> bytes:
        data = {
            "server_timestamp_ms": record.server_timestamp_ms,
            "protocol_version": record.protocol_version,
            "device_id": record.device_id,
            "app_version": record.app_version,
            "record_id": record.record_id,
            "source": record.source,
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
        return json.dumps(data, separators=(",", ":")).encode("utf-8")

    # ------------------------------------------------------------------
    # Public API (HitStorage protocol)
    # ------------------------------------------------------------------

    async def store(self, record: ServerHitRecordData) -> None:
        key = self._object_key(record.server_timestamp_ms, record.record_id)
        body = self._serialize_record(record)
        blob = self._bucket.blob(key)
        blob.upload_from_string(body, content_type="application/json")
        log.debug("firebase_hit_written", record_id=record.record_id, key=key)

    async def store_batch(self, records: list[ServerHitRecordData]) -> None:
        for record in records:
            await self.store(record)

    def read_all_hits(self) -> list[dict]:
        hits: list[dict] = []
        for blob in self._bucket.list_blobs():
            if not blob.name.endswith(".json"):
                continue
            try:
                data = blob.download_as_bytes()
                hits.append(json.loads(data))
            except (json.JSONDecodeError, Exception) as exc:
                log.warning("firebase_read_failed", key=blob.name, error=str(exc))
        return hits

    def delete_hits(self, record_ids: set[int]) -> int:
        deleted = 0
        for blob in self._bucket.list_blobs():
            if not blob.name.endswith(".json"):
                continue
            try:
                rid = int(blob.name.rsplit("/", 1)[-1].removesuffix(".json"))
            except ValueError:
                continue
            if rid not in record_ids:
                continue
            try:
                blob.delete()
                deleted += 1
                log.info("firebase_hit_deleted", key=blob.name, record_id=rid)
            except Exception as exc:
                log.warning("firebase_delete_failed", key=blob.name, error=str(exc))
        return deleted
