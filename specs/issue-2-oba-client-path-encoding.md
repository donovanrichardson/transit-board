# Spec: Encode OBA path identifiers in ObaClient (transit-board#2)

## Description

`transit-board-api` builds OneBusAway requests by concatenating identifiers into URL paths. Only spaces are escaped, so characters such as `?`, `/`, and `#` reach OBA unchanged. The public, unauthenticated endpoints `GET /api/schedule?stop=` and `GET /api/stops?agency=` pass user input straight into these paths. Benign production probes confirmed that OBA receives attacker-altered requests, and that `..` path segments are accepted.

This spec fixes the injection itself. It does not decide the longer-term Struts2 question tracked in transit-board#2 (in-place upgrade vs. Maglev migration).

## Scope

### In scope
- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ObaClient.java`: encode every identifier placed in a path segment, in all `fetch*` methods.
- Regression tests in `transit-board-api/src/test/java/...` using the existing mock-OBA-server pattern (for example `ObaClientFetchTripScheduleTest`).

### Out of scope
- Struts2 upgrade or Maglev migration (transit-board#2 analysis).
- Restricting which OBA paths the API may reach (interim control, separate decision).
- Host firewall / ufw-docker state (needs root; tracked separately).
- Rotating the OBA API key or changing OBA access-log format.
- Changing HTTP response codes or error messages for valid requests.

## Behavior

1. Add one private helper in `ObaClient`, for example `pathSegment(String raw)`, that percent-encodes a value as a single path segment. Use `URLEncoder.encode(raw, UTF_8)` and then replace `+` with `%20`, so spaces keep their current encoding and nothing else changes for valid IDs.
2. Apply the helper to every identifier interpolated into a path:
   - `fetchSchedule` (`stopId`), `fetchStop` (`stopId`), `fetchTrip` (`tripId`), `fetchTripSchedule` (`tripId`).
   - `fetchStopsForAgency` (`agencyId`), `fetchAgency` (`agencyId`). These currently have no encoding at all.
3. Remove the ad hoc `replace(" ", "%20")` calls, which the helper replaces.
4. Do not change the `?key=` / `&date=` query construction, which uses fixed values.
5. Existing valid IDs (for example `1_1`, `LI_12345`, `MTA_...`) must produce the same request URL as before. Only characters that were previously unsafe change.

## Edge cases

- **Valid IDs with underscores, colons, dots, or hyphens:** `URLEncoder` leaves `_`, `-`, `.`, and `*` alone, and encodes `:`. The helper must produce identical URLs for IDs in the real OBA format. The regression test must assert this for `1_1` and `LI_12345`.
- **Spaces:** must still become `%20`, not `+`.
- **Dot segments (`..`, `.`):** after encoding, `/` becomes `%2F`, so `..` cannot form a path traversal. A bare `..` as the whole ID is harmless within one segment, but test it anyway.
- **Null or empty IDs:** handlers already reject these before calling `ObaClient`. The helper does not need its own validation (trust boundary is the handler).
- **Unicode IDs:** encoded as UTF-8 percent sequences. Assert one case.
- **Tomcat encoded-slash handling:** OBA (Tomcat) may reject `%2F` with 400 by default. That is an acceptable outcome: a request that tried to inject a path now fails, instead of succeeding.

## Acceptance criteria

- [ ] A stop ID containing `?` is sent to OBA as `%3F` inside one path segment, and the query string OBA receives is only the fixed `key` and `date` parameters.
- [ ] A stop ID containing `/` or `..` is sent with `%2F`, not as a path separator.
- [ ] `stopId`, `tripId`, and `agencyId` are all encoded in all six `fetch*` methods that build a path.
- [ ] Requests for valid IDs (`1_1`, `LI_12345`) produce URLs identical to the pre-change URLs (asserted by test).
- [ ] Spaces still encode as `%20`.
- [ ] All existing unit tests still pass.
- [ ] Production: the public probe `GET /api/schedule?stop=1_1%3Fzzprobe%3D1` no longer changes OBA's query string (verified in the OBA access log, not only by API response body).

## Tests to write (red-green TDD, implementer)

Write these first and confirm they fail against the current code:

1. `fetchSchedule("1_1?zz=1")`: the mock OBA server receives a request whose path contains `1_1%3Fzz%3D1.json` and whose query is exactly `key=…&date=…`.
2. `fetchSchedule("1_1/../x")`: the mock receives `%2F` (no raw `/` inside the stop segment).
3. `fetchStopsForAgency("LI/../x")`: same check on the agency path.
4. `fetchStop`, `fetchTrip`, `fetchTripSchedule`, `fetchAgency`: one injection case each.
5. Valid-ID regression: `fetchSchedule("1_1")` and `fetchStopsForAgency("LI")` produce the URLs they produced before the change (assert the exact string).
6. Space case: `fetchStop("a b")` yields `a%20b`.

Then implement the helper, make the tests pass, and refactor the duplicated `replace` calls away.

## Manual verification (orchestrator, after merge and deploy)

Not part of the automated loop; the implementer does not start containers.

1. Rebuild and restart `transit-board-api`.
2. Confirm normal responses for a known stop (`GET /api/schedule?stop=<known stop>`) are unchanged.
3. Repeat the benign `?` probe. Check `docker logs oba_app` for the request line: the query must contain only `key` and `date`.
4. Check `/api/stops?agency=` with a known agency and a `?` probe the same way.

## Rollback

Revert the commit and redeploy `transit-board-api`. The change touches only request URL construction, so rollback is a single-container restart.

## Files that will change

- `transit-board-api/src/main/java/dev/shinpei/transitboard/api/ObaClient.java`
- `transit-board-api/src/test/java/dev/shinpei/transitboard/api/ObaClientPathEncodingTest.java` (new)
