"""NidsDePoule server — core internal data models.

These are plain dataclasses with no framework dependencies.
Protobuf objects are converted to/from these at the boundary.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class LocationData:
    lat_microdeg: int
    lon_microdeg: int
    accuracy_m: int = 0

    @property
    def lat(self) -> float:
        return self.lat_microdeg / 1_000_000

    @property
    def lon(self) -> float:
        return self.lon_microdeg / 1_000_000


@dataclass(frozen=True)
class HitPatternData:
    severity: int = 0
    peak_vertical_mg: int = 0
    peak_lateral_mg: int = 0
    duration_ms: int = 0
    waveform_vertical: tuple[int, ...] = ()
    waveform_lateral: tuple[int, ...] = ()
    baseline_mg: int = 0
    peak_to_baseline_ratio: int = 0


@dataclass(frozen=True)
class HitData:
    timestamp_ms: int
    location: LocationData
    speed_mps: float
    bearing_deg: float
    pattern: HitPatternData
    bearing_before_deg: float = 0.0
    bearing_after_deg: float = 0.0


@dataclass(frozen=True)
class ClientMessageData:
    protocol_version: int
    device_id: str
    app_version: int
    hit: HitData | None = None
    hits: list[HitData] = field(default_factory=list)
    heartbeat_timestamp_ms: int | None = None
    heartbeat_pending_hits: int = 0


@dataclass
class ServerHitRecordData:
    server_timestamp_ms: int
    protocol_version: int
    device_id: str
    app_version: int
    hit: HitData
    record_id: int = 0
