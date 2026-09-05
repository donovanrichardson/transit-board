# Spec: GTFS Updater — Project Name Fix and Bundle Safety

## Goal
Fix the silent bundle-rebuild failure caused by Docker Compose project-name mismatch when `gtfs_updater` invokes `docker compose run oba_bundler` from inside its container, and add safety guarantees so that a failed rebuild never corrupts the live bundle or overwrites the last-known-good pristine GTFS zip.

## Scope
### In scope
- `docker-compose.yml`: add `COMPOSE_PROJECT_NAME=transit-board` env var to the `gtfs_updater` service
- `gtfs-updater/updater.py`: reorder `run_update_check` so the pristine zip is only overwritten after a successful build; add backup/restore of the bundle directory around `run_bundler()` so a failed build leaves the previous bundle intact
- `gtfs-updater/test_updater.py`: add regression tests for all three changes

### Out of scope
- Changes to `docker-compose.override.yml` (only touches `frontend`; irrelevant to bundler/app)
- Changes to the OneBusAway bundler image itself or its internal output layout
- Adding `-p transit-board` flag to the Python subprocess commands (env var approach chosen instead)
- Any changes to `restart_oba_app` beyond what is needed for the bundle-safety flow

## Behavior

### 1. Project-name fix (docker-compose.yml)

Add `COMPOSE_PROJECT_NAME=transit-board` to the `environment` list of the `gtfs_updater` service. Docker Compose subprocess calls (`docker compose run`, `docker compose restart`) inherit this env var and use it as the project name, matching the host stack's project name. No Python code changes needed for this fix.

Before:
```yaml
gtfs_updater:
  environment:
    - GTFS_FEED_URL=...
    - GTFS_UPDATE_HOUR=5
    - MYSQL_HOST=oba_database
    # ... etc
```

After:
```yaml
gtfs_updater:
  environment:
    - COMPOSE_PROJECT_NAME=transit-board
    - GTFS_FEED_URL=...
    - GTFS_UPDATE_HOUR=5
    - MYSQL_HOST=oba_database
    # ... etc
```

### 2. Pristine zip: overwrite only after successful build (updater.py)

Keep the copy-then-build order, but copy into a *staging path* (`/bundle/gtfs_staging.zip`) rather than overwriting `BUNDLE_PRISTINE_PATH` directly. Pass `GTFS_URL=file:///bundle/gtfs_staging.zip` to the bundler. Only after `run_bundler` succeeds, rename the staging file to `BUNDLE_PRISTINE_PATH` (atomic on same filesystem). On failure, remove the staging file and leave `BUNDLE_PRISTINE_PATH` untouched.

New constant:
```python
BUNDLE_STAGING_PATH = "/bundle/gtfs_staging.zip"
```

New order in `run_update_check`:
1. `shutil.copy2(tmp_path, BUNDLE_STAGING_PATH)` — write to staging location
2. Back up existing bundle (see section 3 below)
3. `run_bundler(compose_dir)` — bundler uses `GTFS_URL=file:///bundle/gtfs_staging.zip`
4. If bundler succeeds: `shutil.move(BUNDLE_STAGING_PATH, BUNDLE_PRISTINE_PATH)`, proceed to `save_checksum` / `restart_oba_app`
5. If bundler fails: remove staging zip, restore bundle backup (see section 3), do NOT overwrite `BUNDLE_PRISTINE_PATH`

Update `run_bundler` to use `BUNDLE_STAGING_PATH` in its `-e GTFS_URL=...` argument:
```python
"-e", f"GTFS_URL=file://{BUNDLE_STAGING_PATH}",
```

### 3. Bundle backup and restore on failure (updater.py)

The `oba_bundler` container writes output directly into `/bundle` (bind-mounted to `./oba-server/bundle`). A build that fails partway through can leave the live bundle in a broken/incomplete state. To protect against this:

Add two helper functions:

```python
BUNDLE_DIR = "/bundle"
BUNDLE_BACKUP_DIR = "/bundle-backup"
```

**`backup_bundle(bundle_dir, backup_dir)`**: Before invoking `run_bundler`, copy the current bundle directory contents (excluding `gtfs_staging.zip`) to a backup location. Use `shutil.copytree` with `dirs_exist_ok=True` (or remove-then-copy). Only called when a rebuild is about to be attempted (i.e., checksum changed), not on every check cycle.

**`restore_bundle(bundle_dir, backup_dir)`**: If `run_bundler` fails, remove the (potentially corrupted) contents of `bundle_dir` and restore from `backup_dir`. Then remove the backup directory.

On success, remove the backup directory (no longer needed).

`BUNDLE_BACKUP_DIR` (`/bundle-backup`) is bind-mounted to `./oba-server/bundle-backup` on the host (add `- ./oba-server/bundle-backup:/bundle-backup` to the `gtfs_updater` service's volumes in `docker-compose.yml`, and add `oba-server/bundle-backup/` to `.gitignore`). Persisting it on the host (rather than the container's ephemeral writable layer) means the last-known-good backup survives a `gtfs_updater` container restart mid-rebuild, so a crash during the bundler run doesn't leave you with neither a valid live bundle nor a usable backup.

If `backup_bundle()` itself fails (e.g. disk full), the rebuild is aborted entirely before `run_bundler()` is invoked — a new `"backup_error"` JSONL event is written, the staging zip is removed, and neither the pristine zip nor the live bundle are touched. The next scheduled check will retry from scratch.

Revised flow in `run_update_check` (when checksum changed):
1. Copy downloaded feed to `BUNDLE_STAGING_PATH`
2. `backup_bundle(BUNDLE_DIR, BUNDLE_BACKUP_DIR)` — snapshot current good state
3. `run_bundler(compose_dir)` — bundler writes into `/bundle`, using staging zip
4. **If success:**
   - `shutil.move(BUNDLE_STAGING_PATH, BUNDLE_PRISTINE_PATH)` — promote staging zip
   - `save_checksum(conn, new_sha, feed_url)`
   - `restart_oba_app(compose_dir)`
   - Remove `BUNDLE_BACKUP_DIR`
5. **If failure:**
   - `restore_bundle(BUNDLE_DIR, BUNDLE_BACKUP_DIR)` — roll back to previous bundle
   - Remove `BUNDLE_STAGING_PATH` if it exists
   - Log `bundler_error` event (already exists)
   - Do NOT save checksum, do NOT restart oba_app (already the case)

### JSONL logging update

The `bundler_error` event already exists and is emitted on failure. Update its `error` message to note the restore:

```python
write_event({
    "ts": _now_ts(),
    "event": "bundler_error",
    "feed_url": feed_url,
    "sha256": new_sha,
    "previous_sha256": stored_sha,
    "error": "oba_bundler exited with non-zero code",
    "bundle_restored": True,  # or False if restore_bundle() itself failed
})
```

A new `"backup_error"` event type is also added, written when `backup_bundle()` fails and the rebuild is aborted before the bundler ever runs:

```python
write_event({
    "ts": _now_ts(),
    "event": "backup_error",
    "feed_url": feed_url,
    "sha256": new_sha,
    "previous_sha256": stored_sha,
    "error": "failed to back up bundle directory; rebuild aborted",
})
```

## Edge cases

- **Backup directory already exists from a previous failed cleanup**: `backup_bundle` should remove any existing backup directory before creating a new one.
- **Bundle directory is empty on first-ever run (no prior bundle to back up)**: `backup_bundle` should handle an empty or nonexistent source gracefully — if there is nothing to back up, skip the backup step. On failure, `restore_bundle` should only attempt restore if the backup directory exists.
- **Staging zip left behind from a previous crashed run**: `run_update_check` should clean up any pre-existing `BUNDLE_STAGING_PATH` at the start of its rebuild path (or at container startup in `main()`).
- **`shutil.move` of staging zip to pristine path fails after successful build**: Log error, do NOT remove the backup (it is the last-known-good state). The staging zip remains as a fallback.
- **Disk space**: The backup is a full copy of the bundle directory. The bundle is modest in size (search indices, .obj files, a couple zips). This is acceptable for the duration of a single rebuild.

## Acceptance criteria
- [ ] `COMPOSE_PROJECT_NAME=transit-board` is present in the `gtfs_updater` service's `environment` list in `docker-compose.yml`
- [ ] `./oba-server/bundle-backup:/bundle-backup` volume mount is present on the `gtfs_updater` service; `oba-server/bundle-backup/` is added to `.gitignore`
- [ ] `run_bundler()` accepts a `gtfs_url` parameter and passes it as `GTFS_URL` to the bundler subprocess (used to point at `BUNDLE_STAGING_PATH` instead of the hardcoded pristine path)
- [ ] When the GTFS checksum changes and `run_bundler()` succeeds: the staging zip is promoted to `BUNDLE_PRISTINE_PATH` via `os.replace`, the checksum is saved, `oba_app` is restarted, and the backup directory is cleaned up
- [ ] When the GTFS checksum changes and `run_bundler()` fails: `BUNDLE_PRISTINE_PATH` is NOT overwritten, the bundle directory is restored from backup, no checksum is saved, `oba_app` is NOT restarted, a `bundler_error` event with `bundle_restored` is written
- [ ] When `backup_bundle()` itself fails: the rebuild is aborted before `run_bundler()` is ever invoked, a `backup_error` event is written, staging zip is removed, nothing else is touched
- [ ] When the GTFS checksum is unchanged: no backup, no bundler run, no file operations on the bundle directory
- [ ] A test parses `docker-compose.yml` and asserts `COMPOSE_PROJECT_NAME=transit-board` is set on `gtfs_updater`
- [ ] A test asserts that on bundler failure, `shutil.copy2`/`os.replace` to `BUNDLE_PRISTINE_PATH` was never called (staging zip not promoted)
- [ ] A test asserts that on bundler failure, `restore_bundle` is called and `BUNDLE_STAGING_PATH` is removed
- [ ] A test asserts that on bundler success, the backup directory is cleaned up and staging zip is promoted
- [ ] A test asserts that a `backup_bundle()` failure prevents `run_bundler()` from being called at all
- [ ] All existing tests continue to pass (updated as needed for the new function signatures / call order)

## Tests to write
- **`test_compose_project_name_set`**: Parse `docker-compose.yml` (via `yaml.safe_load` or plain string search), assert that `gtfs_updater` service has `COMPOSE_PROJECT_NAME=transit-board` in its environment list. Catches silent regression of the env var being removed.
- **`test_run_bundler_uses_staging_path`**: Call `run_bundler` with a mocked `subprocess.run`, assert the constructed command includes `GTFS_URL=file:///bundle/gtfs_staging.zip`.
- **`test_update_check_success_promotes_staging_zip`**: Simulate changed checksum + successful bundler. Assert: `shutil.copy2` called with `(tmp_path, BUNDLE_STAGING_PATH)`, `shutil.move` called with `(BUNDLE_STAGING_PATH, BUNDLE_PRISTINE_PATH)`, `save_checksum` called, `restart_oba_app` called.
- **`test_update_check_failure_restores_bundle`**: Simulate changed checksum + failed bundler. Assert: `shutil.copy2` called with `(tmp_path, BUNDLE_STAGING_PATH)` but NOT `(anything, BUNDLE_PRISTINE_PATH)`, `restore_bundle` called, `save_checksum` NOT called, `restart_oba_app` NOT called, `bundler_error` JSONL event emitted.
- **`test_update_check_failure_does_not_overwrite_pristine`**: Simulate changed checksum + failed bundler. Assert `BUNDLE_PRISTINE_PATH` file content is unchanged (use temp directory with a known pristine file, verify contents match after failed run).
- **`test_backup_bundle_copies_contents`**: Create a temp directory with known files, call `backup_bundle`, assert backup directory contains identical files.
- **`test_restore_bundle_restores_contents`**: Create a temp bundle dir, back it up, modify the bundle dir (simulating partial bundler output), call `restore_bundle`, assert bundle dir matches original contents.
- **`test_backup_bundle_handles_empty_dir`**: Call `backup_bundle` on an empty directory, assert no error raised.
- **`test_backup_bundle_removes_stale_backup`**: Create a pre-existing backup directory, call `backup_bundle`, assert it is replaced with new backup contents.
- **`test_backup_failure_aborts_rebuild`**: Mock `backup_bundle` to return `False`. Assert `run_bundler` is never called, a `backup_error` JSONL event is written, and the pristine zip is unchanged.
- **`test_bundler_error_event_includes_bundle_restored`**: Simulate `run_bundler` returning `False` with a successful restore; assert the `bundler_error` event has `"bundle_restored": true`. Simulate again with `restore_bundle` also failing; assert `"bundle_restored": false`.

## Files that will change
- `docker-compose.yml` — add `COMPOSE_PROJECT_NAME=transit-board` to `gtfs_updater` environment; add `./oba-server/bundle-backup:/bundle-backup` volume mount
- `.gitignore` — add `oba-server/bundle-backup/`
- `gtfs-updater/updater.py` — add `BUNDLE_STAGING_PATH` and `BUNDLE_BACKUP_DIR` constants; add `backup_bundle()`, `restore_bundle()`, and `cleanup_backup()` functions; reorder `run_update_check()` to use staging zip, backup/restore, and promote-on-success flow; update `run_bundler()` to accept a `gtfs_url` parameter; add `backup_error` event type and `bundle_restored` field on `bundler_error`
- `gtfs-updater/test_updater.py` — add regression tests for compose project name, staging zip flow, backup/restore behavior, and backup-failure abort path; update existing tests for changed call signatures/order
