import hashlib
import json
import logging
import os
import shutil
import socket
import tempfile
import time
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from urllib.error import URLError
from zoneinfo import ZoneInfo

import pymysql

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(message)s",
    level=logging.INFO,
    stream=None,
)
logger = logging.getLogger("updater")

GTFS_FEED_URL = os.environ.get(
    "GTFS_FEED_URL", "https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip"
)
GTFS_UPDATE_HOUR = int(os.environ.get("GTFS_UPDATE_HOUR", "5"))
MYSQL_HOST = os.environ.get("MYSQL_HOST", "oba_database")
MYSQL_PORT = int(os.environ.get("MYSQL_PORT", "3306"))
MYSQL_USER = os.environ.get("MYSQL_USER", "")
MYSQL_PASSWORD = os.environ.get("MYSQL_PASSWORD", "")
MYSQL_DATABASE = os.environ.get("MYSQL_DATABASE", "oba_database")
LOG_PATH = os.environ.get("LOG_PATH", "/logs/gtfs_updater.jsonl")

BUNDLE_PRISTINE_PATH = "/bundle/gtfs_pristine.zip"
BUNDLE_STAGING_PATH = "/bundle/gtfs_staging.zip"
BUNDLE_DIR = "/bundle"
DOWNLOAD_TIMEOUT = 120

REBUILD_REQUEST_PATH = "/bundle/.rebuild_request.json"
REBUILD_RESULT_PATH = "/bundle/.rebuild_result.json"
REBUILD_POLL_INTERVAL = 10   # seconds between checks for result marker
REBUILD_TIMEOUT = 600        # seconds (10 minutes) to wait for watcher to complete


def write_event(event: dict, log_path: str | None = None) -> None:
    path = log_path if log_path is not None else LOG_PATH
    try:
        with open(path, "a") as f:
            f.write(json.dumps(event) + "\n")
            f.flush()
    except OSError as exc:
        logger.warning("Failed to write JSONL event to %s: %s", path, exc)


def compute_checksum(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def seconds_until_next_run(update_hour: int, now: datetime | None = None) -> float:
    ET = ZoneInfo("America/New_York")
    if now is None:
        now = datetime.now(tz=ET)
    elif now.tzinfo is None:
        now = now.replace(tzinfo=ET)

    target = now.replace(hour=update_hour, minute=0, second=0, microsecond=0)
    if target <= now:
        target = target + timedelta(days=1)

    return (target - now).total_seconds()


def download_feed(url: str) -> str | None:
    try:
        with urllib.request.urlopen(url, timeout=DOWNLOAD_TIMEOUT) as resp:
            data = resp.read()
        f = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
        f.write(data)
        f.close()
        return f.name
    except (URLError, socket.timeout) as exc:
        logger.error("Failed to download feed from %s: %s", url, exc)
        return None


def connect_mysql(retries: int = 10, backoff: int = 5) -> pymysql.Connection:
    for attempt in range(1, retries + 1):
        try:
            conn = pymysql.connect(
                host=MYSQL_HOST,
                port=MYSQL_PORT,
                user=MYSQL_USER,
                password=MYSQL_PASSWORD,
                database=MYSQL_DATABASE,
                autocommit=True,
            )
            logger.info("Connected to MySQL on attempt %d", attempt)
            return conn
        except pymysql.Error as exc:
            logger.warning(
                "MySQL connection attempt %d/%d failed: %s", attempt, retries, exc
            )
            if attempt < retries:
                time.sleep(backoff)
    logger.error("Could not connect to MySQL after %d attempts", retries)
    raise SystemExit(1)


def ensure_table(conn: pymysql.Connection) -> None:
    sql = """
    CREATE TABLE IF NOT EXISTS gtfs_checksums (
        id INT AUTO_INCREMENT PRIMARY KEY,
        sha256 CHAR(64) NOT NULL,
        feed_url VARCHAR(512) NOT NULL,
        checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        bundle_built_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """
    with conn.cursor() as cursor:
        cursor.execute(sql)
    logger.info("gtfs_checksums table ensured")


def get_latest_checksum(conn: pymysql.Connection) -> str | None:
    sql = "SELECT sha256 FROM gtfs_checksums ORDER BY id DESC LIMIT 1"
    with conn.cursor() as cursor:
        cursor.execute(sql)
        row = cursor.fetchone()
    if row is None:
        return None
    return row[0]


def save_checksum(conn: pymysql.Connection, sha256: str, feed_url: str) -> None:
    sql = "INSERT INTO gtfs_checksums (sha256, feed_url) VALUES (%s, %s)"
    with conn.cursor() as cursor:
        cursor.execute(sql, (sha256, feed_url))
    logger.info("Saved new checksum %s", sha256)


def _now_ts() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


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


def cleanup_marker_files() -> None:
    """Remove request and result marker files."""
    for path in (REBUILD_REQUEST_PATH, REBUILD_RESULT_PATH):
        try:
            os.unlink(path)
        except FileNotFoundError:
            pass


def run_update_check(conn: pymysql.Connection, feed_url: str) -> None:
    logger.info("Running GTFS update check")

    tmp_path = download_feed(feed_url)
    if tmp_path is None:
        logger.error("Skipping update check due to download failure")
        write_event({
            "ts": _now_ts(),
            "event": "download_error",
            "feed_url": feed_url,
            "sha256": None,
            "error": "Feed download failed (see stdout logs for details)",
        })
        return

    try:
        new_sha = compute_checksum(tmp_path)
        stored_sha = get_latest_checksum(conn)

        if stored_sha == new_sha:
            logger.info("GTFS feed unchanged (sha256=%s)", new_sha)
            write_event({
                "ts": _now_ts(),
                "event": "check_unchanged",
                "feed_url": feed_url,
                "sha256": new_sha,
                "previous_sha256": stored_sha,
            })
            return

        logger.info(
            "GTFS feed changed (stored=%s new=%s), triggering rebuild",
            stored_sha,
            new_sha,
        )
        write_event({
            "ts": _now_ts(),
            "event": "check_changed",
            "feed_url": feed_url,
            "sha256": new_sha,
            "previous_sha256": stored_sha,
        })

        # Step 1: write downloaded feed to staging location
        shutil.copy2(tmp_path, BUNDLE_STAGING_PATH)

        # Step 2: request rebuild via marker file
        nonce = request_rebuild(new_sha)

        # Step 3: wait for watcher to complete the rebuild
        result = wait_for_rebuild_result(nonce)

        if result is None:
            # Timeout
            logger.error("Rebuild timed out after %ds", REBUILD_TIMEOUT)
            write_event({
                "ts": _now_ts(),
                "event": "bundler_error",
                "feed_url": feed_url,
                "sha256": new_sha,
                "previous_sha256": stored_sha,
                "error": "rebuild timed out after 600s",
                "bundle_restored": None,
            })
            try:
                os.unlink(BUNDLE_STAGING_PATH)
            except OSError:
                pass
            cleanup_marker_files()
            return

        if result["success"]:
            # Promote staging zip to pristine
            shutil.move(BUNDLE_STAGING_PATH, BUNDLE_PRISTINE_PATH)
            save_checksum(conn, new_sha, feed_url)
            write_event({
                "ts": _now_ts(),
                "event": "update_complete",
                "feed_url": feed_url,
                "sha256": new_sha,
            })
            cleanup_marker_files()
            logger.info("GTFS update complete")
        else:
            # Build failed
            error_string = result.get("error", "")
            bundle_restored = "restore_failed" not in error_string
            logger.error("Rebuild failed: %s", error_string)
            write_event({
                "ts": _now_ts(),
                "event": "bundler_error",
                "feed_url": feed_url,
                "sha256": new_sha,
                "previous_sha256": stored_sha,
                "error": error_string,
                "bundle_restored": bundle_restored,
            })
            try:
                os.unlink(BUNDLE_STAGING_PATH)
            except OSError:
                pass
            cleanup_marker_files()
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


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


if __name__ == "__main__":
    main()
