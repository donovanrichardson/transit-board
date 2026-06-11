# Spec: Transit Board

## Goal
Stand up a self-hosted OneBusAway server (Docker) and build a Java CLI tool (Docker) that queries it, printing a flat, time-sorted departure board for a single stop. Every component runs in Docker. Both parts live under a new `transit-board/` directory.

---

## Folder structure

```
transit-board/
├── docker-compose.yml            — all services (oba_database, oba_bundler, oba_app, cli)
├── .env                          — secrets and config (gitignored)
├── .env.example                  — committed template (no secrets)
├── oba-server/
│   └── bundle/                   — created by oba_bundler at runtime, gitignored
└── departure-board/
    ├── Dockerfile                — multi-stage build for the CLI jar
    ├── pom.xml
    └── src/
        ├── main/java/dev/shinpei/departureboard/
        │   ├── Main.java
        │   ├── ObaClient.java
        │   ├── ScheduleParser.java
        │   ├── DeparturePrinter.java
        │   └── model/
        │       ├── Departure.java
        │       └── ObaResponse.java
        └── test/java/dev/shinpei/departureboard/
            ├── ScheduleParserTest.java
            ├── DeparturePrinterTest.java
            └── fixtures/
                └── schedule-response.json
```

---

## Part 1 — OBA Server

### Services (in `docker-compose.yml`)

| Service | Purpose | Port |
|---|---|---|
| `oba_database` | MySQL — bundle metadata | 3306 (internal) |
| `oba_bundler` | One-shot: download GTFS, build bundle into `oba-server/bundle/` | — |
| `oba_app` | OBA REST API server | 8080 |

Images: `oba_bundler` and `oba_app` are built from the official `OneBusAway/onebusaway-docker` base images. The `docker-compose.yml` references those upstream images directly (no custom Dockerfile needed for the server).

### Configuration (`.env`)

```bash
GTFS_URL=https://example.com/path/to/gtfs.zip   # URL of your agency's GTFS feed
JDBC_URL=jdbc:mysql://oba_database:3306/oba_database
JDBC_USER=oba
JDBC_PASSWORD=***REMOVED***
```

`.env` is gitignored. `.env.example` is committed with placeholder values.

### Workflow to start the server

```bash
# 1. Fill in .env (copy from .env.example)
cp transit-board/.env.example transit-board/.env
# edit .env — set GTFS_URL to your agency's GTFS feed

# 2. Build bundle (one-time; re-run when GTFS data changes)
docker compose -f transit-board/docker-compose.yml up oba_bundler
# Wait for bundler to exit cleanly (~2–10 min depending on feed size)

# 3. Start the API server
docker compose -f transit-board/docker-compose.yml up oba_app
# API live at http://localhost:8080
```

### Verification
```bash
curl "http://localhost:8080/api/where/current-time.json?key=TEST"
# Expect: {"code":200,...}
```

---

## Part 2 — Departure Board CLI

### Stack
- Java 17+, Maven
- Jackson (`com.fasterxml.jackson.databind`)
- `java.net.http.HttpClient` (stdlib)
- Docker (multi-stage Dockerfile in `departure-board/`)

### Dockerfile (`departure-board/Dockerfile`)

Multi-stage build:

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS run
WORKDIR /app
COPY --from=build /app/target/departure-board-*.jar departure-board.jar
ENTRYPOINT ["java", "-jar", "departure-board.jar"]
```

### CLI service in `docker-compose.yml`

```yaml
cli:
  build:
    context: ./departure-board
    dockerfile: Dockerfile
  environment:
    - OBA_BASE_URL=http://oba_app:8080
  depends_on:
    - oba_app
  profiles:
    - cli
```

`profiles: [cli]` means `docker compose up` does not start this as a daemon. It only runs on-demand:

```bash
docker compose -f transit-board/docker-compose.yml run --rm cli 1_75403
```

All services share the same Docker Compose network, so `cli` can reach `oba_app` by service name.

### CLI interface

```
java -jar departure-board.jar <stopId> [--base-url <url>]
```

| Argument | Required | Default | Description |
|---|---|---|---|
| `stopId` | yes | — | OBA stop ID (e.g. `1_75403`) |
| `--base-url` | no | `http://localhost:8080` | OBA server base URL |

`OBA_BASE_URL` env var also accepted. CLI flag > env var > default.

If no `stopId` given: print usage to stderr, exit 1.

---

## OBA API interaction

### Request
```
GET {baseUrl}/api/where/schedule-for-stop/{stopId}.json?key=TEST&date={YYYY-MM-DD}
```

API key hardcoded to `TEST`:
```java
// TODO: Make API key configurable via CLI flag or environment variable
private static final String API_KEY = "TEST";
```

### Date selection logic
1. First request uses today's date in the system default time zone.
2. Parse `data.entry.timeZone` from the response.
3. Parse `data.entry.stopCalendarDays[]` — each has `date` in ms since epoch.
4. Convert each to `LocalDate` in the stop's time zone.
5. If today (in stop's tz) is in the list **and** there is at least one `scheduleStopTime` with `departureEnabled == true` → use this response.
6. Otherwise find the **most recent past date** from `stopCalendarDays` and make a second request. If none exists → exit 2.

### Route short name
Lookup map from `data.references.routes[]`: `routeId` → `shortName`. Fall back to `routeId` if `shortName` null/empty.

### Headsign
Prefer `stopHeadsign` on `scheduleStopTime` if non-null/non-empty; else use `tripHeadsign` from parent `stopRouteDirectionSchedule`.

### Departure time
`departureTime` = **milliseconds since Unix epoch**. Parse with `Instant.ofEpochMilli(departureTime)`.

### Flattening
Iterate `stopRouteSchedules[].stopRouteDirectionSchedules[].scheduleStopTimes[]`. For each where `departureEnabled == true`, emit `Departure(routeShortName, headsign, departureTime)`. Sort by `departureTime` ascending.

---

## Output format

```
Route   Headsign                        Departs
40      Loyal Heights Greenwood         06:12
40      Downtown Seattle                06:18
E Line  Aurora Village                  06:22
```

- One header row, then one row per departure
- Columns left-aligned, padded to longest value (min: header width), 3-space separator
- `Departs` in `HH:mm` in the stop's time zone
- No trailing whitespace

---

## Error handling

| Condition | Behavior |
|---|---|
| No args / bad args | Usage to stderr, exit 1 |
| HTTP non-2xx | `"OBA API error: HTTP {status}"` to stderr, exit 3 |
| Network error | `"Cannot connect to OBA server at {baseUrl}: {message}"` to stderr, exit 3 |
| JSON parse failure | `"Failed to parse OBA response: {message}"` to stderr, exit 3 |
| No service dates | `"No scheduled service found for stop {stopId}"` to stderr, exit 2 |
| No departures | `"No departures found for stop {stopId}"` to stderr, exit 2 |

HTTP timeout: 10s connect, 30s read.

---

## Acceptance criteria

### Server
- [ ] `docker compose up oba_bundler` builds a valid bundle in `oba-server/bundle/`
- [ ] `docker compose up oba_app` starts the API at `http://localhost:8080`
- [ ] `curl http://localhost:8080/api/where/current-time.json?key=TEST` returns HTTP 200

### CLI
- [ ] `docker compose build cli` succeeds
- [ ] `docker compose run --rm cli <stopId>` prints a formatted departure table when `oba_app` is running
- [ ] Departures sorted ascending by time across all routes
- [ ] Times in the stop's time zone from the API `timeZone` field
- [ ] Route short names from `data.references.routes[]`
- [ ] `stopHeadsign` preferred over `tripHeadsign`
- [ ] Falls back to most recent past service date when today has no service
- [ ] `--base-url` and `OBA_BASE_URL` both work; flag takes precedence
- [ ] Exit codes: 1 = bad args, 2 = no data, 3 = API/network error
- [ ] TODO comment in source about API key configurability
- [ ] `mvn test` passes
- [ ] No dependencies beyond Jackson and JUnit 5

---

## Tests
- **ScheduleParserTest.parsesFullResponse** — fixture JSON → correct Departure records, sorted
- **ScheduleParserTest.prefersStopHeadsignOverTripHeadsign** — stopHeadsign wins when set
- **ScheduleParserTest.fallsBackToRouteIdWhenNoShortName** — null shortName → routeId used
- **ScheduleParserTest.filtersOutDepartureDisabled** — departureEnabled=false excluded
- **ScheduleParserTest.findsFallbackDate** — stopCalendarDays without today → correct past date
- **ScheduleParserTest.noCalendarDaysThrows** — empty stopCalendarDays → exception
- **DeparturePrinterTest.formatsColumnsCorrectly** — known Departure list → exact stdout
- **DeparturePrinterTest.emptyListProducesNoOutput** — empty list → no stdout

---

## Files that will be created
- `transit-board/docker-compose.yml`
- `transit-board/.env.example`
- `transit-board/departure-board/Dockerfile`
- `transit-board/departure-board/pom.xml`
- `transit-board/departure-board/src/main/java/dev/shinpei/departureboard/Main.java`
- `transit-board/departure-board/src/main/java/dev/shinpei/departureboard/ObaClient.java`
- `transit-board/departure-board/src/main/java/dev/shinpei/departureboard/ScheduleParser.java`
- `transit-board/departure-board/src/main/java/dev/shinpei/departureboard/DeparturePrinter.java`
- `transit-board/departure-board/src/main/java/dev/shinpei/departureboard/model/Departure.java`
- `transit-board/departure-board/src/main/java/dev/shinpei/departureboard/model/ObaResponse.java`
- `transit-board/departure-board/src/test/java/dev/shinpei/departureboard/ScheduleParserTest.java`
- `transit-board/departure-board/src/test/java/dev/shinpei/departureboard/DeparturePrinterTest.java`
- `transit-board/departure-board/src/test/java/dev/shinpei/departureboard/fixtures/schedule-response.json`
