# Spec: Stop the OBA API key from leaking into Tomcat access logs (transit-board#7, step 4)

## Goal

The `oba_app` container's Tomcat access-log valve records the full request line, which includes the query string. transit-board-api sends the OBA API key as a query parameter on every request, so the key is written to the access log in plain text. Change the log pattern so the query string is never written, without disabling access logging.

## Scope

### In scope
- `transit-board/oba-app/Dockerfile`: add a build step that edits the Tomcat `server.xml` inside the image, replacing the full-request-line token (`%r`) in the access-log valve pattern with `%m %U %H` (method, URI path only, protocol). The other tokens (`%h %l %u %t %s %b`) are left untouched. The step must fail the Docker build if the expected pattern is not found, so a base-image change cannot silently leave the query string in the log.
- `transit-board/tests/`: a static test that reads `oba-app/Dockerfile` as text and asserts the replacement step and its fail-if-not-found guard are present.

### Out of scope
- Rotating the OBA API key (separate step of #7, in progress).
- Other places the key may leak: the `ObaClient` and `ObaUrlBuilder` classes in transit-board-api (exception or debug logging), frontend nginx access logs, `gtfs_updater` logs. To be checked in a separate follow-up.
- Any retention limit or size-based rollover on the access logs. Decided against by the user on 2026-10-11: this change only stops the query string being written.
- Purging existing access logs. They live in the container's writable layer and are discarded when `oba_app` is recreated.

## Behavior

### Mechanism: sed in the Dockerfile, not a mounted `server.xml`
The `oba-app` directory has no `server.xml`; the base image supplies the whole Tomcat install. A full custom copy would drift from the base image over time. A targeted in-place edit with a build-time guard keeps the diff minimal, travels with the image, and fails loudly if the base image changes.

### Change
After `FROM transit-board-oba_app_base`, add one `RUN` step that:
1. Checks that the current valve pattern (`%h %l %u %t "%r" %s %b`, as it appears in the file with XML-escaped quotes) is present in `/usr/local/tomcat/conf/server.xml`, exiting non-zero with a clear message if not.
2. Replaces that exact pattern with the same string using `%m %U %H` in place of `%r`.

Match the whole pattern attribute value, not just `%r`, to avoid touching anything else.

### Example
Before: `pattern="%h %l %u %t &quot;%r&quot; %s %b"`
After:  `pattern="%h %l %u %t &quot;%m %U %H&quot; %s %b"`

A request now logs method, path and protocol only (for example `GET /api/where/arrivals-and-departures-for-stop/1_1234.json HTTP/1.1`), with no query string.

## Edge cases
- Base image reformats or changes the default pattern: the build fails at the guard.
- The sed matches more than intended: mitigated by matching the full attribute value.
- Existing logs on disk are not scrubbed by this change; they disappear when the container is recreated.

## Acceptance criteria
- [ ] `oba-app/Dockerfile` edits the access-log valve pattern to use `%m %U %H` in place of `%r`, leaving the other tokens unchanged.
- [ ] The step exits non-zero if the expected old pattern is not found.
- [ ] A static test in `transit-board/tests/` asserts both the replacement and the guard are present in the Dockerfile.
- [ ] The access-log valve remains active (logging not disabled).
- [ ] No new `server.xml` file and no new compose volume mount.

### Post-deploy verification (orchestrator, manual; not an implementer test)
- [ ] After `oba_app` is rebuilt and recreated, new lines in the access log files inside the container contain no query string.
- [ ] The access log still receives lines (logging not accidentally disabled).
- [ ] After any compose up: the OBA API key is present in `transit-board/.env`, and the frontend is restarted because its nginx caches the API address.

## Tests to write
- `transit-board/tests/test_oba_app_access_log_pattern.py`: reads `oba-app/Dockerfile` as text and asserts (1) `server.xml` and `%m %U %H` appear in the same `RUN` instruction, and (2) a build-failing guard (for example `grep -q ... || exit 1`) is present with that step.
- Red first against the current Dockerfile (no such step, must fail), then green after the `RUN` step is added.

## Sequencing (decided 2026-10-11)
This fix lands in the same `oba_app` rebuild as the OBA API key rotation, so the new key is never written to an access log. Order: implement and merge this change; run the key-sync script to copy the new key from the password store into the env file; then recreate `oba_app` and `transit-board-api`, restart `gtfs_updater` and `frontend`; then run the post-deploy verification.

## Files that will change
- `transit-board/oba-app/Dockerfile`
- `transit-board/tests/test_oba_app_access_log_pattern.py` (new)

## Open questions
- File a follow-up issue to check the other leak surfaces listed under out of scope.
