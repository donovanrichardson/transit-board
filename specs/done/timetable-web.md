# Spec: Japanese Timetable Web App

## Goal
Add a web-based Japanese-style timetable viewer to the transit-board project. A new Java API service wraps the existing OBA schedule data (reusing `ObaClient` and `ScheduleParser`) and a Svelte frontend renders it as a compact hour-by-minute grid with route colors, direction toggle, headsign filtering, and date selection.

## Scope
### In scope
- New `transit-board-api/` Java HTTP service exposing a JSON schedule endpoint
- New `frontend/` Svelte application rendering the timetable
- Two new services in `docker-compose.yml` (`transit-board-api`, `frontend`)
- Extending `ObaResponse.Route` model with `color` and `textColor` fields
- New `Departure` fields for route color information
- New OBA stop metadata fetch (for parent/child/direction data)

### Out of scope
- Stop search UI (day-two feature)
- Multi-stop view
- Real-time arrival data or "now" marker
- Auto-scroll or live updates
- Vue.js (explicitly excluded)
- Modifying the existing CLI `departure-board` behavior

## Architecture

```
Browser → Nginx (frontend, port 5173) → static Svelte dist/
Browser → Nginx proxy_pass /api/* → transit-board-api (port 4000)
transit-board-api → oba_app (port 8080) OBA REST API
```

The `frontend` Nginx container serves the compiled Svelte app and reverse-proxies `/api/` requests to `transit-board-api`. The `transit-board-api` service is a small Java HTTP server that calls the OBA API via the existing `ObaClient` class and returns transformed JSON.

Both new services join the existing docker-compose network and depend on `oba_app`.

## Backend API

### Shared code
The `transit-board-api` service lives in a new Maven module `transit-board-api/` but depends on (or copies) the existing classes from `departure-board/`:
- `ObaClient` — HTTP client for OBA REST API
- `ScheduleParser` — parses `ObaResponse` into `Departure` list
- `ObaResponse` — response model (extended, see below)
- `Departure` — departure model (extended, see below)

The simplest approach: create `transit-board-api/` as its own Maven project that includes the shared model and client code. The existing `departure-board/` code can be extracted into a shared module, or the relevant classes can be copied. The implementer should choose the least-disruptive option (a `shared/` Maven module that both `departure-board` and `transit-board-api` depend on is preferred if feasible; otherwise, copy the classes).

### Model changes

**`ObaResponse.Route`** — add two fields:
```java
public String color;      // maps to OBA "color" field (hex string without #, e.g. "0039A6")
public String textColor;  // maps to OBA "textColor" field (hex string without #)
```

**`ObaResponse`** — add a new inner class for stop metadata:
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public static class Stop {
    public String id;
    public String name;
    public String direction;  // OBA "direction" field, e.g. "NW", "SE", may be null
    public String parent;     // parent stop ID, may be null or empty
}
```

Add `stops` to `References`:
```java
public static class References {
    public List<Route> routes;
    public List<Stop> stops;  // NEW
}
```

**`Departure`** — add fields:
```java
private final String routeId;
private final String routeColor;     // hex without #, e.g. "0039A6"
private final String routeTextColor; // hex without #
```

### New OBA API call: stop metadata

The backend needs to call the OBA stop endpoint to get direction and parent info:
```
GET /api/where/stop/{stopId}.json?key=TEST
```

This returns stop metadata including `direction`, `name`, and references to parent/child stops. The backend must parse this to populate the response.

### Endpoint

```
GET /api/schedule?stop={stopId}&date={YYYY-MM-DD}
```

**Request parameters:**
| Param  | Required | Description |
|--------|----------|-------------|
| `stop` | yes      | OBA stop ID, e.g. `MTA_NYCT_725S` |
| `date` | yes      | Schedule date in `YYYY-MM-DD` format |

**Response (200 OK):**
```json
{
  "stop": {
    "id": "MTA_NYCT_725S",
    "name": "Times Sq-42 St",
    "direction": "NW",
    "parentId": "MTA_NYCT_725",
    "siblingStopIds": ["MTA_NYCT_725N"]
  },
  "date": "2026-06-14",
  "timeZone": "America/New_York",
  "routes": [
    {
      "id": "MTA_NYCT_7",
      "shortName": "7",
      "color": "B933AD",
      "textColor": "FFFFFF"
    }
  ],
  "headsigns": ["34 St-Hudson Yards", "Flushing-Main St"],
  "departures": [
    {
      "departureEpochMs": 1718370720000,
      "hour": 6,
      "minute": 12,
      "routeId": "MTA_NYCT_7",
      "routeShortName": "7",
      "routeColor": "B933AD",
      "routeTextColor": "FFFFFF",
      "headsign": "34 St-Hudson Yards"
    }
  ],
  "agencyColor": null
}
```

**Key response fields:**
- `stop.direction` — the OBA `direction` field for this stop. Null if OBA does not provide it.
- `stop.parentId` — parent stop ID if this stop has a parent. Null otherwise.
- `stop.siblingStopIds` — other child stops of the same parent (excluding this stop). Empty array if none. The backend resolves this by: if this stop has a `parentId`, fetch the parent stop to get its child stop IDs, filter out the current stop ID.
- `headsigns` — deduplicated list of all `tripHeadsign` values across all departures, sorted alphabetically.
- `departures[].hour` and `departures[].minute` — pre-computed in the stop's local timezone using 24h+ format (see below).
- `agencyColor` — agency-level color from OBA if available (from `references.agencies[].color`). Null if absent.

**Error responses:**
- `400 Bad Request` — missing or malformed parameters
- `502 Bad Gateway` — OBA API unreachable or returned an error
- `404 Not Found` — stop not found in OBA

### 24h+ hour calculation

The schedule day starts at the earliest departure of the day. Departures occurring after midnight but belonging to the same service day must show as hours 24, 25, 26, etc.

Algorithm:
1. Get the schedule date from the request (e.g. `2026-06-14`).
2. For each departure, convert `departureEpochMs` to local time in the stop's timezone.
3. Compute the `LocalDate` of the departure in the stop's timezone.
4. If the departure's `LocalDate` equals the schedule date → hour is the normal hour (0–23).
5. If the departure's `LocalDate` is the day *after* the schedule date → add 24 to the hour. E.g., 1:30 AM on June 15 for schedule date June 14 becomes hour=25, minute=30.
6. If the departure's `LocalDate` is before the schedule date (should not happen in practice) → use the normal hour.

### Backend HTTP server

Use `com.sun.net.httpserver.HttpServer` (built into the JDK, no framework needed). The service should:
- Listen on port 4000 (configurable via `PORT` env var)
- Read `OBA_BASE_URL` from environment (default: `http://oba-app:8080`)
- Set CORS headers: `Access-Control-Allow-Origin: *`
- Return `Content-Type: application/json`
- Log requests to stdout

## Frontend

### Tech stack
- Svelte (latest stable, compiled)
- Vite for build tooling
- No SPA router needed — single page component
- No CSS framework — plain CSS

### URL routing
Pattern: `/stop/{stopId}`. Nginx serves `index.html` for all `/stop/*` requests. The Svelte app reads the stop ID from `window.location.pathname`.

### App structure

```
frontend/
  src/
    App.svelte
    lib/
      api.js            — fetch wrapper for /api/schedule
      timetable.js      — pure functions: group by hour, row colors, icon rules
    components/
      Header.svelte
      DatePicker.svelte
      HeadsignFilter.svelte
      Timetable.svelte
      MinuteCell.svelte
      LoadingIndicator.svelte
  public/
    index.html
  vite.config.js
  package.json
  Dockerfile
  nginx.conf
```

### Data flow

1. On mount, `App.svelte` reads stop ID from URL path.
2. Default date = today (client local date).
3. Fetch `GET /api/schedule?stop={stopId}&date={date}`.
4. Show `LoadingIndicator` while fetch is in flight.
5. On response, populate header, headsign filter (all selected), and timetable grid.
6. Date change → re-fetch.
7. Direction toggle → navigate to `/stop/{siblingStopId}`.
8. Headsign filter change → re-filter client-side, no re-fetch.

### Header logic

1. **Stop name and direction label**:
   - `stop.direction` present: `"{stop.name} ({stop.direction})"` — e.g. "Times Sq-42 St (NW)"
   - `stop.direction` absent: `"{stop.name}"`
2. **Direction toggle button**:
   - Show only when `stop.siblingStopIds` is non-empty.
   - Label: opposite direction if `stop.direction` is known (derive by looking at sibling's direction from stop name or ID), otherwise show sibling stop ID.
   - On click: navigate to `/stop/{siblingStopIds[0]}`.
   - One button per sibling (uncommon to have more than one).
3. **Headsign pills** — only when `stop.direction` is absent:
   - Show a pill badge for each currently-selected headsign below the stop name.
   - Each pill's background color = the `routeColor` of the route most frequently serving that headsign (majority route by departure count for that headsign). Text color = that route's `routeTextColor`.
   - If `routeColor` is null/empty for the resolved route, fall back to background `#666666`, text `#FFFFFF`.
   - Colors are full opacity — no lightening.

### Date picker

- Native `<input type="date">`.
- On change, triggers re-fetch.
- Positioned above the timetable grid.

### Headsign multiselect

- Checkboxes, one per unique headsign from `response.headsigns`.
- All checked by default.
- At least one must remain checked — if user unchecks the last one, re-check it and show a brief inline message beneath the filter: "At least one destination must be selected."
- Filtering is client-side.

### Timetable grid layout

```
┌──────┬─────────────────────────────────┐
│  05  │ 02  15  22  38  45  51         │
│  06  │ 01  08  12  19  25  32  40  48 │
│  25  │ 02  15  30                      │
│  26  │ 10                              │
└──────┴─────────────────────────────────┘
```

- **Hour column**: fixed width, 2-digit display, 24h+ notation (hours ≥24 display as-is: 24, 25, 26…).
- **Minute cells**: fixed-size boxes (~36–44px wide), 2-digit minute, flow left-to-right. Every cell reserves the same space regardless of whether a route icon is shown — the icon area is always present as a placeholder at the bottom right so cells never shift size.
- All hours between the first and last departure of the day are rendered, including hours with no departures (empty minutes area).
- Departures sorted by minute within each row.

### Row color algorithm

1. **Determine base color** (computed once per page load, not per row):
   - All visible departures from a single route → use that route's `routeColor` (hex, prepend `#`).
   - Else if `agencyColor` is non-null → use `agencyColor`.
   - Else → `#CCCCCC`.

2. **Apply alternating pattern**:
   - Odd hour values (1, 3, 5, …, 25): tinted background — base color at 12% opacity over white. CSS: `background-color: rgba(R, G, B, 0.12)` where RGB is parsed from the hex base color.
   - Even hour values (0, 2, 4, …, 24, 26): `background-color: #FFFFFF`.

3. Text always black (`#000000`). 12% opacity ensures legibility.

### Route icon display logic (minority route rule)

Computed after headsign filtering:

1. Count departures per route. Let `maxCount` = highest count, `totalCount` = total departures.
2. **Single route**: no icons on any cell.
3. **`maxCount > (2/3) * totalCount`** (one dominant route): icons only on minority-route cells. Dominant route cells show plain minutes.
4. **No route exceeds 2/3**: icons on all cells.

### Route icon rendering

- Small circle (`border-radius: 50%`) filled with `#` + `routeColor` (gray `#999999` if null/empty).
- `routeShortName` inside the circle in `routeTextColor`.
- Positioned at the **bottom right** of the minute cell (absolute or flex positioning within the cell).

### Loading indicator

- Pure CSS spinner: a `div` with a circular border where one side is the accent color and the rest is transparent, animated with `@keyframes` rotation. No JS library.
- Accent color = the same base color used for row tints (route color if single route, else agency color, else `#CCCCCC`). If data is not yet loaded (first fetch), fall back to `#CCCCCC`.
- Centered in place of the timetable grid during fetch.
- Replaced by data or error message on completion.

### Error handling

- 404 → "Stop not found."
- 502 / network error → "Could not load schedule. Try again later."
- 400 → "Invalid request."
- No retry logic in v1.

## Docker Compose additions

```yaml
transit-board-api:
  build:
    context: ./transit-board-api
    dockerfile: Dockerfile
  environment:
    - OBA_BASE_URL=http://oba-app:8080
    - PORT=4000
  depends_on:
    - oba_app
  restart: always

frontend:
  build:
    context: ./frontend
    dockerfile: Dockerfile
  ports:
    - "5173:80"
  depends_on:
    - transit-board-api
  restart: always
```

### Frontend Dockerfile

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

### frontend/nginx.conf

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://transit-board-api:4000;
    }

    location /stop/ {
        try_files $uri /index.html;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

### transit-board-api Dockerfile

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/transit-board-api-*.jar transit-board-api.jar
ENTRYPOINT ["java", "-jar", "transit-board-api.jar"]
```

## Edge cases

- **No departures on date**: 200 with empty `departures` array. Frontend shows "No scheduled service for this date."
- **Stop ID not found**: API 404. Frontend shows "Stop not found."
- **OBA down**: API 502. Frontend shows error.
- **All headsigns deselected**: Re-check the last unchecked one and show inline message "At least one destination must be selected." beneath the filter.
- **No parent/siblings**: Toggle button hidden.
- **Null route color**: Fall back to `#999999` for icon.
- **Null route shortName**: Display `routeId`.
- **Departures past midnight**: 1:30 AM next calendar day → hour=25, minute=30.
- **Hour with no departures**: Row is rendered with the hour label but an empty minutes area.
- **Long headsign text**: CSS truncation with ellipsis.
- **Malformed date param**: API 400.

## Acceptance criteria

- [ ] 1. `GET /api/schedule?stop=MTA_NYCT_725S&date=2026-06-14` returns valid JSON with `stop`, `routes`, `headsigns`, `departures`, `agencyColor`.
- [ ] 2. `departures[].hour` uses 24h+ notation: 1:15 AM on the day after the schedule date → `hour: 25`.
- [ ] 3. `routes[]` includes `color` and `textColor` fields from OBA route references.
- [ ] 4. `stop.siblingStopIds` is populated by resolving the parent stop's children minus the current stop.
- [ ] 5. Navigating to `/stop/MTA_NYCT_725S` renders a timetable grid with hour rows and minute cells.
- [ ] 6. Changing the date picker triggers a new API fetch and re-renders the grid.
- [ ] 7. All headsigns are selected by default; unchecking one hides its departures without an API call.
- [ ] 8. Direction toggle appears only when `siblingStopIds` is non-empty; clicking navigates to the sibling stop.
- [ ] 9. Odd-hour rows have 12%-opacity tinted background; even-hour rows are white.
- [ ] 10. Single dominant route (>2/3): only minority-route cells show icons.
- [ ] 11. No dominant route: all cells show icons.
- [ ] 12. Single route: no cells show icons.
- [ ] 13. Route icons show a colored circle with `routeColor` and `routeShortName` in `routeTextColor`.
- [ ] 14. Header: "Stop Name (direction)" when direction present; "Stop Name" + headsign pills when absent.
- [ ] 15. Loading indicator visible during fetch, hidden when data arrives.
- [ ] 16. API 404 for unknown stop → frontend shows "Stop not found."
- [ ] 17. `transit-board-api` and `frontend` services start via `docker compose up`.
- [ ] 18. Nginx proxies `/api/*` to `transit-board-api` and serves `/stop/*` with `index.html` fallback.

## Tests

### Backend (JUnit 5)
- `ScheduleApiHandlerTest.validRequest` — 200 with correct schema
- `ScheduleApiHandlerTest.missingStopParam` — 400
- `ScheduleApiHandlerTest.missingDateParam` — 400
- `ScheduleApiHandlerTest.invalidDateFormat` — 400
- `ScheduleApiHandlerTest.obaServerDown` — 502
- `HourCalculatorTest.sameDayHour` — 14:30 same day → hour=14, minute=30
- `HourCalculatorTest.pastMidnightHour` — 01:15 next day → hour=25, minute=15
- `HourCalculatorTest.midnightExact` — 00:00 next day → hour=24, minute=0
- `ObaResponseRouteColorTest.parsesColorFields` — `color` and `textColor` deserialize correctly
- `SiblingResolverTest.resolvesSiblings` — parent with children [A,B,C], current=B → returns [A,C]
- `SiblingResolverTest.noParent` — no parent → empty siblings

### Frontend (Vitest)
- `timetable.groupByHour` — departures with hours [5,5,6,6,25] → groups {5:[…], 6:[…], 25:[…]}
- `timetable.computeRowColor` — single route color `"0039A6"`, odd hour → `rgba(0,57,166,0.12)`, even → `#FFFFFF`
- `timetable.routeIconVisibility` — route A at 70% → icon=false for A, icon=true for others
- `timetable.routeIconVisibilityAllShown` — no route >2/3 → icon=true for all
- `timetable.routeIconVisibilitySingleRoute` — one route → icon=false for all
- `HeadsignFilter.allSelectedByDefault` — all checkboxes checked on mount
- `HeadsignFilter.preventEmptySelection` — unchecking last re-checks it and shows inline message

## Files to create / modify

### New
- `transit-board-api/pom.xml`
- `transit-board-api/Dockerfile`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ApiServer.java`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleApiHandler.java`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/HourCalculator.java`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/SiblingResolver.java`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleResponse.java`
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/ScheduleApiHandlerTest.java`
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/HourCalculatorTest.java`
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/SiblingResolverTest.java`
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/ObaResponseRouteColorTest.java`
- `frontend/package.json`
- `frontend/vite.config.js`
- `frontend/Dockerfile`
- `frontend/nginx.conf`
- `frontend/public/index.html`
- `frontend/src/App.svelte`
- `frontend/src/lib/api.js`
- `frontend/src/lib/timetable.js`
- `frontend/src/lib/__tests__/timetable.test.js`
- `frontend/src/components/Header.svelte`
- `frontend/src/components/DatePicker.svelte`
- `frontend/src/components/HeadsignFilter.svelte`
- `frontend/src/components/Timetable.svelte`
- `frontend/src/components/MinuteCell.svelte`
- `frontend/src/components/LoadingIndicator.svelte`
- `frontend/src/components/__tests__/HeadsignFilter.test.js`

### Modified
- `departure-board/src/main/java/dev/shinpei/departureboard/model/ObaResponse.java` — add `color`, `textColor` to `Route`; add `Stop` class; add `stops` to `References`
- `departure-board/src/main/java/dev/shinpei/departureboard/model/Departure.java` — add `routeId`, `routeColor`, `routeTextColor`
- `departure-board/src/main/java/dev/shinpei/departureboard/ScheduleParser.java` — populate new `Departure` fields
- `departure-board/src/main/java/dev/shinpei/departureboard/ObaClient.java` — add `fetchStop(String stopId)` method
- `docker-compose.yml` — add `transit-board-api` and `frontend` services
