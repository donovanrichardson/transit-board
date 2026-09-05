import hashlib
import inspect
import io
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
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


class TestRequestRebuild(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.request_path = os.path.join(self.tmpdir, ".rebuild_request.json")
        self.result_path = os.path.join(self.tmpdir, ".rebuild_result.json")

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_request_rebuild_writes_marker_file(self):
        sha = "a" * 64
        with patch("updater.REBUILD_REQUEST_PATH", self.request_path), \
             patch("updater.REBUILD_RESULT_PATH", self.result_path), \
             self.assertLogs("updater", level="INFO"):
            nonce = updater.request_rebuild(sha)

        self.assertTrue(os.path.exists(self.request_path))
        with open(self.request_path) as f:
            data = json.load(f)
        self.assertIn("nonce", data)
        self.assertEqual(len(data["nonce"]), 12)
        self.assertTrue(all(c in "0123456789abcdef" for c in data["nonce"]))
        self.assertIn("ts", data)
        self.assertEqual(data["sha256"], sha)
        self.assertEqual(data["staging_filename"], "gtfs_staging.zip")
        self.assertEqual(nonce, data["nonce"])

    def test_request_rebuild_clears_stale_result(self):
        with open(self.result_path, "w") as f:
            json.dump({"nonce": "old", "success": True}, f)

        sha = "b" * 64
        with patch("updater.REBUILD_REQUEST_PATH", self.request_path), \
             patch("updater.REBUILD_RESULT_PATH", self.result_path), \
             self.assertLogs("updater", level="INFO"):
            updater.request_rebuild(sha)

        self.assertFalse(os.path.exists(self.result_path))

    def test_request_rebuild_returns_unique_nonces(self):
        sha = "c" * 64
        with patch("updater.REBUILD_REQUEST_PATH", self.request_path), \
             patch("updater.REBUILD_RESULT_PATH", self.result_path), \
             self.assertLogs("updater", level="INFO"):
            nonce1 = updater.request_rebuild(sha)
        with patch("updater.REBUILD_REQUEST_PATH", self.request_path), \
             patch("updater.REBUILD_RESULT_PATH", self.result_path), \
             self.assertLogs("updater", level="INFO"):
            nonce2 = updater.request_rebuild(sha)
        self.assertNotEqual(nonce1, nonce2)


class TestWaitForRebuildResult(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.result_path = os.path.join(self.tmpdir, ".rebuild_result.json")

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_wait_for_rebuild_result_success(self):
        nonce = "abc123456789"
        result_data = {
            "nonce": nonce, "success": True, "sha256": "a" * 64,
            "error": "", "ts": "2026-07-28T00:00:00Z",
        }
        with open(self.result_path, "w") as f:
            json.dump(result_data, f)

        with patch("updater.REBUILD_RESULT_PATH", self.result_path):
            result = updater.wait_for_rebuild_result(nonce, timeout=2, poll_interval=0.1)

        self.assertIsNotNone(result)
        self.assertTrue(result["success"])

    def test_wait_for_rebuild_result_failure(self):
        nonce = "def123456789"
        result_data = {
            "nonce": nonce, "success": False, "sha256": "a" * 64,
            "error": "build_failed_exit_1_bundle_restored", "ts": "2026-07-28T00:00:00Z",
        }
        with open(self.result_path, "w") as f:
            json.dump(result_data, f)

        with patch("updater.REBUILD_RESULT_PATH", self.result_path):
            result = updater.wait_for_rebuild_result(nonce, timeout=2, poll_interval=0.1)

        self.assertIsNotNone(result)
        self.assertFalse(result["success"])
        self.assertEqual(result["error"], "build_failed_exit_1_bundle_restored")

    def test_wait_for_rebuild_result_timeout(self):
        with patch("updater.REBUILD_RESULT_PATH", self.result_path):
            result = updater.wait_for_rebuild_result("anonce", timeout=0.5, poll_interval=0.1)
        self.assertIsNone(result)

    def test_wait_for_rebuild_result_ignores_wrong_nonce(self):
        result_data = {
            "nonce": "wrongnonce123", "success": True, "sha256": "a" * 64,
            "error": "", "ts": "2026-07-28T00:00:00Z",
        }
        with open(self.result_path, "w") as f:
            json.dump(result_data, f)

        with patch("updater.REBUILD_RESULT_PATH", self.result_path):
            result = updater.wait_for_rebuild_result("correctnonce1", timeout=0.5, poll_interval=0.1)

        self.assertIsNone(result)

    def test_wait_for_rebuild_result_handles_partial_json(self):
        nonce = "partial12345"
        with open(self.result_path, "w") as f:
            f.write("{invalid json}")

        valid_data = {
            "nonce": nonce, "success": True, "sha256": "a" * 64,
            "error": "", "ts": "2026-07-28T00:00:00Z",
        }

        def overwrite_with_valid():
            with open(self.result_path, "w") as f:
                json.dump(valid_data, f)

        timer = threading.Timer(0.3, overwrite_with_valid)
        timer.start()
        try:
            with patch("updater.REBUILD_RESULT_PATH", self.result_path):
                result = updater.wait_for_rebuild_result(nonce, timeout=2, poll_interval=0.1)
        finally:
            timer.cancel()

        self.assertIsNotNone(result)
        self.assertTrue(result["success"])


class TestCleanupMarkerFiles(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.request_path = os.path.join(self.tmpdir, ".rebuild_request.json")
        self.result_path = os.path.join(self.tmpdir, ".rebuild_result.json")

    def tearDown(self):
        shutil.rmtree(self.tmpdir)

    def test_cleanup_marker_files(self):
        with open(self.request_path, "w") as f:
            f.write("{}")
        with open(self.result_path, "w") as f:
            f.write("{}")

        with patch("updater.REBUILD_REQUEST_PATH", self.request_path), \
             patch("updater.REBUILD_RESULT_PATH", self.result_path):
            updater.cleanup_marker_files()

        self.assertFalse(os.path.exists(self.request_path))
        self.assertFalse(os.path.exists(self.result_path))

    def test_cleanup_marker_files_idempotent(self):
        with patch("updater.REBUILD_REQUEST_PATH", self.request_path), \
             patch("updater.REBUILD_RESULT_PATH", self.result_path):
            updater.cleanup_marker_files()
            updater.cleanup_marker_files()


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
             patch("updater.request_rebuild") as mock_request_rebuild, \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.get_latest_checksum", return_value=sha), \
             self.assertLogs("updater", level="INFO"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_request_rebuild.assert_not_called()
        mock_save.assert_not_called()

    def test_update_check_unchanged_writes_jsonl(self):
        data = b"same zip content"
        sha = hashlib.sha256(data).hexdigest()
        conn = self._make_conn(stored_sha=sha)
        _, fake_download = self._setup_download(data)

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.get_latest_checksum", return_value=sha), \
             patch("updater.write_event") as mock_write_event, \
             self.assertLogs("updater", level="INFO"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_write_event.assert_called_once()
        event = mock_write_event.call_args[0][0]
        self.assertEqual(event["event"], "check_unchanged")
        self.assertEqual(event["sha256"], sha)
        self.assertEqual(event["previous_sha256"], sha)
        self.assertEqual(event["feed_url"], "https://example.com/feed.zip")

    def test_update_check_download_error_writes_jsonl(self):
        conn = self._make_conn(stored_sha=None)

        with patch("updater.download_feed", return_value=None), \
             patch("updater.write_event") as mock_write_event, \
             self.assertLogs("updater", level="ERROR"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_write_event.assert_called_once()
        event = mock_write_event.call_args[0][0]
        self.assertEqual(event["event"], "download_error")
        self.assertIsNone(event["sha256"])
        self.assertIsNotNone(event["error"])
        self.assertEqual(event["feed_url"], "https://example.com/feed.zip")

    def test_update_check_unchanged_no_rebuild(self):
        data = b"same content"
        sha = hashlib.sha256(data).hexdigest()
        conn = self._make_conn(stored_sha=sha)
        _, fake_download = self._setup_download(data)

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.get_latest_checksum", return_value=sha), \
             patch("updater.request_rebuild") as mock_request_rebuild, \
             patch("updater.write_event"), \
             self.assertLogs("updater", level="INFO"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_request_rebuild.assert_not_called()

    def test_update_check_changed_success(self):
        old_sha = "a" * 64
        data = b"new zip content"
        new_sha = hashlib.sha256(data).hexdigest()
        conn = self._make_conn(stored_sha=old_sha)
        _, fake_download = self._setup_download(data)

        nonce = "abc123def456"
        result = {
            "nonce": nonce, "success": True, "sha256": new_sha,
            "error": "", "ts": "2026-07-28T00:00:00Z",
        }

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.get_latest_checksum", return_value=old_sha), \
             patch("updater.request_rebuild", return_value=nonce) as mock_request, \
             patch("updater.wait_for_rebuild_result", return_value=result) as mock_wait, \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.cleanup_marker_files") as mock_cleanup, \
             patch("updater.write_event") as mock_write_event, \
             patch("shutil.copy2") as mock_copy2, \
             patch("shutil.move") as mock_move, \
             self.assertLogs("updater", level="INFO"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        copy2_dests = [c[0][1] for c in mock_copy2.call_args_list]
        self.assertIn(updater.BUNDLE_STAGING_PATH, copy2_dests)
        self.assertNotIn(updater.BUNDLE_PRISTINE_PATH, copy2_dests)

        mock_move.assert_called_once_with(
            updater.BUNDLE_STAGING_PATH, updater.BUNDLE_PRISTINE_PATH
        )
        mock_save.assert_called_once()
        events = [c[0][0]["event"] for c in mock_write_event.call_args_list]
        self.assertIn("update_complete", events)
        mock_cleanup.assert_called()

    def test_update_check_changed_build_failure(self):
        old_sha = "a" * 64
        data = b"new content"
        conn = self._make_conn(stored_sha=old_sha)
        _, fake_download = self._setup_download(data)

        nonce = "failnonce1234"
        result = {
            "nonce": nonce, "success": False, "sha256": "b" * 64,
            "error": "build_failed_exit_1_bundle_restored", "ts": "2026-07-28T00:00:00Z",
        }

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.get_latest_checksum", return_value=old_sha), \
             patch("updater.request_rebuild", return_value=nonce), \
             patch("updater.wait_for_rebuild_result", return_value=result), \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.cleanup_marker_files") as mock_cleanup, \
             patch("updater.write_event") as mock_write_event, \
             patch("shutil.copy2"), \
             patch("shutil.move") as mock_move, \
             self.assertLogs("updater", level="ERROR"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_save.assert_not_called()
        mock_move.assert_not_called()
        events = [c[0][0]["event"] for c in mock_write_event.call_args_list]
        self.assertIn("bundler_error", events)
        bundler_event = next(
            c[0][0] for c in mock_write_event.call_args_list
            if c[0][0]["event"] == "bundler_error"
        )
        self.assertTrue(bundler_event["bundle_restored"])
        mock_cleanup.assert_called()

    def test_update_check_changed_build_failure_restore_failed(self):
        old_sha = "a" * 64
        data = b"new content"
        conn = self._make_conn(stored_sha=old_sha)
        _, fake_download = self._setup_download(data)

        nonce = "failnonce1234"
        result = {
            "nonce": nonce, "success": False, "sha256": "b" * 64,
            "error": "build_failed_exit_1_restore_failed", "ts": "2026-07-28T00:00:00Z",
        }

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.get_latest_checksum", return_value=old_sha), \
             patch("updater.request_rebuild", return_value=nonce), \
             patch("updater.wait_for_rebuild_result", return_value=result), \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.cleanup_marker_files") as mock_cleanup, \
             patch("updater.write_event") as mock_write_event, \
             patch("shutil.copy2"), \
             patch("shutil.move") as mock_move, \
             self.assertLogs("updater", level="ERROR"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_save.assert_not_called()
        events = [c[0][0]["event"] for c in mock_write_event.call_args_list]
        self.assertIn("bundler_error", events)
        bundler_event = next(
            c[0][0] for c in mock_write_event.call_args_list
            if c[0][0]["event"] == "bundler_error"
        )
        self.assertFalse(bundler_event["bundle_restored"])

    def test_update_check_changed_timeout(self):
        old_sha = "a" * 64
        data = b"new content"
        conn = self._make_conn(stored_sha=old_sha)
        _, fake_download = self._setup_download(data)

        with patch("updater.download_feed", side_effect=fake_download), \
             patch("updater.get_latest_checksum", return_value=old_sha), \
             patch("updater.request_rebuild", return_value="somenonce123"), \
             patch("updater.wait_for_rebuild_result", return_value=None), \
             patch("updater.save_checksum") as mock_save, \
             patch("updater.cleanup_marker_files") as mock_cleanup, \
             patch("updater.write_event") as mock_write_event, \
             patch("shutil.copy2"), \
             self.assertLogs("updater", level="ERROR"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_save.assert_not_called()
        events = [c[0][0]["event"] for c in mock_write_event.call_args_list]
        self.assertIn("bundler_error", events)
        bundler_event = next(
            c[0][0] for c in mock_write_event.call_args_list
            if c[0][0]["event"] == "bundler_error"
        )
        self.assertIn("timed out", bundler_event["error"])
        self.assertIsNone(bundler_event["bundle_restored"])
        mock_cleanup.assert_called()

    def test_update_check_download_error_no_rebuild(self):
        conn = self._make_conn(stored_sha=None)

        with patch("updater.download_feed", return_value=None), \
             patch("updater.request_rebuild") as mock_request, \
             patch("updater.write_event"), \
             self.assertLogs("updater", level="ERROR"):
            updater.run_update_check(conn, "https://example.com/feed.zip")

        mock_request.assert_not_called()

    def test_run_update_check_no_compose_dir_param(self):
        sig = inspect.signature(updater.run_update_check)
        params = list(sig.parameters.keys())
        self.assertEqual(params, ["conn", "feed_url"])

    def test_no_removed_functions(self):
        removed = [
            "run_bundler", "restart_oba_app", "backup_bundle", "restore_bundle",
            "cleanup_backup", "clean_bundle_intermediates",
        ]
        for name in removed:
            self.assertFalse(hasattr(updater, name), f"updater.{name} should not exist")


class TestWriteEvent(unittest.TestCase):
    def test_write_event_creates_file(self):
        with tempfile.NamedTemporaryFile(delete=False, suffix=".jsonl") as f:
            tmp_path = f.name
        os.unlink(tmp_path)  # remove so we test file creation
        try:
            event = {
                "ts": "2026-07-29T00:00:00Z",
                "event": "check_unchanged",
                "feed_url": "https://example.com/feed.zip",
                "sha256": "a" * 64,
            }
            updater.write_event(event, log_path=tmp_path)
            self.assertTrue(os.path.exists(tmp_path))
            with open(tmp_path) as f:
                lines = f.readlines()
            self.assertEqual(len(lines), 1)
            parsed = json.loads(lines[0])
            self.assertEqual(parsed["event"], "check_unchanged")
            self.assertIn("ts", parsed)
            self.assertIn("feed_url", parsed)
            self.assertIn("sha256", parsed)
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)

    def test_write_event_appends(self):
        with tempfile.NamedTemporaryFile(delete=False, suffix=".jsonl") as f:
            tmp_path = f.name
        try:
            event1 = {"ts": "2026-07-29T00:00:00Z", "event": "check_unchanged", "feed_url": "https://example.com/feed.zip", "sha256": "a" * 64}
            event2 = {"ts": "2026-07-29T01:00:00Z", "event": "check_changed", "feed_url": "https://example.com/feed.zip", "sha256": "b" * 64}
            updater.write_event(event1, log_path=tmp_path)
            updater.write_event(event2, log_path=tmp_path)
            with open(tmp_path) as f:
                lines = f.readlines()
            self.assertEqual(len(lines), 2)
            self.assertEqual(json.loads(lines[0])["event"], "check_unchanged")
            self.assertEqual(json.loads(lines[1])["event"], "check_changed")
        finally:
            os.unlink(tmp_path)

    def test_write_event_io_error_does_not_raise(self):
        with patch("builtins.open", side_effect=OSError("disk full")), \
             self.assertLogs("updater", level="WARNING"):
            # Must not raise
            updater.write_event(
                {"ts": "2026-07-29T00:00:00Z", "event": "check_unchanged", "feed_url": "https://example.com/feed.zip", "sha256": "a" * 64},
                log_path="/nonexistent/path.jsonl",
            )

    def test_jsonl_event_ts_format(self):
        data = b"same zip content"
        sha = hashlib.sha256(data).hexdigest()
        conn = MagicMock()
        cursor = MagicMock()
        cursor.fetchone.return_value = (sha,)
        conn.cursor.return_value.__enter__ = lambda s: cursor
        conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        def fake_download(url):
            f = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
            f.write(data)
            f.close()
            return f.name

        with tempfile.NamedTemporaryFile(delete=False, suffix=".jsonl") as f:
            tmp_path = f.name
        try:
            with patch("updater.download_feed", side_effect=fake_download), \
                 patch("updater.get_latest_checksum", return_value=sha), \
                 patch("updater.LOG_PATH", tmp_path), \
                 self.assertLogs("updater", level="INFO"):
                updater.run_update_check(conn, "https://example.com/feed.zip")
            with open(tmp_path) as f:
                lines = f.readlines()
            self.assertGreater(len(lines), 0)
            event = json.loads(lines[0])
            ts = event["ts"]
            self.assertTrue(ts.endswith("Z"), f"ts does not end with Z: {ts!r}")
            datetime.fromisoformat(ts.replace("Z", "+00:00"))
        finally:
            os.unlink(tmp_path)


if __name__ == "__main__":
    unittest.main()
