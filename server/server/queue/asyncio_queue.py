"""In-process asyncio queue implementation of HitQueue."""

from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData


class AsyncioHitQueue:
    """HitQueue backed by asyncio.Queue. Zero dependencies."""

    def __init__(self, max_size: int = 10_000) -> None:
        self._queue: asyncio.Queue[ServerHitRecordData] = asyncio.Queue(maxsize=max_size)

    async def put(self, record: ServerHitRecordData) -> None:
        await self._queue.put(record)

    async def get(self) -> ServerHitRecordData:
        return await self._queue.get()

    def qsize(self) -> int:
        return self._queue.qsize()
