# Spec: GTFS Updater Persistent JSONL Logging and Container Startup

## Goal
Add structured JSONL audit logging to the gtfs_updater service so every scheduled check cycle (unchanged, changed+rebuilt, or error) is recorded in a persistent file at `logs/gtfs_updater.jsonl`, and ensure the gtfs_updater container is actually running (it is defined in docker-compose.yml but currently has no container at all).

## Scope
### In scope
- `gtfs-updater/updater.py` — add a JSONL file logger that writes one structured JSON line per check cycle to `/logs/gtfs_updater.jsonl`
- `docker-compose.yml` — add `./logs:/logs` volume mount to the `gtfs_updater` service
- `gtfs-updater/test_updater.py` — add tests for the new JSONL logging behavior

### Out of scope
- Changes to any other service (oba_app, oba_database, oba_bundler, transit-board-api, frontend, monitor)
- Log rotation or size management for `gtfs_updater.jsonl`
- Changes to the existing Python `logging` stdout output (keep it as-is; the JSONL file is additive)
- Alerting or dashboarding on the JSONL file
- Changes to the update check logic, scheduling, or bundler orchestration

## Behavior

### JSONL log file

A new function `write_event(event: dict) -> None` appends a single JSON line to the file at path `LOG_PATH` (env var, default `/logs/gtfs_updater.jsonl`). The function opens the file in append mode, writes `json.dumps(event) + "\n"`, and flushes. If the parent directory does not exist, create it with `os.makedirs(exist_ok=True)` on startup.

`LOG_PATH` must also be accepted as a function parameter (not only env var) so tests can inject a temp path without monkeypatching.

### Event schema

Every JSONL line is a JSON object with these fields:

| Field | Type | Present | Description |
|---|---|---|---|
| `ts` | string (ISO 8601 UTC, trailing `Z`) | always | Timestamp of the event |
| `event` | string enum | always | One of: `check_unchanged`, `check_changed`, `update_complete`, `download_error`, `bundler_error`, `restart_error` |
| `feed_url` | string | always | The GTFS feed URL that was checked |
| `sha256` | string (64 hex chars) or null | always | Checksum of the downloaded feed; null if download failed |
| `previous_sha256` | string or null | when event is `check_changed` or `check_unchanged` | The stored checksum from the database; null on first run |
| `error` | string or null | when event is `*_error` | Error description |

Example lines:

```json
{"ts": "2026-07-28T09:00:03Z", "event": "check_unchanged", "feed_url": "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip", "sha256": "abc123...", "previous_sha256": "abc123..."}
{"ts": "2026-07-29T09:00:05Z", "event": "check_changed", "feed_url": "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip", "sha256": "def456...", "previous_sha256": "abc123..."}
{"ts": "2026-07-29T09:02:18Z", "event": "update_complete", "feed_url": "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip", "sha256": "def456..."}
{"ts": "2026-07-30T09:00:02Z", "event": "download_error", "feed_url": "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip", "sha256": null, "error": "HTTP Error 503: Service Unavailable"}
{"ts": "2026-07-31T09:00:45Z", "event": "bundler_error", "feed_url": "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip", "sha256": "789aaa...", "previous_sha256": "def456...", "error": "oba_bundler exited with code 137"}
```

### Where events are emitted in `run_update_check`

The existing function `run_update_check` in `updater.py` (lines 158-194) is modified to call `write_event` at these points:

1. **Download failure** (after `download_feed` returns `None`): emit `{"event": "download_error", "sha256": null, "error": "<generic message>"}`. Preferred approach: keep `download_feed` returning `str | None` and use the generic message `"Feed download failed (see stdout logs for details)"` in the JSONL event — the stdout log already has the specific exception.

2. **Checksum unchanged** (after the `stored_sha == new_sha` branch): emit `{"event": "check_unchanged", "sha256": new_sha, "previous_sha256": stored_sha}`.

3. **Checksum changed** (after detecting mismatch, before bundler runs): emit `{"event": "check_changed", "sha256": new_sha, "previous_sha256": stored_sha}`.

4. **Bundler failure** (after `run_bundler` returns `False`): emit `{"event": "bundler_error", "sha256": new_sha, "previous_sha256": stored_sha, "error": "oba_bundler exited with non-zero code"}`.

5. **Update complete** (after successful bundler + checksum save + oba_app restart): emit `{"event": "update_complete", "sha256": new_sha}`.

6. **Restart failure**: If `restart_oba_app` fails (non-zero exit), emit `{"event": "restart_error", "sha256": new_sha, "error": "Failed to restart oba_app"}`. This requires `restart_oba_app` to return a boolean. Currently it returns `None` and only logs. Change its return type to `bool`.

### docker-compose.yml change

Add `./logs:/logs` to the `gtfs_updater` service's `volumes` list:

```yaml
gtfs_updater:
  build: ./gtfs-updater
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
    - ./oba-server/bundle:/bundle
    - .:/compose:ro
    - ./logs:/logs          # <-- new
  environment:
    # ... unchanged ...
```

### Container startup

The `gtfs_updater` service is already fully defined in `docker-compose.yml` with `restart: unless-stopped`. It is not running because it was never started (or was stopped and removed). After the code changes are made, running `docker compose up -d gtfs_updater` will build and start it. The spec includes verifying it starts and stays running as an acceptance criterion. No docker-compose.yml changes are needed beyond the volume mount addition (the service definition, build context, env vars, depends_on, and restart policy are already correct).

### Startup directory creation

In `main()`, before the first `run_update_check` call, add `os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)` to ensure the `/logs` directory exists inside the container (it will exist via the bind mount, but this is defensive).

## Edge cases
- **`/logs` directory does not exist inside container**: `os.makedirs` in `main()` creates it. In practice the bind mount always creates it, so this is purely defensive.
- **`write_event` I/O error (disk full, permissions)**: Catch `OSError`, log a warning to stdout via the existing Python logger, and continue. The updater must not crash because of a log write failure.
- **First run (no stored checksum)**: `previous_sha256` is `null` in the JSONL event. `event` is `check_changed`.
- **Very large JSONL file over time**: Out of scope (no log rotation). At one line per day (~200 bytes), the file grows ~73 KB/year.
- **Concurrent writes**: Only one updater instance runs, so no concurrency concern.

## Acceptance criteria
- [ ] `docker compose up -d gtfs_updater` starts the container and it stays running (visible in `docker ps`)
- [ ] After the first check cycle, `logs/gtfs_updater.jsonl` exists and contains at least one valid JSON line
- [ ] Each JSONL line contains `ts`, `event`, `feed_url`, and `sha256` keys
- [ ] `ts` is a valid ISO 8601 UTC timestamp ending in `Z`
- [ ] When the feed checksum is unchanged, a line with `"event": "check_unchanged"` is written with matching `sha256` and `previous_sha256`
- [ ] When the feed checksum differs and the rebuild succeeds, lines with `"event": "check_changed"` and `"event": "update_complete"` are both written
- [ ] When the download fails, a line with `"event": "download_error"` and a non-null `error` field is written
- [ ] When the bundler fails, a line with `"event": "bundler_error"` and a non-null `error` field is written
- [ ] A `write_event` I/O failure does not crash the updater
- [ ] Existing stdout logging behavior is unchanged
- [ ] `restart_oba_app` returns a boolean and a restart failure emits `"event": "restart_error"`
- [ ] All existing tests in `test_updater.py` continue to pass
- [ ] All new tests pass

## Tests to write
- **test_write_event_creates_file**: Call `write_event` with a known dict and a temp file path. Assert file exists and contains exactly one valid JSON line with expected keys.
- **test_write_event_appends**: Call `write_event` twice with different events. Assert file contains exactly two lines, both valid JSON.
- **test_write_event_io_error_does_not_raise**: Mock `open` to raise `OSError`. Assert `write_event` does not raise; assert a warning is logged to stdout logger.
- **test_update_check_unchanged_writes_jsonl**: Mock download (matching checksum). Assert `write_event` called once with `event="check_unchanged"`, correct `sha256` and `previous_sha256`.
- **test_update_check_changed_writes_jsonl**: Mock download (different checksum), bundler succeeds. Assert `write_event` called with both `event="check_changed"` and `event="update_complete"`.
- **test_update_check_download_error_writes_jsonl**: Mock `download_feed` returning `None`. Assert `write_event` called with `event="download_error"`, `sha256=None`, non-null `error`.
- **test_update_check_bundler_error_writes_jsonl**: Mock download (different checksum), bundler returns `False`. Assert `write_event` called with `event="bundler_error"`, non-null `error`.
- **test_update_check_restart_error_writes_jsonl**: Mock download (different checksum), bundler succeeds, `restart_oba_app` returns `False`. Assert `write_event` called with `event="restart_error"`.
- **test_restart_oba_app_returns_bool**: Mock `subprocess.run` with exit code 0 returns `True`; exit code 1 returns `False`.
- **test_jsonl_event_ts_format**: Assert the `ts` field in a written event ends with `Z` and is parseable as ISO 8601.

## Files that will change
- `gtfs-updater/updater.py` — add `write_event` function, add `LOG_PATH` constant, add `import json`, modify `run_update_check` to emit JSONL events at each decision point, modify `restart_oba_app` to return `bool`, add `os.makedirs` call in `main()`
- `gtfs-updater/test_updater.py` — add tests for `write_event`, JSONL emission in `run_update_check`, and `restart_oba_app` return type
- `docker-compose.yml` — add `./logs:/logs` volume mount to `gtfs_updater` service
