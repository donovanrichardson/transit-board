# Spec: Timezone fix and DST fall-back repeat display

## Goal
Fix the transit-board API to use the correct agency timezone (instead of always falling back to UTC) and add a visual indicator in the frontend for trains that depart during the second repetition of the 1:00 AM hour on DST fall-back days.

## Scope
### In scope
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ObaClient.java` — new `fetchAgency` method
- `transit-board-api/src/main/java/dev/shinpei/transitboard/model/ObaAgencyResponse.java` — new model class
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleApiHandler.java` — replace broken timezone lookup with agency fetch; compute and set `dstRepeat` on each departure
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleResponse.java` — add `boolean dstRepeat` field to `DepartureInfo`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/HourCalculator.java` — add static method to detect DST second-repetition
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/HourCalculatorTest.java` — new test cases for DST detection
- `frontend/src/components/MinuteCell.svelte` — red text color on the minute number when `departure.dstRepeat` is true

### Out of scope
- Caching the agency timezone across requests
- Handling agencies other than LIRR
- Spring-forward gap handling beyond detection (no trains are scheduled in the skipped hour)

## Behavior

### Fix 1: Agency timezone resolution

**Current behavior:** `ScheduleApiHandler.buildResponse` reads `scheduleOba.data.entry.timeZone`, which is always `null` because the OBA schedule-for-stop response does not include a `timeZone` field. The code falls back to `"UTC"`, causing all displayed departure times to be 4-5 hours wrong.

**New behavior:**
1. After fetching the schedule response, extract the agency ID from the first route ID in `scheduleOba.data.references.routes`. Route IDs have the format `"LI_10"` — split on `"_"` and take the first element (`"LI"`).
2. Call a new `ObaClient.fetchAgency(agencyId)` method, which hits `GET /api/where/agency/{agencyId}.json?key=...`.
3. Parse the response into a new `ObaAgencyResponse` model that captures `data.entry.timezone`.
4. Use this timezone string to construct the `ZoneId` used by `HourCalculator.compute`.
5. Set `response.timeZone` on the `ScheduleResponse` to the fetched timezone string.
6. If no routes exist in the schedule response, fall back to `"America/New_York"`.

**ObaClient.fetchAgency signature:**
```java
public ObaAgencyResponse fetchAgency(String agencyId)
// URL: {baseUrl}/api/where/agency/{agencyId}.json?key={apiKey}
```

**ObaAgencyResponse model:**
```java
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObaAgencyResponse {
    public DataWrapper data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataWrapper { public AgencyEntry entry; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgencyEntry { public String timezone; }
}
```
Note: field is `timezone` (lowercase) to match OBA's JSON.

### Fix 2: DST fall-back second-repetition flag

**New field:** `ScheduleResponse.DepartureInfo` gains `public boolean dstRepeat` (defaults to `false`).

**New static method in HourCalculator:**
```java
public static boolean isDstRepeat(long epochMs, ZoneId zone)
```
Logic:
1. Convert `epochMs` to `ZonedDateTime` via `Instant.ofEpochMilli(epochMs).atZone(zone)`.
2. Call `zone.getRules().getValidOffsets(zdt.toLocalDateTime())`.
3. Return `true` if and only if `validOffsets.size() == 2` AND `zdt.getOffset().equals(validOffsets.get(1))`.

`validOffsets.size() == 2` means DST overlap. Index 1 is the post-transition (winter) offset — the second repetition.

**In `ScheduleApiHandler.buildResponse`:** After computing `hourMinute`, call `HourCalculator.isDstRepeat(epochMs, zone)` and set `di.dstRepeat`.

**Frontend — `MinuteCell.svelte`:** When `departure.dstRepeat` is truthy, apply `color: red` to the `.minute-value` span (minute number text only).

API response example:
```json
{
  "departures": [
    { "hour": 1, "minute": 15, "dstRepeat": false },
    { "hour": 1, "minute": 45, "dstRepeat": true }
  ]
}
```

## Edge cases

| Scenario | Behavior |
|---|---|
| No routes in schedule response | Fall back to `"America/New_York"` for timezone |
| Agency fetch fails | Propagate exception → 502 to client (no silent UTC fallback) |
| Route ID without underscore | Use entire route ID as agency ID (avoids AIOOBE) |
| Normal day | `isDstRepeat` returns `false` for all departures |
| Spring-forward gap | `validOffsets.size() == 0`; returns `false` |
| Fall-back first repetition (EDT, -04:00) | `validOffsets.size() == 2`, offset == get(0); returns `false` |
| Fall-back second repetition (EST, -05:00) | `validOffsets.size() == 2`, offset == get(1); returns `true` |

## Acceptance criteria

- [ ] All displayed departure times use the agency's actual timezone, not UTC
- [ ] `ScheduleResponse.timeZone` field contains the agency timezone string (e.g. `"America/New_York"`)
- [ ] `DepartureInfo` includes a `dstRepeat` boolean, defaulting to `false`
- [ ] On a normal day, all departures have `dstRepeat: false`
- [ ] On a DST fall-back day, first-repetition departures have `dstRepeat: false`
- [ ] On a DST fall-back day, second-repetition departures have `dstRepeat: true`
- [ ] Frontend renders minute number in red when `departure.dstRepeat` is true
- [ ] Frontend renders minute number in default color when `departure.dstRepeat` is false or absent
- [ ] All existing `HourCalculatorTest` tests still pass
- [ ] New DST tests pass

## Tests: `HourCalculatorTest.java`

| Test | Setup | Expected |
|---|---|---|
| `isDstRepeat_normalDay_returnsFalse` | 14:30 on 2026-06-14 in America/New_York | `false` |
| `isDstRepeat_springForwardGap_returnsFalse` | 03:00 on 2026-03-08 in America/New_York | `false` |
| `isDstRepeat_fallBackFirstRepetition_returnsFalse` | 01:30 EDT (-04:00) on 2026-11-01; build epoch via `ZonedDateTime.of(..., ZoneOffset.ofHours(-4))` | `false` |
| `isDstRepeat_fallBackSecondRepetition_returnsTrue` | 01:30 EST (-05:00) on 2026-11-01; build epoch via `ZonedDateTime.of(..., ZoneOffset.ofHours(-5))` | `true` |

## Files that will change

- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ObaClient.java`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/model/ObaAgencyResponse.java` — **new**
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleApiHandler.java`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ScheduleResponse.java`
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/HourCalculator.java`
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/HourCalculatorTest.java`
- `frontend/src/components/MinuteCell.svelte`
