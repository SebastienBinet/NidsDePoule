#!/usr/bin/env python3
"""Generate an MBTiles file with OSM tiles for the Montreal area.

Downloads tiles at zoom levels 11-15 for the Montreal island bounding box
and stores them in an SQLite database using the MBTiles specification.

Usage:
    python tools/generate_mbtiles.py [output_path]

Default output: android/app/src/main/assets/montreal_tiles.mbtiles
"""

import math
import os
import sqlite3
import sys
import time
import urllib.request

# Montreal island bounding box
MIN_LAT = 45.40
MAX_LAT = 45.72
MIN_LON = -73.98
MAX_LON = -73.47

ZOOM_MIN = 11
ZOOM_MAX = 17

USER_AGENT = "NidsDePoule/1.0 (pothole detection app; tile pack generator)"
TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

# OSM tile usage policy: max 2 requests/sec
REQUEST_DELAY = 0.5


def lon_to_tile_x(lon: float, z: int) -> int:
    return int((lon + 180.0) / 360.0 * (1 << z))


def lat_to_tile_y(lat: float, z: int) -> int:
    lat_rad = math.radians(lat)
    return int(
        (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi)
        / 2.0
        * (1 << z)
    )


def osm_y_to_tms_y(osm_y: int, z: int) -> int:
    """Convert OSM y-coordinate to TMS y-coordinate (MBTiles convention)."""
    return (1 << z) - 1 - osm_y


def open_mbtiles(path: str) -> sqlite3.Connection:
    """Open an existing MBTiles database or create a new one."""
    exists = os.path.exists(path)
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA journal_mode=WAL;")
    if not exists:
        conn.execute(
            "CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT);"
        )
        conn.execute(
            "CREATE TABLE tiles ("
            "  zoom_level INTEGER,"
            "  tile_column INTEGER,"
            "  tile_row INTEGER,"
            "  tile_data BLOB"
            ");"
        )
        conn.execute(
            "CREATE UNIQUE INDEX tile_index "
            "ON tiles (zoom_level, tile_column, tile_row);"
        )
    # Upsert metadata
    metadata = [
        ("name", "Montreal OSM Tiles"),
        ("format", "png"),
        ("minzoom", str(ZOOM_MIN)),
        ("maxzoom", str(ZOOM_MAX)),
        ("bounds", f"{MIN_LON},{MIN_LAT},{MAX_LON},{MAX_LAT}"),
        ("center", f"{(MIN_LON + MAX_LON) / 2},{(MIN_LAT + MAX_LAT) / 2},{ZOOM_MIN}"),
        ("type", "baselayer"),
        ("description", "OSM tiles for Montreal island"),
    ]
    conn.executemany(
        "INSERT OR REPLACE INTO metadata VALUES (?, ?);", metadata
    )
    conn.commit()
    return conn


MAX_RETRIES = 4
RETRY_BACKOFF = [2, 4, 8, 16]


def download_tile(z: int, x: int, y: int):
    """Download a single tile from OSM with retry on transient errors."""
    url = TILE_URL.format(z=z, x=x, y=y)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(MAX_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.read()
        except Exception as e:
            if attempt < MAX_RETRIES:
                wait = RETRY_BACKOFF[attempt]
                print(f"  RETRY {attempt + 1}/{MAX_RETRIES} {url}: {e} (waiting {wait}s)")
                time.sleep(wait)
            else:
                print(f"  FAILED {url}: {e}")
                return None


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    default_output = os.path.join(
        project_root, "android", "app", "src", "main", "assets", "montreal_tiles.mbtiles"
    )
    output_path = sys.argv[1] if len(sys.argv) > 1 else default_output

    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    # Calculate total tiles
    total = 0
    for z in range(ZOOM_MIN, ZOOM_MAX + 1):
        min_x = lon_to_tile_x(MIN_LON, z)
        max_x = lon_to_tile_x(MAX_LON, z)
        min_y = lat_to_tile_y(MAX_LAT, z)  # note: lat/y inverted
        max_y = lat_to_tile_y(MIN_LAT, z)
        count = (max_x - min_x + 1) * (max_y - min_y + 1)
        print(f"  z={z}: x=[{min_x},{max_x}] y=[{min_y},{max_y}] → {count} tiles")
        total += count
    print(f"Total: {total} tiles")
    print(f"Output: {output_path}")
    print()

    conn = open_mbtiles(output_path)

    # Load set of already-downloaded tiles so we can resume
    existing = set(
        conn.execute(
            "SELECT zoom_level, tile_column, tile_row FROM tiles"
        ).fetchall()
    )
    if existing:
        print(f"Resuming: {len(existing)} tiles already in database, skipping them.")
        print()

    downloaded = 0
    skipped = 0
    failed = 0
    total_bytes = 0

    for z in range(ZOOM_MIN, ZOOM_MAX + 1):
        min_x = lon_to_tile_x(MIN_LON, z)
        max_x = lon_to_tile_x(MAX_LON, z)
        min_y = lat_to_tile_y(MAX_LAT, z)
        max_y = lat_to_tile_y(MIN_LAT, z)

        for x in range(min_x, max_x + 1):
            for y in range(min_y, max_y + 1):
                tms_y = osm_y_to_tms_y(y, z)
                if (z, x, tms_y) in existing:
                    skipped += 1
                    continue

                data = download_tile(z, x, y)
                if data:
                    conn.execute(
                        "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?);",
                        (z, x, tms_y, data),
                    )
                    downloaded += 1
                    total_bytes += len(data)
                else:
                    failed += 1

                done = skipped + downloaded + failed
                if (downloaded + failed) % 50 == 0 or done == total:
                    pct = done / total * 100
                    mb = total_bytes / (1024 * 1024)
                    print(
                        f"  [{done}/{total}] {pct:.0f}%  "
                        f"skipped={skipped} downloaded={downloaded} failed={failed} "
                        f"new={mb:.1f} MB"
                    )
                    conn.commit()

                time.sleep(REQUEST_DELAY)

    conn.commit()
    # Checkpoint WAL so the output file is self-contained (no -wal/-shm artifacts)
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    conn.execute("PRAGMA journal_mode=DELETE;")
    conn.close()

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print()
    print(f"Done! {skipped} skipped, {downloaded} downloaded, {failed} failures, {size_mb:.1f} MB")
    print(f"Output: {output_path}")


if __name__ == "__main__":
    main()
