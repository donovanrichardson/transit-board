# Spec: GTFS Updater — Switch from GTFS_URL to GTFS_ZIP_FILENAME

## Goal
Fix the root cause of bundler build failures: the `oba_bundler` container's `wget` does not support `file://` URLs, and `wget -O` destructively truncates the destination file before failing. Switch `run_bundler()` from passing `GTFS_URL=file:///bundle/gtfs_staging.zip` to `GTFS_ZIP_FILENAME=gtfs_staging.zip`, which uses the bundler entrypoint's local-file code path (no wget). Also clean stale intermediate artifacts before each build so `gtfstidy` failures can never be silently masked by leftover cached output, and remove all now-unused `GTFS_URL` references from the project.

## Scope
### In scope
- `gtfs-updater/updater.py`: change `run_bundler()` to pass `GTFS_ZIP_FILENAME` instead of `GTFS_URL`; add pre-build cleanup of stale intermediate artifacts in `/bundle`
- `gtfs-updater/test_updater.py`: update existing tests and add new tests for the changed behavior
- `docker-compose.yml`: remove `GTFS_URL=${GTFS_URL}` from `oba_bundler` environment (required — both vars set simultaneously causes the entrypoint to exit 1)
- `.env`: remove the `GTFS_URL=...` line
- `.env.example`: remove the `GTFS_URL=...` line
- `README.md`: update/remove `GTFS_URL` references

### Out of scope
- Changes to the OneBusAway bundler image itself or its entrypoint script
- Changes to `GTFS_FEED_URL` (the feed URL used by `gtfs_updater` to download — this is a different variable and is correct as-is)
- Any changes to backup/restore logic (already implemented and working)
- Changes to `oba_app` service configuration

## Behavior

### 1. `run_bundler()` uses `GTFS_ZIP_FILENAME` instead of `GTFS_URL` (updater.py)

Current signature and command construction:
```python
def run_bundler(
    compose_dir: str,
    gtfs_url: str = f"file://{BUNDLE_STAGING_PATH}",
) -> bool:
    # ...
    "-e", f"GTFS_URL={gtfs_url}",
```

New signature and command construction:
```python
def run_bundler(
    compose_dir: str,
    gtfs_zip_filename: str = "gtfs_staging.zip",
) -> bool:
    # ...
    "-e", f"GTFS_ZIP_FILENAME={gtfs_zip_filename}",
```

The value is a bare filename (not a path, not a URL). The bundler entrypoint does `cd /bundle` before checking for the file, so `gtfs_staging.zip` resolves to `/bundle/gtfs_staging.zip` — exactly where `run_update_check()` places the downloaded feed via `shutil.copy2(tmp_path, BUNDLE_STAGING_PATH)`.

The `gtfs_url` parameter is removed entirely. There is no fallback or alternative code path for `GTFS_URL`.

### 2. Pre-build cleanup of stale intermediate artifacts (updater.py)

Add a new function `clean_bundle_intermediates(bundle_dir: str)` that removes, if they exist:
- `<bundle_dir>/gtfs-out/` (directory that `gtfstidy` writes output to; the bundler script re-zips this)
- `<bundle_dir>/gtfs_tidied.zip` (the re-zipped tidied output)

Call this function in `run_update_check()` immediately before calling `run_bundler()` (after `backup_bundle()` succeeds, before `run_bundler()`). This ensures that if `gtfstidy` fails on bad input, there is no stale `gtfs-out/` directory for the bundler script's `if [[ -d "gtfs-out" ]]` check to find and silently re-package.

The function should log at INFO level what it removes, and should not raise on missing paths (use `shutil.rmtree` with `ignore_errors` or check existence first). Errors removing files should be logged as warnings but should not abort the build (the build itself will likely fail if artifacts can't be cleaned, but that failure will be caught by the existing `run_bundler()` return-code check).

### 3. Remove `GTFS_URL` from `oba_bundler` environment in docker-compose.yml

Current:
```yaml
oba_bundler:
    environment:
      - GTFS_URL=${GTFS_URL}
```

After: remove the entire `environment` block from `oba_bundler` (it contains only `GTFS_URL`). The `-e GTFS_ZIP_FILENAME=gtfs_staging.zip` flag on `docker compose run` provides the needed env var at runtime.

This is a correctness fix, not just cleanup: the bundler entrypoint exits immediately with an error if both `GTFS_URL` and `GTFS_ZIP_FILENAME` are set. If the compose-file environment injects `GTFS_URL` (resolved from `.env`) while `run_bundler()` also passes `-e GTFS_ZIP_FILENAME=...`, both would be present in the container and the entrypoint would fail.

### 4. Remove `GTFS_URL` from `.env` and `.env.example`

`.env` — remove line: `GTFS_URL=https://files.mobilitydatabase.org/mdb-507/mdb-507-202606110133/mdb-507-202606110133.zip`

`.env.example` — remove line: `GTFS_URL=https://example.com/path/to/gtfs.zip`

Nothing else in the project reads `GTFS_URL` from the environment. The `gtfs_updater` service uses `GTFS_FEED_URL` (a different variable) for the actual feed download URL.

### 5. Update README.md

Remove or replace all `GTFS_URL` references:
- The env var table row for `GTFS_URL` should be removed
- Any setup instructions referencing `GTFS_URL` in `.env` should be updated to remove it
- The `GTFS_FEED_URL` variable (used by `gtfs_updater`) should remain documented as-is

## Edge cases

- **`oba_bundler` run manually via `docker compose run oba_bundler` without `-e` flags**: Without either `GTFS_URL` or `GTFS_ZIP_FILENAME` set, the bundler entrypoint prints "Error: Neither GTFS_URL nor GTFS_ZIP_FILENAME is set" and exits 1. This is correct behavior — the bundler should only be invoked through `gtfs_updater` or with explicit env vars.
- **Stale `gtfs-out/` from a previous successful build**: `clean_bundle_intermediates()` removes it before each build, preventing silent reuse.
- **`clean_bundle_intermediates()` fails to remove a file (permission error, etc.)**: Logged as warning; build proceeds. The build will likely fail on its own merits, and the existing backup/restore handles rollback.
- **`gtfs_staging.zip` does not exist at `/bundle/gtfs_staging.zip` when bundler runs**: The bundler entrypoint's `else` branch checks `if [ ! -f "$GTFS_ZIP_FILENAME" ]` and exits 1 with a clear error. `run_bundler()` returns `False`, triggering the existing restore path. This cannot happen in normal operation because `run_update_check()` does `shutil.copy2(tmp_path, BUNDLE_STAGING_PATH)` before calling `run_bundler()`.
- **`GTFS_ZIP_FILENAME` value contains path separators or special characters**: Not a concern — the value is hardcoded as `"gtfs_staging.zip"` (a constant bare filename), never user-supplied.

## Acceptance criteria
- [ ] `run_bundler()` passes `-e GTFS_ZIP_FILENAME=gtfs_staging.zip` (not `GTFS_URL`) to the `docker compose run` command
- [ ] `run_bundler()` no longer has any reference to `GTFS_URL` in its signature, default arguments, or command construction
- [ ] `clean_bundle_intermediates()` exists and removes `/bundle/gtfs-out/` and `/bundle/gtfs_tidied.zip` if present
- [ ] `clean_bundle_intermediates()` is called in `run_update_check()` after `backup_bundle()` succeeds and before `run_bundler()` is invoked
- [ ] `docker-compose.yml` `oba_bundler` service has no `environment` block (or at minimum no `GTFS_URL` entry)
- [ ] `.env` does not contain a `GTFS_URL` line
- [ ] `.env.example` does not contain a `GTFS_URL` line
- [ ] `README.md` does not reference `GTFS_URL` as a required or used env var
- [ ] All 46 existing tests pass (updated as needed for the changed `run_bundler()` signature)
- [ ] New tests cover `clean_bundle_intermediates()` behavior and the updated `run_bundler()` command

## Tests to write
- **`test_run_bundler_passes_gtfs_zip_filename`**: Call `run_bundler` with mocked `subprocess.run`; assert the constructed command includes `-e` followed by `GTFS_ZIP_FILENAME=gtfs_staging.zip`; assert `GTFS_URL` does not appear anywhere in the command.
- **`test_run_bundler_custom_filename`**: Call `run_bundler("/compose", gtfs_zip_filename="custom.zip")`; assert command includes `GTFS_ZIP_FILENAME=custom.zip`.
- **`test_clean_bundle_intermediates_removes_gtfs_out`**: Create a temp dir with a `gtfs-out/` subdirectory containing files; call `clean_bundle_intermediates()`; assert `gtfs-out/` no longer exists.
- **`test_clean_bundle_intermediates_removes_gtfs_tidied_zip`**: Create a temp dir with a `gtfs_tidied.zip` file; call `clean_bundle_intermediates()`; assert the file no longer exists.
- **`test_clean_bundle_intermediates_no_artifacts_is_noop`**: Call `clean_bundle_intermediates()` on a directory with no intermediate artifacts; assert no error raised, directory is unchanged.
- **`test_clean_bundle_intermediates_called_before_bundler`**: In `run_update_check` with a changed checksum, track call order of `backup_bundle`, `clean_bundle_intermediates`, and `run_bundler`; assert order is backup -> clean -> bundler.
- **`test_compose_no_gtfs_url_in_bundler`**: Parse `docker-compose.yml`; assert the `oba_bundler` service does not have `GTFS_URL` in its environment.

## Tests to update
- **`test_run_bundler_success`** (line 148): Remove assertion for `file:///bundle/gtfs_staging.zip` in command; replace with assertion for `GTFS_ZIP_FILENAME=gtfs_staging.zip`.
- **`test_run_bundler_uses_staging_path`** (line 164): This test's purpose is replaced by `test_run_bundler_passes_gtfs_zip_filename`. Either rewrite it to assert `GTFS_ZIP_FILENAME=gtfs_staging.zip` or delete it in favor of the new test.
- **`test_bundle_backup_created_before_bundler`**: Update to expect call order `["backup", "clean", "bundler"]` instead of `["backup", "bundler"]`.
- Any test that patches or references `run_bundler`'s `gtfs_url` parameter must be updated to use `gtfs_zip_filename` instead.

## Files that will change
- `gtfs-updater/updater.py` — change `run_bundler()` parameter from `gtfs_url` to `gtfs_zip_filename`, change `-e` flag from `GTFS_URL=...` to `GTFS_ZIP_FILENAME=...`, add `clean_bundle_intermediates()` function, call it in `run_update_check()` before `run_bundler()`
- `gtfs-updater/test_updater.py` — update existing `run_bundler` tests for new parameter/command, update call-order test, add new tests for `clean_bundle_intermediates()` and compose config validation
- `docker-compose.yml` — remove `environment` block (or `GTFS_URL` line) from `oba_bundler` service
- `.env` — remove `GTFS_URL=...` line
- `.env.example` — remove `GTFS_URL=...` line
- `README.md` — remove/update `GTFS_URL` references
