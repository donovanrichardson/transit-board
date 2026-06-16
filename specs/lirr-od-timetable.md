# Spec: LIRR Origin-Destination Timetable

## Goal
Extend the transit-board web app to support LIRR stops. A new home page (`/`) lets users pick an origin stop. LIRR stop pages (`/stop/LI_{id}`) show a destination picker (specific stop, All Inbound, or All Outbound) that filters the existing Japanese hour/minute timetable grid. Minute cells display destination stop_code abbreviations using the same visibility rule as subway route icons. Existing subway rendering is unchanged.

## Scope
### In scope
- `transit-board/frontend/src/App.svelte` — add routing for `/` (home page) vs `/stop/{id}` (stop page); detect LIRR via `LI_` prefix
- `transit-board/frontend/src/components/HomePage.svelte` — new: origin stop search/select UI
- `transit-board/frontend/src/components/DestinationPicker.svelte` — new: destination/direction picker for LIRR stops
- `transit-board/frontend/src/components/MinuteCell.svelte` — add bottom-right headsign abbreviation indicator for LIRR
- `transit-board/frontend/src/components/Timetable.svelte` — pass through new LIRR-specific props
- `transit-board/frontend/src/components/Header.svelte` — show destination picker instead of headsign filter for LIRR stops
- `transit-board/frontend/src/lib/api.js` — add `fetchStops()` function
- `transit-board/frontend/src/lib/timetable.js` — add `computeHeadsignAbbreviationVisibility()`, `filterByDestination()`, `filterByDirection()` helpers
- `transit-board/frontend/src/lib/lirr.js` — new: `isLirr()` helper, `CITY_TERMINALS` constant
- `transit-board/transit-board-api/src/main/java/.../api/ApiServer.java` — register new `/api/stops` endpoint
- `transit-board/transit-board-api/src/main/java/.../api/StopsApiHandler.java` — new: serves list of all LIRR stops
- `transit-board/transit-board-api/src/main/java/.../api/HeadsignAbbreviationResolver.java` — new: headsign→stopCode mapping logic
- `transit-board/transit-board-api/src/main/java/.../api/ObaClient.java` — add `fetchStopsForAgency()` method
- `transit-board/transit-board-api/src/main/java/.../api/ScheduleApiHandler.java` — build `headsignAbbreviations` map in response
- `transit-board/transit-board-api/src/main/java/.../api/ScheduleResponse.java` — add `headsignAbbreviations` field; add `tripId` to DepartureInfo
- `transit-board/transit-board-api/src/main/java/.../api/ScheduleParser.java` — extract `tripId` from OBA response
- `transit-board/transit-board-api/src/main/java/.../model/Departure.java` — add `tripId` field
- `transit-board/transit-board-api/src/main/java/.../model/ObaResponse.java` — add `tripId` to ScheduleStopTime; add `stopCode` to Stop
- `transit-board/transit-board-api/src/main/java/.../model/ObaStopsForAgencyResponse.java` — new: OBA response model for stops-for-agency
- `transit-board/frontend/nginx.conf` — ensure `/` serves index.html (SPA fallback)

### Out of scope
- Docker-compose, OBA config, or GTFS bundle changes
- Real-time arrival data
- Multi-stop view or trip detail view
- Subway stop search (home page lists LIRR only; existing `/stop/MTA NYCT_*` URLs continue unchanged)
- Peak/off-peak display in the timetable

---

## Behavior

### Home page (`/`)

Displays a searchable list of all LIRR stops for origin selection.

**UI elements:**
1. Title: "Transit Board"
2. Subtitle: "Select your origin station"
3. Text input with placeholder "Search stations..." — filters list as user types (case-insensitive substring match on stop name)
4. Flat alphabetical list of all LIRR stop names. Each item navigates to `/stop/{stopId}` on click.

**Data source:** `GET /api/stops?agency=LI` — called on mount.

**Empty state:** Show "No stations found." when search matches nothing.
**Error state:** Show "Could not load stations. Try again later." when API fails.

---

### Stop page (`/stop/{stopId}`)

URL pattern unchanged. Detection:
- `stopId` starts with `LI_` → LIRR mode
- Otherwise → subway mode, existing behavior unchanged

### LIRR stop page layout

1. **Header** — origin stop name (no direction label; direction handled by destination picker)
2. **Date picker** — same as subway, unchanged
3. **Destination picker** — replaces HeadsignFilter for LIRR
4. **Timetable grid** — same hour/minute grid, filtered by selected destination or direction

---

### Destination picker

Appears only for LIRR stops (`LI_` prefix). Two sections:

**Direction shortcuts (top):**
- "All Inbound" — shows all departures toward city terminals
- "All Outbound" — shows all departures away from city terminals

**Specific destination search (below):**
- Text input: "Search destinations..."
- Flat alphabetical list of all unique headsigns present in today's schedule for this stop
- Selecting a headsign filters departures to that headsign only

**State machine:** Exactly one selection active at a time.
- Default on page load: **"All Inbound"** selected
- Clicking "All Outbound" deselects everything else
- Clicking a specific destination deselects direction buttons
- Clicking the already-active item: no-op

**Direction detection:**
A departure is **inbound** if its `directionId` is `"1"`. A departure is **outbound** if its `directionId` is `"0"`. This is the GTFS `direction_id` field, exposed per departure in the schedule API response (see backend changes). Do not use the headsign to infer direction — inbound trains can terminate at intermediate stops (e.g. Port Jefferson → Huntington has `direction_id=1` but "Huntington" is not a city terminal).

**City terminal list (used for icon color only — not for direction):**
The following headsigns indicate a train going all the way to a Manhattan/Brooklyn terminus. These departures render with a **gray** icon regardless of route color:
- `Penn Station`
- `Grand Central`
- `Atlantic Terminal`
- `Jamaica`
- `Woodside`
- `Hunterspoint Avenue`
- `Long Island City`

> Note: verify these strings match actual OBA LIRR headsign values at implementation time and adjust if needed.

Non-city-terminal inbound trains (e.g. headsign "Huntington") are still correctly classified as inbound via `directionId`, but their icon renders in route color (not gray).

---

### Timetable grid filtering

Client-side only — no re-fetch on filter change.

| Selection | Filter |
|---|---|
| Specific destination | `departures.filter(d => d.headsign === selected)` |
| All Inbound | `departures.filter(d => d.directionId === '1')` |
| All Outbound | `departures.filter(d => d.directionId === '0')` |

---

### Minute cell indicator (LIRR mode)

For LIRR stops, each minute cell shows a **headsign abbreviation** in the bottom-right corner instead of the subway route icon.

**Abbreviation source:** A hardcoded map keyed by headsign string. Values are the GTFS `stop_code` from `stops.txt`, with two manual overrides. `(Bus)` suffix headsigns share the same abbreviation as their base station.

| Headsign | Abbr | Headsign | Abbr |
|---|---|---|---|
| Amagansett | AGT | Montauk | MTK |
| Atlantic Terminal | ATL | Oyster Bay | OBY |
| Babylon | BAB | Patchogue | PGE |
| Far Rockaway | FRY | Penn Station | NYP |
| Floral Park | FPK | Port Jefferson | PJN |
| Freeport | FPT | Port Washington | PWS |
| Grand Central | GCT | Riverhead | RHD |
| Great Neck | GNK | Ronkonkoma | RON |
| Greenport | GPT | Seaford | SFD |
| Hampton Bays | HBY | Shinnecock Hills | SHC |
| Hempstead | HEM | Smithtown | STN |
| Hicksville | HVL | Southampton | SHN |
| Hunterspoint Avenue | HPA | Speonk | SPK |
| Huntington | HUN | Wantagh | WGH |
| Jamaica | JAM | West Hempstead | WHD |
| Long Beach | LBH | | |
| Long Island City | LIC | | |
| Massapequa | MQA | | |

Manual overrides from GTFS `stop_code`: Penn Station (NYK → **NYP**), Babylon (BTA → **BAB**).

**Visibility rule** (same dominance logic as subway route icon visibility, applied per headsign):
1. Count departures per headsign among currently filtered departures.
2. Single unique headsign → show no abbreviation on any cell.
3. One headsign accounts for >2/3 of filtered departures → show abbreviation only on minority-headsign cells; dominant headsign cells show nothing.
4. No headsign exceeds 2/3 → show abbreviation on all cells.

**Color rule (abbreviation text color):**
Each cell's abbreviation color is determined by the departure's `routeColor` from GTFS. All LIRR routes have a color — there is no missing-color case. The city terminal list is NOT used for individual cell coloring; it is used only for `baseColor` (row background) logic described below.

**Rendering:** Plain text (not a circle icon). Font: ~0.45rem, bold. Position: bottom-right of cell.

**Cell width:**
- Subway mode: 40px min-width (unchanged)
- LIRR mode: 46px min-width to accommodate 3-letter abbreviation alongside minute number

---

### Timetable row background color (LIRR mode)

Row tinting uses the existing `computeRowColor` function unchanged (even hours = white; odd hours = `rgba(r,g,b,0.12)`). What changes for LIRR is how `baseColor` is computed before being passed in.

**Outbound timetable `baseColor`:** Same as existing subway logic — single route → that route's color; multiple routes → `#0039A6` (MTA blue).

**Inbound timetable `baseColor`** — three-tier rule, evaluated in order:

1. **Dominant route (>2/3 of filtered inbound departures share one `routeId`):** `baseColor` = that route's `routeColor` from GTFS.
2. **City terminal majority (≥2/3 of filtered inbound departures have a headsign in the city terminal list):** `baseColor` = `#4D5357` (City Terminal Zone gray — route 12's official GTFS color).
3. **Neither:** `baseColor` = `#0039A6` (MTA blue).

City terminal headsign list (used only for tier-2 check above):
- `Penn Station`, `Grand Central`, `Atlantic Terminal`, `Jamaica`, `Woodside`, `Hunterspoint Avenue`, `Long Island City`

> Note: verify these strings match actual OBA LIRR headsign values at implementation time.

The 0.12 opacity transformation from `computeRowColor` applies to whichever `baseColor` is chosen, unchanged.

**Specific destination selected:** When the user picks a single destination, `baseColor` follows the outbound rule (single route → route color) regardless of direction, since only one headsign is shown.

---

## Backend API

### `GET /api/stops?agency={agencyId}`

Returns all stops for the given agency.

**Response (200):**
```json
{
  "stops": [
    { "id": "LI_102", "name": "Jamaica", "stopCode": "JAM" },
    { "id": "LI_141", "name": "Montauk", "stopCode": "MTK" }
  ]
}
```

- Sorted alphabetically by `name`
- Deduplicated by `name` — if multiple stop IDs share a name, return one entry (any ID)
- OBA call: `GET /api/where/stops-for-agency/LI.json?key=TEST`

**Errors:**
- `400` — missing `agency` param
- `502` — OBA unreachable

---

### `GET /api/schedule` — additions only

Two new fields added to existing response. Subway responses are unaffected (empty map).

```json
{
  "headsignAbbreviations": {
    "Penn Station": "PEN",
    "Montauk": "MTK",
    "Jamaica": "JAM"
  },
  "departures": [
    {
      "tripId": "LI_GO201_26_202",
      "hour": 6,
      "minute": 12,
      "...": "existing fields unchanged"
    }
  ]
}
```

**`headsignAbbreviations` construction:** For each unique headsign in the schedule response, find the stop in OBA's `references.stops` whose `name` matches the headsign, and use its `stopCode`. Fallback: first 3 characters of the headsign uppercased.

**`tripId`:** Extracted from `ScheduleStopTime.tripId` already present in OBA response — just not parsed previously.

---

## New OBA model fields

**`ObaResponse.ScheduleStopTime`** — add:
```java
public String tripId;
```

**`ObaResponse.Stop`** — add:
```java
public String stopCode;
```

---

## Edge cases

- **LIRR stop with no departures on selected date:** 200 with empty departures. Grid shows "No scheduled service." Destination picker shows no specific destinations (direction shortcuts still visible).
- **Headsign not matching any stop name in references:** First 3 chars uppercased as fallback abbreviation.
- **Single departure in filtered view:** No abbreviation shown (single-headsign rule).
- **Jamaica as origin, All Inbound:** Shows trains to other city terminals. Valid.
- **Navigating directly to `/stop/LI_102`:** Works — page fetches its own schedule, no home-page visit required.
- **Subway stop URL (`/stop/MTA NYCT_725S`):** No destination picker, no abbreviation, 40px cells. Unchanged.

---

## Acceptance criteria

- [ ] 1. `/` shows a searchable list of LIRR stations sorted alphabetically.
- [ ] 2. Typing in the search box filters stations by case-insensitive substring match.
- [ ] 3. Clicking a station navigates to `/stop/{stopId}`.
- [ ] 4. `/stop/LI_102` shows destination picker with "All Inbound", "All Outbound", and a searchable destination list.
- [ ] 5. "All Inbound" is selected by default on LIRR stop page load.
- [ ] 6. "All Inbound" filters departures to city-terminal headsigns only.
- [ ] 7. "All Outbound" filters departures to non-city-terminal headsigns only.
- [ ] 8. Selecting a specific destination filters departures to that headsign only.
- [ ] 9. Timetable grid re-renders after each filter change with no re-fetch.
- [ ] 10. LIRR minute cells show 3-letter stop_code abbreviation bottom-right when visibility rule triggers (2/3 dominance logic, per headsign).
- [ ] 11. City terminal headsign abbreviations render gray; non-terminal abbreviations render in route color.
- [ ] 12. LIRR minute cells are 46px wide; subway cells remain 40px.
- [ ] 13. `/stop/MTA NYCT_725S` renders identically to before — no destination picker, no abbreviation, 40px cells.
- [ ] 14. `GET /api/stops?agency=LI` returns alphabetically sorted, name-deduplicated LIRR stops with `stopCode`.
- [ ] 15. `GET /api/schedule?stop=LI_102&date=2026-06-15` includes `headsignAbbreviations` map with correct stop_code values.
- [ ] 16. Subway schedule response has `headsignAbbreviations: {}`.
- [ ] 17. All existing backend tests pass.
- [ ] 18. All existing frontend tests pass.

---

## Tests to write

### Backend (JUnit 5)
- `StopsApiHandlerTest.returnsLirrStops` — mock OBA stops-for-agency response; verify 200 with sorted, deduplicated list including stopCode
- `StopsApiHandlerTest.missingAgencyParam` — verify 400
- `StopsApiHandlerTest.obaDown` — verify 502
- `ScheduleApiHandlerTest.lirrResponseIncludesHeadsignAbbreviations` — mock LIRR schedule; verify map present with correct values
- `ScheduleApiHandlerTest.subwayResponseHasEmptyHeadsignAbbreviations` — existing fixture; verify `{}`
- `ScheduleParserTest.extractsTripId` — verify tripId populated
- `HeadsignAbbreviationResolverTest.matchesStopByName` — "Jamaica" + stopCode "JAM" → "JAM"
- `HeadsignAbbreviationResolverTest.fallbackToTruncation` — unmatched headsign → first 3 chars uppercased

### Frontend (Vitest)
- `lirr.isLirr` — `"LI_102"` → true; `"MTA NYCT_725S"` → false
- `lirr.cityTerminals` — includes expected values
- `timetable.computeHeadsignAbbreviationVisibility` — single headsign → all false; dominant >2/3 → minority true; no dominant → all true
- `timetable.filterByDirection.inbound` — returns only city terminal headsigns
- `timetable.filterByDirection.outbound` — returns only non-city-terminal headsigns
- `DestinationPicker.defaultsToAllInbound` — "All Inbound" active on mount
- `DestinationPicker.searchFiltersDestinations` — typing filters destination list
- `DestinationPicker.mutuallyExclusive` — selecting destination deactivates direction buttons and vice versa
- `HomePage.searchFiltersStations` — typing filters station list
- `HomePage.clickNavigates` — clicking station calls navigation to correct URL

---

## New files
- `frontend/src/components/HomePage.svelte`
- `frontend/src/components/DestinationPicker.svelte`
- `frontend/src/lib/lirr.js`
- `frontend/src/lib/__tests__/lirr.test.js`
- `frontend/src/components/__tests__/DestinationPicker.test.js`
- `frontend/src/components/__tests__/HomePage.test.js`
- `api/src/main/java/.../api/StopsApiHandler.java`
- `api/src/main/java/.../api/HeadsignAbbreviationResolver.java`
- `api/src/main/java/.../model/ObaStopsForAgencyResponse.java`
- `api/src/test/java/.../api/StopsApiHandlerTest.java`
- `api/src/test/java/.../api/HeadsignAbbreviationResolverTest.java`
- `api/src/test/resources/fixtures/stops-for-agency-lirr.json`
- `api/src/test/resources/fixtures/lirr-schedule-response.json`
