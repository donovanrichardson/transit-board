# Spec: Build OBA request URLs safely in ObaClient (transit-board#2)

## Description

`transit-board-api` builds OneBusAway requests by concatenating identifiers into URL paths. Only spaces are escaped, so characters such as `?`, `/`, and `#` reach OBA unchanged. The public, unauthenticated endpoints `GET /api/schedule?stop=` and `GET /api/stops?agency=` pass user input straight into these paths. Benign production probes confirmed that OBA receives attacker-altered requests, and that `..` path segments are accepted.

This spec fixes the injection by routing every OBA request through one URL builder that encodes path segments and query values, and by rejecting identifiers outside an allowlist before any request is made. It does not decide the longer-term Struts2 question tracked in transit-board#2 (in-place upgrade vs. Maglev migration).

## Scope

### In scope
- A new `ObaUrlBuilder` class in the `dev.shinpei.transitboard.api` package. It takes path segments and query parameters separately, encodes each one for its position, and is the only place OBA URLs are assembled.
- The `ObaClient` class in the same package: all six `fetch*` methods that build a request switch to `ObaUrlBuilder`. The fixed `key` parameter is added by the builder.
- An identifier allowlist, enforced in `ObaUrlBuilder` for every path segment: `^[A-Za-z0-9_.:-]+$`.
- A feed-ID check in the `updater.py` module of the GTFS updater, run on every newly promoted feed, mirroring the existing headsign-coverage check. It checks the same pattern and writes an event to `gtfs_updater.jsonl`.
- The `ScheduleApiHandler` class and the `StopsApiHandler` class: an identifier that fails the allowlist returns HTTP 400 with a clear message. It does not produce a 502.
- Regression tests in the `ObaUrlBuilderTest` class (new) and the `ObaClientPathEncodingTest` class (new), in the `dev.shinpei.transitboard.api` package, using the existing mock-OBA-server pattern.

### Out of scope
- Typed encoders for query parameters the app does not send today (`time`, `minutesBefore`, `serviceDate`). They are added when a method first sends one.
- Struts2 upgrade or Maglev migration (transit-board#2 analysis).
- Restricting which OBA paths the API may reach (interim control, separate decision).
- Host firewall / ufw-docker state (needs root; tracked separately).
- Rotating the OBA API key or changing OBA access-log format.

## Feed data check (done before writing this spec)

The allowlist was checked against the committed feed in the gtfs-out directory of the OBA server bundle:
- `stops.txt`: 126 distinct `stop_id` values, all match `^[A-Za-z0-9_.:-]+$`.
- `trips.txt`: 2,074 distinct `trip_id` values, all match.
- `stop_times.txt`: its `stop_id` values are the same 126 stops.
- `agency.txt`: one `agency_id`, `LI`, matches.

GTFS allows any UTF-8 in an ID, so this pattern is narrower than the spec permits. It is safe for this project's current feed only.

Future feeds are not covered by a committed-feed test. The GTFS updater loads new feeds at runtime, and a feed can change without a commit. Every promoted feed must therefore be checked by the updater (Behavior 7).

## Behavior

1. `ObaUrlBuilder` exposes:
   - `path(String segment)`: appends one path segment. Encodes it with `URLEncoder.encode(raw, UTF_8)` and replaces `+` with `%20`, so `/`, `?`, `#`, `%`, `;`, and `\` are always escaped.
   - `query(String name, String value)`: appends one query parameter. Encodes the name and value the same way.
   - `build()`: returns the full URL, adding `key` from configuration.
2. Before encoding, `path` checks the segment against `^[A-Za-z0-9_.:-]+$`. A non-matching or empty segment throws `IllegalArgumentException`.
3. `ObaClient` builds every request through `ObaUrlBuilder`. No method concatenates strings into a URL. Each method passes its identifier to `path(...)`, and the `date` value to `query(...)`.
4. `ScheduleApiHandler` and `StopsApiHandler` catch `IllegalArgumentException` from the client path and return HTTP 400 with `{"error": "Invalid stop ID"}` or `{"error": "Invalid agency ID"}`. The message names the parameter only, not the rejected value.
5. Requests for valid IDs produce exactly the URLs they produced before this change. Only the characters that were previously unsafe change behaviour.
6. A test loads the committed feed IDs from the gtfs-out directory of the OBA server bundle and asserts each one passes the allowlist. This is a regression guard for the committed feed only.
7. The GTFS updater runs a new check, `check_id_allowlist`, right after `check_headsign_coverage()` in the success branch of `run_update_check` in the `updater.py` module. It reads `stops.txt`, `trips.txt`, `stop_times.txt`, and `agency.txt` from the same promoted feed as the headsign check, and collects every ID that fails `^[A-Za-z0-9_.:-]+$`, grouped by file. It writes one `id_allowlist` event to `gtfs_updater.jsonl`, with `ts`, `non_matching_ids` (a map of file name to a sorted list), and `total_checked`. Promotion is not blocked.
   - A non-empty `non_matching_ids` is not a security incident, because the API still rejects those IDs at runtime. It means the affected stops or trips return HTTP 400 in production, so the feed needs review before the next update.
   - Surfacing the event at session start is a follow-up change to the root `CLAUDE.md` session-start checks, not part of this issue.

## Edge cases

- **Valid IDs with underscores, colons, dots, or hyphens:** must pass the allowlist and produce identical URLs to today (for example `1_1`, `LI_12345`).
- **Spaces:** the allowlist rejects spaces, so `a b` returns HTTP 400. Previously it was forwarded as `%20`. This is an intended behaviour change: no feed ID in this project contains a space.
- **Dot segments:** `..` passes the allowlist, but the builder always adds `.json` after the segment, so it cannot form a dot segment. Test this case.
- **Null or empty IDs:** handlers already reject these before calling the client.
- **Tomcat encoded-slash handling:** the allowlist removes `/`, so `%2F` should never be sent. If a valid ID ever needs `/`, that is a feed-data question for review, not a silent encoding change.
- **Key parameter:** `key` comes from configuration and is added only by `build()`, so no caller can set or override it.

## Acceptance criteria

- [ ] `ObaUrlBuilder` is the only place OBA URLs are constructed. No `ObaClient` method concatenates a URL.
- [ ] A stop ID containing `?`, `/`, `..`, `#`, `%`, `;`, `\`, or a space is rejected with HTTP 400 at the public API and never reaches OBA.
- [ ] Valid stop, trip, and agency IDs from the committed feed produce URLs identical to the pre-change URLs (asserted by test).
- [ ] The `key` parameter appears exactly once in every OBA request, and no caller can override it.
- [ ] The feed-ID test passes on the committed feed.
- [ ] After a successful feed promotion, the updater writes an `id_allowlist` event to `gtfs_updater.jsonl`, and promotion still completes when IDs fail the check.
- [ ] All existing unit tests still pass.
- [ ] Production: the public probe `GET /api/schedule?stop=1_1%3Fzzprobe%3D1` returns HTTP 400, and the OBA access log shows no new request for it.

## Tests to write (red-green TDD, implementer)

Write these first and confirm they fail against the current code:

1. `ObaUrlBuilder.path("1_1?zz=1")` throws `IllegalArgumentException`.
2. `ObaUrlBuilder.path("1_1/../x")` throws.
3. `ObaUrlBuilder.path("a b")` throws, and `path("1_1")` returns `1_1`.
4. `ObaUrlBuilder.query("date", "2026-10-04")` produces `date=2026-10-04`, and a value containing `&` is encoded as `%26`.
5. `build()` produces a URL with exactly one `key=` parameter.
6. The feed-ID test described in Behavior 6.
7. `ObaClient.fetchSchedule("1_1")` sends the exact URL the old code sent (assert the full string against the mock OBA server).
8. `ObaClient.fetchStopsForAgency("LI")` sends the exact URL the old code sent.
9. `ObaClient.fetchStop("1_1?zz=1")` throws before any HTTP call (the mock OBA server receives no request).
10. Each of the six `fetch*` methods rejects one injection case.
11. `ScheduleApiHandler` returns HTTP 400 for `stop=1_1%3Fzz%3D1`, with no request to the mock OBA server.
12. `StopsApiHandler` returns HTTP 400 for an agency ID containing `/`.
13. `check_id_allowlist` (updater test module) on a fixture feed with one valid stop and one stop ID containing `?`: writes one `id_allowlist` event listing that stop ID under `stops.txt` and nothing else.
14. `check_id_allowlist` on a fixture feed where every ID is valid: writes an event with an empty `non_matching_ids`.
15. `check_id_allowlist` on a feed missing `agency.txt`: logs a warning and writes no event, the same way `check_headsign_coverage` handles a missing file.

Then implement `ObaUrlBuilder`, migrate the six methods, add the handler mapping, make the tests pass, and refactor the old `replace(" ", "%20")` calls away.

## Manual verification (orchestrator, after merge and deploy)

Not part of the automated loop; the implementer does not start containers.

1. Rebuild and restart `transit-board-api`.
2. Confirm a known stop (`GET /api/schedule?stop=<known stop>`) still returns normal data.
3. Repeat the benign `?` probe. Expect HTTP 400, and confirm `docker logs oba_app` shows no new request.
4. Repeat the same probe against `/api/stops?agency=`.

## Rollback

Revert the commit and redeploy `transit-board-api`. The change is confined to request construction and input validation, so rollback is a single-container restart. Rolling back reopens the injection, so only roll back if a valid request is broken and no fix is available.

## Files that will change

- The `ObaUrlBuilder` class in the `dev.shinpei.transitboard.api` package (new, main sources)
- The `ObaClient` class in the `dev.shinpei.transitboard.api` package (main sources)
- The `ScheduleApiHandler` class and the `StopsApiHandler` class in the `dev.shinpei.transitboard.api` package (main sources)
- The `ObaUrlBuilderTest` class and the `ObaClientPathEncodingTest` class in the `dev.shinpei.transitboard.api` package (test sources, new)
- The `updater.py` module in the `gtfs-updater` directory: new `check_id_allowlist` function and its call in `run_update_check`
- The `test_updater.py` module in the `gtfs-updater` directory: tests 13 to 15
