"""S3-compatible storage implementation (works with Cloudflare R2, Backblaze B2, etc.).

Stores hit records as individual JSON objects in S3, keyed by
YYYY/MM/DD/HH/<record_id>.json.  This avoids read-modify-write
races that would arise from appending to a single object.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import TYPE_CHECKING

import boto3
from botocore.config import Config as BotoConfig
from botocore.exceptions import ClientError

import structlog

if TYPE_CHECKING:
    from server.core.models import ServerHitRecordData

log = structlog.get_logger()


class S3HitStorage:
    """HitStorage backed by an S3-compatible bucket."""

    def __init__(
        self,
        bucket: str,
        endpoint_url: str = "",
        region: str = "auto",
    ) -> None:
        self._bucket = bucket
        kwargs: dict = {"region_name": region}
        if endpoint_url:
            kwargs["endpoint_url"] = endpoint_url
        # Disable automatic S3-specific checksums that R2 doesn't support.
        kwargs["config"] = BotoConfig(
            request_checksum_calculation="when_required",
            response_checksum_validation="when_required",
        )
        self._s3 = boto3.client("s3", **kwargs)

    # ------------------------------------------------------------------
    # Key helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _object_key(timestamp_ms: int, record_id: int) -> str:
        dt = datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc)
        return (
            f"{dt.year:04d}/{dt.month:02d}/{dt.day:02d}/"
            f"{dt.hour:02d}/{record_id}.json"
        )

    # ------------------------------------------------------------------
    # Serialisation (matches FileHitStorage JSON format)
    # ------------------------------------------------------------------

    @staticmethod
    def _serialize_record(record: ServerHitRecordData) -> bytes:
        data = {
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
        return json.dumps(data, separators=(",", ":")).encode("utf-8")

    # ------------------------------------------------------------------
    # Public API (HitStorage protocol)
    # ------------------------------------------------------------------

    async def store(self, record: ServerHitRecordData) -> None:
        key = self._object_key(record.server_timestamp_ms, record.record_id)
        body = self._serialize_record(record)
        self._s3.put_object(Bucket=self._bucket, Key=key, Body=body)
        log.debug("s3_hit_written", record_id=record.record_id, key=key)

    async def store_batch(self, records: list[ServerHitRecordData]) -> None:
        for record in records:
            await self.store(record)

    def read_all_hits(self) -> list[dict]:
        hits: list[dict] = []
        paginator = self._s3.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self._bucket):
            for obj in page.get("Contents", []):
                key = obj["Key"]
                if not key.endswith(".json"):
                    continue
                try:
                    resp = self._s3.get_object(Bucket=self._bucket, Key=key)
                    body = resp["Body"].read()
                    hits.append(json.loads(body))
                except (ClientError, json.JSONDecodeError) as exc:
                    log.warning("s3_read_failed", key=key, error=str(exc))
        return hits

    def delete_hits(self, record_ids: set[int]) -> int:
        deleted = 0
        paginator = self._s3.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self._bucket):
            for obj in page.get("Contents", []):
                key = obj["Key"]
                if not key.endswith(".json"):
                    continue
                # Extract record_id from key: .../NNNN.json
                try:
                    rid = int(key.rsplit("/", 1)[-1].removesuffix(".json"))
                except ValueError:
                    continue
                if rid not in record_ids:
                    continue
                try:
                    self._s3.delete_object(Bucket=self._bucket, Key=key)
                    deleted += 1
                    log.info("s3_hit_deleted", key=key, record_id=rid)
                except ClientError as exc:
                    log.warning("s3_delete_failed", key=key, error=str(exc))
        return deleted
