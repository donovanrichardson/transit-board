import hashlib
import io
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch, call
from urllib.error import URLError
from zoneinfo import ZoneInfo

import updater


class TestComputeChecksum(unittest.TestCase):
    def test_compute_checksum(self):
        data = b"hello world"
        expected = hashlib.sha256(data).hexdigest()
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(data)
            path = f.name
        try:
            result = updater.compute_checksum(path)
            self.assertEqual(result, expected)
        finally:
            os.unlink(path)

    def test_compute_checksum_different_content(self):
        with tempfile.NamedTemporaryFile(delete=False) as f1, \
             tempfile.NamedTemporaryFile(delete=False) as f2:
            f1.write(b"content one")
            f2.write(b"content two")
            path1, path2 = f1.name, f2.name
        try:
            self.assertNotEqual(
                updater.compute_checksum(path1),
                updater.compute_checksum(path2),
            )
        finally:
            os.unlink(path1)
            os.unlink(path2)


class TestScheduleNextRun(unittest.TestCase):
    def test_schedule_next_run_future_today(self):
        # 03:00 ET → next run at 05:00 ET same day ≈ 7200 seconds
        ET = ZoneInfo("America/New_York")
        mock_now = datetime(2024, 6, 15, 3, 0, 0, tzinfo=ET)
        seconds = updater.seconds_until_next_run(update_hour=5, now=mock_now)
        self.assertAlmostEqual(seconds, 7200, delta=5)

    def test_schedule_next_run_past_today(self):
        # 06:00 ET → next run at 05:00 ET next day ≈ 82800 seconds
        ET = ZoneInfo("America/New_York")
        mock_now = datetime(2024, 6, 15, 6, 0, 0, tzinfo=ET)
        seconds = updater.seconds_until_next_run(update_hour=5, now=mock_now)
        self.assertAlmostEqual(seconds, 82800, delta=5)

    def test_schedule_next_run_dst_transition(self):
        # Spring-forward 2024: 2024-03-10 at 02:00 clocks skip to 03:00
        # If now is 2024-03-10 01:30 ET (before spring-forward),
        # next 05:00 is same day but the day is only 23 hours long.
        ET = ZoneInfo("America/New_York")
        mock_now = datetime(2024, 3, 10, 1, 30, 0, tzinfo=ET)
        seconds = updater.seconds_until_next_run(update_hour=5, now=mock_now)
        # 01:30 → 05:00 on same day = 3.5 hours = 12600 seconds
        # DST spring-forward makes this day 23h but the gap from 01:30 to 05:00
        # is still wall-clock 3h30m = 12600 seconds
        self.assertAlmostEqual(seconds, 12600, delta=10)


class TestDownloadFeed(unittest.TestCase):
    def test_download_feed_success(self):
        fake_data = b"PK fake zip content"
        mock_response = MagicMock()
        mock_response.__enter__ = lambda s: s
        mock_response.__exit__ = MagicMock(return_value=False)
        mock_response.read.return_value = fake_data

        with patch("updater.urllib.request.urlopen", return_value=mock_response):
            path = updater.download_feed("https://example.com/feed.zip")

        self.assertIsNotNone(path)
        try:
            with open(path, "rb") as f:
                self.assertEqual(f.read(), fake_data)
        finally:
            if path and os.path.exists(path):
                os.unlink(path)

    def test_download_feed_http_error(self):
        with patch("updater.urllib.request.urlopen", side_effect=URLError("404")):
            with self.assertLogs("updater", level="ERROR"):
                path = updater.download_feed("https://example.com/feed.zip")
        self.assertIsNone(path)

    def test_download_feed_timeout(self):
        with patch("updater.urllib.request.urlopen", side_effect=socket.timeout("timed out")):
            path = updater.download_feed("https://example.com/feed.zip")
        self.assertIsNone(path)


class TestDatabaseFunctions(unittest.TestCase):
    def _make_conn(self, fetchone_return=None):
        cursor = MagicMock()
        cursor.fetchone.return_value = fetchone_return
        conn = MagicMock()
        conn.cursor.return_value.__enter__ = lambda s: cursor
        conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
        return conn, cursor

    def test_ensure_table_creates(self):
        conn, cursor = self._make_conn()
        updater.ensure_table(conn)
        executed_sql = cursor.execute.call_args[0][0]
        self.assertIn("CREATE TABLE IF NOT EXISTS", executed_sql)
        self.assertIn("gtfs_checksums", executed_sql)

    def test_get_latest_checksum_no_rows(self):
        conn, cursor = self._make_conn(fetchone_return=None)
        result = updater.get_latest_checksum(conn)
        self.assertIsNone(result)

    def test_get_latest_checksum_has_row(self):
        fake_sha = "a" * 64
        conn, cursor = self._make_conn(fetchone_return=(fake_sha,))
        result = updater.get_latest_checksum(conn)
        self.assertEqual(result, fake_sha)

    def test_save_checksum(self):
        conn, cursor = self._make_conn()
        sha = "b" * 64
        feed_url = "https://example.com/feed.zip"
        updater.save_checksum(conn, sha, feed_url)
        executed_sql = cursor.execute.call_args[0][0]
        self.assertIn("INSERT INTO gtfs_checksums", executed_sql)
        args = cursor.execute.call_args[0][1]
        self.assertIn(sha, args)
        self.assertIn(feed_url, args)


class TestSubprocessFunctions(unittest.TestCase):
    def test_run_bundler_success(self):
        with patch("updater.subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0)
            result = updater.run_bundler("/compose")
        self.assertTrue(result)
        cmd = mock_run.call_args[0][0]
        self.assertIn("oba_bundler", cmd)
        self.assertIn("file:///bundle/gtfs_pristine.zip", " ".join(cmd))

    def test_run_bundler_failure(self):
        with patch("updater.subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=1)
            with self.assertLogs("updater", level="ERROR"):
                result = updater.run_bundler("/compose")
        self.assertFalse(result)

    def test_restart_oba_app(self):
        with patch("updater.subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0)
            updater.restart_oba_app("/compose")
        cmd = mock_run.call_args[0][0]
        self.assertIn("restart", cmd)
        self.assertIn("oba_app", cmd)


class TestUpdateCheck(unittest.TestCase):
    def _make_conn(self, stored_sha=None):
        cursor = MagicMock()
        cursor.fetchone.return_value = (stored_sha,) if stored_sha else None
        conn = MagicMock()
        conn.cursor.return_value.__enter__ = lambda s: cursor
        conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
        return conn

    def _setup_download(self, data=b"fake zip"):
        """Returns (fake_sha, context_manager_patch_target)"""
        fake_sha = hashlib.sha256(data).hexdigest()

        def fake_download(url):
            f = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
            f.write(data)
            f.close()
            return f.name

        return fake_sha, fake_download

    def test_update_check_unchanged(self):
        data = b"same zip content"
        sha = hashlib.sha256(data).hexdigest()
        conn = self._make_conn(stored_sha=sha)

        _, fake_download = self._setup_download(data)

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.run_bundler") as mock_bundler, \
             patch("updater.restart_oba_app") as mock_restart, \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.get_latest_checksum", return_value=sha), \
             self.assertLogs("updater", level="INFO"):
            updater.run_update_check(conn, "https://example.com/feed.zip", "/compose")

        mock_bundler.assert_not_called()
        mock_restart.assert_not_called()
        mock_save.assert_not_called()

    def test_update_check_changed(self):
        old_sha = "a" * 64
        data = b"new zip content"
        new_sha = hashlib.sha256(data).hexdigest()
        conn = self._make_conn(stored_sha=old_sha)

        _, fake_download = self._setup_download(data)

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.run_bundler", return_value=True) as mock_bundler, \
             patch("updater.restart_oba_app") as mock_restart, \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.get_latest_checksum", return_value=old_sha), \
             patch("shutil.copy2"), \
             self.assertLogs("updater", level="INFO"):
            updater.run_update_check(conn, "https://example.com/feed.zip", "/compose")

        mock_bundler.assert_called_once()
        mock_restart.assert_called_once()
        mock_save.assert_called_once()

    def test_update_check_first_run(self):
        data = b"first run zip"
        conn = self._make_conn(stored_sha=None)

        _, fake_download = self._setup_download(data)

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.run_bundler", return_value=True) as mock_bundler, \
             patch("updater.restart_oba_app"), \
             patch("updater.save_checksum"), \
             patch("updater.get_latest_checksum", return_value=None), \
             patch("shutil.copy2"), \
             self.assertLogs("updater", level="INFO"):
            updater.run_update_check(conn, "https://example.com/feed.zip", "/compose")

        mock_bundler.assert_called_once()

    def test_update_check_bundler_fails(self):
        old_sha = "c" * 64
        data = b"updated zip but bundler fails"
        conn = self._make_conn(stored_sha=old_sha)

        _, fake_download = self._setup_download(data)

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.run_bundler", return_value=False), \
             patch("updater.restart_oba_app") as mock_restart, \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.get_latest_checksum", return_value=old_sha), \
             patch("shutil.copy2"), \
             self.assertLogs("updater", level="ERROR"):
            updater.run_update_check(conn, "https://example.com/feed.zip", "/compose")

        mock_save.assert_not_called()
        mock_restart.assert_not_called()


if __name__ == "__main__":
    unittest.main()
