import json
import os
import socket
import sys
import unittest
from unittest.mock import MagicMock, mock_open, patch

# We import after writing so that import failures are test failures, not setup failures.
import monitor


FAKE_STAT_1 = (
    "cpu  100 0 50 800 50 0 0 0 0 0\n"
    "cpu0 50 0 25 400 25 0 0 0 0 0\n"
)
FAKE_STAT_2 = (
    "cpu  200 0 100 880 20 0 0 0 0 0\n"
    "cpu0 100 0 50 440 10 0 0 0 0 0\n"
)

FAKE_MEMINFO = (
    "MemTotal:        3878912 kB\n"
    "MemFree:          512000 kB\n"
    "MemAvailable:    1037312 kB\n"
    "Buffers:           80000 kB\n"
    "Cached:           500000 kB\n"
)

FAKE_NET_DEV = """\
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 123456  1000    0    0    0     0          0         0   654321  1000    0    0    0     0       0          0
  eth0: 1258291200 5000    0    0    0     0          0         0 356515840  2000    0    0    0     0       0          0
 wlan0: 0       0    0    0    0     0          0         0        0     0    0    0    0     0       0          0
"""


class TestReadCpu(unittest.TestCase):
    def test_read_cpu_computes_delta(self):
        # stat1: idle=800, total=1000; stat2: idle=880, total=1200
        # delta_idle=80, delta_total=200; cpu_pct = 100*(1 - 80/200) = 60.0
        # Wait — let me recalc from the fake data above:
        # stat1: user=100 nice=0 sys=50 idle=800 iowait=50 ... total=1000
        # stat2: user=200 nice=0 sys=100 idle=880 iowait=20 ... total=1200
        # delta_idle=80, delta_total=200, cpu_pct=100*(1-80/200)=60.0
        m = mock_open()
        m.return_value.__iter__ = lambda s: iter(s.readline, '')
        handles = [
            mock_open(read_data=FAKE_STAT_1)(),
            mock_open(read_data=FAKE_STAT_2)(),
        ]
        with patch("builtins.open", side_effect=handles):
            with patch("time.sleep"):
                result = monitor.read_cpu(proc_root="/fake/proc")
        self.assertEqual(result, 60.0)

    def test_read_cpu_returns_none_on_missing_file(self):
        result = monitor.read_cpu(proc_root="/nonexistent/proc")
        self.assertIsNone(result)


class TestReadRam(unittest.TestCase):
    def test_read_ram_parses_meminfo(self):
        with patch("builtins.open", mock_open(read_data=FAKE_MEMINFO)):
            result = monitor.read_ram(proc_root="/fake/proc")
        # MemTotal=3878912 kB -> 3878912//1024=3788 MB
        # MemAvailable=1037312 kB -> used = (3878912-1037312)//1024 = 2841600//1024 = 2775 MB
        self.assertEqual(result, {"ram_used_mb": 2775, "ram_total_mb": 3788})

    def test_read_ram_returns_none_on_missing_file(self):
        result = monitor.read_ram(proc_root="/nonexistent/proc")
        self.assertIsNone(result)


class TestReadDisk(unittest.TestCase):
    def test_read_disk(self):
        fake_stat = MagicMock()
        fake_stat.f_blocks = 10000000
        fake_stat.f_bavail = 5000000
        fake_stat.f_bfree = 5500000  # different from f_bavail to verify correct field used
        fake_stat.f_frsize = 4096
        with patch("os.statvfs", return_value=fake_stat) as mock_stat:
            result = monitor.read_disk(disk_path="/fake/host")
            mock_stat.assert_called_once_with("/fake/host")
        # used = (10000000 - 5000000) * 4096 = 20480000000 bytes = 20480000000/1e9 GB
        used_gb = round((10000000 - 5000000) * 4096 / 1_000_000_000, 1)
        free_gb = round(5000000 * 4096 / 1_000_000_000, 1)
        self.assertEqual(result["disk_used_gb"], used_gb)
        self.assertEqual(result["disk_free_gb"], free_gb)
        # Verify f_bavail used (5000000), not f_bfree (5500000)
        expected_free_if_bfree = round(5500000 * 4096 / 1_000_000_000, 1)
        self.assertNotEqual(result["disk_free_gb"], expected_free_if_bfree)

    def test_read_disk_returns_none_on_oserror(self):
        with patch("os.statvfs", side_effect=OSError("Permission denied")):
            result = monitor.read_disk(disk_path="/fake/host")
        self.assertIsNone(result)


class TestReadNetwork(unittest.TestCase):
    def test_read_network_sums_non_loopback(self):
        with patch("builtins.open", mock_open(read_data=FAKE_NET_DEV)):
            result = monitor.read_network(proc_root="/fake/proc")
        # eth0: rx=1258291200 bytes, tx=356515840 bytes; wlan0: 0/0
        # rx_mb = 1258291200/1048576 = 1200.0, tx_mb = 356515840/1048576 = 340.0
        self.assertAlmostEqual(result["net_rx_mb"], round(1258291200 / 1048576, 1), places=1)
        self.assertAlmostEqual(result["net_tx_mb"], round(356515840 / 1048576, 1), places=1)

    def test_read_network_loopback_only(self):
        loopback_only = """\
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 123456  1000    0    0    0     0          0         0   654321  1000    0    0    0     0       0          0
"""
        with patch("builtins.open", mock_open(read_data=loopback_only)):
            result = monitor.read_network(proc_root="/fake/proc")
        self.assertEqual(result, {"net_rx_mb": 0.0, "net_tx_mb": 0.0})

    def test_read_network_returns_none_on_missing_file(self):
        result = monitor.read_network(proc_root="/nonexistent/proc")
        self.assertIsNone(result)


FAKE_CONTAINERS_JSON = json.dumps([
    {"Id": "abc123def456", "Names": ["/oba_app"]},
    {"Id": "fed654cba321", "Names": ["/oba_database"]},
])

FAKE_STATS_OBA_APP = json.dumps({
    "cpu_stats": {
        "cpu_usage": {"total_usage": 200000000, "percpu_usage": [0, 0, 0, 0]},
        "system_cpu_usage": 4000000000,
        "online_cpus": 4,
    },
    "precpu_stats": {
        "cpu_usage": {"total_usage": 100000000, "percpu_usage": [0, 0, 0, 0]},
        "system_cpu_usage": 3000000000,
    },
    "memory_stats": {"usage": 2147483648},  # 2048 MB
})

FAKE_STATS_OBA_DB = json.dumps({
    "cpu_stats": {
        "cpu_usage": {"total_usage": 14000000, "percpu_usage": [0, 0, 0, 0]},
        "system_cpu_usage": 3500000000,
        "online_cpus": 4,
    },
    "precpu_stats": {
        "cpu_usage": {"total_usage": 10000000, "percpu_usage": [0, 0, 0, 0]},
        "system_cpu_usage": 3000000000,
    },
    "memory_stats": {"usage": 327155712},  # ~312 MB
})


def _make_fake_socket_response(*bodies):
    """Return a sequence of fake socket objects whose recv() yields HTTP responses."""

    class FakeSocket:
        def __init__(self, body):
            self._data = (
                f"HTTP/1.1 200 OK\r\nContent-Length: {len(body)}\r\n\r\n{body}"
            ).encode()
            self._sent = 0

        def connect(self, path): pass
        def sendall(self, data): pass

        def recv(self, size):
            chunk = self._data[self._sent:self._sent + size]
            self._sent += size
            return chunk

        def close(self): pass

        def __enter__(self): return self
        def __exit__(self, *a): pass

    return iter(FakeSocket(b) for b in bodies)


class TestReadContainers(unittest.TestCase):
    def test_read_containers_no_socket(self):
        result = monitor.read_containers(socket_path="/nonexistent/docker.sock")
        self.assertEqual(result, {})

    def test_read_containers_parses_stats(self):
        bodies = [
            FAKE_CONTAINERS_JSON,
            FAKE_STATS_OBA_APP,
            FAKE_STATS_OBA_DB,
        ]

        fake_sockets = []
        for body in bodies:
            encoded = body.encode()
            http_response = (
                f"HTTP/1.1 200 OK\r\nContent-Length: {len(encoded)}\r\n\r\n"
            ).encode() + encoded
            s = MagicMock()
            s.recv.side_effect = _chunked_recv(http_response)
            s.__enter__ = lambda self: self
            s.__exit__ = MagicMock(return_value=False)
            fake_sockets.append(s)

        with patch("socket.socket", side_effect=[
            _make_context_manager(fake_sockets[0]),
            _make_context_manager(fake_sockets[1]),
            _make_context_manager(fake_sockets[2]),
        ]):
            result = monitor.read_containers(socket_path="/fake/docker.sock")

        self.assertIn("oba_app", result)
        self.assertIn("oba_database", result)

        # oba_app: delta_cpu=100000000, delta_system=1000000000, ncpus=4
        # cpu_pct = (100000000/1000000000)*4*100 = 40.0
        self.assertEqual(result["oba_app"]["cpu_pct"], 40.0)
        self.assertEqual(result["oba_app"]["ram_mb"], 2147483648 // 1048576)  # 2048

        # oba_database: delta_cpu=4000000, delta_system=500000000, ncpus=4
        # cpu_pct = (4000000/500000000)*4*100 = 3.2
        self.assertEqual(result["oba_database"]["cpu_pct"], 3.2)
        self.assertEqual(result["oba_database"]["ram_mb"], 327155712 // 1048576)  # 312


def _chunked_recv(data, chunk_size=4096):
    """Yield chunks of data then empty bytes."""
    chunks = []
    for i in range(0, len(data), chunk_size):
        chunks.append(data[i:i + chunk_size])
    chunks.append(b"")
    return chunks


def _make_context_manager(mock_sock):
    """Wrap a mock socket so it works as a context manager."""
    mock_sock.__enter__ = MagicMock(return_value=mock_sock)
    mock_sock.__exit__ = MagicMock(return_value=False)
    return mock_sock


class TestCollectSample(unittest.TestCase):
    def test_collect_sample_shape(self):
        with patch.object(monitor, "read_cpu", return_value=12.4), \
             patch.object(monitor, "read_ram", return_value={"ram_used_mb": 2841, "ram_total_mb": 3790}), \
             patch.object(monitor, "read_disk", return_value={"disk_used_gb": 18.1, "disk_free_gb": 19.2}), \
             patch.object(monitor, "read_network", return_value={"net_rx_mb": 1204.3, "net_tx_mb": 340.1}), \
             patch.object(monitor, "read_containers", return_value={"oba_app": {"cpu_pct": 8.1, "ram_mb": 2048}}):
            sample = monitor.collect_sample()

        required_keys = {"ts", "cpu_pct", "ram_used_mb", "ram_total_mb",
                         "disk_used_gb", "disk_free_gb", "net_rx_mb", "net_tx_mb", "containers"}
        self.assertEqual(set(sample.keys()), required_keys)
        self.assertTrue(sample["ts"].endswith("Z"))
        self.assertIsInstance(sample["cpu_pct"], float)
        self.assertIsInstance(sample["ram_used_mb"], int)
        self.assertIsInstance(sample["ram_total_mb"], int)
        self.assertIsInstance(sample["containers"], dict)

    def test_collect_sample_null_on_failure(self):
        with patch.object(monitor, "read_cpu", return_value=None), \
             patch.object(monitor, "read_ram", return_value={"ram_used_mb": 2841, "ram_total_mb": 3790}), \
             patch.object(monitor, "read_disk", return_value={"disk_used_gb": 18.1, "disk_free_gb": 19.2}), \
             patch.object(monitor, "read_network", return_value={"net_rx_mb": 1204.3, "net_tx_mb": 340.1}), \
             patch.object(monitor, "read_containers", return_value={}):
            sample = monitor.collect_sample()

        self.assertIsNone(sample["cpu_pct"])
        self.assertEqual(sample["ram_used_mb"], 2841)
        self.assertEqual(sample["ram_total_mb"], 3790)
        self.assertEqual(sample["containers"], {})


if __name__ == "__main__":
    unittest.main()
