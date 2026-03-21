"""NidsDePoule server — main entry point.

This is the only file that knows about concrete implementations.
It wires together the core, queue, storage, and API layers.
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from zoneinfo import ZoneInfo

from pathlib import Path

import structlog
from fastapi import FastAPI
from fastapi.responses import HTMLResponse

from server.api.hits import router as hits_router
from server.api.monitoring import router as monitoring_router
from server.api.potholes import router as potholes_router
from server.config import AppConfig, load_config
from server.core.processor import HitProcessor
from server.core.stats import ServerStats
from server.queue.asyncio_queue import AsyncioHitQueue
from server.storage.base import HitStorage
from server.storage.file_storage import FileHitStorage

log = structlog.get_logger()

# Module-level singletons (set during startup)
_processor: HitProcessor | None = None
_stats: ServerStats | None = None
_config: AppConfig | None = None
_storage: HitStorage | None = None


def get_processor() -> HitProcessor:
    assert _processor is not None, "Server not initialized"
    return _processor


def get_stats() -> ServerStats:
    assert _stats is not None, "Server not initialized"
    return _stats


def get_config() -> AppConfig:
    assert _config is not None, "Server not initialized"
    return _config


def get_storage() -> HitStorage:
    assert _storage is not None, "Server not initialized"
    return _storage


def _setup_logging(config: AppConfig) -> None:
    """Configure structlog based on the logging config."""
    processors = [
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=False),
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
    global _processor, _stats, _config, _storage

    _config = load_config()
    _setup_logging(_config)

    log.info("server_starting",
             env=_config.server.env,
             storage_backend=_config.storage.backend,
             storage_dir=_config.storage.base_dir,
             queue_max_size=_config.queue.max_size)

    # Create components
    _stats = ServerStats(active_window_seconds=_config.limits.active_window_seconds)
    queue = AsyncioHitQueue(max_size=_config.queue.max_size)

    if _config.storage.backend == "s3":
        from server.storage.s3_storage import S3HitStorage
        _storage = S3HitStorage(
            bucket=_config.storage.s3_bucket,
            endpoint_url=_config.storage.s3_endpoint,
            region=_config.storage.s3_region,
        )
        log.info("storage_s3", bucket=_config.storage.s3_bucket,
                 endpoint=_config.storage.s3_endpoint)
    elif _config.storage.backend == "firebase":
        from server.storage.firebase_storage import FirebaseHitStorage
        _storage = FirebaseHitStorage(
            bucket_name=_config.storage.firebase_bucket,
            credentials_json=_config.storage.firebase_credentials_json,
        )
        log.info("storage_firebase", bucket=_config.storage.firebase_bucket)
    elif _config.storage.backend == "firestore":
        from server.storage.firestore_storage import FirestoreHitStorage
        _storage = FirestoreHitStorage(
            project_id=_config.storage.firestore_project_id,
            credentials_json=_config.storage.firestore_credentials_json,
            collection=_config.storage.firestore_collection,
        )
        log.info("storage_firestore",
                 project=_config.storage.firestore_project_id,
                 collection=_config.storage.firestore_collection)
    else:
        _storage = FileHitStorage(base_dir=_config.storage.base_dir)

    _processor = HitProcessor(queue=queue, storage=_storage, stats=_stats)

    # Start background storage consumer
    consumer_task = asyncio.create_task(_processor.run_storage_consumer())

    log.info("server_started",
             host=_config.server.host,
             port=_config.server.port)

    yield

    # Shutdown — flush buffered hits to Firestore before exiting.
    consumer_task.cancel()
    try:
        await consumer_task
    except asyncio.CancelledError:
        pass

    if _storage is not None and hasattr(_storage, "shutdown"):
        _storage.shutdown()

    log.info("server_stopped")


app = FastAPI(
    title="NidsDePoule",
    description="Pothole detection server",
    version="0.1.0",
    lifespan=lifespan,
)

app.include_router(hits_router)
app.include_router(monitoring_router)
app.include_router(potholes_router)

_WEB_DIR = Path(__file__).parent / "web"
_VERSION_LABEL = "v33"


@app.get("/", response_class=HTMLResponse)
async def dashboard():
    """Serve the pothole map dashboard."""
    html = (_WEB_DIR / "index.html").read_text()
    return html.replace("{{VERSION_LABEL}}", _VERSION_LABEL)
