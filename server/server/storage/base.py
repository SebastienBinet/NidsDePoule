"""Storage interface (port) for persisting hit records."""

from __future__ import annotations

from typing import Protocol, TYPE_CHECKING

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData


class HitStorage(Protocol):
    """Port: persists hit records to durable storage."""

    async def store(self, record: ServerHitRecordData) -> None: ...

    async def store_batch(self, records: list[ServerHitRecordData]) -> None: ...
