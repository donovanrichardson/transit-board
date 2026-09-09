---
analyzed_commit: 5d71f6b02567ead609c1e3913e5260a6dc45d4d4
analyzed_date: 2026-09-09
model: claude-sonnet-4-6
---

# Codebase Analysis: transit-board

## Overview

transit-board is a self-hosted departure board for the Long Island Rail Road (adaptable to any GTFS feed), backed by a self-hosted [OneBusAway](https://onebusaway.org/) (OBA) server. It presents static GTFS schedules two ways: a Java CLI that prints a stop's full-day departure table, and a Svelte web app that renders a Japanese-style hour/minute timetable grid with per-trip headsigns, colored route pills, a searchable station picker, and Japanese localization. Supporting services keep the GTFS bundle fresh (daily auto-updater) and monitor host/container health.

## Architecture & Data Structures

The system is a Docker Compose stack of seven services (plus build-only images):

- **`oba_database`** — MySQL 8.4; OBA's backing store. Also hosts a small `gtfs_checksums` table (`id`, `sha256`, `feed_url`, `checked_at`, `bundle_built_at`) created and used by the updater. mem_limit: 512 MB.
- **`oba_app`** — OneBusAway server built from a local `oba-app/Dockerfile` that layers atop a pre-built base image (`transit-board-oba_app_base`). Runs under supervisord, managing two processes: the OBA webapp and a **bundle-watcher** bash daemon (`bundle-watcher.sh` / `bundle-watcher-lib.sh`). The bundle-watcher polls `/bundle/.rebuild_request.json` on a 5-second interval; when found, it orchestrates the in-container bundle rebuild and writes `/bundle/.rebuild_result.json` with the nonce and outcome. Port 8080 is now bound to `127.0.0.1` only (not all interfaces). mem_limit: 768 MB.
- **`oba_app_base`** (build-only, profile `build`) — the upstream OBA Docker image, pre-built locally so `oba_app`'s local Dockerfile can layer onto it without re-fetching the full build context each time.
- **`transit-board-api`** — Java 17 REST API (port 4000, internal) built on the JDK's `com.sun.net.httpserver.HttpServer` with Jackson for JSON. Key classes:
  - `ApiServer` — wires two handlers onto `/api/schedule` and `/api/stops`.
  - `ObaClient` — thin HTTP client over six OBA endpoints (schedule-for-stop, stop, trip, trip-details, stops-for-agency, agency), with typed exceptions (`ObaClientException`, `ObaNotFoundException`).
  - `ScheduleApiHandler` — the core aggregator: builds a `ScheduleResponse` (stop info with parent/sibling platform IDs, routes, deduplicated headsigns, destinations, and per-departure `hour`/`minute`/`dstRepeat`/`directionId`/`downstreamStops`).
  - Helpers: `ScheduleParser` (flatten OBA schedule into `Departure` objects), `HourCalculator` (24h+ hour math and DST-repeat detection), `SiblingResolver` (sibling platforms via parent stop).
  - `model/` — Jackson DTOs mirroring OBA response shapes (`ObaResponse`, `ObaStopResponse`, `ObaTripResponse`, `ObaTripScheduleResponse`, etc.).
  - mem_limit: 128 MB.
- **`frontend`** — Svelte 4 + Vite single-page app served by Nginx (host port 5173). Components: `HomePage` (station picker), `Timetable`/`MinuteCell` (hour-grid rendering), `Header`, `HeadsignFilter`, `DestinationPicker`, `DatePicker`, `ClockToggle`, `LoadingIndicator`. Libraries: `lib/timetable.js` (group-by-hour, row coloring), `lib/lirr.js` (LIRR detection, city terminals, headsign abbreviations), `lib/i18n.js` + `lib/lirr-ja.json` (Japanese localization; the JSON is generated from `logs/lirr_japanese_stations.csv` by `scripts/csv-to-json.js` at build time), `lib/locale.js`, `lib/api.js`. mem_limit: 64 MB.
- **`departure-board` (CLI)** — a smaller standalone Java app (`Main`, `ObaClient`, `ScheduleParser`, `DeparturePrinter`) that prints one stop's day schedule, with automatic fallback to the most recent past date that has service. Invoked via the `board` shell wrapper (`docker compose run --rm cli`). mem_limit: 128 MB.
- **`gtfs_updater`** — Python 3.12 daemon (`updater.py`): daily checksum-based feed refresh, coordinating bundle rebuilds through JSON marker files (`.rebuild_request.json` / `.rebuild_result.json`) in the shared `/bundle` volume, with a nonce handshake and a 10-minute timeout. No longer mounts `/var/run/docker.sock`; the docker-socket dependency was removed in favor of the marker-file handshake exclusively. Logs JSONL events to `logs/gtfs_updater.jsonl`. mem_limit: 128 MB.
- **`monitor`** — Python daemon sampling host CPU/RAM/disk from `/host/proc` and container stats from the Docker socket, appending JSONL to `./logs/monitor.jsonl`. Now connects to Docker via `DOCKER_HOST=tcp://docker-socket-proxy:2375` instead of a raw socket mount. mem_limit: 64 MB.
- **`docker-socket-proxy`** (new) — `tecnativa/docker-socket-proxy:0.3` on a dedicated internal bridge network (`docker-proxy-internal`); only `CONTAINERS: 1` is enabled (all write verbs and other read endpoints are disabled). Accepts connections from `monitor` over TCP, replacing the raw socket mount. mem_limit: 32 MB.

All services have `mem_limit`/`cpus` constraints and `json-file` log rotation (10 MB, 3 files). Required env vars use Docker Compose's `:?` error syntax — missing vars abort startup with a message directing users to `./generate-env.sh`.

There is no application-owned relational schema beyond `gtfs_checksums`; the GTFS bundle on disk and OBA's own MySQL tables are the data store.

## Access Patterns

- **Web flow:** Browser → Nginx (port 5173) serves the SPA and proxies `/api/*` → `transit-board-api:4000` → OBA REST API (`http://oba-app:8080/api/where/...`) over the Docker network. Users never call port 4000 directly.
- **API endpoints:**
  - `GET /api/schedule?stop=<stopId>&date=YYYY-MM-DD` — validates params, fetches the stop schedule, resolves the agency timezone (via the first route's agency, falling back to `America/New_York`), fetches stop metadata and the parent stop to compute sibling platforms, then for each unique trip calls OBA `/trip` (authoritative `trip_headsign` + `directionId`, deliberately bypassing OBA's majority-vote headsign grouping, which mislabels origin stops) and `/trip-details` (downstream stop names). Errors: 400 (bad params), 404 (stop not found), 405, 502 (OBA unreachable/internal).
  - `GET /api/stops?agency=<agencyId>` — stops-for-agency, deduplicated by name and sorted, powering the station picker.
- **CLI flow:** `./board <stopId> [--base-url]` → `docker compose run --rm cli` → fetch schedule for today, fall back to the most recent serviced date, print sorted table. Exit codes: 0 success, 1 bad args, 2 no data, 3 API/network error.
- **Updater flow:** at startup and daily at `GTFS_UPDATE_HOUR` (ET), download the feed → SHA-256 → compare against `gtfs_checksums` in MySQL → if changed, write `gtfs_staging.zip`, drop a rebuild-request marker, poll for the result marker (nonce-matched), then promote staging to `gtfs_pristine.zip` and record the checksum. All outcomes are appended as JSONL events to `logs/gtfs_updater.jsonl`.
- **Monitor flow:** every 60s, read `/host/proc/stat|meminfo`, `statvfs`, and the Docker stats API via the socket proxy; append JSONL to `logs/monitor.jsonl`.

## Dependencies & External Services

- **Java (both modules):** Java 17, Maven, `jackson-databind` 2.17.1 (only runtime dependency), JUnit 5, maven-shade for fat jars. HTTP via the JDK's `java.net.http.HttpClient` and built-in `HttpServer` — no web framework.
- **Frontend:** Svelte ^4.2.18 (sole runtime dep); Vite 5, Vitest, @testing-library/svelte, jsdom as dev deps. Served by Nginx in the production image.
- **Python:** `pymysql` for the updater (Python 3.12 Alpine); the monitor uses only the standard library.
- **Infrastructure:** Docker/Compose, MySQL 8.4, OneBusAway (pre-built base via `transit-board-oba_app_base` profile), tecnativa/docker-socket-proxy:0.3.
- **External services:** the agency's GTFS feed URL (default `https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip`, MTA's LIRR feed) is the only outbound internet dependency at runtime. No real-time feeds — static schedules only.

## Security Posture

- **Deployment model:** designed for self-hosting on a personal VPS/LAN; there is no user auth anywhere in the stack. The frontend (5173) is the only service now published on all interfaces; `oba_app` port 8080 is bound to `127.0.0.1` only; `transit-board-api` is internal-only.
- **Secrets:** `MYSQL_ROOT_PASSWORD`, `JDBC_PASSWORD`, and `OBA_API_KEY` come from `.env` (gitignored, with `.env.example` and `generate-env.sh`). Required vars use `:?` syntax — startup fails explicitly if any are missing.
- **Attack surface:** two GET-only JSON endpoints with parameter validation. CORS is `Access-Control-Allow-Origin: *`. 502 error bodies echo internal exception messages (minor information leak). Error JSON is built by string concatenation with quote-escaping rather than a serializer.
- **Docker socket access:** `monitor` no longer mounts the raw socket; it connects to `docker-socket-proxy` (CONTAINERS read-only) over an internal bridge network. `gtfs_updater` no longer has any docker socket access at all. The remaining raw socket mount exists only on `docker-socket-proxy` itself.
- **In-progress hardening:** `specs/security-hardening.md` documents planned Phase A0/C2 work including finalizing mem_limit/cpus values after measurement, and other items.
- No injection-prone SQL: the updater uses parameterized queries.

## Known Limitations & Technical Debt

- **Static schedules only** — no real-time arrivals; and the CLI handles only one stop per invocation (both documented in the README).
- **N+1 fan-out in `/api/schedule`:** two OBA calls per unique trip (trip + trip-details) per request, with only per-request caching. Fine for LIRR-scale stops, but the main scaling concern; failures in those per-trip calls are silently swallowed (`catch (Exception ignored)`).
- **Fragile operational choreography**, extensively documented in the README: `OBA_API_KEY` must be re-injected after every rebuild; frontend rebuilds need `--no-cache` and `docker compose up -d` (not `docker restart`); Nginx must re-resolve API DNS after recreation; simultaneous recreation of `oba_app` + `oba_database` triggers a MySQL 8.4 `caching_sha2_password` failure with the old JDBC driver in the OBA image (documented password-reset workaround); `external-proxy-net` is a scrubbed placeholder whose real definition lives in a gitignored override file.
- **Monitor known issues** (`monitor/KNOWN_ISSUES.md`): network throughput metrics always read 0.0 (interface parsing), CPU% is a noisy single 1-second sample, and per-container `cpu_pct` is usually 0.0 because the Docker stats delta window is near-zero.
- Timezone falls back to a hardcoded `America/New_York` when the schedule has no route references; `agencyColor` and `headsignAbbreviations` in the API response are stubbed (null/empty) with the frontend carrying the LIRR abbreviation map instead.
- **Incomplete `HEADSIGN_ABBREVIATIONS` map** (`frontend/src/lib/lirr.js`): headsigns not present in the map render as the literal text "Unlabeled" in `Header.svelte` above the route pill. The map covers major LIRR terminals but not all valid trip headsigns — e.g. "Central Islip" (a short-turn terminus on the Ronkonkoma branch) is absent, causing those trips to display "Unlabeled."
- LIRR-specific knowledge (terminal lists, abbreviations, `LI_` prefix checks, Japanese station names) is hardcoded in the frontend, limiting agency portability despite the generic backend.
- mem_limit/cpus values are labeled as placeholders pending docker-stats-log.sh measurement data (see `specs/security-hardening.md`).

## Testing

- **Java** (both `transit-board-api` and `departure-board`): JUnit 5 via Surefire; run with `mvn test` in each module. Coverage is strongest in `transit-board-api` — handler behavior (`ScheduleApiHandlerTest` plus LIRR- and headsign-patch-specific variants), parsing, hour/DST math (`HourCalculatorTest`), sibling resolution, DTO deserialization — all driven by a rich set of recorded OBA JSON fixtures under `src/test/resources/fixtures/`.
- **Frontend:** Vitest + @testing-library/svelte + jsdom; run with `npm test` in `frontend/`. Tests cover most components (`Timetable`, `MinuteCell`, `Header`, `HomePage`, `HeadsignFilter`, `DestinationPicker`, `ClockToggle`) and the `timetable`, `lirr`, `i18n`, and `locale` libraries.
- **Python:** `gtfs-updater/test_updater.py` and `monitor/test_monitor.py`; run with `pytest` in each directory. The updater tests exercise the checksum/scheduling/marker-file logic (substantially expanded since the docker-socket removal).
- **Shell:** `oba-app/test_bundle_watcher.sh` — BATS-style test suite for the `bundle-watcher-lib.sh` functions.
- The project's workflow (see `specs/`) is spec-first red-green TDD, and the test tree reflects that: each shipped feature spec has corresponding test coverage. There is no top-level CI configuration in the repository; tests are run per-module locally.
