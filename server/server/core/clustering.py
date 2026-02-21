"""Pothole clustering — groups nearby hits into pothole entities.

Uses a greedy spatial clustering approach: for each hit, find the nearest
existing cluster within CLUSTER_RADIUS_M. If found, merge; otherwise create
a new cluster.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field


# Maximum distance (meters) for two hits to be considered the same pothole.
CLUSTER_RADIUS_M = 15.0

# Earth radius in meters (for Haversine).
_EARTH_R = 6_371_000.0


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in meters between two points."""
    rlat1, rlat2 = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(rlat1) * math.cos(rlat2) * math.sin(dlon / 2) ** 2
    return 2 * _EARTH_R * math.asin(math.sqrt(a))


@dataclass
class PotholeCluster:
    lat: float = 0.0
    lon: float = 0.0
    hit_count: int = 0
    severity_sum: int = 0
    severity_max: int = 0
    peak_mg_max: int = 0
    first_seen_ms: int = 0
    last_seen_ms: int = 0
    devices: set[str] = field(default_factory=set)
    manual_reports: int = 0
    sources: dict[str, int] = field(default_factory=dict)

    # Running sums for centroid update.
    _lat_sum: float = 0.0
    _lon_sum: float = 0.0

    def add_hit(self, lat: float, lon: float, severity: int, peak_mg: int,
                timestamp_ms: int, device_id: str, source: str = "auto") -> None:
        self.hit_count += 1
        self._lat_sum += lat
        self._lon_sum += lon
        self.lat = self._lat_sum / self.hit_count
        self.lon = self._lon_sum / self.hit_count
        self.severity_sum += severity
        self.severity_max = max(self.severity_max, severity)
        self.peak_mg_max = max(self.peak_mg_max, peak_mg)
        if self.first_seen_ms == 0 or timestamp_ms < self.first_seen_ms:
            self.first_seen_ms = timestamp_ms
        if timestamp_ms > self.last_seen_ms:
            self.last_seen_ms = timestamp_ms
        self.devices.add(device_id)
        self.sources[source] = self.sources.get(source, 0) + 1
        if source != "auto":
            self.manual_reports += 1

    @property
    def severity_avg(self) -> float:
        return self.severity_sum / self.hit_count if self.hit_count else 0

    @property
    def confidence(self) -> float:
        """Confidence score 0-1 based on hit count, device diversity, and manual reports."""
        device_factor = min(len(self.devices) / 3, 1.0)
        count_factor = min(self.hit_count / 5, 1.0)
        # Manual reports from a human are a strong signal.
        manual_factor = min(self.manual_reports / 2, 1.0)
        base = device_factor * 0.5 + count_factor * 0.3 + manual_factor * 0.2
        return round(min(base, 1.0), 2)

    def to_geojson_feature(self) -> dict:
        return {
            "type": "Feature",
            "geometry": {
                "type": "Point",
                "coordinates": [round(self.lon, 6), round(self.lat, 6)],
            },
            "properties": {
                "hit_count": self.hit_count,
                "severity_avg": round(self.severity_avg, 1),
                "severity_max": self.severity_max,
                "peak_mg_max": self.peak_mg_max,
                "confidence": self.confidence,
                "devices": len(self.devices),
                "first_seen_ms": self.first_seen_ms,
                "last_seen_ms": self.last_seen_ms,
                "manual_reports": self.manual_reports,
                "sources": dict(self.sources),
            },
        }


def cluster_hits(raw_hits: list[dict], radius_m: float = CLUSTER_RADIUS_M) -> list[PotholeCluster]:
    """Cluster raw hit dicts into PotholeCluster objects.

    Each raw_hit dict comes from FileHitStorage.read_all_hits() and has the
    structure written by _serialize_record().
    """
    clusters: list[PotholeCluster] = []

    for record in raw_hits:
        hit = record.get("hit", {})
        loc = hit.get("location", {})
        pat = hit.get("pattern", {})

        lat_microdeg = loc.get("lat_microdeg", 0)
        lon_microdeg = loc.get("lon_microdeg", 0)
        if lat_microdeg == 0 and lon_microdeg == 0:
            continue

        lat = lat_microdeg / 1_000_000
        lon = lon_microdeg / 1_000_000
        severity = pat.get("severity", 0)
        peak_mg = pat.get("peak_vertical_mg", 0)
        timestamp_ms = hit.get("timestamp_ms", 0)
        device_id = record.get("device_id", "")
        source = record.get("source", "auto")

        # Find nearest cluster.
        best_cluster = None
        best_dist = radius_m + 1
        for c in clusters:
            d = _haversine_m(lat, lon, c.lat, c.lon)
            if d < best_dist:
                best_dist = d
                best_cluster = c

        if best_cluster is not None and best_dist <= radius_m:
            best_cluster.add_hit(lat, lon, severity, peak_mg, timestamp_ms, device_id, source)
        else:
            c = PotholeCluster()
            c.add_hit(lat, lon, severity, peak_mg, timestamp_ms, device_id, source)
            clusters.append(c)

    return clusters


def clusters_to_geojson(clusters: list[PotholeCluster]) -> dict:
    """Convert clusters to a GeoJSON FeatureCollection."""
    return {
        "type": "FeatureCollection",
        "features": [c.to_geojson_feature() for c in clusters],
    }
