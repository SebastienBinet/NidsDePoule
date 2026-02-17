"""Server configuration.

Loads from config.yaml if present, with environment variable overrides.
Environment variables use the pattern: NIDS_<SECTION>_<KEY> (uppercase).
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 8000
    env: str = "dev"  # "dev" or "prod"


@dataclass
class QueueConfig:
    backend: str = "asyncio"  # "asyncio" or "redis"
    max_size: int = 10_000
    redis_url: str = "redis://localhost:6379"


@dataclass
class StorageConfig:
    backend: str = "file"  # "file" or "s3"
    base_dir: str = "data/incoming"
    s3_bucket: str = ""


@dataclass
class LimitsConfig:
    max_hits_per_device_per_hour: int = 600
    max_waveform_samples: int = 150
    max_batch_size: int = 100
    active_window_seconds: float = 120.0


@dataclass
class LoggingConfig:
    level: str = "info"
    format: str = "console"  # "console" or "json"
    file: str = ""


@dataclass
class AppConfig:
    server: ServerConfig = field(default_factory=ServerConfig)
    queue: QueueConfig = field(default_factory=QueueConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)
    limits: LimitsConfig = field(default_factory=LimitsConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)


def _apply_env_overrides(config: AppConfig) -> None:
    """Override config values from environment variables."""
    mapping = {
        "NIDS_SERVER_HOST": lambda v: setattr(config.server, "host", v),
        "NIDS_SERVER_PORT": lambda v: setattr(config.server, "port", int(v)),
        "NIDS_SERVER_ENV": lambda v: setattr(config.server, "env", v),
        "NIDS_QUEUE_BACKEND": lambda v: setattr(config.queue, "backend", v),
        "NIDS_QUEUE_MAX_SIZE": lambda v: setattr(config.queue, "max_size", int(v)),
        "NIDS_QUEUE_REDIS_URL": lambda v: setattr(config.queue, "redis_url", v),
        "NIDS_STORAGE_BACKEND": lambda v: setattr(config.storage, "backend", v),
        "NIDS_STORAGE_BASE_DIR": lambda v: setattr(config.storage, "base_dir", v),
        "NIDS_STORAGE_S3_BUCKET": lambda v: setattr(config.storage, "s3_bucket", v),
        "NIDS_LIMITS_MAX_BATCH_SIZE": lambda v: setattr(config.limits, "max_batch_size", int(v)),
        "NIDS_LIMITS_ACTIVE_WINDOW": lambda v: setattr(config.limits, "active_window_seconds", float(v)),
        "NIDS_LOG_LEVEL": lambda v: setattr(config.logging, "level", v),
        "NIDS_LOG_FORMAT": lambda v: setattr(config.logging, "format", v),
        "NIDS_LOG_FILE": lambda v: setattr(config.logging, "file", v),
    }
    for env_key, setter in mapping.items():
        val = os.environ.get(env_key)
        if val is not None:
            setter(val)


def load_config(config_path: str | Path | None = None) -> AppConfig:
    """Load configuration from YAML file + environment overrides."""
    config = AppConfig()

    # Try to load YAML
    if config_path is None:
        config_path = Path("config.yaml")
    else:
        config_path = Path(config_path)

    if config_path.exists():
        with open(config_path) as f:
            raw = yaml.safe_load(f) or {}

        if "server" in raw:
            for k, v in raw["server"].items():
                if hasattr(config.server, k):
                    setattr(config.server, k, v)
        if "queue" in raw:
            for k, v in raw["queue"].items():
                if hasattr(config.queue, k):
                    setattr(config.queue, k, v)
        if "storage" in raw:
            for k, v in raw["storage"].items():
                if hasattr(config.storage, k):
                    setattr(config.storage, k, v)
        if "limits" in raw:
            for k, v in raw["limits"].items():
                if hasattr(config.limits, k):
                    setattr(config.limits, k, v)
        if "logging" in raw:
            for k, v in raw["logging"].items():
                if hasattr(config.logging, k):
                    setattr(config.logging, k, v)

    # Environment overrides always win
    _apply_env_overrides(config)
    return config
