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
- Web UI

---

## Project structure

```
transit-board/
├── docker-compose.yml       — all services
├── .env                     — secrets and config (gitignored)
├── .env.example             — template
├── oba-server/
│   └── bundle/              — built by oba_bundler at runtime
└── departure-board/
    ├── Dockerfile
    ├── pom.xml
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

### 4. Start the OBA server

```bash
docker compose up oba_app
```

The API is live at `http://localhost:8080` once startup completes. Verify with:

```bash
curl "http://localhost:8080/api/where/current-time.json?key=TEST"
# Expect: {"code":200,...}
```

---

## Usage

Find your stop ID from the `stops.txt` file in your GTFS zip, or browse `http://localhost:8080` once the server is running.

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
| `OBA_BASE_URL` | `http://localhost:8080` | OBA server URL (CLI env var) |
