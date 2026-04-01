"""Pothole clustering — groups nearby hits into pothole entities.

Uses a greedy spatial clustering approach: for each hit, find the nearest
existing cluster within CLUSTER_RADIUS_M. If found, merge; otherwise create
a new cluster.

GPS accuracy weighting: hits with better accuracy contribute more to the
centroid position. Weight = 1 / max(accuracy_m, 3).

Bearing tracking: average bearing stored per cluster for direction filtering.
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


def _circular_mean_deg(angles: list[float]) -> float | None:
    """Compute the circular mean of angles in degrees. Returns None if empty."""
    if not angles:
        return None
    sin_sum = sum(math.sin(math.radians(a)) for a in angles)
    cos_sum = sum(math.cos(math.radians(a)) for a in angles)
    return math.degrees(math.atan2(sin_sum, cos_sum)) % 360


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
    bearings: list[float] = field(default_factory=list)

    # Running weighted sums for GPS-accuracy-weighted centroid.
    _lat_wsum: float = 0.0
    _lon_wsum: float = 0.0
    _weight_sum: float = 0.0

    def add_hit(self, lat: float, lon: float, severity: int, peak_mg: int,
                timestamp_ms: int, device_id: str, source: str = "auto",
                accuracy_m: int = 0, bearing_deg: float = 0.0) -> None:
        self.hit_count += 1

        # GPS accuracy weighting: better accuracy = higher weight
        weight = 1.0 / max(accuracy_m, 3)
        self._lat_wsum += lat * weight
        self._lon_wsum += lon * weight
        self._weight_sum += weight
        self.lat = self._lat_wsum / self._weight_sum
        self.lon = self._lon_wsum / self._weight_sum

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
        if bearing_deg != 0.0:
            self.bearings.append(bearing_deg)

    @property
    def severity_avg(self) -> float:
        return self.severity_sum / self.hit_count if self.hit_count else 0

    @property
    def bearing_avg(self) -> float | None:
        return _circular_mean_deg(self.bearings)

    @property
    def classification(self) -> str:
        """Classify as 'pothole' or 'infrastructure' based on hit patterns."""
        if len(self.devices) >= 5 and self.hit_count >= 10:
            hit_rate = self.hit_count / max(len(self.devices), 1)
            if hit_rate >= 3.0 and self.severity_max <= 2:
                return "infrastructure"
        return "pothole"

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
        bearing = self.bearing_avg
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
                "bearing_avg": round(bearing, 1) if bearing is not None else None,
                "bearing_count": len(self.bearings),
                "classification": self.classification,
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
        accuracy_m = loc.get("accuracy_m", 0) or 0
        bearing_deg = hit.get("bearing_deg", 0.0) or 0.0

        # Effective radius accounts for GPS inaccuracy
        effective_radius = radius_m + (accuracy_m * 0.5 if accuracy_m else 0)

        # Find nearest cluster.
        best_cluster = None
        best_dist = effective_radius + 1
        for c in clusters:
            d = _haversine_m(lat, lon, c.lat, c.lon)
            if d < best_dist:
                best_dist = d
                best_cluster = c

        if best_cluster is not None and best_dist <= effective_radius:
            best_cluster.add_hit(lat, lon, severity, peak_mg, timestamp_ms,
                                 device_id, source, accuracy_m, bearing_deg)
        else:
            c = PotholeCluster()
            c.add_hit(lat, lon, severity, peak_mg, timestamp_ms,
                       device_id, source, accuracy_m, bearing_deg)
            clusters.append(c)

    return clusters


def clusters_to_geojson(clusters: list[PotholeCluster]) -> dict:
    """Convert clusters to a GeoJSON FeatureCollection."""
    return {
        "type": "FeatureCollection",
        "features": [c.to_geojson_feature() for c in clusters],
    }
