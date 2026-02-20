"""NidsDePoule server — main entry point.

This is the only file that knows about concrete implementations.
It wires together the core, queue, storage, and API layers.
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI

from server.api.hits import router as hits_router
from server.api.monitoring import router as monitoring_router
from server.config import AppConfig, load_config
from server.core.processor import HitProcessor
from server.core.stats import ServerStats
from server.queue.asyncio_queue import AsyncioHitQueue
from server.storage.file_storage import FileHitStorage

log = structlog.get_logger()

# Module-level singletons (set during startup)
_processor: HitProcessor | None = None
_stats: ServerStats | None = None
_config: AppConfig | None = None


def get_processor() -> HitProcessor:
    assert _processor is not None, "Server not initialized"
    return _processor


def get_stats() -> ServerStats:
    assert _stats is not None, "Server not initialized"
    return _stats


def get_config() -> AppConfig:
    assert _config is not None, "Server not initialized"
    return _config


def _setup_logging(config: AppConfig) -> None:
    """Configure structlog based on the logging config."""
    processors = [
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
    ]

    if config.logging.format == "json":
        processors.append(structlog.processors.JSONRenderer())
    else:
        processors.append(structlog.dev.ConsoleRenderer())

    structlog.configure(
        processors=processors,
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelName(config.logging.level.upper()),
        ),
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application startup and shutdown."""
    global _processor, _stats, _config

    _config = load_config()
    _setup_logging(_config)

    log.info("server_starting",
             env=_config.server.env,
             storage_dir=_config.storage.base_dir,
             queue_max_size=_config.queue.max_size)

    # Create components
    _stats = ServerStats(active_window_seconds=_config.limits.active_window_seconds)
    queue = AsyncioHitQueue(max_size=_config.queue.max_size)
    storage = FileHitStorage(base_dir=_config.storage.base_dir)
    _processor = HitProcessor(queue=queue, storage=storage, stats=_stats)

    # Start background storage consumer
    consumer_task = asyncio.create_task(_processor.run_storage_consumer())

    log.info("server_started",
             host=_config.server.host,
             port=_config.server.port)

    yield

    # Shutdown
    consumer_task.cancel()
    try:
        await consumer_task
    except asyncio.CancelledError:
        pass
    log.info("server_stopped")


app = FastAPI(
    title="NidsDePoule",
    description="Pothole detection server",
    version="0.1.0",
    lifespan=lifespan,
)

app.include_router(hits_router)
app.include_router(monitoring_router)
