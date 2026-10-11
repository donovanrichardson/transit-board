# Spec: Bump Jackson in transit-board-api to a fixed version (transit-board#14)

## Goal
Eliminate 8 HIGH-severity Jackson CVEs (jackson-core and jackson-databind 2.17.1) flagged by the 2026-10-11 Trivy scan of the transit-board-api image, by bumping to a release that fixes all of them, with no behavior change to the API.

## Scope
### In scope
- `transit-board-api/pom.xml`: bump the `jackson.version` property (line 16) from `2.17.1` to `2.18.11`.
- One new unit test asserting the resolved Jackson version is at least 2.18.11 (the red test for this dependency bump).
- Rebuild and redeploy the `transit-board-api` service in `docker-compose.yml`.

### Out of scope
- Any other dependency in the pom (junit, surefire, shade plugin).
- Any Jackson API usage change or ObjectMapper configuration; none exists today (all usage is a default `new ObjectMapper()` with readValue / readTree / writeValueAsString).
- Other transit-board services and images.
- Changing the existing rebuild sequence (OBA_API_KEY re-injection and frontend restart after any compose up).

## Behavior
- `jackson.version` changes from `2.17.1` to `2.18.11`, the smallest release covering all 8 findings (CVE-2026-68494, -89407, -89425 in jackson-core; CVE-2026-54512, -54513, -68497, -91776, -91777 in jackson-databind). jackson-core and jackson-annotations arrive transitively at the same version because only jackson-databind is declared directly.
- No source changes to ObaClient, StopsApiHandler, ScheduleApiHandler or the model classes.

## Edge cases
- A future direct dependency on jackson-core or jackson-annotations without pinning to the property could resolve a mismatched version; the version test inspects the resolved runtime version so it guards against this.
- A newly vulnerable transitive dependency of Jackson itself is caught by the Trivy acceptance check, not chased here.

## Acceptance criteria
- [ ] `jackson.version` is 2.18.11 or a later release fixing all 8 findings.
- [ ] The new version test fails before the pom change and passes after (red then green).
- [ ] All existing transit-board-api unit tests pass under Maven in the documented container command.
- [ ] A Trivy scan of the rebuilt transit-board-api image shows no jackson-core or jackson-databind findings.
- [ ] After recreating the service and following the existing rebuild sequence, the stops endpoint and the departure board both return data.

## Tests to write
- Jackson version test (new): reads the runtime version from Jackson's own `PackageVersion.VERSION` and asserts it is at least 2.18.11. Written first against 2.17.1 to confirm red, then the pom is bumped to confirm green.
- The existing test suite (path encoding, schedule and stops handlers, route colors, trip schedule deserialization) is the behavior-preservation safety net.

## Files that will change
- `transit-board-api/pom.xml`
- A new version-check test class in the transit-board-api module's test sources (the Jackson version test)

## Decisions
- Release: 2.18.11, the smallest jump that fixes all 8 findings (chosen 2026-10-11 over 2.21.7 and 2.22.3).
- Test approach: read the runtime version Jackson reports for itself, not the pom (chosen 2026-10-11).

## Open questions
None.
