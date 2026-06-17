# Spec: Intermediate Stop Destinations and Headsign Pill 2/3 Color Rule

## Goal
Two related features for the LIRR transit board: (1) populate DestinationPicker with all intermediate downstream stops instead of only final headsigns, and filter departures by whether they serve a selected intermediate stop; (2) apply a 2/3 dominance threshold to headsign pill background colors in the header, matching the logic already used for row tinting.

## Scope
### In scope
- Backend: OBA trip schedule API integration (new model + client method)
- Backend: `destinations` and per-departure `downstreamStops` fields in schedule response
- Frontend: DestinationPicker uses `destinations` instead of `headsigns`
- Frontend: Timetable.svelte filters by `downstreamStops` for specific destination
- Frontend: Header.svelte `headsignColor()` applies 2/3 rule in LIRR mode

### Out of scope
- Caching trip schedule results across requests
- Parallel OBA calls
- Any changes to the subway (non-LIRR) flow
- Changing the existing `headsigns` field in the API response
- Changes to MinuteCell, HeadsignFilter, or other non-listed components

## Behavior

### Feature 1: Intermediate stop destinations

**Backend — new OBA model (`ObaTripScheduleResponse.java`):**

A new model class to deserialize `GET /api/where/trip/{tripId}/schedule.json?key=test`. Structure:

```
data.entry.stopTimes: List<StopTime>   // each has { stopId: String }
data.references.stops: List<Stop>      // each has { id: String, name: String }
```

Reuse `ObaResponse.Stop` for the references stops (it already has `id` and `name`).

**Backend — `ObaClient.fetchTripSchedule(String tripId)`:**

New method. Calls `GET {baseUrl}/api/where/trip/{tripId}/schedule.json?key={API_KEY}`. Deserializes into `ObaTripScheduleResponse`.

**Backend — `ScheduleApiHandler.buildResponse()` changes:**

After the existing `tripDirectionCache` loop, add a second loop over unique tripIds. For each tripId, call `obaClient.fetchTripSchedule(tripId)` and cache the result in a `Map<String, List<String>> tripDownstreamCache` (tripId -> ordered list of downstream stop names relative to the queried stop).

To compute downstream stops for a given trip:
1. Get the `stopTimes` list (ordered by sequence).
2. Find the index where `stopId` matches the queried `stopId`.
3. All stops after that index are downstream. Look up each `stopId` in `data.references.stops` to get the name.
4. Store as `List<String>` of stop names.

If the queried stop is not found in the trip's stop list (edge case), return an empty list.
If the OBA call fails for a trip, treat downstream stops as empty for that trip (do not fail the whole request).

New response fields:
- `ScheduleResponse.destinations: List<String>` — alphabetically sorted, deduplicated set of all downstream stop names across all trips.
- `ScheduleResponse.DepartureInfo.downstreamStops: List<String>` — ordered list of downstream stop names for this specific departure's trip.

**Frontend — `App.svelte`:**

Extract `destinations` from `data`: `$: destinations = data ? data.destinations : [];`

Pass `destinations` to `DestinationPicker` as a new prop. Keep passing `headsigns` to `Header` (unchanged).

**Frontend — `DestinationPicker.svelte`:**

Add new prop `destinations` (array of strings). The searchable list displays `destinations` instead of `headsigns`. When a specific destination is selected, dispatch `{ mode: 'specific', headsign: selectedDestination }` (same event shape, `headsign` key now holds a stop name).

**Frontend — `Timetable.svelte`:**

Change the `lirrDestinationMode === 'specific'` filter from:
```js
departures.filter((d) => d.headsign === lirrSelectedHeadsign)
```
to:
```js
departures.filter((d) => d.downstreamStops && d.downstreamStops.includes(lirrSelectedHeadsign))
```

### Feature 2: Headsign pill 2/3 color rule

**`Header.svelte` — `headsignColor(headsign)` rewrite (LIRR mode only):**

When `isLirrMode` is true:
1. Count departures per `routeId` for the given headsign. Let `total` = sum of counts.
2. Find the `routeId` with the highest count (`topCount`).
3. If `topCount > (2/3) * total` → return `{ bg: '#' + route.color, text: '#' + (route.textColor || 'FFFFFF') }`.
4. Else, count how many of those departures have `headsign` in `CITY_TERMINALS`. If `>= (2/3) * total` → return `{ bg: '#4D5357', text: '#FFFFFF' }`.
5. Else → return `{ bg: '#0039A6', text: '#FFFFFF' }`.

When `isLirrMode` is false: keep current behavior (plurality pick, no threshold).

Import `CITY_TERMINALS` from `../lib/lirr.js` in Header.svelte.

## Edge cases
- **Queried stop not in trip's stop list:** `downstreamStops` for that departure is empty.
- **Trip schedule API call fails:** Treat that trip as having no downstream stops. Do not fail the request.
- **All departures for a headsign are on distinct routes (no plurality):** Falls through to city terminal check, then to MTA blue.
- **Headsign has zero departures:** `headsignColor` returns existing fallback `{ bg: '#666666', text: '#FFFFFF' }`.
- **`destinations` array is empty:** DestinationPicker shows an empty list (direction buttons still work).
- **Departure has null/undefined `downstreamStops`:** The `&&` guard in Timetable filter handles this.
- **Queried stop is the last stop in a trip:** `downstreamStops` is empty for that departure.

## Acceptance criteria
- [ ] 1. API response includes `destinations` field — sorted, deduplicated list of downstream stop names across all trips.
- [ ] 2. API response `DepartureInfo` includes `downstreamStops` field — ordered list of downstream stop names for that departure's trip.
- [ ] 3. Existing `headsigns` field is unchanged (still contains `tripHeadsign` values).
- [ ] 4. `ObaClient.fetchTripSchedule(tripId)` calls the correct OBA endpoint and deserializes the response.
- [ ] 5. Trip schedule results are cached per-tripId within `buildResponse()` (no duplicate calls for same tripId).
- [ ] 6. If `fetchTripSchedule` throws, the departure's `downstreamStops` is empty and the request does not fail.
- [ ] 7. DestinationPicker displays `destinations` (not `headsigns`) in its searchable list.
- [ ] 8. Selecting a specific destination filters Timetable to departures where `downstreamStops.includes(selectedDestination)`.
- [ ] 9. Inbound/Outbound buttons in DestinationPicker still filter by `directionId` (unchanged behavior).
- [ ] 10. `headsignColor()` in LIRR mode: if one routeId has >2/3 of departures for that headsign, uses that route's color.
- [ ] 11. `headsignColor()` in LIRR mode: if no route dominates but >=2/3 of departures have a city-terminal headsign, uses `#4D5357` with white text.
- [ ] 12. `headsignColor()` in LIRR mode: otherwise uses `#0039A6` with white text.
- [ ] 13. `headsignColor()` in non-LIRR mode: unchanged (plurality pick, no threshold).
- [ ] 14. Subway API responses are unaffected.

## Tests to write

### Backend (Java — `transit-board-api`)
- **`ObaTripScheduleResponseDeserializationTest`**: Verify `ObaTripScheduleResponse` correctly deserializes a fixture JSON with `stopTimes` and `references.stops`.
- **`ObaClientFetchTripScheduleTest`**: Mock OBA server returns trip schedule JSON; verify `fetchTripSchedule()` returns correct stop data.
- **`ScheduleApiHandlerLirrTest.lirrResponseIncludesDestinations`**: Assert response JSON has `destinations` array with expected stop names.
- **`ScheduleApiHandlerLirrTest.lirrDepartureIncludesDownstreamStops`**: Assert each departure has `downstreamStops` array with correct stop names in order.
- **`ScheduleApiHandlerLirrTest.lirrDestinationsAreSortedAndDeduplicated`**: With multiple trips sharing stops, verify `destinations` is sorted alphabetically with no duplicates.
- **`ScheduleApiHandlerLirrTest.tripScheduleFailureDoesNotBreakResponse`**: Mock returns 500 for trip schedule; verify response still returns 200 with empty `downstreamStops`.
- **`ScheduleApiHandlerTest.subwayResponseStillWorks`**: Existing subway test still passes.

### Frontend (Vitest)
- **`DestinationPicker.test.js` — `displaysDestinationsNotHeadsigns`**: Pass `destinations` prop; verify destination names render in list.
- **`DestinationPicker.test.js` — `searchFiltersDestinations`**: Type in search box; verify filtering works against `destinations`.
- **`DestinationPicker.test.js` — `selectingDestinationDispatchesCorrectEvent`**: Click a destination; verify `{ mode: 'specific', headsign: destinationName }`.
- **`Header.test.js` — `lirrHeadsignColorDominantRoute`**: One routeId has >2/3 departures; pill uses that route's color.
- **`Header.test.js` — `lirrHeadsignColorCityTerminalFallback`**: No dominant route, >=2/3 city terminals; pill uses `#4D5357`.
- **`Header.test.js` — `lirrHeadsignColorMtaBlueFallback`**: No dominant route, <2/3 city terminals; pill uses `#0039A6`.
- **`Header.test.js` — `nonLirrHeadsignColorUnchanged`**: Non-LIRR mode; plurality behavior, no threshold.

## Files that will change

### New files
- `transit-board-api/src/main/java/dev/shinpei/transitboard/model/ObaTripScheduleResponse.java`
- `transit-board-api/src/test/resources/fixtures/lirr-trip-001-schedule-response.json`
- `frontend/src/components/__tests__/Header.test.js`

### Modified files
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ObaClient.java` — add `fetchTripSchedule()`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleResponse.java` — add `destinations`, `downstreamStops`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleApiHandler.java` — call `fetchTripSchedule()`, compute downstream stops
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/ScheduleApiHandlerLirrTest.java` — add new tests
- `frontend/src/App.svelte` — extract `destinations`, pass to DestinationPicker
- `frontend/src/components/DestinationPicker.svelte` — add `destinations` prop
- `frontend/src/components/Timetable.svelte` — update specific-destination filter
- `frontend/src/components/Header.svelte` — rewrite `headsignColor()` with 2/3 rule, import CITY_TERMINALS
- `frontend/src/components/__tests__/DestinationPicker.test.js` — update for `destinations` prop
