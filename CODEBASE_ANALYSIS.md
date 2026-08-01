---
analyzed_commit: e08199f54a1902f63fca67e3b2e382fee6468ef7
analyzed_date: 2026-08-01
model: claude-fable-5
---

# Codebase Analysis: transit-board

## Overview

transit-board is a self-hosted departure board for the Long Island Rail Road (adaptable to any GTFS feed), backed by a self-hosted [OneBusAway](https://onebusaway.org/) (OBA) server. It presents static GTFS schedules two ways: a Java CLI that prints a stop's full-day departure table, and a Svelte web app that renders a Japanese-style hour/minute timetable grid with per-trip headsigns, colored route pills, a searchable station picker, and Japanese localization. Supporting services keep the GTFS bundle fresh (daily auto-updater) and monitor host/container health.

## Architecture & Data Structures

The system is a Docker Compose stack of six services plus a build-only image:

- **`oba_database`** — MySQL 8.4; OBA's backing store. Also hosts a small `gtfs_checksums` table (`id`, `sha256`, `feed_url`, `checked_at`, `bundle_built_at`) created and used by the updater.
- **`oba_app`** — OneBusAway server (built from a local `oba-app/` context layered on the upstream `onebusaway-docker` image), serving the OBA REST API on port 8080 and loading the transit bundle from `./oba-server/bundle`.
- **`transit-board-api`** — Java 17 REST API (port 4000, internal) built on the JDK's `com.sun.net.httpserver.HttpServer` with Jackson for JSON. Key classes:
  - `ApiServer` — wires two handlers onto `/api/schedule` and `/api/stops`.
  - `ObaClient` — thin HTTP client over six OBA endpoints (schedule-for-stop, stop, trip, trip-details, stops-for-agency, agency), with typed exceptions (`ObaClientException`, `ObaNotFoundException`).
  - `ScheduleApiHandler` — the core aggregator: builds a `ScheduleResponse` (stop info with parent/sibling platform IDs, routes, deduplicated headsigns, destinations, and per-departure `hour`/`minute`/`dstRepeat`/`directionId`/`downstreamStops`).
  - Helpers: `ScheduleParser` (flatten OBA schedule into `Departure` objects), `HourCalculator` (24h+ hour math and DST-repeat detection), `SiblingResolver` (sibling platforms via parent stop).
  - `model/` — Jackson DTOs mirroring OBA response shapes (`ObaResponse`, `ObaStopResponse`, `ObaTripResponse`, `ObaTripScheduleResponse`, etc.).
- **`frontend`** — Svelte 4 + Vite single-page app served by Nginx (host port 5173). Components: `HomePage` (station picker), `Timetable`/`MinuteCell` (hour-grid rendering), `Header`, `HeadsignFilter`, `DestinationPicker`, `DatePicker`, `ClockToggle`, `LoadingIndicator`. Libraries: `lib/timetable.js` (group-by-hour, row coloring), `lib/lirr.js` (LIRR detection, city terminals, headsign abbreviations), `lib/i18n.js` + `lib/lirr-ja.json` (Japanese localization; the JSON is generated from `logs/lirr_japanese_stations.csv` by `scripts/csv-to-json.js` at build time), `lib/locale.js`, `lib/api.js`.
- **`departure-board` (CLI)** — a smaller standalone Java app (`Main`, `ObaClient`, `ScheduleParser`, `DeparturePrinter`) that prints one stop's day schedule, with automatic fallback to the most recent past date that has service. Invoked via the `board` shell wrapper (`docker compose run --rm cli`).
- **`gtfs_updater`** — Python 3.12 daemon (`updater.py`): daily checksum-based feed refresh, coordinating bundle rebuilds through JSON marker files (`.rebuild_request.json` / `.rebuild_result.json`) in the shared `/bundle` volume, with a nonce handshake and a 10-minute timeout.
- **`monitor`** — Python daemon sampling host CPU/RAM/disk from `/host/proc` and container stats from the Docker socket, appending JSONL to `./logs/monitor.jsonl`.

There is no application-owned relational schema beyond `gtfs_checksums`; the GTFS bundle on disk and OBA's own MySQL tables are the data store.

## Access Patterns

- **Web flow:** Browser → Nginx (port 5173) serves the SPA and proxies `/api/*` → `transit-board-api:4000` → OBA REST API (`http://oba-app:8080/api/where/...`) over the Docker network. Users never call port 4000 directly.
- **API endpoints:**
  - `GET /api/schedule?stop=<stopId>&date=YYYY-MM-DD` — validates params, fetches the stop schedule, resolves the agency timezone (via the first route's agency, falling back to `America/New_York`), fetches stop metadata and the parent stop to compute sibling platforms, then for each unique trip calls OBA `/trip` (authoritative `trip_headsign` + `directionId`, deliberately bypassing OBA's majority-vote headsign grouping, which mislabels origin stops) and `/trip-details` (downstream stop names). Errors: 400 (bad params), 404 (stop not found), 405, 502 (OBA unreachable/internal).
  - `GET /api/stops?agency=<agencyId>` — stops-for-agency, deduplicated by name and sorted, powering the station picker.
- **CLI flow:** `./board <stopId> [--base-url]` → `docker compose run --rm cli` → fetch schedule for today, fall back to the most recent serviced date, print sorted table. Exit codes: 0 success, 1 bad args, 2 no data, 3 API/network error.
- **Updater flow:** at startup and daily at `GTFS_UPDATE_HOUR` (ET), download the feed → SHA-256 → compare against `gtfs_checksums` in MySQL → if changed, write `gtfs_staging.zip`, drop a rebuild-request marker, poll for the result marker (nonce-matched), then promote staging to `gtfs_pristine.zip` and record the checksum. All outcomes are appended as JSONL events to `logs/gtfs_updater.jsonl`.
- **Monitor flow:** every 60s, read `/host/proc/stat|meminfo`, `statvfs`, and the Docker stats API via the mounted socket; append JSONL to `logs/monitor.jsonl`.

## Dependencies & External Services

- **Java (both modules):** Java 17, Maven, `jackson-databind` 2.17.1 (only runtime dependency), JUnit 5, maven-shade for fat jars. HTTP via the JDK's `java.net.http.HttpClient` and built-in `HttpServer` — no web framework.
- **Frontend:** Svelte ^4.2.18 (sole runtime dep); Vite 5, Vitest, @testing-library/svelte, jsdom as dev deps. Served by Nginx in the production image.
- **Python:** `pymysql` for the updater (Python 3.12 Alpine); the monitor uses only the standard library.
- **Infrastructure:** Docker/Compose, MySQL 8.4, OneBusAway (built from `github.com/OneBusAway/onebusaway-docker#main`).
- **External services:** the agency's GTFS feed URL (default `https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip`, MTA's LIRR feed) is the only outbound internet dependency at runtime. No real-time feeds — static schedules only.

## Security Posture

- **Deployment model:** designed for self-hosting on a personal VPS/LAN; there is no user auth anywhere in the stack. The frontend (5173) and OBA itself (8080) are both published on host ports; `transit-board-api` is internal-only.
- **Secrets:** `MYSQL_ROOT_PASSWORD`, `JDBC_PASSWORD`, and `OBA_API_KEY` come from `.env` (gitignored, with a clean `.env.example`) or the launch environment; the README workflow injects `OBA_API_KEY` at `docker compose up` time rather than storing it. `ObaClient` defaults the API key to `"TEST"` when unset, and the README's health check uses `key=TEST` — the OBA API key is effectively a formality on a private network, not an access control.
- **Attack surface:** two GET-only JSON endpoints with parameter validation; query values are URL-decoded and passed to OBA path segments with only space-encoding (fine against a trusted OBA backend, but no general encoding). CORS is `Access-Control-Allow-Origin: *`. 502 error bodies echo internal exception messages (minor information leak, e.g. internal hostnames). Error JSON is built by string concatenation with quote-escaping rather than a serializer.
- **Privileged mounts:** the `monitor` container mounts `/var/run/docker.sock` (read-only) and `/proc` — the most sensitive surface in the stack; a compromise of that container approaches host control. An untracked spec (`specs/gtfs-updater-docker-socket-removal.md`) indicates the updater's former socket dependency was removed in favor of the marker-file handshake, which is the safer pattern.
- No injection-prone SQL: the updater uses parameterized queries.

## Known Limitations & Technical Debt

- **Static schedules only** — no real-time arrivals; and the CLI handles only one stop per invocation (both documented in the README).
- **N+1 fan-out in `/api/schedule`:** two OBA calls per unique trip (trip + trip-details) per request, with only per-request caching. Fine for LIRR-scale stops, but the main scaling concern; failures in those per-trip calls are silently swallowed (`catch (Exception ignored)`).
- **Fragile operational choreography**, extensively documented in the README: `OBA_API_KEY` must be re-injected after every rebuild; frontend rebuilds need `--no-cache` and `docker compose up -d` (not `docker restart`); Nginx must re-resolve API DNS after recreation; simultaneous recreation of `oba_app` + `oba_database` triggers a MySQL 8.4 `caching_sha2_password` failure with the old JDBC driver in the OBA image (documented password-reset workaround); `external-proxy-net` is a scrubbed placeholder whose real definition lives in a gitignored override file.
- **Monitor known issues** (`monitor/KNOWN_ISSUES.md`): network throughput metrics always read 0.0 (interface parsing), CPU% is a noisy single 1-second sample, and per-container `cpu_pct` is usually 0.0 because the Docker stats delta window is near-zero.
- Timezone falls back to a hardcoded `America/New_York` when the schedule has no route references; `agencyColor` and `headsignAbbreviations` in the API response are stubbed (null/empty) with the frontend carrying the LIRR abbreviation map instead.
- LIRR-specific knowledge (terminal lists, abbreviations, `LI_` prefix checks, Japanese station names) is hardcoded in the frontend, limiting agency portability despite the generic backend.
- In-flight work exists as uncommitted specs (`specs/gtfs-updater-*.md`: logging, reliability fixes, zip-filename fix, Docker-socket removal) plus completed specs archived under `specs/done/`.

## Testing

- **Java** (both `transit-board-api` and `departure-board`): JUnit 5 via Surefire; run with `mvn test` in each module. Coverage is strongest in `transit-board-api` — handler behavior (`ScheduleApiHandlerTest` plus LIRR- and headsign-patch-specific variants), parsing, hour/DST math (`HourCalculatorTest`), sibling resolution, DTO deserialization — all driven by a rich set of recorded OBA JSON fixtures under `src/test/resources/fixtures/`.
- **Frontend:** Vitest + @testing-library/svelte + jsdom; run with `npm test` in `frontend/`. Tests cover most components (`Timetable`, `MinuteCell`, `Header`, `HomePage`, `HeadsignFilter`, `DestinationPicker`, `ClockToggle`) and the `timetable`, `lirr`, `i18n`, and `locale` libraries.
- **Python:** `gtfs-updater/test_updater.py` and `monitor/test_monitor.py`; run with `pytest` in each directory. The updater tests exercise the checksum/scheduling/marker-file logic.
- The project's workflow (see `specs/`) is spec-first red-green TDD, and the test tree reflects that: each shipped feature spec has corresponding test coverage. There is no top-level CI configuration in the repository; tests are run per-module locally.
