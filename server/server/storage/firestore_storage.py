"""Firestore storage implementation.

Stores hit records as documents in a Firestore collection.
Uses the free Spark plan (no credit card required).

All reads are served from an in-memory cache.  Incoming hits are buffered
in memory and flushed to Firestore as a single "chunk" document every
``flush_interval_s`` seconds (default 300 = 5 min).  Each chunk document
contains a list of hits, which dramatically reduces the number of
Firestore documents (and therefore reads on startup and writes overall).

Two tunable limits protect against quota exhaustion:
- ``max_docs``: max *chunk* documents kept in the collection.  When a
  flush would exceed this, the oldest chunk is deleted first.
- ``max_writes``: max Firestore writes per server lifetime.  Once
  reached the buffer keeps accumulating in memory (still served to the
  dashboard) but is never persisted until the server restarts.
"""

from __future__ import annotations

import json
import time
import threading
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
DEFAULT_FLUSH_INTERVAL_S = 300  # 5 minutes


class FirestoreHitStorage:
    """HitStorage backed by Cloud Firestore (free Spark plan)."""

    def __init__(
        self,
        project_id: str,
        credentials_json: str = "",
        collection: str = "potholes",
        max_docs: int = DEFAULT_MAX_DOCS,
        max_writes: int = DEFAULT_MAX_WRITES,
        flush_interval_s: int = DEFAULT_FLUSH_INTERVAL_S,
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
        self._flush_interval_s = flush_interval_s

        # Volatile counters (reset on restart).
        self._doc_count: int = 0
        self._write_count: int = 0

        # In-memory cache: record_id -> hit dict  (individual hits).
        self._cache: dict[int, dict] = {}

        # Buffer of hits waiting to be flushed as a chunk document.
        self._buffer: list[dict] = []
        self._buffer_lock = threading.Lock()

        self._load_cache()
        self._start_flush_timer()

    # ------------------------------------------------------------------
    # Bootstrap
    # ------------------------------------------------------------------

    def _load_cache(self) -> None:
        """Read at most *max_docs* most-recent documents and unpack.

        Supports two document types:
        - **chunk docs** (have ``hits`` list): bulk-written by flush().
        - **tombstone docs** (have ``tombstone: True``): record deletions.
        - **legacy single-hit docs** (have ``record_id``): from before
          the chunk migration — loaded for backwards compatibility.
        """
        # We cannot order_by a field that doesn't exist on legacy docs,
        # so we stream all docs (up to max_docs) without ordering and let
        # the cache handle recency via eviction at flush time.
        col_ref = self._db.collection(self._collection)

        deleted_ids: set[int] = set()
        doc_count = 0
        hit_count = 0

        for doc in col_ref.limit(self._max_docs).stream():
            try:
                data = doc.to_dict()
                doc_count += 1

                # Tombstone document — collect IDs to exclude.
                if data.get("tombstone"):
                    for rid in data.get("deleted_record_ids", []):
                        deleted_ids.add(rid)
                    continue

                # Chunk document.
                hits = data.get("hits", [])
                if hits:
                    for h in hits:
                        rid = h.get("record_id", 0)
                        self._cache[rid] = h
                        hit_count += 1
                    continue

                # Legacy single-hit document (pre-chunk migration).
                rid = data.get("record_id")
                if rid is not None:
                    self._cache[rid] = data
                    hit_count += 1
            except Exception as exc:
                log.warning("firestore_cache_load_failed", doc_id=doc.id, error=str(exc))

        # Apply tombstones.
        for rid in deleted_ids:
            self._cache.pop(rid, None)

        self._doc_count = doc_count
        log.info(
            "firestore_cache_loaded",
            docs=doc_count,
            hits=hit_count,
            tombstones=len(deleted_ids),
            max_docs=self._max_docs,
            max_writes=self._max_writes,
        )

    # ------------------------------------------------------------------
    # Periodic flush timer
    # ------------------------------------------------------------------

    def _start_flush_timer(self) -> None:
        """Schedule the next flush as a daemon timer thread."""
        self._timer = threading.Timer(self._flush_interval_s, self._timer_flush)
        self._timer.daemon = True
        self._timer.start()

    def _timer_flush(self) -> None:
        """Called by the timer thread — flush then reschedule."""
        try:
            self.flush()
        except Exception:
            log.error("firestore_timer_flush_failed", exc_info=True)
        self._start_flush_timer()

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
        data = self._to_dict(record)
        # Add to cache immediately (dashboard sees it right away).
        self._cache[record.record_id] = data
        # Buffer for the next chunk flush.
        with self._buffer_lock:
            self._buffer.append(data)
        log.debug("firestore_hit_buffered", record_id=record.record_id,
                  buffer_size=len(self._buffer))

    async def store_batch(self, records: list[ServerHitRecordData]) -> None:
        for record in records:
            await self.store(record)

    def flush(self) -> None:
        """Write all buffered hits as a single chunk document to Firestore.

        Called periodically by the timer and on shutdown.
        """
        with self._buffer_lock:
            if not self._buffer:
                return
            hits_to_flush = list(self._buffer)
            self._buffer.clear()

        if self._writes_exhausted():
            log.warning("firestore_flush_skipped_writes_exhausted",
                        hits_dropped=len(hits_to_flush))
            return

        # Evict oldest chunk if at capacity.
        if self._doc_count >= self._max_docs:
            self._evict_oldest_chunk()
            if self._writes_exhausted():
                log.warning("firestore_flush_skipped_after_evict",
                            hits_dropped=len(hits_to_flush))
                return

        # Build the chunk document.
        now_ms = int(time.time() * 1000)
        chunk_doc = {
            "chunk_timestamp_ms": now_ms,
            "hit_count": len(hits_to_flush),
            "hits": hits_to_flush,
        }

        doc_id = f"chunk_{now_ms}"
        self._db.collection(self._collection).document(doc_id).set(chunk_doc)
        self._doc_count += 1
        self._write_count += 1
        log.info(
            "firestore_chunk_flushed",
            doc_id=doc_id,
            hits=len(hits_to_flush),
            doc_count=self._doc_count,
            write_count=self._write_count,
        )

    def _evict_oldest_chunk(self) -> None:
        """Delete the oldest chunk document from Firestore."""
        query = (
            self._db.collection(self._collection)
            .order_by("chunk_timestamp_ms")
            .limit(1)
        )
        for doc in query.stream():
            # Remove the individual hits from cache.
            data = doc.to_dict()
            for h in data.get("hits", []):
                self._cache.pop(h.get("record_id", 0), None)
            doc.reference.delete()
            self._doc_count -= 1
            self._write_count += 1
            log.debug("firestore_evicted_oldest_chunk", doc_id=doc.id)

    def shutdown(self) -> None:
        """Flush remaining buffer to Firestore.  Call on SIGTERM / app shutdown."""
        self._timer.cancel()
        log.info("firestore_shutdown_flush_starting",
                 buffered_hits=len(self._buffer))
        self.flush()
        log.info("firestore_shutdown_flush_done")

    def read_all_hits(self) -> list[dict]:
        return list(self._cache.values())

    def delete_hits(self, record_ids: set[int]) -> int:
        # Remove from in-memory cache immediately.
        deleted = 0
        for rid in record_ids:
            if self._cache.pop(rid, None) is not None:
                deleted += 1
        # Note: the hits may already be persisted inside chunk documents.
        # We don't rewrite chunks — the deleted hits simply won't appear
        # in the cache after the next restart (they'll be loaded then
        # filtered out ... actually they won't be filtered).
        # For now, to truly delete from Firestore we'd need to rewrite
        # the chunk.  Instead we track deletions and filter on load.
        if deleted > 0:
            self._save_tombstones(record_ids)
        log.info("firestore_hits_deleted", count=deleted)
        return deleted

    def _save_tombstones(self, record_ids: set[int]) -> None:
        """Persist a tombstone document so deleted hits stay deleted on reload."""
        if self._writes_exhausted():
            return
        now_ms = int(time.time() * 1000)
        doc_id = f"tombstone_{now_ms}"
        self._db.collection(self._collection).document(doc_id).set({
            "tombstone": True,
            "chunk_timestamp_ms": now_ms,
            "deleted_record_ids": list(record_ids),
        })
        self._doc_count += 1
        self._write_count += 1
        log.debug("firestore_tombstones_saved", count=len(record_ids))
