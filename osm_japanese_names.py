#!/usr/bin/env python3
"""
Query the Overpass API for Japanese name translations around a station.

Three-phase strategy for --station-name mode:
  Phase 1 — Exact:     strict phrase match + name:ja required within --radius.
  Phase 2 — Fuzzy:     retry with significant words only (no name:ja required),
                        then score results client-side via difflib; shows features
                        whose name is a partial / variant match (e.g. "Pennsylvania
                        Station" when searching "Penn Station").
  Phase 3 — Enclosing: is_in() at the exact coordinates; surfaces the
                        administrative and geographic areas that contain the point
                        (borough, city, state, …) and their Japanese names.

--lirr mode queries all 124 LIRR stations network-wide (no coordinate needed).

Output is always written to a timestamped file in --output-dir.

Station name drift detection (TODO):
  logs/lirr_japanese_stations.csv is the canonical mapping of all OBA stops to
  their Japanese Wikipedia names (53/128 stops have a Japanese name; 75 do not;
  Belmont Park is a known GAP — special-event only, not in OBA GTFS).

  To detect new or renamed stations, diff the live OBA stops-for-agency response
  against the oba_stop_name column in that CSV:
    - stop in OBA but missing from CSV        → new station, needs Japanese lookup
    - stop in CSV but gone from OBA           → renamed or removed station
    - stop in CSV with match_type="GAP"       → special-event station; check if it
                                                 has been added to regular service
  The CSV was generated on 2026-06-27 from GTFS feed mdb-507-202606110133.

Usage:
    python3 osm_japanese_names.py --station-name "Penn Station"
    python3 osm_japanese_names.py --lirr
    python3 osm_japanese_names.py --station-name "Grand Central" --lat 40.7527 --lon -73.9772 --radius 2000
    python3 osm_japanese_names.py --lirr --json
"""

import argparse
import difflib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone


# ── progress logger ───────────────────────────────────────────────────────────

def log(msg: str, level: str = "INFO") -> None:
    ts = datetime.now(timezone.utc).strftime("%H:%M:%S")
    print(f"[{ts}] {level:<5} {msg}", file=sys.stderr)

OVERPASS_URL = "https://overpass-api.de/api/interpreter"

DEFAULT_LAT    = 40.7506   # Penn Station NYC
DEFAULT_LON    = -73.9936
DEFAULT_RADIUS = 2000      # metres
DEFAULT_OUTDIR = os.path.join(os.path.dirname(__file__), "logs")

NATIVE_NAME_CANDIDATES = ["name", "official_name", "name:en", "short_name"]

JAPANESE_TAGS = [
    "name:ja", "name:ja-Hira", "name:ja-Latn",
    "alt_name:ja", "brand:ja", "operator:ja", "description:ja",
]

META_TAGS = ("amenity", "shop", "tourism", "railway", "public_transport",
             "boundary", "admin_level", "place", "network", "operator", "wikidata")

# Words too generic to use as a fuzzy search signal for station names
TRANSIT_STOP_WORDS = {
    "station", "terminal", "stop", "depot", "platform", "yard",
    "the", "of", "and", "at", "new", "york", "city",
}

FUZZY_EXACT_THRESHOLD = 0.85   # treat as exact match
FUZZY_MIN_THRESHOLD   = 0.40   # below this, discard


# ── helpers ───────────────────────────────────────────────────────────────────

def escape_regex(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("'", "\\'")


def significant_words(name: str) -> list:
    return [w for w in name.lower().split()
            if len(w) > 2 and w not in TRANSIT_STOP_WORDS]


def fuzzy_score(candidate: str, target: str) -> float:
    return difflib.SequenceMatcher(None, candidate.lower(), target.lower()).ratio()


def native_name(tags: dict) -> str:
    for key in NATIVE_NAME_CANDIDATES:
        if key in tags:
            return tags[key]
    return "(no native name)"


def coords_label(el: dict) -> str:
    if el["type"] == "node":
        return f"{el['lat']:.5f}, {el['lon']:.5f}"
    if "center" in el:
        c = el["center"]
        return f"{c['lat']:.5f}, {c['lon']:.5f}"
    return ""


def to_record(el: dict, match_type: str = "exact", score: float = 1.0) -> dict:
    tags = el.get("tags", {})
    return {
        "osm_type":    el["type"],
        "osm_id":      el["id"],
        "coords":      coords_label(el),
        "native_name": native_name(tags),
        "japanese":    {k: tags[k] for k in JAPANESE_TAGS if k in tags},
        "meta":        {k: tags[k] for k in META_TAGS if k in tags},
        "match_type":  match_type,
        "score":       round(score, 3),
    }


# ── geo ───────────────────────────────────────────────────────────────────────

def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in metres."""
    import math
    R = 6_371_000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a  = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


# ── queries ───────────────────────────────────────────────────────────────────

def build_lirr_stations_query() -> str:
    return (
        "[out:json][timeout:60];\n"
        'node["railway"="station"]["network"~"LIRR"]->.lirr;\n'
        ".lirr out center tags;"
    )


def build_lirr_features_query(radius: int) -> str:
    """All name:ja features within radius of any LIRR station, one round-trip."""
    return (
        "[out:json][timeout:120];\n"
        'node["railway"="station"]["network"~"LIRR"]->.lirr;\n'
        "(\n"
        f'  node(around.lirr:{radius})["name:ja"];\n'
        f'  way(around.lirr:{radius})["name:ja"];\n'
        f'  relation(around.lirr:{radius})["name:ja"];\n'
        ");\n"
        "out center tags;"
    )


def build_station_query(lat: float, lon: float, radius: int) -> str:
    """Single query: all features with name:ja within radius + enclosing is_in areas.

    No server-side name filter — keeps the query cheap. Client-side fuzzy scoring
    categorises results relative to the station name after the fact.
    area elements from is_in have no geometry center, so they use plain `out tags`;
    both out statements append into the same JSON elements array.
    """
    around = f"(around:{radius},{lat},{lon})"
    return (
        "[out:json][timeout:60];\n"
        f"is_in({lat},{lon})->.enc;\n"
        "(\n"
        f'  node{around}["name:ja"];\n'
        f'  way{around}["name:ja"];\n'
        f'  relation{around}["name:ja"];\n'
        ")->.features;\n"
        ".features out center tags;\n"
        ".enc out tags;"
    )


# ── HTTP ──────────────────────────────────────────────────────────────────────

def fetch(query: str, label: str = "") -> dict:
    payload         = urllib.parse.urlencode({"data": query}).encode()
    retryable_codes = {429, 504}
    preview         = query.replace("\n", " ")[:120]
    log(f"[{label}] POST → {preview} …")

    for attempt in range(1, 6):
        req = urllib.request.Request(
            OVERPASS_URL, data=payload,
            headers={"User-Agent": "transit-board-osm-experiment/1.0"},
        )
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                slots = resp.headers.get("X-Rate-Limit")
                body  = json.load(resp)
            elapsed = time.monotonic() - t0
            count   = len(body.get("elements", []))
            log(f"[{label}] OK  {elapsed:.1f}s — {count} element(s)"
                + (f"  [slots={slots}]" if slots else ""), "OK")
            return body

        except urllib.error.HTTPError as e:
            if e.code not in retryable_codes or attempt == 5:
                log(f"[{label}] HTTP {e.code} — giving up", "ERROR")
                raise
            reason = "rate-limited (429)" if e.code == 429 else "gateway timeout (504)"

        except (TimeoutError, urllib.error.URLError, OSError) as e:
            if attempt == 5:
                log(f"[{label}] {type(e).__name__} — giving up", "ERROR")
                raise
            reason = f"{type(e).__name__}: {e}"

        wait = 15 * attempt
        log(f"[{label}] {reason} — waiting {wait}s before retry {attempt + 1}/5", "WARN")
        time.sleep(wait)


# ── station search (single request) ──────────────────────────────────────────

def search_station(lat: float, lon: float, radius: int, station_name: str) -> dict:
    log(f"Single query — name:ja within r={radius}m + is_in enclosing areas")

    result = fetch(build_station_query(lat, lon, radius), "station")
    osm_ts = result["osm3s"]["timestamp_osm_base"]

    exact, fuzzy, enclosing = [], [], []

    for el in result.get("elements", []):
        tags = el.get("tags", {})

        # area type → came from is_in
        if el["type"] == "area":
            ja = {k: tags[k] for k in JAPANESE_TAGS if k in tags}
            enclosing.append({
                "osm_type":    el["type"],
                "osm_id":      el["id"],
                "native_name": native_name(tags),
                "japanese":    ja,
                "meta":        {k: tags[k] for k in META_TAGS if k in tags},
            })
            continue

        nn    = native_name(tags)
        score = fuzzy_score(nn, station_name)

        if station_name.lower() in nn.lower():
            exact.append(to_record(el, "exact", 1.0))
            log(f"  exact  '{nn}'", "DEBUG")
        elif score >= FUZZY_MIN_THRESHOLD:
            fuzzy.append(to_record(el, "fuzzy", score))
            log(f"  fuzzy  '{nn}'  score={score:.2f}", "DEBUG")
        else:
            log(f"  drop   '{nn}'  score={score:.2f}", "DEBUG")

    with_ja    = [e for e in enclosing if e["japanese"]]
    without_ja = [e for e in enclosing if not e["japanese"]]
    log(f"Done — exact={len(exact)}  fuzzy={len(fuzzy)}  "
        f"enclosing={len(with_ja)}+ja / {len(without_ja)}-ja", "OK")

    return {
        "osm_ts":               osm_ts,
        "exact":                exact,
        "fuzzy":                fuzzy,
        "enclosing_with_ja":    with_ja,
        "enclosing_without_ja": without_ja,
    }


# ── formatting ────────────────────────────────────────────────────────────────

def fmt_record(rec: dict, idx: int) -> list:
    lines = [f"\n[{idx:03d}] {rec['osm_type']}/{rec['osm_id']}  ({rec['coords']})"]
    score_tag = f"  [score={rec['score']:.2f}]" if rec.get("score", 1.0) < 1.0 else ""
    lines.append(f"      native name  : {rec['native_name']}{score_tag}")
    if rec["japanese"]:
        for tag, val in rec["japanese"].items():
            lines.append(f"      {tag:<20}: {val}")
    else:
        lines.append("      (no name:ja)")
    if rec["meta"]:
        lines.append("      meta         : " +
                     "  |  ".join(f"{k}={v}" for k, v in rec["meta"].items()))
    return lines


def format_station_output(data: dict, station_name: str, lat: float,
                           lon: float, radius: int) -> str:
    sep   = "─" * 72
    lines = [
        sep,
        f"  OSM Japanese-name audit  —  \"{station_name}\"",
        f"  r={radius}m around ({lat}, {lon})  |  OSM data: {data['osm_ts']}",
        sep,
    ]

    exact_with    = [r for r in data["exact"] if r["japanese"]]
    exact_without = [r for r in data["exact"] if not r["japanese"]]
    fuzzy_with    = [r for r in data["fuzzy"] if r["japanese"]]
    fuzzy_without = [r for r in data["fuzzy"] if not r["japanese"]]

    idx = 1

    lines.append(f"\n  ── EXACT matches with name:ja ({len(exact_with)}) ──")
    for rec in exact_with:
        lines += fmt_record(rec, idx); idx += 1

    lines.append(f"\n  ── EXACT matches WITHOUT name:ja ({len(exact_without)}) ──")
    for rec in exact_without:
        lines += fmt_record(rec, idx); idx += 1

    lines.append(f"\n  ── FUZZY / VARIANT matches with name:ja ({len(fuzzy_with)}) ──")
    for rec in fuzzy_with:
        lines += fmt_record(rec, idx); idx += 1

    lines.append(f"\n  ── FUZZY / VARIANT matches WITHOUT name:ja ({len(fuzzy_without)}) ──")
    for rec in fuzzy_without:
        lines += fmt_record(rec, idx); idx += 1

    lines.append(f"\n  ── ENCLOSING areas with name:ja ({len(data['enclosing_with_ja'])}) ──")
    for enc in data["enclosing_with_ja"]:
        lines.append(f"\n      {enc['native_name']}")
        for tag, val in enc["japanese"].items():
            lines.append(f"        {tag:<20}: {val}")
        if enc["meta"]:
            lines.append("        meta         : " +
                         "  |  ".join(f"{k}={v}" for k, v in enc["meta"].items()))

    lines.append(f"\n  ── ENCLOSING areas without name:ja ({len(data['enclosing_without_ja'])}) ──")
    for enc in data["enclosing_without_ja"]:
        nn = enc["native_name"]
        lines.append(f"      {nn}")

    total = (len(data["exact"]) + len(data["fuzzy"]) +
             len(data["enclosing_with_ja"]) + len(data["enclosing_without_ja"]))
    with_ja = (len(exact_with) + len(fuzzy_with) + len(data["enclosing_with_ja"]))

    lines += [
        f"\n{sep}",
        f"  Total features examined : {total}",
        f"  With name:ja            : {with_ja}",
        sep,
    ]
    return "\n".join(lines)


def format_lirr_output(records: list, osm_ts: str) -> str:
    sep   = "─" * 72
    lines = [sep, "  LIRR stations — Japanese name audit", f"  OSM data: {osm_ts}", sep]

    with_ja    = [r for r in records if r["japanese"]]
    without_ja = [r for r in records if not r["japanese"]]

    lines.append(f"\n  ── {len(with_ja)} station(s) WITH Japanese name ──")
    for i, rec in enumerate(with_ja, 1):
        lines += fmt_record(rec, i)

    lines.append(f"\n  ── {len(without_ja)} station(s) WITHOUT Japanese name ──")
    for rec in without_ja:
        lines.append(f"  {rec['native_name']:<42} ({rec['coords']})")

    lines += [
        f"\n{sep}",
        f"  Total stations  : {len(records)}",
        f"  With name:ja    : {len(with_ja)}",
        f"  Without name:ja : {len(without_ja)}",
        sep,
    ]
    return "\n".join(lines)


# ── lirr all-stations search ──────────────────────────────────────────────────

def search_lirr_all(radius: int) -> dict:
    log("Step 1 — fetch all LIRR stations")
    r1 = fetch(build_lirr_stations_query(), "lirr-stations")
    osm_ts = r1["osm3s"]["timestamp_osm_base"]
    stations = []
    for el in r1.get("elements", []):
        tags = el.get("tags", {})
        stations.append({
            "osm_id":  el["id"],
            "lat":     el["lat"],
            "lon":     el["lon"],
            "name":    native_name(tags),
            "tags":    tags,
        })
    log(f"Step 1 complete — {len(stations)} station(s)", "OK")

    time.sleep(3)
    log(f"Step 2 — fetch all name:ja features within {radius}m of any LIRR station")
    r2       = fetch(build_lirr_features_query(radius), "lirr-features")
    features = r2.get("elements", [])
    log(f"Step 2 complete — {len(features)} feature(s) with name:ja", "OK")

    log("Step 3 — assign features to stations by distance + fuzzy score")
    results = []
    for st in stations:
        slat, slon, sname = st["lat"], st["lon"], st["name"]
        nearby = []
        for feat in features:
            tags = feat.get("tags", {})
            if feat["type"] == "node":
                flat, flon = feat["lat"], feat["lon"]
            elif "center" in feat:
                flat, flon = feat["center"]["lat"], feat["center"]["lon"]
            else:
                continue
            dist = haversine(slat, slon, flat, flon)
            if dist > radius:
                continue
            nn    = native_name(tags)
            score = fuzzy_score(nn, sname)
            match = ("exact" if sname.lower() in nn.lower()
                     else "fuzzy" if score >= FUZZY_MIN_THRESHOLD
                     else "nearby")
            rec = to_record(feat, match, score)
            rec["distance_m"] = round(dist)
            nearby.append(rec)

        nearby.sort(key=lambda r: r["distance_m"])
        results.append({"station": st, "features": nearby})

    log(f"Step 3 complete — {sum(len(r['features']) for r in results)} assignments", "OK")
    return {"osm_ts": osm_ts, "radius": radius, "stations": results}


def format_lirr_all_output(data: dict) -> str:
    sep    = "─" * 72
    radius = data["radius"]
    lines  = [
        sep,
        f"  LIRR — Japanese name audit  |  r={radius}m per station",
        f"  OSM data: {data['osm_ts']}",
        sep,
    ]

    for entry in data["stations"]:
        st       = entry["station"]
        features = entry["features"]
        with_ja  = [f for f in features if f["japanese"]]
        lines.append(f"\n{'─'*40}")
        lines.append(f"  {st['name']}  ({st['lat']:.5f}, {st['lon']:.5f})")
        if not with_ja:
            lines.append("    (no name:ja features within radius)")
            continue
        for feat in with_ja:
            nn    = feat["native_name"]
            dist  = feat["distance_m"]
            match = feat["match_type"]
            score = feat["score"]
            lines.append(f"\n    [{match:<6} {dist:>5}m  score={score:.2f}]  {nn}")
            for tag, val in feat["japanese"].items():
                lines.append(f"      {tag:<20}: {val}")
            if feat["meta"]:
                lines.append("      meta : " +
                             "  |  ".join(f"{k}={v}" for k, v in feat["meta"].items()))

    total_with = sum(1 for e in data["stations"]
                     if any(f["japanese"] for f in e["features"]))
    lines += [
        f"\n{sep}",
        f"  Stations with ≥1 name:ja feature nearby : {total_with} / {len(data['stations'])}",
        sep,
    ]
    return "\n".join(lines)


# ── file output ───────────────────────────────────────────────────────────────

def write_output(content: str, output_dir: str, prefix: str, as_json: bool) -> str:
    os.makedirs(output_dir, exist_ok=True)
    ts   = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    ext  = "json" if as_json else "txt"
    path = os.path.join(output_dir, f"{prefix}_{ts}.{ext}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return path


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--lirr", action="store_true",
                      help="Fetch all LIRR stations network-wide")
    mode.add_argument("--station-name", default=None,
                      help='Station name to search (default: "Penn Station")')

    parser.add_argument("--lat",        type=float, default=DEFAULT_LAT)
    parser.add_argument("--lon",        type=float, default=DEFAULT_LON)
    parser.add_argument("--radius",     type=int,   default=DEFAULT_RADIUS,
                        help=f"Search radius in metres (default: {DEFAULT_RADIUS})")
    parser.add_argument("--output-dir", default=DEFAULT_OUTDIR,
                        help=f"Directory for timestamped output (default: logs/)")
    parser.add_argument("--json",       dest="as_json", action="store_true",
                        help="Write JSON instead of human-readable text")
    args = parser.parse_args()

    if args.lirr:
        log(f"Mode: LIRR all-stations  r={args.radius}m")
        data    = search_lirr_all(args.radius)
        content = (json.dumps(data, ensure_ascii=False, indent=2)
                   if args.as_json else format_lirr_all_output(data))
        prefix  = "lirr_japanese"

    else:
        name = args.station_name or "Penn Station"
        log(f'Mode: station-name  name="{name}"  r={args.radius}m  ({args.lat}, {args.lon})')
        data    = search_station(args.lat, args.lon, args.radius, name)
        content = (json.dumps(data, ensure_ascii=False, indent=2)
                   if args.as_json
                   else format_station_output(data, name, args.lat, args.lon, args.radius))
        prefix  = "osm_japanese"

    path = write_output(content, args.output_dir, prefix, args.as_json)
    log(f"Output written → {path}", "OK")
    print(content)


if __name__ == "__main__":
    main()
