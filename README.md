# Transit Board

A self-hosted transit departure board powered by [OneBusAway](https://onebusaway.org/). Point it at any GTFS feed and query departure schedules for any stop from the command line.

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
```bash
docker compose up oba_app transit-board-api frontend
```

Verify OBA is ready:
```bash
curl "http://localhost:8080/api/where/current-time.json?key=TEST"
# Expect: {"code":200,...}
```

OBA loads the full transit bundle into memory at startup. Wait until the `oba_app` logs show Tomcat started before the frontend will return real data.

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

The timetable shows a Japanese-style hour/minute grid for the stop's full day schedule. Use the date picker to view past or future dates. Use the headsign filter (shown when the stop has no direction in the GTFS data) to filter by destination.

To navigate between platforms at a station (e.g. northbound ↔ southbound), use the direction toggle in the header — it appears automatically when the stop has a sibling platform.

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
| `JDBC_PASSWORD` | `***REMOVED***` | Database password |
| `OBA_BASE_URL` | `http://localhost:8080` | OBA server URL (used by CLI and transit-board-api) |
| `PORT` | `4000` | Port transit-board-api listens on (internal Docker network only) |
