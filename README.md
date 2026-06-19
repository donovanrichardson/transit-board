# LIRR Departure Board

A self-hosted departure board for the Long Island Rail Road, powered by [OneBusAway](https://onebusaway.org/). Displays a Japanese-style timetable grid with per-trip headsigns derived from GTFS ground truth, colored route pills, and a searchable station picker.

---

## What it does

Given a stop ID, prints that stop's full day schedule as a sorted departure table:

```
Route   Headsign                        Departs
40      Loyal Heights Greenwood         06:12
40      Downtown Seattle                06:18
E Line  Aurora Village                  06:22
```

**Current features:**
- Takes a single stop ID as a CLI argument
- Fetches the full day's schedule from a self-hosted OBA server
- If today has no service, automatically falls back to the most recent past date that does
- Flattens all routes and directions into one list, sorted by departure time
- Resolves route short names from the GTFS data
- Formats times in the stop's local timezone
- Base URL configurable via `--base-url` flag or `OBA_BASE_URL` environment variable

**Not yet supported:**
- Multiple stops in one query
- Real-time arrivals (static schedule only)

---

## Project structure

```
transit-board/
├── docker-compose.yml       — all services
├── .env                     — secrets and config (gitignored)
├── .env.example             — template
├── board                    — shell wrapper: runs CLI via docker compose
├── oba-server/
│   └── bundle/              — built by oba_bundler at runtime
├── departure-board/         — CLI tool (Java/Maven)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
├── transit-board-api/       — REST API for the web frontend (Java/Maven, port 4000)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
└── frontend/                — Svelte timetable web app (served on port 5173)
    ├── Dockerfile
    ├── nginx.conf
    └── src/
```

---

## Setup

### Prerequisites
- Docker and Docker Compose
- A GTFS feed URL for your transit agency (see [transit.land](https://www.transit.land/feeds) or your agency's open data page)

### 1. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and set your GTFS feed URL:

```bash
GTFS_URL=https://your-agency.gov/gtfs.zip
```

### 2. Build the images

```bash
docker compose build
```

### 3. Build the transit bundle

This downloads your GTFS feed and processes it into an OBA-optimized bundle. Takes 2–10 minutes depending on feed size.

```bash
docker compose up oba_bundler
```

Wait for the bundler to exit cleanly before proceeding. The bundle is written to `oba-server/bundle/` and persists between server restarts. Re-run this step whenever your agency publishes a new GTFS feed.

### 4. Start the services

**CLI only** (departure board):
```bash
docker compose up oba_app
```

**Web timetable** (frontend + API + OBA):

`transit-board-api` requires `OBA_API_KEY` set in your environment at launch — it is not stored in `.env`:

```bash
docker compose up -d oba_app frontend
OBA_API_KEY=<your-oba-api-key> docker compose up -d transit-board-api
docker restart transit-board-frontend-1
```

The final `docker restart` is required so Nginx re-resolves the `transit-board-api` DNS after the container is recreated.

Verify OBA is ready:
```bash
curl "http://localhost:8080/api/where/current-time.json?key=TEST"
# Expect: {"code":200,...}
```

OBA loads the full transit bundle into memory at startup. Wait until the `oba_app` logs show Tomcat started before the frontend will return real data.

> **After any rebuild** of any service, always re-run the `OBA_API_KEY=<your-oba-api-key> docker compose up -d transit-board-api` and `docker restart transit-board-frontend-1` steps. `docker compose up --build` cascades restarts that wipe injected env vars.

---

## Timetable web app

Open your browser to:

```
http://localhost:5173/stop/<stopId>
```

Replace `<stopId>` with a full OBA stop ID (agency-prefixed), e.g.:

```
http://localhost:5173/stop/MTA%20NYCT_725S
```

The timetable shows a Japanese-style hour/minute grid. Each cell contains the departure minute and a colored bar indicating the trip's destination. Headsigns are derived per-trip from the GTFS `trip_headsign` field (via the OBA `/trip` endpoint), bypassing OBA's majority-vote grouping which produces incorrect labels at origin stops.

**Features:**
- Searchable station picker on the home screen
- Colored `.timetable-header` border using dominant route color
- "Trips toward" pill row showing all headsign destinations with abbreviations
- Inbound/Outbound direction toggle and specific destination search
- Date picker for past/future schedules
- Clock toggle between 12h and 24h

To navigate between platforms at a station (e.g. inbound ↔ outbound), use the direction controls in the header.

**Architecture:** the frontend (`http://localhost:5173`) is served by Nginx, which proxies `/api/*` requests to `transit-board-api` (port 4000 internal). `transit-board-api` talks to `oba_app` over the Docker network. You never need to call port 4000 directly.

---

## Usage (CLI)

Find your stop ID from the `stops.txt` file in your GTFS zip, or browse `http://localhost:8080` once the OBA server is running.

```bash
# Basic usage
docker compose run --rm cli <stopId>

# Custom OBA server URL
docker compose run --rm cli <stopId> --base-url http://my-oba-server:8080
```

Example:

```bash
docker compose run --rm cli 1_75403
```

The `OBA_BASE_URL` environment variable can be used instead of `--base-url`. The CLI flag takes precedence.

---

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Bad arguments |
| 2 | No data (no service found for stop) |
| 3 | API or network error |

---

## GTFS data

GTFS feeds expire on a schedule set by each agency (weekly, monthly, or ad hoc). When the feed expires, OBA continues to work but the data becomes stale. To refresh:

1. Update `GTFS_URL` in `.env` if the URL has changed
2. Re-run the bundler: `docker compose up oba_bundler`
3. Restart the server: `docker compose restart oba_app`

---

## Configuration reference

| Variable | Default | Description |
|---|---|---|
| `GTFS_URL` | — | **Required.** URL of your agency's GTFS zip |
| `JDBC_URL` | `jdbc:mysql://oba_database:3306/oba_database` | Database connection string |
| `JDBC_USER` | `oba` | Database user |
| `JDBC_PASSWORD` | | Database password (must match `MYSQL_PASSWORD` in `.env`) |
| `OBA_BASE_URL` | `http://localhost:8080` | OBA server URL (used by CLI and transit-board-api) |
| `PORT` | `4000` | Port transit-board-api listens on (internal Docker network only) |
