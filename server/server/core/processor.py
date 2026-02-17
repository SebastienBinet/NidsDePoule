"""Hit processor — validates, enriches, and enqueues incoming hits.

This is the core business logic. It depends on HitQueue and HitStorage
protocols, not concrete implementations.
"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING

import structlog

if TYPE_CHECKING:
    from server.core.models import ClientMessageData, HitData, ServerHitRecordData
    from server.core.stats import ServerStats
    from server.queue.base import HitQueue
    from server.storage.base import HitStorage

log = structlog.get_logger()

# Minimum protocol version the server accepts.
MIN_PROTOCOL_VERSION = 1


class HitProcessor:
    """Validates incoming client messages and enqueues hits for storage."""

    def __init__(
        self,
        queue: HitQueue,
        storage: HitStorage,
        stats: ServerStats,
    ) -> None:
        self._queue = queue
        self._storage = storage
        self._stats = stats
        self._next_record_id = 1

    async def process_message(self, msg: ClientMessageData, size_bytes: int) -> tuple[bool, str, int]:
        """Process a client message. Returns (accepted, error_message, hits_stored)."""
        if msg.protocol_version < MIN_PROTOCOL_VERSION:
            self._stats.record_rejected()
            return False, f"protocol_version {msg.protocol_version} too old, minimum is {MIN_PROTOCOL_VERSION}", 0

        if not msg.device_id:
            self._stats.record_rejected()
            return False, "device_id is required", 0

        # Handle heartbeat
        if msg.heartbeat_timestamp_ms is not None:
            self._stats.record_heartbeat(msg.device_id)
            log.debug("heartbeat_received", device=msg.device_id[:8],
                      pending=msg.heartbeat_pending_hits)
            return True, "", 0

        # Collect hits (single or batch)
        hits: list[HitData] = []
        is_batch = False
        if msg.hit is not None:
            hits = [msg.hit]
        elif msg.hits:
            hits = msg.hits
            is_batch = True

        if not hits:
            return True, "", 0

        # Record stats
        if is_batch:
            self._stats.record_batch(msg.device_id, len(hits), size_bytes)
        else:
            self._stats.record_hit(msg.device_id, size_bytes)

        # Enqueue each hit
        stored = 0
        now_ms = int(time.time() * 1000)
        for hit in hits:
            from server.core.models import ServerHitRecordData

            record = ServerHitRecordData(
                server_timestamp_ms=now_ms,
                protocol_version=msg.protocol_version,
                device_id=msg.device_id,
                app_version=msg.app_version,
                hit=hit,
                record_id=self._next_record_id,
            )
            self._next_record_id += 1

            try:
                await self._queue.put(record)
                stored += 1
            except Exception:
                log.error("queue_put_failed", device=msg.device_id[:8],
                          record_id=record.record_id, exc_info=True)
                self._stats.record_rejected()

        self._stats.update_queue_depth(self._queue.qsize())

        if stored > 0:
            log.info("hits_enqueued", device=msg.device_id[:8],
                     count=stored, batch=is_batch)

        return True, "", stored

    async def run_storage_consumer(self) -> None:
        """Consume from the queue and write to storage. Runs as a background task."""
        log.info("storage_consumer_started")
        while True:
            record = await self._queue.get()
            try:
                await self._storage.store(record)
                self._stats.record_stored(1, 0)
                self._stats.update_queue_depth(self._queue.qsize())
                log.debug("hit_stored", record_id=record.record_id,
                          device=record.device_id[:8])
            except Exception:
                log.error("storage_write_failed", record_id=record.record_id,
                          exc_info=True)
                self._stats.record_storage_error()
