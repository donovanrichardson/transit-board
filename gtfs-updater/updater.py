import hashlib
import logging
import os
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
from datetime import datetime, timedelta
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
COMPOSE_PROJECT_DIR = os.environ.get("COMPOSE_PROJECT_DIR", "/compose")

BUNDLE_PRISTINE_PATH = "/bundle/gtfs_pristine.zip"
DOWNLOAD_TIMEOUT = 120


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


def run_bundler(compose_dir: str) -> bool:
    compose_file = os.path.join(compose_dir, "docker-compose.yml")
    cmd = [
        "docker", "compose",
        "-f", compose_file,
        "run", "--rm",
        "-e", "GTFS_URL=file:///bundle/gtfs_pristine.zip",
        "oba_bundler",
    ]
    result = subprocess.run(cmd)
    if result.returncode != 0:
        logger.error("oba_bundler exited with code %d", result.returncode)
        return False
    return True


def restart_oba_app(compose_dir: str) -> None:
    compose_file = os.path.join(compose_dir, "docker-compose.yml")
    cmd = [
        "docker", "compose",
        "-f", compose_file,
        "restart", "oba_app",
    ]
    result = subprocess.run(cmd)
    if result.returncode != 0:
        logger.error("Failed to restart oba_app (exit code %d)", result.returncode)
    else:
        logger.info("oba_app restarted successfully")


def run_update_check(conn: pymysql.Connection, feed_url: str, compose_dir: str) -> None:
    logger.info("Running GTFS update check")

    tmp_path = download_feed(feed_url)
    if tmp_path is None:
        logger.error("Skipping update check due to download failure")
        return

    try:
        new_sha = compute_checksum(tmp_path)
        stored_sha = get_latest_checksum(conn)

        if stored_sha == new_sha:
            logger.info("GTFS feed unchanged (sha256=%s)", new_sha)
            return

        logger.info(
            "GTFS feed changed (stored=%s new=%s), triggering rebuild",
            stored_sha,
            new_sha,
        )

        shutil.copy2(tmp_path, BUNDLE_PRISTINE_PATH)

        success = run_bundler(compose_dir)
        if not success:
            logger.error("Bundle build failed; skipping checksum save and oba_app restart")
            return

        save_checksum(conn, new_sha, feed_url)
        restart_oba_app(compose_dir)
        logger.info("GTFS update complete")
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def main() -> None:
    conn = connect_mysql()
    ensure_table(conn)
    run_update_check(conn, GTFS_FEED_URL, COMPOSE_PROJECT_DIR)

    ET = ZoneInfo("America/New_York")
    while True:
        secs = seconds_until_next_run(GTFS_UPDATE_HOUR)
        next_time = datetime.now(tz=ET).replace(
            hour=GTFS_UPDATE_HOUR, minute=0, second=0, microsecond=0
        )
        logger.info("Next update check in %.0f seconds (at %s ET)", secs, next_time)
        time.sleep(secs)
        run_update_check(conn, GTFS_FEED_URL, COMPOSE_PROJECT_DIR)


if __name__ == "__main__":
    main()
