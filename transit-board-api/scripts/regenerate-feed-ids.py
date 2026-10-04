#!/usr/bin/env python3
"""Regenerate transit-board-api/src/test/resources/feed-ids.txt from a gtfs-out directory.

Usage:
    python3 regenerate-feed-ids.py [<gtfs-out-dir>]

The optional argument is the path to the gtfs-out directory.  It defaults to
oba-server/bundle/gtfs-out relative to the repository root (two levels above
this script).  The output is written to
transit-board-api/src/test/resources/feed-ids.txt relative to the same root.

Each output line has the format:
    <kind><TAB><id>
where kind is one of: agency, stop, trip.
Lines are sorted by kind, then by ID.
"""

import csv
import os
import sys

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_DEFAULT_GTFS_DIR = os.path.join(_REPO_ROOT, "oba-server", "bundle", "gtfs-out")
_OUTPUT_PATH = os.path.join(_REPO_ROOT, "transit-board-api", "src", "test", "resources", "feed-ids.txt")

_SOURCES = [
    ("agency", "agency.txt", "agency_id"),
    ("stop",   "stops.txt",  "stop_id"),
    ("trip",   "trips.txt",  "trip_id"),
]


def _read_ids(gtfs_dir, filename, column):
    path = os.path.join(gtfs_dir, filename)
    if not os.path.isfile(path):
        raise FileNotFoundError(f"Required source file not found: {path}")
    seen = set()
    with open(path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        if column not in reader.fieldnames:
            raise ValueError(f"Column '{column}' not found in {path}")
        for row in reader:
            val = row[column].strip()
            if val:
                seen.add(val)
    return sorted(seen)


def main():
    gtfs_dir = sys.argv[1] if len(sys.argv) > 1 else _DEFAULT_GTFS_DIR

    rows = []
    for kind, filename, column in _SOURCES:
        try:
            ids = _read_ids(gtfs_dir, filename, column)
        except FileNotFoundError as exc:
            print(f"error: {exc}", file=sys.stderr)
            sys.exit(1)
        except ValueError as exc:
            print(f"error: {exc}", file=sys.stderr)
            sys.exit(1)
        for id_val in ids:
            rows.append((kind, id_val))

    # Sort: kind first (agency < stop < trip alphabetically), then id
    rows.sort()

    # Write atomically via a temp file
    tmp = _OUTPUT_PATH + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            for kind, id_val in rows:
                fh.write(f"{kind}\t{id_val}\n")
        os.replace(tmp, _OUTPUT_PATH)
    except Exception:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise

    print(f"Wrote {len(rows)} lines to {_OUTPUT_PATH}")


if __name__ == "__main__":
    main()
