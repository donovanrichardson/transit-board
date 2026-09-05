# Spec: GTFS Updater — Docker Socket Removal

## Goal
Eliminate the Docker socket bind-mount from `gtfs_updater` (a confirmed root-on-host escalation vector) by moving bundle-build and tomcat-restart responsibility into `oba_app` itself via a polling watcher process managed by supervisord, coordinated with `gtfs_updater` through marker files on the shared `/bundle` volume.

## Scope
### In scope
- `docker-compose.yml`: remove `oba_bundler` service entirely; remove Docker socket mount, `.:/compose:ro` mount, `COMPOSE_PROJECT_NAME` env var, and the now-unused bundle-backup mount from `gtfs_updater`; restructure `oba_app` into a two-service build (base + local overlay)
- `gtfs-updater/Dockerfile`: remove `docker-cli docker-cli-compose` from `apk add`
- `gtfs-updater/updater.py`: replace `run_bundler()` and `restart_oba_app()` with marker-file-based coordination (`request_rebuild()` and `wait_for_rebuild_result()`); remove `subprocess` import; remove `COMPOSE_PROJECT_DIR` constant; remove `compose_dir` parameter from `run_update_check()` and `main()`; move `backup_bundle()`, `restore_bundle()`, `cleanup_backup()`, `clean_bundle_intermediates()` out (they move to the watcher)
- `gtfs-updater/test_updater.py`: rewrite tests for the new coordination protocol; remove tests for `run_bundler()`, `restart_oba_app()`, compose config; add tests for `request_rebuild()`, `wait_for_rebuild_result()`, and the updated `run_update_check()` flow
- `.gitignore`: remove the now-unused `oba-server/bundle-backup/` entry
- New file `oba-app/Dockerfile`: local overlay on the upstream OBA image, adding the watcher script and supervisor config
- New file `oba-app/bundle-watcher-lib.sh`: sourceable shared functions (backup, restore, clean, write_result, process_request)
- New file `oba-app/bundle-watcher.sh`: bash polling watcher entry point (sources lib, runs main loop)
- New file `oba-app/bundle-watcher.ini`: supervisord program config for the watcher
- New file `oba-app/test_bundle_watcher.sh`: bash tests for the watcher functions
- New service `oba_app_base` in `docker-compose.yml`: build-only service (profile `build`) producing the unmodified upstream image

### Out of scope
- Changes to the upstream OneBusAway Docker image or `build_bundle.sh`
- Changes to the monitor service's Docker socket mount (it is read-only and serves a different purpose; can be addressed separately)
- Log rotation for JSONL files
- Changes to the MySQL schema or checksum logic
- Changes to `download_feed()`, `compute_checksum()`, `seconds_until_next_run()`, or any scheduling logic
- Migration of `oba_app_base` from git-URL build context to a pinned image tag or submodule (future improvement)

## Behavior

### 1. docker-compose.yml restructuring

#### New `oba_app_base` service (build-only)

```yaml
oba_app_base:
  build:
    context: https://github.com/OneBusAway/onebusaway-docker.git#main:oba
  image: transit-board-oba_app_base
  profiles:
    - build
```

This service is never started by `docker compose up`. It exists solely to produce the `transit-board-oba_app_base` image, which `oba_app`'s own Dockerfile then builds `FROM`. This keeps the base tracking whatever this project's own build step produces (currently upstream `main`, but automatically honoring any future local fork/modification of the OBA source), rather than depending on a separately-published third-party image that could silently diverge or ignore local changes. Must be built before `oba_app`: `docker compose --profile build build oba_app_base`.

#### Modified `oba_app` service

```yaml
oba_app:
  container_name: oba_app
  hostname: oba-app
  build:
    context: ./oba-app
    dockerfile: Dockerfile
  environment:
    - JDBC_URL=${JDBC_URL:-jdbc:mysql://oba_database:3306/oba_database}
    - JDBC_DRIVER=com.mysql.cj.jdbc.Driver
    - JDBC_USER=${JDBC_USER:-oba}
    - JDBC_PASSWORD=${JDBC_PASSWORD}
    - TEST_API_KEY=${OBA_API_KEY}
  volumes:
    - ./oba-server/bundle:/bundle
  ports:
    - "8080:8080"
  depends_on:
    - oba_database
  restart: always
```

The `build.context` changes from the upstream git URL to the local `./oba-app` directory.

#### Modified `gtfs_updater` service

```yaml
gtfs_updater:
  build: ./gtfs-updater
  volumes:
    - ./oba-server/bundle:/bundle
    - ./logs:/logs
  environment:
    - GTFS_FEED_URL=https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip
    - GTFS_UPDATE_HOUR=5
    - MYSQL_HOST=oba_database
    - MYSQL_USER=${JDBC_USER:-oba}
    - MYSQL_PASSWORD=${JDBC_PASSWORD}
    - MYSQL_DATABASE=oba_database
  depends_on:
    - oba_database
  restart: unless-stopped
```

Removed: `/var/run/docker.sock:/var/run/docker.sock`, `.:/compose:ro`, `COMPOSE_PROJECT_NAME=transit-board`, and `./oba-server/bundle-backup:/bundle-backup` (backup/restore now happens inside `oba_app`'s own watcher, using a subdirectory of the already-shared `/bundle` volume — `gtfs_updater` no longer performs any backup/restore itself, so this mount is dead weight).

#### Deleted `oba_bundler` service

The entire `oba_bundler` service definition is removed from `docker-compose.yml`.

### 2. New file: `oba-app/Dockerfile`

```dockerfile
FROM transit-board-oba_app_base

RUN mkdir -p /etc/supervisord.d

COPY bundle-watcher-lib.sh /usr/local/bin/bundle-watcher-lib.sh
COPY bundle-watcher.sh /usr/local/bin/bundle-watcher.sh
RUN chmod +x /usr/local/bin/bundle-watcher.sh

COPY bundle-watcher.ini /etc/supervisord.d/bundle-watcher.ini
```

This layers onto the base image built from the upstream git context. The upstream image's `supervisord.conf` already includes `files = /etc/supervisord.d/*.ini`, so the watcher process is automatically picked up by supervisord on container start — no changes to any upstream-owned config file are needed.

### 3. New file: `oba-app/bundle-watcher.ini`

```ini
[program:bundle-watcher]
command=/usr/local/bin/bundle-watcher.sh
autostart=true
autorestart=true
stdout_logfile=/dev/stdout
stdout_logfile_maxbytes=0
stderr_logfile=/dev/stderr
stderr_logfile_maxbytes=0
```

### 4. New file: `oba-app/bundle-watcher-lib.sh`

A sourceable library of functions used by both the watcher and its tests. All functions operate on variables `BUNDLE_DIR`, `BACKUP_DIR`, `REQUEST_FILE`, and `RESULT_FILE` which the sourcing script must set before calling them.

```bash
#!/usr/bin/env bash
# bundle-watcher-lib.sh — shared functions for bundle-watcher and its tests.
# Caller must set: BUNDLE_DIR, BACKUP_DIR, REQUEST_FILE, RESULT_FILE

log() { echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') bundle-watcher: $*"; }

backup_bundle() {
    if [ -d "$BACKUP_DIR" ]; then
        rm -rf "${BACKUP_DIR:?}/"* 2>/dev/null || true
    else
        mkdir -p "$BACKUP_DIR"
    fi
    local item base
    for item in "$BUNDLE_DIR"/*; do
        [ -e "$item" ] || continue
        base="$(basename "$item")"
        case "$base" in
            .rebuild_request.json|.rebuild_result.json|gtfs_staging.zip|backup) continue ;;
        esac
        cp -a "$item" "$BACKUP_DIR/" || return 1
    done
    return 0
}

restore_bundle() {
    if [ ! -d "$BACKUP_DIR" ] || [ -z "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]; then
        log "No backup to restore from"
        return 1
    fi
    local item base
    for item in "$BUNDLE_DIR"/*; do
        [ -e "$item" ] || continue
        base="$(basename "$item")"
        case "$base" in
            .rebuild_request.json|.rebuild_result.json|gtfs_staging.zip|backup) continue ;;
        esac
        rm -rf "$item"
    done
    cp -a "$BACKUP_DIR"/* "$BUNDLE_DIR/" || return 1
    rm -rf "$BACKUP_DIR"
    return 0
}

clean_intermediates() {
    [ -d "$BUNDLE_DIR/gtfs-out" ] && rm -rf "$BUNDLE_DIR/gtfs-out" && log "Removed gtfs-out/"
    [ -f "$BUNDLE_DIR/gtfs_tidied.zip" ] && rm -f "$BUNDLE_DIR/gtfs_tidied.zip" && log "Removed gtfs_tidied.zip"
    return 0
}

write_result() {
    local nonce="$1" success="$2" sha256="$3" error="${4:-}"
    jq -n \
        --arg nonce "$nonce" \
        --arg success "$success" \
        --arg sha256 "$sha256" \
        --arg error "$error" \
        --arg ts "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
        '{nonce: $nonce, success: ($success == "true"), sha256: $sha256, error: $error, ts: $ts}' \
        > "$RESULT_FILE"
    log "Wrote result: nonce=$nonce success=$success"
}

process_request() {
    local nonce sha256 staging_filename
    nonce="$(jq -r '.nonce' "$REQUEST_FILE")"
    sha256="$(jq -r '.sha256' "$REQUEST_FILE")"
    staging_filename="$(jq -r '.staging_filename' "$REQUEST_FILE")"

    if [ -z "$nonce" ] || [ "$nonce" = "null" ]; then
        log "Invalid request: missing nonce"
        return
    fi

    if [ "$nonce" = "$LAST_NONCE" ]; then
        return
    fi

    log "Processing rebuild request: nonce=$nonce sha256=$sha256"

    # Step 1: Backup current bundle
    if ! backup_bundle; then
        log "ERROR: Backup failed"
        write_result "$nonce" "false" "$sha256" "backup_failed"
        LAST_NONCE="$nonce"
        return
    fi
    log "Bundle backed up"

    # Step 2: Clean stale intermediates
    clean_intermediates

    # Step 3: Run build_bundle.sh
    log "Running build_bundle.sh with GTFS_ZIP_FILENAME=$staging_filename"
    local build_exit=0
    BUNDLE_DIR="$BUNDLE_DIR" GTFS_ZIP_FILENAME="$staging_filename" /oba/build_bundle.sh || build_exit=$?

    if [ "$build_exit" -ne 0 ]; then
        log "ERROR: build_bundle.sh exited with code $build_exit"
        if restore_bundle; then
            log "Bundle restored from backup"
            write_result "$nonce" "false" "$sha256" "build_failed_exit_${build_exit}_bundle_restored"
        else
            log "ERROR: Bundle restore also failed"
            write_result "$nonce" "false" "$sha256" "build_failed_exit_${build_exit}_restore_failed"
        fi
        LAST_NONCE="$nonce"
        return
    fi

    log "Build succeeded"

    # Step 4: Remove backup (no longer needed)
    rm -rf "$BACKUP_DIR"

    # Step 5: Restart tomcat via supervisorctl
    log "Restarting tomcat"
    if supervisorctl restart tomcat; then
        log "Tomcat restarted successfully"
        write_result "$nonce" "true" "$sha256" ""
    else
        log "WARNING: Tomcat restart failed"
        write_result "$nonce" "false" "$sha256" "build_succeeded_but_tomcat_restart_failed"
    fi

    LAST_NONCE="$nonce"
}
```

### 5. New file: `oba-app/bundle-watcher.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

BUNDLE_DIR="/bundle"
BACKUP_DIR="/bundle/backup"
REQUEST_FILE="${BUNDLE_DIR}/.rebuild_request.json"
RESULT_FILE="${BUNDLE_DIR}/.rebuild_result.json"
POLL_INTERVAL=5
LAST_NONCE=""

# Source shared library
source /usr/local/bin/bundle-watcher-lib.sh

log "Starting bundle watcher (poll interval: ${POLL_INTERVAL}s)"
while true; do
    if [ -f "$REQUEST_FILE" ]; then
        process_request
    fi
    sleep "$POLL_INTERVAL"
done
```

Key design points:
- `BACKUP_DIR` is `/bundle/backup` (a subdirectory of the shared volume, inside `oba_app`'s own filesystem view). This has nothing to do with the old `/bundle-backup` host mount that used to exist on `gtfs_updater` — that mount is removed entirely.
- The watcher tracks `LAST_NONCE` in memory to avoid reprocessing the same request after writing the result.
- The request file is NOT deleted by the watcher — `gtfs_updater` owns cleanup of both marker files.
- `jq` is used for JSON generation (confirmed present in the `oba_app` image).

### 6. Marker file schemas

#### Request marker: `/bundle/.rebuild_request.json`

Written by `gtfs_updater`. Read by the watcher.

```json
{
  "nonce": "a1b2c3d4e5f6",
  "ts": "2026-07-28T09:00:05Z",
  "sha256": "abc123...64chars...",
  "staging_filename": "gtfs_staging.zip"
}
```

| Field | Type | Description |
|---|---|---|
| `nonce` | string | Unique identifier for this rebuild request. Generated as `uuid.uuid4().hex[:12]` in Python. |
| `ts` | string | ISO 8601 UTC timestamp of when the request was created. |
| `sha256` | string (64 hex chars) | Checksum of the new GTFS feed (for traceability; the watcher does not verify it). |
| `staging_filename` | string | Bare filename of the staged zip within `/bundle` (always `"gtfs_staging.zip"`). |

#### Result marker: `/bundle/.rebuild_result.json`

Written by the watcher. Read by `gtfs_updater`.

```json
{
  "nonce": "a1b2c3d4e5f6",
  "success": true,
  "sha256": "abc123...64chars...",
  "error": "",
  "ts": "2026-07-28T09:02:18Z"
}
```

| Field | Type | Description |
|---|---|---|
| `nonce` | string | Echoed from the request, used by `gtfs_updater` to match result to request. |
| `success` | boolean | `true` if build + tomcat restart both succeeded; `false` otherwise. |
| `sha256` | string | Echoed from the request. |
| `error` | string | Empty string on success. On failure, one of: `"backup_failed"`, `"build_failed_exit_N_bundle_restored"`, `"build_failed_exit_N_restore_failed"`, `"build_succeeded_but_tomcat_restart_failed"`. |
| `ts` | string | ISO 8601 UTC timestamp of when the result was written. |

### 7. Changes to `gtfs-updater/updater.py`

#### Constants to remove
- `COMPOSE_PROJECT_DIR`

#### Constants to add
```python
REBUILD_REQUEST_PATH = "/bundle/.rebuild_request.json"
REBUILD_RESULT_PATH = "/bundle/.rebuild_result.json"
REBUILD_POLL_INTERVAL = 10   # seconds between checks for result marker
REBUILD_TIMEOUT = 600        # seconds (10 minutes) to wait for watcher to complete
```

#### Imports to remove
- `subprocess`

#### Imports to add
- `uuid`

`shutil` remains — `shutil.copy2`/`shutil.move` are still used for the staging/pristine zip.

#### Functions to remove entirely
- `run_bundler()`
- `restart_oba_app()`
- `backup_bundle()`
- `restore_bundle()`
- `cleanup_backup()`
- `clean_bundle_intermediates()`
- `_clear_dir_contents()`
- `_remove_dir()`

#### New function: `request_rebuild()`

```python
def request_rebuild(sha256: str, staging_filename: str = "gtfs_staging.zip") -> str:
    """Write a rebuild request marker file and return the nonce."""
    nonce = uuid.uuid4().hex[:12]
    request = {
        "nonce": nonce,
        "ts": _now_ts(),
        "sha256": sha256,
        "staging_filename": staging_filename,
    }
    # Remove any stale result file from a previous cycle
    try:
        os.unlink(REBUILD_RESULT_PATH)
    except FileNotFoundError:
        pass
    with open(REBUILD_REQUEST_PATH, "w") as f:
        json.dump(request, f)
    logger.info("Wrote rebuild request: nonce=%s sha256=%s", nonce, sha256)
    return nonce
```

#### New function: `wait_for_rebuild_result()`

```python
def wait_for_rebuild_result(
    nonce: str,
    timeout: int | None = None,
    poll_interval: int | None = None,
) -> dict | None:
    """Poll for a rebuild result marker matching the given nonce.

    Returns the parsed result dict on success/failure, or None on timeout.
    """
    if timeout is None:
        timeout = REBUILD_TIMEOUT
    if poll_interval is None:
        poll_interval = REBUILD_POLL_INTERVAL

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if os.path.exists(REBUILD_RESULT_PATH):
            try:
                with open(REBUILD_RESULT_PATH) as f:
                    result = json.load(f)
                if result.get("nonce") == nonce:
                    return result
            except (json.JSONDecodeError, OSError):
                pass  # File may be partially written; retry next poll
        time.sleep(poll_interval)
    return None
```

#### New function: `cleanup_marker_files()`

```python
def cleanup_marker_files() -> None:
    """Remove request and result marker files."""
    for path in (REBUILD_REQUEST_PATH, REBUILD_RESULT_PATH):
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass
```

#### Modified function: `run_update_check()`

New signature (removes `compose_dir` parameter):
```python
def run_update_check(conn: pymysql.Connection, feed_url: str) -> None:
```

Everything before the checksum-changed branch is unchanged. The changed portion (after the `check_changed` event is written):

1. `shutil.copy2(tmp_path, BUNDLE_STAGING_PATH)` — unchanged.
2. `nonce = request_rebuild(new_sha)` — replaces the backup + clean + run_bundler sequence.
3. `result = wait_for_rebuild_result(nonce)` — replaces the inline success/failure handling.
4. If `result is None` (timeout):
   - Log error.
   - Write `bundler_error` event with `"error": "rebuild timed out after 600s"` and `"bundle_restored": None`.
   - Clean up staging zip via `try: os.unlink(BUNDLE_STAGING_PATH)` / `except OSError: pass`.
   - Call `cleanup_marker_files()`.
   - Return (do NOT save checksum).
5. If `result["success"]` is `True`:
   - `shutil.move(BUNDLE_STAGING_PATH, BUNDLE_PRISTINE_PATH)` — promote staging zip.
   - `save_checksum(conn, new_sha, feed_url)`.
   - Write `update_complete` event.
   - Call `cleanup_marker_files()`.
6. If `result["success"]` is `False`:
   - Log error.
   - Derive `bundle_restored` from the error string: `bundle_restored = "restore_failed" not in result.get("error", "")`.
   - Write `bundler_error` event with `"error"` from the result and `"bundle_restored"` as derived.
   - Clean up staging zip.
   - Call `cleanup_marker_files()`.
   - Return (do NOT save checksum).

The `restart_error` event type is no longer emitted by `gtfs_updater`. A failed tomcat restart is reported as `success: false` in the result marker with error `"build_succeeded_but_tomcat_restart_failed"` and logged as a `bundler_error` event (the error string distinguishes build-failed from restart-failed).

#### Modified function: `main()`

```python
def main() -> None:
    conn = connect_mysql()
    ensure_table(conn)
    os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
    run_update_check(conn, GTFS_FEED_URL)

    ET = ZoneInfo("America/New_York")
    while True:
        secs = seconds_until_next_run(GTFS_UPDATE_HOUR)
        next_time = datetime.now(tz=ET).replace(
            hour=GTFS_UPDATE_HOUR, minute=0, second=0, microsecond=0
        )
        logger.info("Next update check in %.0f seconds (at %s ET)", secs, next_time)
        time.sleep(secs)
        run_update_check(conn, GTFS_FEED_URL)
```

Removes `COMPOSE_PROJECT_DIR` from both `run_update_check` calls.

### 8. Changes to `gtfs-updater/Dockerfile`

```dockerfile
FROM python:3.12-alpine

RUN pip install --no-cache-dir pymysql

WORKDIR /app
COPY updater.py .
CMD ["python", "updater.py"]
```

Removes `apk add --no-cache docker-cli docker-cli-compose`.

### 9. Changes to `.gitignore`

Remove the `oba-server/bundle-backup/` entry — that directory no longer exists in the design.

## Edge cases

- **Stale request marker from a previous crash**: `request_rebuild()` always removes any existing result file before writing the new request. The watcher tracks `LAST_NONCE` in memory and only processes a nonce once. If `gtfs_updater` crashes and restarts, it generates a new nonce, so stale requests are never confused with fresh ones.
- **Stale result marker from a previous crash**: `request_rebuild()` deletes the result file before writing the request file, ensuring the result file found during polling always corresponds to the current request.
- **Watcher container restart mid-build**: `LAST_NONCE` resets to empty. The watcher re-reads the request file and reprocesses it (idempotent — `build_bundle.sh` overwrites its own output). `gtfs_updater` is still polling for the result and will get it when the re-run completes.
- **Both marker files partially written (race)**: `gtfs_updater` writes the request file via a single `json.dump` call. The watcher writes the result file via `jq > file` (shell redirect is atomic at the filesystem level for small writes). The `json.JSONDecodeError` catch in `wait_for_rebuild_result` handles any partial-read case by retrying next poll.
- **`/bundle/backup` subdirectory leaks into the next bundle build**: The watcher's `backup_bundle()` excludes it when iterating. `build_bundle.sh` operates on `$BUNDLE_DIR` and uses known filenames; an unexpected `backup/` subdirectory does not interfere. It is cleaned up on success (explicit `rm -rf`). On failure with successful restore, the restore function deletes it.
- **Disk full during backup inside `oba_app`**: The watcher catches the failure and writes a result with `error: "backup_failed"`. `gtfs_updater` sees `success: false` and does not save the checksum, so the next check retries.
- **`build_bundle.sh` hangs indefinitely**: `gtfs_updater` times out after 600 seconds and treats it as a failure. The watcher process may still be running the build, but the next request (with a new nonce) will not be processed until the current one finishes (single-threaded bash loop). The `LAST_NONCE` mechanism prevents duplicate processing once it does finish.
- **`gtfs_updater` and `oba_app` use the same `/bundle` volume**: Already confirmed in `docker-compose.yml` — both services mount `./oba-server/bundle:/bundle`.
- **Marker files visible in bundle directory**: They are dotfiles (`.rebuild_request.json`, `.rebuild_result.json`) and will not be served by OBA or interfere with bundle loading.
- **`oba_app_base` image not built before `oba_app`**: `docker compose build oba_app` will fail with `FROM transit-board-oba_app_base: not found`. The README must document running `docker compose --profile build build oba_app_base` first. This is a one-time step (and after any upstream OBA image update).
- **`backup/` directory within `/bundle` persists across container restarts**: Because `/bundle` is a host bind-mount, a leftover `backup/` directory from a crashed build survives restarts. The watcher's `backup_bundle()` clears any existing backup before writing a new one, so this is handled correctly.

## Acceptance criteria

- [ ] No `/var/run/docker.sock` mount exists anywhere in `docker-compose.yml` except on `monitor` (which is read-only and out of scope)
- [ ] No `.:/compose:ro` mount exists on `gtfs_updater` in `docker-compose.yml`
- [ ] No `COMPOSE_PROJECT_NAME` env var exists on `gtfs_updater` in `docker-compose.yml`
- [ ] No `./oba-server/bundle-backup:/bundle-backup` mount exists on `gtfs_updater` in `docker-compose.yml`
- [ ] `oba_bundler` service does not exist in `docker-compose.yml`
- [ ] `oba-server/bundle-backup/` is removed from `.gitignore`
- [ ] `gtfs-updater/Dockerfile` does not install `docker-cli` or `docker-cli-compose`
- [ ] `updater.py` does not import `subprocess`
- [ ] `updater.py` does not contain `run_bundler()`, `restart_oba_app()`, `backup_bundle()`, `restore_bundle()`, `cleanup_backup()`, `clean_bundle_intermediates()`, `_clear_dir_contents()`, or `_remove_dir()` functions
- [ ] `updater.py` contains `request_rebuild()` that writes a JSON request marker to `/bundle/.rebuild_request.json` with a unique nonce
- [ ] `updater.py` contains `wait_for_rebuild_result()` that polls for a JSON result marker matching the nonce, with configurable timeout and poll interval
- [ ] `updater.py` contains `cleanup_marker_files()` that removes both marker files
- [ ] `run_update_check()` no longer accepts a `compose_dir` parameter
- [ ] On checksum change, `run_update_check()` writes the staging zip, calls `request_rebuild()`, calls `wait_for_rebuild_result()`, and handles success/failure/timeout correctly (saves checksum only on success; writes appropriate JSONL events)
- [ ] `oba_app` service in `docker-compose.yml` builds from `./oba-app` directory (not the upstream git URL directly)
- [ ] `oba_app_base` service exists with `profiles: [build]` and builds from the upstream git URL, producing image `transit-board-oba_app_base`
- [ ] `oba-app/Dockerfile` exists and layers the watcher script + supervisor config onto the base image (`FROM transit-board-oba_app_base`)
- [ ] `oba-app/bundle-watcher-lib.sh` exists with all shared functions (backup_bundle, restore_bundle, clean_intermediates, write_result, process_request)
- [ ] `oba-app/bundle-watcher.sh` exists, sources the lib, and runs the main poll loop
- [ ] `oba-app/bundle-watcher.ini` exists and configures the watcher as a supervisord program
- [ ] The watcher cleans `gtfs-out/` and `gtfs_tidied.zip` intermediates before every build
- [ ] The watcher backs up the bundle before building and restores on failure
- [ ] The watcher restarts `tomcat` (not the whole container) via `supervisorctl restart tomcat` on success
- [ ] The watcher writes a result marker with `success: true` or `success: false` and an `error` field
- [ ] All new tests pass
- [ ] No existing test that validates still-present functionality is broken

## Tests to write

### Tests in `gtfs-updater/test_updater.py` (Python, unittest)

- **`test_request_rebuild_writes_marker_file`**: Call `request_rebuild()` with patched `REBUILD_REQUEST_PATH` pointing to a temp file. Assert: file exists, contains valid JSON, has `nonce` (12-char hex string), `ts`, `sha256`, and `staging_filename` fields. Assert any pre-existing result file at `REBUILD_RESULT_PATH` is deleted.
- **`test_request_rebuild_clears_stale_result`**: Create a pre-existing result file at `REBUILD_RESULT_PATH`. Call `request_rebuild()`. Assert the result file no longer exists.
- **`test_request_rebuild_returns_unique_nonces`**: Call `request_rebuild()` twice with patched paths. Assert the returned nonces differ.
- **`test_wait_for_rebuild_result_success`**: Write a result JSON file with a matching nonce and `success: true` to a temp path. Patch `REBUILD_RESULT_PATH`. Call `wait_for_rebuild_result()` with a short poll interval (0.1s). Assert it returns the parsed dict with `success` equal to `True`.
- **`test_wait_for_rebuild_result_failure`**: Write a result JSON file with a matching nonce and `success: false` and a non-empty `error`. Call `wait_for_rebuild_result()`. Assert it returns the parsed dict with `success` equal to `False` and the error string.
- **`test_wait_for_rebuild_result_timeout`**: Do NOT write a result file. Call `wait_for_rebuild_result()` with `timeout=0.5, poll_interval=0.1`. Assert it returns `None`.
- **`test_wait_for_rebuild_result_ignores_wrong_nonce`**: Write a result file with a different nonce. Call `wait_for_rebuild_result()` with `timeout=0.5, poll_interval=0.1`. Assert it returns `None`.
- **`test_wait_for_rebuild_result_handles_partial_json`**: Write invalid JSON to the result file path, then use a `threading.Timer` to overwrite with valid JSON after 0.3s. Call `wait_for_rebuild_result()` with `timeout=2, poll_interval=0.1`. Assert the function recovers and returns the valid result.
- **`test_cleanup_marker_files`**: Create both marker files at patched paths. Call `cleanup_marker_files()`. Assert both are gone. Call again (files already absent). Assert no error raised.
- **`test_update_check_unchanged_no_rebuild`**: Simulate unchanged checksum. Assert `request_rebuild` is NOT called. Assert `write_event` is called with `event="check_unchanged"`.
- **`test_update_check_changed_success`**: Simulate changed checksum. Mock `request_rebuild` to return a known nonce. Mock `wait_for_rebuild_result` to return `{"nonce": ..., "success": true, "sha256": ..., "error": "", "ts": ...}`. Assert: `shutil.copy2` called to staging path, `shutil.move` called from staging to pristine, `save_checksum` called, `write_event` called with `event="update_complete"`, `cleanup_marker_files` called.
- **`test_update_check_changed_build_failure`**: Simulate changed checksum. Mock `wait_for_rebuild_result` to return `{"success": false, "error": "build_failed_exit_1_bundle_restored", ...}`. Assert: `save_checksum` NOT called, `shutil.move` NOT called, `write_event` called with `event="bundler_error"` and `bundle_restored` equal to `True`, `cleanup_marker_files` called.
- **`test_update_check_changed_build_failure_restore_failed`**: Same as above but error string is `"build_failed_exit_1_restore_failed"`. Assert `bundler_error` event has `bundle_restored` equal to `False`.
- **`test_update_check_changed_timeout`**: Mock `wait_for_rebuild_result` to return `None`. Assert: `save_checksum` NOT called, `write_event` called with `event="bundler_error"` and `error` containing `"timed out"`, `cleanup_marker_files` called.
- **`test_update_check_download_error_no_rebuild`**: Mock `download_feed` returning `None`. Assert `request_rebuild` NOT called. Assert `write_event` called with `event="download_error"`.
- **`test_run_update_check_no_compose_dir_param`**: Use `inspect.signature` to confirm `run_update_check` has exactly two parameters (`conn` and `feed_url`), no `compose_dir`.
- **`test_no_removed_functions`**: Assert that `updater` module does not have attributes `run_bundler`, `restart_oba_app`, `backup_bundle`, `restore_bundle`, `cleanup_backup`, `clean_bundle_intermediates`.

### Tests to remove

- `TestSubprocessFunctions` class entirely
- `TestBackupRestore` class entirely
- `TestCleanBundleIntermediates` class entirely
- `TestComposeConfig` class entirely
- In `TestUpdateCheck`: all tests that mock `run_bundler`, `restart_oba_app`, `backup_bundle`, `restore_bundle`, `clean_bundle_intermediates`, or `cleanup_backup` are replaced by the new protocol-based tests listed above.

### Tests to keep (unchanged or with minor signature updates)

- `TestComputeChecksum`, `TestScheduleNextRun`, `TestDownloadFeed`, `TestDatabaseFunctions`, `TestWriteEvent` — unchanged.
- `TestUpdateCheck.test_update_check_unchanged`, `test_update_check_unchanged_writes_jsonl`, `test_update_check_download_error_writes_jsonl` — update to remove the `compose_dir` arg from `run_update_check` calls.

### Tests for `oba-app/bundle-watcher-lib.sh`

Create `oba-app/test_bundle_watcher.sh` — a bash test script that sources `bundle-watcher-lib.sh` and tests its functions using temporary directories. Must be runnable on the host (requires `bash` and `jq`).

- **`test_backup_and_restore`**: Create files in a temp bundle dir. Run `backup_bundle`. Assert backup dir exists with copies. Modify bundle contents. Run `restore_bundle`. Assert bundle matches original state and backup dir is removed.
- **`test_backup_excludes_markers_and_staging`**: Create `.rebuild_request.json`, `.rebuild_result.json`, `gtfs_staging.zip`, and `backup/` alongside normal bundle files. Run `backup_bundle`. Assert none of the excluded items appear in the backup.
- **`test_clean_intermediates`**: Create `gtfs-out/` directory with files and `gtfs_tidied.zip`. Run `clean_intermediates`. Assert both removed. Other files in the directory are untouched.
- **`test_clean_intermediates_noop`**: Run `clean_intermediates` with no artifacts present. Assert no error (exit 0).
- **`test_write_result_valid_json`**: Run `write_result "abc123" "true" "def456" ""`. Parse output file with `jq`. Assert `nonce`, `success` (boolean true), `sha256`, `error` (empty string), and `ts` fields are present and correctly typed.
- **`test_write_result_failure`**: Run `write_result "abc123" "false" "def456" "build_failed_exit_1_bundle_restored"`. Assert `success` is boolean false and `error` matches.
- **`test_process_request_build_success`**: Create a stub `/oba/build_bundle.sh` that exits 0. Create a stub `supervisorctl` that exits 0. Create a request file with a known nonce. Run `process_request`. Assert result file exists, has matching nonce, `success` is true.
- **`test_process_request_build_failure_with_restore`**: Create a stub `build_bundle.sh` that exits 1. Populate bundle dir with known files. Run `process_request`. Assert result file has `success` false and error contains `"build_failed"` and `"bundle_restored"`. Assert bundle contents are restored.
- **`test_process_request_skips_duplicate_nonce`**: Set `LAST_NONCE` to a value. Create request with same nonce. Run `process_request`. Assert no result file written.

## Files that will change

- `docker-compose.yml` — remove `oba_bundler` service; add `oba_app_base` service (profile: build, context: upstream git URL, image: transit-board-oba_app_base); change `oba_app` build context to `./oba-app`; remove Docker socket mount, compose-dir mount, `COMPOSE_PROJECT_NAME`, and bundle-backup mount from `gtfs_updater`
- `.gitignore` — remove `oba-server/bundle-backup/` entry
- `gtfs-updater/Dockerfile` — remove `apk add --no-cache docker-cli docker-cli-compose` line
- `gtfs-updater/updater.py` — remove `subprocess` import, add `uuid` import; remove `COMPOSE_PROJECT_DIR` constant; add `REBUILD_REQUEST_PATH`, `REBUILD_RESULT_PATH`, `REBUILD_POLL_INTERVAL`, `REBUILD_TIMEOUT` constants; remove `run_bundler()`, `restart_oba_app()`, `backup_bundle()`, `restore_bundle()`, `cleanup_backup()`, `clean_bundle_intermediates()`, `_clear_dir_contents()`, `_remove_dir()`; add `request_rebuild()`, `wait_for_rebuild_result()`, `cleanup_marker_files()`; rewrite `run_update_check()` to use marker-file protocol (remove `compose_dir` param); update `main()` to remove `COMPOSE_PROJECT_DIR` usage
- `gtfs-updater/test_updater.py` — remove `TestSubprocessFunctions`, `TestBackupRestore`, `TestCleanBundleIntermediates`, `TestComposeConfig` classes; rewrite `TestUpdateCheck` for the new protocol; add new test classes for `request_rebuild`, `wait_for_rebuild_result`, `cleanup_marker_files`
- `oba-app/Dockerfile` — new file; local overlay on `transit-board-oba_app_base`
- `oba-app/bundle-watcher-lib.sh` — new file; shared bash functions for backup, restore, clean, write_result, process_request
- `oba-app/bundle-watcher.sh` — new file; sources lib, runs main poll loop
- `oba-app/bundle-watcher.ini` — new file; supervisord program config
- `oba-app/test_bundle_watcher.sh` — new file; bash tests for watcher functions
