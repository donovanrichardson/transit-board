# Spec: Headsign derivation from trip endpoint

## Goal
Derive every departure's headsign exclusively from `fetchTrip(tripId)`, replacing the current approach of reading `tripHeadsign` from `schedule-for-stop`. This eliminates OBA's `getBestHeadsign()` majority-vote bug, which incorrectly labels all outbound departures at origin stops (e.g. Huntington) with the stop name instead of the actual GTFS destination.

## Background
OBA's `schedule-for-stop` groups trips by direction and computes a single `tripHeadsign` per group via `getBestHeadsign()` — a majority vote over all trips in the group, including terminating trains. At Huntington, 152 direction-0 trains terminate there vs 39 that pass through to Port Jefferson, so "Huntington" wins and every outbound departure gets the wrong label. The `trip` endpoint reads directly from GTFS `trip_headsign` per trip and is always correct.

## Scope

### In scope
- `model/ObaTripResponse.java` — add `tripHeadsign` field to `TripEntry`
- `api/ScheduleApiHandler.java` — in the existing `tripDirectionCache` loop, also capture `tripHeadsign` from the trip response and overwrite `d.setHeadsign()` unconditionally

### Out of scope
- `ScheduleParser.java` — headsign reading there remains unchanged (it still sets the initial value; the trip loop overwrites it)
- `model/ObaTripScheduleResponse.java` — revert the `stopHeadsign` field added in the previous attempt (it is unused)
- Frontend changes
- Abbreviation map — all expected headsigns already have entries

---

## Behavior

In the existing `tripDirectionCache` loop in `buildResponse()`:

```java
tripDirectionCache.computeIfAbsent(tripId, tid -> {
    try {
        ObaTripResponse tripResponse = obaClient.fetchTrip(tid);
        if (tripResponse != null && tripResponse.data != null
                && tripResponse.data.entry != null) {
            String fetchedHeadsign = tripResponse.data.entry.tripHeadsign;
            if (fetchedHeadsign != null && !fetchedHeadsign.isEmpty()) {
                for (Departure dep : departures) {
                    if (tid.equals(dep.getTripId())) {
                        dep.setHeadsign(fetchedHeadsign);
                    }
                }
            }
            return tripResponse.data.entry.directionId;
        }
    } catch (Exception ignored) {
    }
    return null;
});
```

This overwrites `headsign` on all departures for that tripId unconditionally. If the fetch fails, the original headsign from `ScheduleParser` is preserved as fallback.

---

## Model change

`ObaTripResponse.TripEntry` gets one new field:

```java
public String tripHeadsign;
```

No comment needed — it directly mirrors the GTFS `trip_headsign` field.

---

## Revert

Remove `public String stopHeadsign` from `ObaTripScheduleResponse.StopTime` (added in the previous aborted attempt — it is now unused).

Also revert the headsign patch block added to the `tripDownstreamCache` lambda in `ScheduleApiHandler` (the `if (queriedIndex + 1 < stopTimes.size())` block that iterated departures).

---

## Effect on UI

At stops like Huntington, outbound departures that previously all showed one "Huntington" pill will now show multiple pills: "Port Jefferson", "Smithtown", "Ronkonkoma", etc. All these destinations already have abbreviations in `HEADSIGN_ABBREVIATIONS` in `lirr.js`.

---

## Acceptance criteria

- [ ] `ObaTripResponse.TripEntry` has `public String tripHeadsign`
- [ ] Every departure's headsign is overwritten with `fetchTrip(tripId).tripHeadsign` when non-null/non-empty
- [ ] When `fetchTrip` fails, original headsign from `ScheduleParser` is preserved
- [ ] `stopHeadsign` field removed from `ObaTripScheduleResponse.StopTime`
- [ ] Patch block removed from `tripDownstreamCache` lambda
- [ ] All existing tests pass

---

## Tests

- `headsignDerivedFromTripEndpoint` — mock `fetchTrip` returning `tripHeadsign: "Port Jefferson"`; assert departure headsign is "Port Jefferson" regardless of what `ScheduleParser` set
- `headsignFallsBackToScheduleParserWhenFetchTripFails` — mock `fetchTrip` throwing; assert original headsign from schedule response is preserved
- `headsignFallsBackWhenTripHeadsignIsEmpty` — mock `fetchTrip` returning `tripHeadsign: ""`; assert original headsign preserved
- `allDeparturesForSameTripGetSameHeadsign` — two departures with same tripId; assert both get patched

---

## Files that will change

- `model/ObaTripResponse.java` — add `tripHeadsign` field
- `model/ObaTripScheduleResponse.java` — remove `stopHeadsign` field
- `api/ScheduleApiHandler.java` — add headsign overwrite in `tripDirectionCache` loop; remove patch block from `tripDownstreamCache` lambda
- `test/.../ScheduleApiHandlerHeadsignPatchTest.java` — replace existing tests with new ones; remove fixtures for the old approach that no longer apply
