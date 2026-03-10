"""Firestore storage implementation.

Stores hit records as documents in a Firestore collection.
Uses the free Spark plan (no credit card required).

All reads are served from an in-memory cache that is populated once at
startup.  This avoids burning through the Spark-plan 50 k reads/day
quota (the dashboard polls every second).
"""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

import structlog

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData

log = structlog.get_logger()


class FirestoreHitStorage:
    """HitStorage backed by Cloud Firestore (free Spark plan)."""

    def __init__(
        self,
        project_id: str,
        credentials_json: str = "",
        collection: str = "potholes",
    ) -> None:
        import firebase_admin
        from firebase_admin import credentials, firestore

        if not firebase_admin._apps:
            if credentials_json:
                cred = credentials.Certificate(json.loads(credentials_json))
            else:
                cred = credentials.ApplicationDefault()
            firebase_admin.initialize_app(cred, {"projectId": project_id})

        self._db = firestore.client()
        self._collection = collection

        # In-memory cache: record_id -> dict
        self._cache: dict[int, dict] = {}
        self._load_cache()

    def _load_cache(self) -> None:
        """One-time read of all documents into the in-memory cache."""
        count = 0
        for doc in self._db.collection(self._collection).stream():
            try:
                data = doc.to_dict()
                rid = data.get("record_id", 0)
                self._cache[rid] = data
                count += 1
            except Exception as exc:
                log.warning("firestore_cache_load_failed", doc_id=doc.id, error=str(exc))
        log.info("firestore_cache_loaded", count=count)

    @staticmethod
    def _to_dict(record: ServerHitRecordData) -> dict:
        return {
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

    async def store(self, record: ServerHitRecordData) -> None:
        data = self._to_dict(record)
        doc_id = str(record.record_id)
        self._db.collection(self._collection).document(doc_id).set(data)
        self._cache[record.record_id] = data
        log.debug("firestore_hit_written", record_id=record.record_id)

    async def store_batch(self, records: list[ServerHitRecordData]) -> None:
        batch = self._db.batch()
        col = self._db.collection(self._collection)
        for record in records:
            data = self._to_dict(record)
            batch.set(col.document(str(record.record_id)), data)
            self._cache[record.record_id] = data
        batch.commit()
        log.debug("firestore_batch_written", count=len(records))

    def read_all_hits(self) -> list[dict]:
        return list(self._cache.values())

    def delete_hits(self, record_ids: set[int]) -> int:
        deleted = 0
        batch = self._db.batch()
        col = self._db.collection(self._collection)
        for rid in record_ids:
            batch.delete(col.document(str(rid)))
            self._cache.pop(rid, None)
            deleted += 1
        batch.commit()
        log.info("firestore_hits_deleted", count=deleted)
        return deleted
