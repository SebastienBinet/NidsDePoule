"""Shared test fixtures."""

from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient

import server.main as main_module
from server.config import AppConfig
from server.core.processor import HitProcessor
from server.core.stats import ServerStats
from server.queue.asyncio_queue import AsyncioHitQueue
from server.storage.file_storage import FileHitStorage


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

    yield

    # Cleanup
    main_module._config = None
    main_module._stats = None
    main_module._processor = None


@pytest.fixture
async def client():
    from server.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
