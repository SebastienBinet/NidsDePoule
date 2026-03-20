"""Shared test fixtures."""

from __future__ import annotations

from typing import TYPE_CHECKING

import pytest
from httpx import ASGITransport, AsyncClient

import server.main as main_module
from server.config import AppConfig
from server.core.processor import HitProcessor
from server.core.stats import ServerStats
from server.queue.asyncio_queue import AsyncioHitQueue
from server.storage.file_storage import FileHitStorage

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData


# ---------------------------------------------------------------------------
# SpyHitStorage — records all store() calls for integration tests
# ---------------------------------------------------------------------------

class SpyHitStorage:
    """In-memory storage that records all calls for assertions."""

    def __init__(self) -> None:
        self.stored_records: list[ServerHitRecordData] = []
        self.fail_next: bool = False

    async def store(self, record: ServerHitRecordData) -> None:
        if self.fail_next:
            self.fail_next = False
            raise RuntimeError("SpyHitStorage: deliberate failure")
        self.stored_records.append(record)

    async def store_batch(self, records: list[ServerHitRecordData]) -> None:
        for record in records:
            await self.store(record)

    def read_all_hits(self) -> list[dict]:
        results = []
        for r in self.stored_records:
            results.append({
                "server_timestamp_ms": r.server_timestamp_ms,
                "protocol_version": r.protocol_version,
                "device_id": r.device_id,
                "app_version": r.app_version,
                "record_id": r.record_id,
                "source": r.source,
                "hit": {
                    "timestamp_ms": r.hit.timestamp_ms,
                    "location": {
                        "lat_microdeg": r.hit.location.lat_microdeg,
                        "lon_microdeg": r.hit.location.lon_microdeg,
                        "accuracy_m": r.hit.location.accuracy_m,
                    },
                    "speed_mps": r.hit.speed_mps,
                    "bearing_deg": r.hit.bearing_deg,
                    "bearing_before_deg": r.hit.bearing_before_deg,
                    "bearing_after_deg": r.hit.bearing_after_deg,
                    "pattern": {
                        "severity": r.hit.pattern.severity,
                        "peak_vertical_mg": r.hit.pattern.peak_vertical_mg,
                        "peak_lateral_mg": r.hit.pattern.peak_lateral_mg,
                        "duration_ms": r.hit.pattern.duration_ms,
                        "waveform_vertical": list(r.hit.pattern.waveform_vertical),
                        "waveform_lateral": list(r.hit.pattern.waveform_lateral),
                        "baseline_mg": r.hit.pattern.baseline_mg,
                        "peak_to_baseline_ratio": r.hit.pattern.peak_to_baseline_ratio,
                    },
                },
            })
        return results

    def delete_hits(self, record_ids: set[int]) -> int:
        before = len(self.stored_records)
        self.stored_records = [r for r in self.stored_records if r.record_id not in record_ids]
        return before - len(self.stored_records)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(autouse=True)
def _init_server(tmp_path):
    """Initialize server singletons for every test, using a temp directory."""
    config = AppConfig()
    config.storage.base_dir = str(tmp_path / "data")
    config.logging.level = "warning"

    stats = ServerStats(active_window_seconds=config.limits.active_window_seconds)
    queue = AsyncioHitQueue(max_size=config.queue.max_size)
    storage = FileHitStorage(base_dir=config.storage.base_dir)
    processor = HitProcessor(queue=queue, storage=storage, stats=stats)

    # Patch module-level singletons
    main_module._config = config
    main_module._stats = stats
    main_module._processor = processor
    main_module._storage = storage

    yield

    # Cleanup
    main_module._config = None
    main_module._stats = None
    main_module._processor = None
    main_module._storage = None


@pytest.fixture
def spy_storage(tmp_path):
    """Replace the default FileHitStorage with a SpyHitStorage.

    The processor and storage singleton are both updated so that
    hits flow through the spy. Must be requested explicitly.
    """
    spy = SpyHitStorage()
    # Rewire processor to use the spy storage
    processor = main_module._processor
    processor._storage = spy
    main_module._storage = spy
    return spy


@pytest.fixture
async def client():
    from server.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
