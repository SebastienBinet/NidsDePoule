#!/usr/bin/env python3
"""NidsDePoule circuit-based GPS simulator.

Drives simulated devices around a predefined circuit (loop of GPS waypoints)
to generate realistic pothole hit data.  Unlike the random-walk simulator,
this follows actual road coordinates so every lap is reproducible and all
data stays within a known geographic area.

Usage:
    # Single device, one lap around the cemetery circuit
    python -m tools.simulator.simulate_circuit \
        --server http://localhost:8000 \
        --circuit tools/simulator/circuits/nddn_cemetery_loop.json

    # 3 devices, 5 laps each, higher hit rate
    python -m tools.simulator.simulate_circuit \
        --server http://localhost:8000 \
        --circuit tools/simulator/circuits/nddn_cemetery_loop.json \
        --devices 3 --laps 5 --hits-per-minute 20
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import random
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

import httpx


# ---------------------------------------------------------------------------
# GPS math helpers
# ---------------------------------------------------------------------------

def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distance in metres between two lat/lon points (Haversine)."""
    R = 6_371_000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def calc_bearing(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Initial bearing in degrees [0, 360) from point 1 to point 2."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def interpolate_point(
    lat1: float, lon1: float, lat2: float, lon2: float, fraction: float
) -> tuple[float, float]:
    """Linearly interpolate between two GPS points (good enough for <100 m)."""
    return (
        lat1 + (lat2 - lat1) * fraction,
        lon1 + (lon2 - lon1) * fraction,
    )


# ---------------------------------------------------------------------------
# Circuit & waypoint model
# ---------------------------------------------------------------------------

@dataclass
class Waypoint:
    index: int
    lat: float
    lon: float
    wp_type: str          # start, straight, stop, turn_right, loop_end
    bearing_deg: float
    distance_to_next_m: float
    suggested_speed_kmh: float
    description: str = ""


@dataclass
class Circuit:
    name: str
    description: str
    total_distance_m: float
    waypoints: list[Waypoint]

    @classmethod
    def from_json(cls, path: str | Path) -> "Circuit":
        raw = json.loads(Path(path).read_text())
        wps = [
            Waypoint(
                index=w["index"],
                lat=w["lat"],
                lon=w["lon"],
                wp_type=w["type"],
                bearing_deg=w["bearing_deg"],
                distance_to_next_m=w["distance_to_next_m"],
                suggested_speed_kmh=w["suggested_speed_kmh"],
                description=w.get("description", ""),
            )
            for w in raw["waypoints"]
        ]
        return cls(
            name=raw["circuit_name"],
            description=raw["description"],
            total_distance_m=raw["circuit_stats"]["total_distance_m"],
            waypoints=wps,
        )


# ---------------------------------------------------------------------------
# Device simulation
# ---------------------------------------------------------------------------

@dataclass
class CircuitDevice:
    device_id: str
    circuit: Circuit
    current_wp_idx: int = 0
    segment_progress: float = 0.0   # 0..1 within current segment
    lat: float = 0.0
    lon: float = 0.0
    bearing: float = 0.0
    speed_mps: float = 0.0
    laps_completed: int = 0
    hits_sent: int = 0
    errors: int = 0

    def __post_init__(self):
        wp = self.circuit.waypoints[0]
        self.lat = wp.lat
        self.lon = wp.lon
        self.bearing = wp.bearing_deg


def generate_waveform(peak_mg: int, n_samples: int = 15) -> list[int]:
    """Generate a plausible pothole waveform centered on a peak."""
    waveform = []
    mid = n_samples // 2
    for i in range(n_samples):
        dist = abs(i - mid)
        factor = math.exp(-0.5 * (dist / max(mid * 0.4, 1)) ** 2)
        noise = random.randint(-100, 100)
        value = int(1000 + (peak_mg - 1000) * factor + noise)
        waveform.append(value)
    return waveform


def make_hit_payload(device: CircuitDevice, timestamp_ms: int, waveform_samples: int) -> dict:
    """Create a single hit JSON payload."""
    severity = random.choices([1, 2, 3], weights=[60, 30, 10])[0]
    peak_mg = {1: random.randint(2000, 3000), 2: random.randint(3000, 5000), 3: random.randint(5000, 8000)}[severity]
    baseline_mg = random.randint(950, 1100)
    ratio = int(peak_mg / baseline_mg * 100)

    waveform_v = generate_waveform(peak_mg, waveform_samples)
    waveform_l = generate_waveform(random.randint(200, 1500), waveform_samples)

    return {
        "timestamp_ms": timestamp_ms,
        "location": {
            "lat_microdeg": int(device.lat * 1_000_000),
            "lon_microdeg": int(device.lon * 1_000_000),
            "accuracy_m": random.randint(3, 15),
        },
        "speed_mps": round(device.speed_mps, 1),
        "bearing_deg": round(device.bearing, 1),
        "bearing_before_deg": round(device.bearing + random.uniform(-5, 5), 1),
        "bearing_after_deg": round(device.bearing + random.uniform(-5, 5), 1),
        "pattern": {
            "severity": severity,
            "peak_vertical_mg": peak_mg,
            "peak_lateral_mg": random.randint(200, 1500),
            "duration_ms": random.randint(50, 300),
            "waveform_vertical": waveform_v,
            "waveform_lateral": waveform_l,
            "baseline_mg": baseline_mg,
            "peak_to_baseline_ratio": ratio,
        },
    }


def advance_device(device: CircuitDevice, dt_seconds: float) -> bool:
    """Move the device along the circuit.  Returns True when a full lap is done."""
    wps = device.circuit.waypoints
    wp = wps[device.current_wp_idx]

    # Current target speed (with small random jitter for realism)
    target_kmh = wp.suggested_speed_kmh
    if wp.wp_type == "stop":
        # Decelerate to stop then wait briefly
        device.speed_mps = 0.0
        # Simulate a 2-second stop then advance to next waypoint
        device.segment_progress += dt_seconds / 2.0
        if device.segment_progress >= 1.0:
            device.segment_progress = 0.0
            device.current_wp_idx += 1
        # Update position to the stop location
        device.lat = wp.lat
        device.lon = wp.lon
        device.bearing = wp.bearing_deg
        return False

    # Convert target speed to m/s with jitter
    target_mps = (target_kmh / 3.6) * random.uniform(0.85, 1.15)
    device.speed_mps = max(1.0, target_mps)

    # Distance we travel this tick
    distance_this_tick = device.speed_mps * dt_seconds

    # Distance of the current segment
    seg_dist = wp.distance_to_next_m
    if seg_dist <= 0:
        # Last waypoint (loop_end) -- complete the lap
        device.current_wp_idx = 0
        device.segment_progress = 0.0
        device.laps_completed += 1
        wp0 = wps[0]
        device.lat = wp0.lat
        device.lon = wp0.lon
        device.bearing = wp0.bearing_deg
        return True

    # Advance progress
    device.segment_progress += distance_this_tick / seg_dist

    if device.segment_progress >= 1.0:
        # Move to next waypoint
        overflow = (device.segment_progress - 1.0) * seg_dist
        device.current_wp_idx += 1
        if device.current_wp_idx >= len(wps):
            # Completed lap
            device.current_wp_idx = 0
            device.segment_progress = 0.0
            device.laps_completed += 1
            wp0 = wps[0]
            device.lat = wp0.lat
            device.lon = wp0.lon
            device.bearing = wp0.bearing_deg
            return True

        next_wp = wps[device.current_wp_idx]
        device.segment_progress = overflow / max(next_wp.distance_to_next_m, 0.1)
        device.segment_progress = min(device.segment_progress, 0.99)
    else:
        next_idx = device.current_wp_idx + 1
        if next_idx < len(wps):
            next_wp = wps[next_idx]
        else:
            next_wp = wps[0]

    # Interpolate current position between current and next waypoint
    cur_wp = wps[device.current_wp_idx]
    next_idx = device.current_wp_idx + 1
    if next_idx >= len(wps):
        next_idx = 0
    nxt_wp = wps[next_idx]

    device.lat, device.lon = interpolate_point(
        cur_wp.lat, cur_wp.lon, nxt_wp.lat, nxt_wp.lon, device.segment_progress
    )
    device.bearing = cur_wp.bearing_deg

    # Add small GPS noise (typical smartphone: 3-8m accuracy)
    noise_m = random.gauss(0, 2)
    device.lat += noise_m / 111_000
    device.lon += noise_m / (111_000 * math.cos(math.radians(device.lat)))

    return False


# ---------------------------------------------------------------------------
# Network & main loop
# ---------------------------------------------------------------------------

async def run_device(
    client: httpx.AsyncClient,
    device: CircuitDevice,
    server_url: str,
    hits_per_minute: float,
    max_laps: int,
    waveform_samples: int,
) -> None:
    """Simulate a single device driving the circuit and sending hits."""
    interval = 60.0 / hits_per_minute

    while device.laps_completed < max_laps:
        lap_done = advance_device(device, interval)

        # Only send a hit if the device is moving (not stopped)
        if device.speed_mps > 0.5:
            now_ms = int(time.time() * 1000)
            payload = {
                "protocol_version": 1,
                "device_id": device.device_id,
                "app_version": 1,
                "hit": make_hit_payload(device, now_ms, waveform_samples),
            }
            try:
                resp = await client.post(
                    f"{server_url}/api/v1/hits",
                    content=json.dumps(payload),
                    headers={"content-type": "application/json"},
                )
                if resp.status_code == 200:
                    device.hits_sent += 1
                else:
                    device.errors += 1
            except httpx.RequestError:
                device.errors += 1

        if lap_done:
            print(f"  [{device.device_id[:8]}] Lap {device.laps_completed}/{max_laps} complete "
                  f"({device.hits_sent} hits sent, {device.errors} errors)")

        await asyncio.sleep(interval)


async def run_simulation(args: argparse.Namespace) -> None:
    """Run the full circuit simulation."""
    circuit = Circuit.from_json(args.circuit)

    devices = []
    for i in range(args.devices):
        # Stagger start positions slightly (offset along circuit)
        dev = CircuitDevice(
            device_id=str(uuid.uuid4()),
            circuit=circuit,
        )
        # Offset each device by a few waypoints for variety
        dev.current_wp_idx = (i * 3) % (len(circuit.waypoints) - 1)
        wp = circuit.waypoints[dev.current_wp_idx]
        dev.lat = wp.lat
        dev.lon = wp.lon
        dev.bearing = wp.bearing_deg
        devices.append(dev)

    print(f"Circuit: {circuit.name}")
    print(f"  Distance: {circuit.total_distance_m:.0f} m per lap")
    print(f"  Waypoints: {len(circuit.waypoints)}")
    print(f"  Devices: {args.devices}")
    print(f"  Laps per device: {args.laps}")
    print(f"  Hits/min per device: {args.hits_per_minute}")
    print(f"  Server: {args.server}")
    print(f"  Waveform samples: {args.waveform_samples}")
    print()

    start = time.monotonic()

    async with httpx.AsyncClient(timeout=10.0) as client:
        tasks = [
            run_device(
                client, dev, args.server,
                args.hits_per_minute, args.laps, args.waveform_samples,
            )
            for dev in devices
        ]
        await asyncio.gather(*tasks)

    elapsed = time.monotonic() - start
    total_hits = sum(d.hits_sent for d in devices)
    total_errors = sum(d.errors for d in devices)
    total_laps = sum(d.laps_completed for d in devices)

    print(f"\nSimulation complete in {elapsed:.1f}s")
    print(f"  Total laps: {total_laps}")
    print(f"  Total hits sent: {total_hits}")
    print(f"  Total errors: {total_errors}")
    print(f"  Throughput: {total_hits / max(elapsed, 0.1):.1f} hits/sec")

    # Check server stats
    try:
        async with httpx.AsyncClient(timeout=5.0) as stats_client:
            resp = await stats_client.get(f"{args.server}/api/v1/stats")
            if resp.status_code == 200:
                stats = resp.json()
                print(f"\nServer stats:")
                print(f"  Hits received: {stats.get('hits_received', 'N/A')}")
                print(f"  Hits stored: {stats.get('hits_stored', 'N/A')}")
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(
        description="NidsDePoule circuit-based GPS simulator",
    )
    parser.add_argument(
        "--server", default="http://localhost:8000", help="Server URL",
    )
    parser.add_argument(
        "--circuit", required=True,
        help="Path to circuit JSON file (e.g. tools/simulator/circuits/nddn_cemetery_loop.json)",
    )
    parser.add_argument(
        "--devices", type=int, default=1, help="Number of simulated devices (default: 1)",
    )
    parser.add_argument(
        "--laps", type=int, default=1, help="Laps per device (default: 1)",
    )
    parser.add_argument(
        "--hits-per-minute", type=float, default=10,
        help="Hits per minute per device (default: 10)",
    )
    parser.add_argument(
        "--waveform-samples", type=int, default=15,
        help="Waveform samples per hit (default: 15)",
    )

    args = parser.parse_args()
    asyncio.run(run_simulation(args))


if __name__ == "__main__":
    main()
