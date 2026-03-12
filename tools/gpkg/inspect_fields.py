#!/usr/bin/env python3
"""Inspect the fields (columns) of each table in a GeoPackage file."""

import argparse
import sqlite3
import sys


def inspect_gpkg(path):
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
        print(f"Table: {table}")
        cols = con.execute(f'PRAGMA table_info("{table}")').fetchall()
        for col in cols:
            # col: (cid, name, type, notnull, default, pk)
            name = col[1]
            col_type = col[2] or ""
            pk = " (PK)" if col[5] else ""
            print(f"  {name:30s} {col_type}{pk}")
        print()

    con.close()
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="List fields of each table in a GeoPackage (.gpkg) file."
    )
    parser.add_argument("gpkg_file", help="Path to the .gpkg file")
    args = parser.parse_args()
    sys.exit(inspect_gpkg(args.gpkg_file))


if __name__ == "__main__":
    main()
