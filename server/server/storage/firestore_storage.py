"""Firestore storage implementation.

Stores hit records as documents in a Firestore collection.
Uses the free Spark plan (no credit card required).

All reads are served from an in-memory cache that is populated once at
startup.  This avoids burning through the Spark-plan 50 k reads/day
quota (the dashboard polls every second).

Two tunable limits protect against quota exhaustion:
- ``max_docs``: max documents kept in the collection / cache.  When a
  write would exceed this, the oldest document is deleted first.
- ``max_writes``: max Firestore *writes* (set + delete) per server
  lifetime.  Once reached, further writes are silently dropped until
  the server is restarted.
"""

from __future__ import annotations

import json
from typing import TYPE_CHECKING

import structlog

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData

log = structlog.get_logger()

# ---------------------------------------------------------------------------
# Defaults – tweak these as needed.
# ---------------------------------------------------------------------------
DEFAULT_MAX_DOCS = 5_000
DEFAULT_MAX_WRITES = 5_000


class FirestoreHitStorage:
    """HitStorage backed by Cloud Firestore (free Spark plan)."""

    def __init__(
        self,
        project_id: str,
        credentials_json: str = "",
        collection: str = "potholes",
        max_docs: int = DEFAULT_MAX_DOCS,
        max_writes: int = DEFAULT_MAX_WRITES,
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
        self._descending = firestore.Query.DESCENDING

        self._max_docs = max_docs
        self._max_writes = max_writes

        # Volatile counters (reset on restart).
        self._doc_count: int = 0
        self._write_count: int = 0

        # In-memory cache: record_id -> dict
        self._cache: dict[int, dict] = {}
        self._load_cache()

    # ------------------------------------------------------------------
    # Bootstrap
    # ------------------------------------------------------------------

    def _load_cache(self) -> None:
        """Read at most *max_docs* most-recent documents into the cache."""
        query = (
            self._db.collection(self._collection)
            .order_by("server_timestamp_ms", direction=self._descending)
            .limit(self._max_docs)
        )
        count = 0
        for doc in query.stream():
            try:
                data = doc.to_dict()
                rid = data.get("record_id", 0)
                self._cache[rid] = data
                count += 1
            except Exception as exc:
                log.warning("firestore_cache_load_failed", doc_id=doc.id, error=str(exc))

        self._doc_count = count
        log.info(
            "firestore_cache_loaded",
            count=count,
            max_docs=self._max_docs,
            max_writes=self._max_writes,
        )

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

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

    def _oldest_record_id(self) -> int | None:
        """Return the record_id with the smallest server_timestamp_ms in cache."""
        if not self._cache:
            return None
        return min(
            self._cache,
            key=lambda rid: self._cache[rid].get("server_timestamp_ms", 0),
        )

    def _evict_oldest(self) -> None:
        """Delete the oldest document from both cache and Firestore."""
        oldest = self._oldest_record_id()
        if oldest is None:
            return
        self._db.collection(self._collection).document(str(oldest)).delete()
        self._cache.pop(oldest, None)
        self._doc_count -= 1
        self._write_count += 1
        log.debug("firestore_evicted_oldest", record_id=oldest)

    def _writes_exhausted(self) -> bool:
        if self._write_count >= self._max_writes:
            log.warning(
                "firestore_writes_exhausted",
                write_count=self._write_count,
                max_writes=self._max_writes,
            )
            return True
        return False

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def store(self, record: ServerHitRecordData) -> None:
        if self._writes_exhausted():
            return

        # Evict oldest if at capacity.
        if self._doc_count >= self._max_docs:
            self._evict_oldest()
            if self._writes_exhausted():
                return

        data = self._to_dict(record)
        doc_id = str(record.record_id)
        self._db.collection(self._collection).document(doc_id).set(data)
        self._cache[record.record_id] = data
        self._doc_count += 1
        self._write_count += 1
        log.debug(
            "firestore_hit_written",
            record_id=record.record_id,
            doc_count=self._doc_count,
            write_count=self._write_count,
        )

    async def store_batch(self, records: list[ServerHitRecordData]) -> None:
        if self._writes_exhausted():
            return

        batch = self._db.batch()
        col = self._db.collection(self._collection)
        stored = 0

        for record in records:
            if self._write_count >= self._max_writes:
                break

            # Evict oldest if at capacity.
            if self._doc_count >= self._max_docs:
                oldest = self._oldest_record_id()
                if oldest is not None:
                    batch.delete(col.document(str(oldest)))
                    self._cache.pop(oldest, None)
                    self._doc_count -= 1
                    self._write_count += 1
                    if self._write_count >= self._max_writes:
                        break

            data = self._to_dict(record)
            batch.set(col.document(str(record.record_id)), data)
            self._cache[record.record_id] = data
            self._doc_count += 1
            self._write_count += 1
            stored += 1

        if stored > 0:
            batch.commit()
        log.debug(
            "firestore_batch_written",
            count=stored,
            doc_count=self._doc_count,
            write_count=self._write_count,
        )

    def read_all_hits(self) -> list[dict]:
        return list(self._cache.values())

    def delete_hits(self, record_ids: set[int]) -> int:
        if self._writes_exhausted():
            return 0

        deleted = 0
        batch = self._db.batch()
        col = self._db.collection(self._collection)
        for rid in record_ids:
            if self._write_count >= self._max_writes:
                break
            batch.delete(col.document(str(rid)))
            self._cache.pop(rid, None)
            self._doc_count -= 1
            self._write_count += 1
            deleted += 1
        if deleted > 0:
            batch.commit()
        log.info(
            "firestore_hits_deleted",
            count=deleted,
            doc_count=self._doc_count,
            write_count=self._write_count,
        )
        return deleted
