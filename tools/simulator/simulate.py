#!/usr/bin/env python3
"""NidsDePoule hit report simulator.

Generates realistic pothole hit traffic for testing the server.

Usage:
    # 5 devices driving around Lyon for 10 minutes
    python -m tools.simulator.simulate --server http://localhost:8000 --devices 5 --duration 600

    # Stress test: 50 devices, max rate
    python -m tools.simulator.simulate --server http://localhost:8000 --devices 50 --hits-per-minute 60

    # Single device, specific location
    python -m tools.simulator.simulate --server http://localhost:8000 --center 48.8566,2.3522
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
from dataclasses import dataclass

import httpx


@dataclass
class SimDevice:
    device_id: str
    lat: float
    lon: float
    bearing: float
    speed_mps: float
    hits_sent: int = 0
    errors: int = 0


def generate_waveform(peak_mg: int, n_samples: int = 15) -> list[int]:
    """Generate a plausible pothole waveform centered on a peak."""
    waveform = []
    mid = n_samples // 2
    for i in range(n_samples):
        dist = abs(i - mid)
        # Gaussian-like envelope around the peak
        factor = math.exp(-0.5 * (dist / max(mid * 0.4, 1)) ** 2)
        noise = random.randint(-100, 100)
        value = int(1000 + (peak_mg - 1000) * factor + noise)
        waveform.append(value)
    return waveform


def make_hit_payload(device: SimDevice, timestamp_ms: int, waveform_samples: int) -> dict:
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


def move_device(device: SimDevice, dt_seconds: float) -> None:
    """Move a device along its current bearing, with random turns."""
    # Random bearing change (simulates turns)
    device.bearing = (device.bearing + random.uniform(-15, 15)) % 360

    # Random speed variation (city driving: 5-15 m/s)
    device.speed_mps = max(3.0, min(20.0, device.speed_mps + random.uniform(-1, 1)))

    # Move position
    distance_m = device.speed_mps * dt_seconds
    bearing_rad = math.radians(device.bearing)

    # Approximate: 1 degree latitude ≈ 111,000 m
    dlat = (distance_m * math.cos(bearing_rad)) / 111_000
    dlon = (distance_m * math.sin(bearing_rad)) / (111_000 * math.cos(math.radians(device.lat)))

    device.lat += dlat
    device.lon += dlon


async def run_device(
    client: httpx.AsyncClient,
    device: SimDevice,
    server_url: str,
    hits_per_minute: float,
    duration_seconds: float,
    waveform_samples: int,
) -> None:
    """Simulate a single device sending hits."""
    interval = 60.0 / hits_per_minute
    end_time = time.monotonic() + duration_seconds

    while time.monotonic() < end_time:
        # Move the device
        move_device(device, interval)

        # Generate a hit
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

        await asyncio.sleep(interval)


async def run_simulation(args: argparse.Namespace) -> None:
    """Run the full simulation."""
    center_lat, center_lon = args.center
    devices = []
    for i in range(args.devices):
        # Scatter devices within radius of center
        angle = random.uniform(0, 2 * math.pi)
        dist_km = random.uniform(0, args.radius_km)
        lat = center_lat + (dist_km / 111.0) * math.cos(angle)
        lon = center_lon + (dist_km / (111.0 * math.cos(math.radians(center_lat)))) * math.sin(angle)

        devices.append(SimDevice(
            device_id=str(uuid.uuid4()),
            lat=lat,
            lon=lon,
            bearing=random.uniform(0, 360),
            speed_mps=random.uniform(5, 15),
        ))

    print(f"Starting simulation: {args.devices} devices, {args.hits_per_minute} hits/min each")
    print(f"  Center: {center_lat:.4f}, {center_lon:.4f}")
    print(f"  Radius: {args.radius_km} km")
    print(f"  Duration: {args.duration}s")
    print(f"  Server: {args.server}")
    print(f"  Waveform samples: {args.waveform_samples}")
    print()

    start = time.monotonic()

    async with httpx.AsyncClient(timeout=10.0) as client:
        tasks = [
            run_device(client, dev, args.server, args.hits_per_minute,
                       args.duration, args.waveform_samples)
            for dev in devices
        ]
        await asyncio.gather(*tasks)

    elapsed = time.monotonic() - start
    total_hits = sum(d.hits_sent for d in devices)
    total_errors = sum(d.errors for d in devices)

    print(f"\nSimulation complete in {elapsed:.1f}s")
    print(f"  Total hits sent: {total_hits}")
    print(f"  Total errors: {total_errors}")
    print(f"  Throughput: {total_hits / elapsed:.1f} hits/sec")

    # Check server stats
    try:
        resp = await httpx.AsyncClient().get(f"{args.server}/api/v1/stats")
        if resp.status_code == 200:
            stats = resp.json()
            print(f"\nServer stats:")
            print(f"  Hits received: {stats['hits_received']}")
            print(f"  Hits stored: {stats['hits_stored']}")
            print(f"  Active devices (realtime): {stats['active_devices']['realtime']}")
            print(f"  Active devices (batch): {stats['active_devices']['batch']}")
            print(f"  Queue depth: {stats['queue_depth']}")
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser(description="NidsDePoule hit report simulator")
    parser.add_argument("--server", default="http://localhost:8000", help="Server URL")
    parser.add_argument("--devices", type=int, default=5, help="Number of simulated devices")
    parser.add_argument("--duration", type=int, default=60, help="Simulation duration in seconds")
    parser.add_argument("--hits-per-minute", type=float, default=10, help="Hits per minute per device")
    parser.add_argument("--center", type=str, default="45.764,4.835",
                        help="Center lat,lon (default: Lyon)")
    parser.add_argument("--radius-km", type=float, default=5.0, help="Scatter radius in km")
    parser.add_argument("--waveform-samples", type=int, default=15,
                        help="Waveform samples per hit (default: 15)")

    args = parser.parse_args()

    # Parse center
    lat, lon = args.center.split(",")
    args.center = (float(lat), float(lon))

    asyncio.run(run_simulation(args))


if __name__ == "__main__":
    main()
