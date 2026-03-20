#!/usr/bin/env python3
"""Count the number of entries in each table of a GeoPackage file."""

import argparse
import sqlite3
import sys


def count_entries(path):
    try:
        con = sqlite3.connect(path)
    except sqlite3.Error as e:
        print(f"error: cannot open '{path}': {e}", file=sys.stderr)
        return 1

    try:
        tables = [
            row[0]
            for row in con.execute(
                "SELECT table_name FROM gpkg_contents"
            ).fetchall()
        ]
    except sqlite3.OperationalError:
        print(f"error: '{path}' does not appear to be a valid GeoPackage", file=sys.stderr)
        con.close()
        return 1

    if not tables:
        print("No tables found in gpkg_contents.")
        con.close()
        return 0

    for table in tables:
        count = con.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
        print(f"{table}: {count} entries")

    con.close()
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Count entries in each table of a GeoPackage (.gpkg) file."
    )
    parser.add_argument("gpkg_file", help="Path to the .gpkg file")
    args = parser.parse_args()
    sys.exit(count_entries(args.gpkg_file))


if __name__ == "__main__":
    main()
