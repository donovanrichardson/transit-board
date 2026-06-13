# Spec: System Resource Monitor

## Goal
Add a lightweight system resource monitor as a Docker container service to the existing `transit-board/` Docker Compose stack. It reads host metrics from `/proc` and container metrics from the Docker socket, then appends a JSON line to a log file every configurable N seconds.

## Scope
### In scope
- `transit-board/monitor/monitor.py` — new script: all metric collection and logging logic
- `transit-board/monitor/Dockerfile` — new: Python 3.12 Alpine image, copies and runs `monitor.py`
- `transit-board/monitor/test_monitor.py` — new: unit tests mocking `/proc` reads and Docker socket
- `transit-board/docker-compose.yml` — add `monitor` service definition
- `transit-board/logs/.gitkeep` — new: ensures `logs/` directory is tracked in git
- `transit-board/.gitignore` — new file: ignore `logs/monitor.jsonl`

### Out of scope
- Any changes to existing services (`oba_database`, `oba_bundler`, `oba_app`, `cli`)
- Log rotation or log size management
- Dashboard or alerting on collected metrics
- Any third-party Python packages (stdlib only in the container)

## Behavior

### Sampling loop

On startup, `monitor.py` enters an infinite loop:

1. Collect all metrics (CPU, RAM, disk, network, containers).
2. Build a single dict with the schema below.
3. Serialize to JSON and append as one line to `/logs/monitor.jsonl` (the bind-mounted path inside the container; on the host this is `transit-board/logs/monitor.jsonl`).
4. Sleep for `MONITOR_INTERVAL_SECONDS` seconds (read from env var, default `60`).

### Output schema

Each line in `monitor.jsonl` is a JSON object with exactly these keys:

```json
{
  "ts": "2026-06-13T14:32:00Z",
  "cpu_pct": 12.4,
  "ram_used_mb": 2841,
  "ram_total_mb": 3790,
  "disk_used_gb": 18.1,
  "disk_free_gb": 19.2,
  "net_rx_mb": 1204.3,
  "net_tx_mb": 340.1,
  "containers": {
    "oba_app": {"cpu_pct": 8.1, "ram_mb": 2048},
    "oba_database": {"cpu_pct": 0.4, "ram_mb": 312}
  }
}
```

Field details:

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `ts` | string (ISO 8601 UTC) | `datetime.datetime.now(datetime.timezone.utc)` | Always UTC, trailing `Z` (not `+00:00`) |
| `cpu_pct` | float, 1 decimal | `/host/proc/stat` | Read `cpu` line twice with 1s sleep. `100 * (1 - delta_idle / delta_total)`. Round to 1 decimal. |
| `ram_used_mb` | int | `/host/proc/meminfo` | `MemTotal - MemAvailable`, kB → MB (integer division by 1024) |
| `ram_total_mb` | int | `/host/proc/meminfo` | `MemTotal` in kB → MB |
| `disk_used_gb` | float, 1 decimal | `os.statvfs('/host')` | `(f_blocks - f_bavail) * f_frsize`, bytes → GB, 1 decimal |
| `disk_free_gb` | float, 1 decimal | `os.statvfs('/host')` | `f_bavail * f_frsize`, bytes → GB, 1 decimal. Use `f_bavail` (non-root available), not `f_bfree`. |
| `net_rx_mb` | float, 1 decimal | `/host/proc/net/dev` | Sum bytes received across all non-loopback interfaces, cumulative since boot. Bytes → MB (÷ 1048576). |
| `net_tx_mb` | float, 1 decimal | `/host/proc/net/dev` | Sum bytes transmitted across all non-loopback interfaces. Same conversion. |
| `containers` | dict | Docker Engine API via Unix socket | Keys = container names (strip leading `/`). Values = `{"cpu_pct": float, "ram_mb": int}`. |

### Container stats collection

Raw HTTP over Unix socket at `/var/run/docker.sock`. No Docker SDK.

1. `GET /v1.43/containers/json` — list running containers. Extract `Id` and `Names[0]` (strip `/`).
2. For each container: `GET /v1.43/containers/{id}/stats?stream=false`.
3. `cpu_pct`: `delta_cpu = cpu_stats.cpu_usage.total_usage - precpu_stats.cpu_usage.total_usage`, `delta_system = cpu_stats.system_cpu_usage - precpu_stats.system_cpu_usage`, `cpu_pct = (delta_cpu / delta_system) * num_cpus * 100`. `num_cpus = len(cpu_stats.cpu_usage.percpu_usage)` or `cpu_stats.online_cpus`. Round to 1 decimal.
4. `ram_mb`: `memory_stats.usage` bytes → MB (integer division by 1048576).

### Function signatures

```python
PROC_ROOT = os.environ.get("PROC_ROOT", "/host/proc")
DISK_PATH = os.environ.get("DISK_PATH", "/host")
DOCKER_SOCKET = os.environ.get("DOCKER_SOCKET", "/var/run/docker.sock")
LOG_PATH = os.environ.get("LOG_PATH", "/logs/monitor.jsonl")
INTERVAL = int(os.environ.get("MONITOR_INTERVAL_SECONDS", "60"))

def read_cpu(proc_root: str = PROC_ROOT) -> float | None: ...
def read_ram(proc_root: str = PROC_ROOT) -> dict | None: ...
def read_disk(disk_path: str = DISK_PATH) -> dict | None: ...
def read_network(proc_root: str = PROC_ROOT) -> dict | None: ...
def read_containers(socket_path: str = DOCKER_SOCKET) -> dict: ...
def collect_sample() -> dict: ...
def main() -> None: ...
```

All path parameters must be passable as arguments (not only env vars) so tests can inject fake paths without monkeypatching env.

## Edge cases

- **Docker socket missing**: `read_containers` catches all exceptions, logs warning to stderr, returns `{}`. Monitor continues.
- **Any `/host/proc/*` file unreadable**: The `read_*` function returns `None`. In `collect_sample`, `None` causes that metric's keys to be `null` in the output JSON.
- **`/logs` does not exist**: `main()` calls `os.makedirs("/logs", exist_ok=True)` on startup.
- **`/logs/monitor.jsonl` not writable**: Print error to stderr and `sys.exit(1)`.
- **`MONITOR_INTERVAL_SECONDS` not a valid integer**: Let `int()` raise `ValueError` naturally — do not catch it.
- **Container has no `percpu_usage`**: Fall back to `cpu_stats.online_cpus`. If neither available, set `cpu_pct` to `null`.
- **`/host/proc/net/dev` has only loopback**: Return `{"net_rx_mb": 0.0, "net_tx_mb": 0.0}`.
- **Empty container list from Docker**: Return `{}`.

## Dockerfile

```dockerfile
FROM python:3.12-alpine
WORKDIR /app
COPY monitor.py .
CMD ["python", "monitor.py"]
```

## docker-compose.yml addition

```yaml
monitor:
  build: ./monitor
  volumes:
    - /proc:/host/proc:ro
    - /var/run/docker.sock:/var/run/docker.sock:ro
    - ./logs:/logs
  environment:
    - MONITOR_INTERVAL_SECONDS=60
  restart: unless-stopped
```

## Acceptance criteria

- [ ] `docker compose up monitor` starts without error
- [ ] After one interval, `transit-board/logs/monitor.jsonl` contains at least one valid JSON line
- [ ] Each line has all nine top-level keys: `ts`, `cpu_pct`, `ram_used_mb`, `ram_total_mb`, `disk_used_gb`, `disk_free_gb`, `net_rx_mb`, `net_tx_mb`, `containers`
- [ ] `ts` is a valid ISO 8601 UTC timestamp ending in `Z`
- [ ] `containers` has one entry per running container with `cpu_pct` (float) and `ram_mb` (int)
- [ ] `MONITOR_INTERVAL_SECONDS=5` causes samples every ~5 seconds
- [ ] Missing Docker socket → monitor continues, logs `"containers": {}`
- [ ] Unreadable `/host/proc` file → affected metrics are `null`, monitor continues
- [ ] `monitor.py` uses zero third-party imports
- [ ] All unit tests pass: `python -m pytest transit-board/monitor/test_monitor.py`

## Tests

File: `transit-board/monitor/test_monitor.py`. Use `unittest.mock.patch`, `mock_open`, `tmp_path`.

- **test_read_cpu_computes_delta**: Two fake `/proc/stat` snapshots via `side_effect` on `open`. Known values: idle delta 80, total delta 100 → expect `20.0`.
- **test_read_cpu_returns_none_on_missing_file**: Nonexistent `proc_root` → returns `None`.
- **test_read_ram_parses_meminfo**: Fake meminfo with `MemTotal: 3878912 kB`, `MemAvailable: 1037312 kB` → `{"ram_used_mb": 2775, "ram_total_mb": 3788}`.
- **test_read_ram_returns_none_on_missing_file**: Nonexistent path → `None`.
- **test_read_disk**: Mock `os.statvfs` with known values. Assert correct `disk_used_gb` and `disk_free_gb`. Verify uses `f_bavail` not `f_bfree`.
- **test_read_disk_returns_none_on_oserror**: `os.statvfs` raises `OSError` → `None`.
- **test_read_network_sums_non_loopback**: Fake `/proc/net/dev` with `lo`, `eth0`, `wlan0`. Assert sum of `eth0` + `wlan0` only, correct byte → MB conversion.
- **test_read_network_loopback_only**: Only `lo` in fake file → `{"net_rx_mb": 0.0, "net_tx_mb": 0.0}`.
- **test_read_containers_no_socket**: Nonexistent socket path → `{}` without raising.
- **test_read_containers_parses_stats**: Mock Unix socket HTTP. Fake container list + stats responses. Assert correct names, `cpu_pct`, `ram_mb`.
- **test_collect_sample_shape**: Mock all `read_*` to return known values. Assert all nine keys present with correct types, `ts` ends in `Z`.
- **test_collect_sample_null_on_failure**: `read_cpu` returns `None`, others normal. Assert `"cpu_pct": null`, all other fields populated.

## Files that will be created/modified

- `transit-board/monitor/Dockerfile`
- `transit-board/monitor/monitor.py`
- `transit-board/monitor/test_monitor.py`
- `transit-board/docker-compose.yml`
- `transit-board/logs/.gitkeep`
- `transit-board/.gitignore`
