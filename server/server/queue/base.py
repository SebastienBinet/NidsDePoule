"""Queue interface (port) for hit record ingestion."""

from __future__ import annotations

from typing import Protocol, TYPE_CHECKING

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData


class HitQueue(Protocol):
    """Port: accepts hit records and delivers them to consumers."""

    async def put(self, record: ServerHitRecordData) -> None: ...

    async def get(self) -> ServerHitRecordData: ...

    def qsize(self) -> int: ...
