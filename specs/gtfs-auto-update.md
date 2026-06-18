# Spec: GTFS Auto-Update

## Goal
Automatically check for LIRR GTFS feed updates daily at 5 AM America/New_York, and when the feed has changed, rebuild the OBA bundle and restart the OBA server so the transit board always serves current schedule data.

## Assumptions
- The OBA bundler container downloads GTFS from `GTFS_URL` and writes the bundle to `/bundle` (bind-mounted from `./oba-server/bundle/` on the host).
- The GTFS zip at `https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip` is a stable URL pointing to the latest LIRR feed.
- Double-downloading the GTFS zip (once for checksum, once by the bundler) is **avoided**: the updater saves the downloaded zip to `/bundle/gtfs_pristine.zip` and passes `GTFS_URL=file:///bundle/gtfs_pristine.zip` when triggering the bundler, eliminating the race condition.
- The Hetzner VPS has 2 vCPU / 3.7 GB RAM. Bundle builds are memory-intensive; the updater must not run concurrently with a manual bundler run.

## Scope

### In scope
- `transit-board/gtfs-updater/updater.py` — new script: download, checksum, orchestration
- `transit-board/gtfs-updater/Dockerfile` — Python 3.12 Alpine with docker-cli and pymysql
- `transit-board/gtfs-updater/test_updater.py` — unit tests
- `transit-board/docker-compose.yml` — add `gtfs_updater` service
- `transit-board/.env.example` — document new env vars

### Out of scope
- Changes to `oba_bundler`, `oba_app`, `oba_database`, `transit-board-api`, `frontend`, or `monitor`
- Monitoring/alerting integration (logs go to stdout; monitor can be extended later)
- Support for multiple GTFS feeds

---

## Behavior

### Service overview
A new Docker service `gtfs_updater` runs continuously. On startup it runs one update check immediately (catching feed changes while the stack was down), then sleeps until the next 05:00 America/New_York and repeats daily.

### Startup check
1. Wait for MySQL to be ready (retry up to 10 times with 5-second backoff; exit with code 1 if unreachable — Docker `restart: unless-stopped` will restart the container).
2. Ensure the `gtfs_checksums` table exists (CREATE IF NOT EXISTS).
3. Run one update check immediately.
4. Enter the scheduling loop.

### Update check logic (daily at 05:00 America/New_York)
1. **Download**: HTTP GET `https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip` to a temporary file. Timeout: 120 seconds.
2. **Checksum**: Compute SHA-256 of the downloaded file.
3. **Compare**: Query `gtfs_checksums` for the most recent `sha256`. No rows = treat as changed.
4. **If unchanged**: log `"GTFS feed unchanged (sha256=<hex>)"`, delete temp file, sleep until next scheduled time.
5. **If changed**:
   a. Copy downloaded zip to `/bundle/gtfs_pristine.zip`.
   b. Run bundler: `docker compose -f /compose/docker-compose.yml run --rm -e GTFS_URL=file:///bundle/gtfs_pristine.zip oba_bundler`
   c. If bundler exits 0: insert row into `gtfs_checksums`, then run `docker compose -f /compose/docker-compose.yml restart oba_app`. Log success.
   d. If bundler exits non-zero: log error to stderr, do NOT update checksum (next run will retry), do NOT restart oba_app.
6. Delete temp file. Sleep until next scheduled time.

### MySQL table: `gtfs_checksums`
Auto-created by the updater on startup:

```sql
CREATE TABLE IF NOT EXISTS gtfs_checksums (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sha256 CHAR(64) NOT NULL,
    feed_url VARCHAR(512) NOT NULL,
    checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    bundle_built_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

The updater connects using `MYSQL_USER` / `MYSQL_PASSWORD` (mapped from `JDBC_USER` / `JDBC_PASSWORD` in docker-compose). Uses `pymysql` (pure Python, avoids compiled deps on Alpine).

### Scheduling
Uses the `zoneinfo` stdlib module (Python 3.9+) for America/New_York. Computes seconds until the next `GTFS_UPDATE_HOUR:00` wall-clock time in that timezone, handling DST correctly.

### Logging
All output via Python `logging` to stdout/stderr. Format: `%(asctime)s %(levelname)s %(message)s`.

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `GTFS_FEED_URL` | `https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip` | URL to download |
| `GTFS_UPDATE_HOUR` | `5` | Hour (0–23) in America/New_York to run daily check |
| `MYSQL_HOST` | `oba_database` | MySQL hostname |
| `MYSQL_PORT` | `3306` | MySQL port |
| `MYSQL_USER` | (required) | MySQL username |
| `MYSQL_PASSWORD` | (required) | MySQL password |
| `MYSQL_DATABASE` | `oba_database` | MySQL database name |
| `COMPOSE_PROJECT_DIR` | `/compose` | Mount point for the directory containing docker-compose.yml |

---

## docker-compose.yml service definition

```yaml
gtfs_updater:
  build: ./gtfs-updater
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
    - ./oba-server/bundle:/bundle
    - .:/compose:ro
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

## Dockerfile

```dockerfile
FROM python:3.12-alpine

RUN apk add --no-cache docker-cli docker-cli-compose

RUN pip install --no-cache-dir pymysql

WORKDIR /app
COPY updater.py .
CMD ["python", "updater.py"]
```

---

## Edge cases

| Scenario | Behavior |
|---|---|
| Download fails (network, HTTP error, timeout) | Log to stderr, skip update, sleep to next cycle. No crash. |
| MySQL not ready at startup | Retry 10× with 5s backoff, then exit 1 (Docker restarts). |
| Bundler exits non-zero (OOM, corrupt GTFS, etc.) | Log error, do NOT save checksum, do NOT restart oba_app. Next run retries. |
| oba_app restart fails | Log error. Checksum IS saved (bundle succeeded). No rebuild on next run — manual intervention needed. |
| Feed changed while stack was down | Startup check catches it and rebuilds. |
| DST transitions | `zoneinfo` handles correctly. |
| First run ever (no checksum rows) | Treat as changed — always build on first run. |
| Race condition: different content on bundler download vs updater download | Avoided: updater saves zip to `/bundle/gtfs_pristine.zip` and passes `GTFS_URL=file:///...` to the bundler. |

---

## Acceptance criteria

- [ ] `docker compose up gtfs_updater` starts without error and connects to MySQL
- [ ] On first start (empty `gtfs_checksums`): downloads feed, builds bundle, restarts oba_app, inserts checksum row
- [ ] On subsequent start with matching checksum: logs "unchanged", does not rebuild
- [ ] `gtfs_checksums` table is auto-created if it does not exist
- [ ] After successful update, oba_app is restarted and serves new schedule data
- [ ] Failed download does not crash the updater; logs error and waits for next cycle
- [ ] Failed bundle build does not update checksum table and does not restart oba_app
- [ ] `GTFS_UPDATE_HOUR=13` causes the check to run at 13:00 instead of 05:00
- [ ] All unit tests pass

---

## Unit tests (`gtfs-updater/test_updater.py`)

- **test_compute_checksum** — known bytes → expected SHA-256 hex
- **test_compute_checksum_different_content** — two different files produce different checksums
- **test_schedule_next_run_future_today** — mock 03:00 ET → assert ~7200 seconds until hour=5
- **test_schedule_next_run_past_today** — mock 06:00 ET → assert ~82800 seconds until hour=5 next day
- **test_schedule_next_run_dst_transition** — mock time just before spring-forward → correct seconds for 23-hour day
- **test_download_feed_success** — mock urlopen returns bytes → file written, path returned
- **test_download_feed_http_error** — mock urlopen raises URLError → returns None, logs error
- **test_download_feed_timeout** — mock urlopen raises socket.timeout → returns None
- **test_ensure_table_creates** — mock pymysql → `CREATE TABLE IF NOT EXISTS` executed
- **test_get_latest_checksum_no_rows** — mock cursor returns None → function returns None
- **test_get_latest_checksum_has_row** — mock cursor returns a row → function returns sha256 string
- **test_save_checksum** — mock cursor → `INSERT INTO gtfs_checksums` called with correct values
- **test_run_bundler_success** — mock subprocess.run exit 0 → returns True
- **test_run_bundler_failure** — mock subprocess.run exit 1 → returns False, error logged
- **test_restart_oba_app** — mock subprocess.run → `docker compose restart oba_app` called
- **test_update_check_unchanged** — mock download, checksum matches stored → bundler NOT called
- **test_update_check_changed** — mock download, checksum differs → bundler called, checksum saved, oba_app restarted
- **test_update_check_first_run** — mock download, no stored checksum → bundler called
- **test_update_check_bundler_fails** — mock download, checksum differs, bundler fails → checksum NOT saved, oba_app NOT restarted

---

## Files that will change

- `transit-board/gtfs-updater/updater.py` — new
- `transit-board/gtfs-updater/Dockerfile` — new
- `transit-board/gtfs-updater/test_updater.py` — new
- `transit-board/docker-compose.yml` — add `gtfs_updater` service
- `transit-board/.env.example` — document `GTFS_FEED_URL`, `GTFS_UPDATE_HOUR`
