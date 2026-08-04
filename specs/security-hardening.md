# Spec: transit-board security hardening (docker socket proxy + resource limits)

## Goal
Reduce the attack surface of the transit-board Docker Compose stack on the shared Hetzner CX22 VPS by (1) eliminating direct docker.sock access from all transit-board containers and (2) adding memory and CPU resource limits to every service so a compromised or runaway container cannot exhaust the host.

## Scope

### In scope
- `transit-board/docker-compose.yml`: add a `docker-socket-proxy` service; redirect `monitor`'s docker.sock mount through the proxy; add `mem_limit` and `cpus` to every service.
- `transit-board/docker-compose.override.yml`: add resource limits to `frontend` (already present here with Caddy labels); ensure the proxy-net addition does not conflict.
- `transit-board/monitor/monitor.py`: change `DOCKER_SOCKET` default and `_http_get_unix` to use a TCP connection to the socket proxy instead of a Unix socket, or make the transport configurable (env var `DOCKER_HOST`).
- `transit-board/monitor/test_monitor.py`: update tests to cover the new TCP transport path.

### Out of scope
- **ufw-docker / firewall rules**: transit-board binds ports `8080` and `5173` to `0.0.0.0`, bypassing UFW. This is a host-level fix owned by the overarching security hardening spec (`/home/shinpei/shinpei/specs/security-hardening-overarching.md`). **Dependency noted**: once ufw-docker is installed, the `ports:` bindings in this compose file may need to change from `"8080:8080"` / `"5173:80"` to `"127.0.0.1:8080:8080"` / `"127.0.0.1:5173:80"` and be fronted by Caddy subdomains. That change should be coordinated in the overarching spec, not here.
- **Hetzner Cloud Firewall, Diun, Trivy, Docker Bench**: all host-level tooling owned by the overarching spec. Trivy will scan this repo's custom images (`oba_app`, `oba_bundler`, `transit-board-api`, `frontend`, `gtfs_updater`, `monitor`) but the cron setup lives elsewhere.
- **Image tag pinning**: `mysql:8.4` is already pinned. No floating tags exist in transit-board's compose files. No action needed here.
- **gtfs_updater docker.sock removal**: **already completed** prior to this spec. The current `docker-compose.yml` does not mount `docker.sock` into `gtfs_updater` — it uses a marker-file protocol on the shared `/bundle` volume (see `updater.py` functions `request_rebuild()` and `wait_for_rebuild_result()`, consumed by `oba-app/bundle-watcher.sh` inside the `oba_app` container). **This means the SECURITY.md finding about gtfs_updater's `rw` docker.sock access is stale — no further changes needed for gtfs_updater's Docker access.** The remaining live issue is `monitor`'s `:ro` docker.sock mount.

## Behavior

### 1. Docker socket proxy for `monitor`

Add a new service `docker-socket-proxy` using `tecnativa/docker-socket-proxy:0.3` (or the latest pinned minor). Configuration:

```yaml
docker-socket-proxy:
  image: tecnativa/docker-socket-proxy:0.3
  container_name: transit-board-docker-proxy
  restart: unless-stopped
  environment:
    CONTAINERS: 1      # GET /containers/json and /containers/{id}/stats
    POST: 0            # deny all POST (no container create/start/stop/restart)
    NETWORKS: 0
    SERVICES: 0
    TASKS: 0
    VOLUMES: 0
    IMAGES: 0
    INFO: 0
    EVENTS: 0
    AUTH: 0
    SECRETS: 0
    BUILD: 0
    COMMIT: 0
    CONFIGS: 0
    DISTRIBUTION: 0
    EXEC: 0
    GRPC: 0
    LOG_LEVEL: warning
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock:ro
  networks:
    - docker-proxy-internal
```

The `monitor` service must:
- Remove its `/var/run/docker.sock` volume mount entirely.
- Keep its `/proc:/host/proc:ro` mount (reads host CPU/RAM/disk; not a Docker socket concern).
- Add `docker-socket-proxy` as a dependency.
- Join the `docker-proxy-internal` network.
- Set env var `DOCKER_HOST=tcp://docker-socket-proxy:2375`.

A new internal network `docker-proxy-internal` isolates the proxy; only `monitor` and the proxy itself join it.

### 2. Changes to `monitor/monitor.py`

The current `read_containers()` function uses `_http_get_unix()` which speaks HTTP over a Unix domain socket. After this change, it must speak HTTP over TCP to the proxy at `tcp://docker-socket-proxy:2375`.

Approach: make the transport configurable via the `DOCKER_HOST` env var.
- If `DOCKER_HOST` starts with `tcp://`, parse host and port, use a standard TCP socket.
- If `DOCKER_HOST` starts with `unix://` or is a filesystem path (legacy default), use the existing Unix socket path.
- Default: `tcp://docker-socket-proxy:2375` (matching the compose config).

The `_http_get_unix` function should be renamed/generalized to `_http_get` with transport selection logic. The Docker API paths (`/v1.43/containers/json`, `/v1.43/containers/{id}/stats?stream=false`) remain unchanged.

### 3. Resource limits

Add `mem_limit` and `cpus` to every service in `docker-compose.yml`. Host: Hetzner CX22, 2 vCPU, 3.7 GB (3788 MB) usable RAM, no swap.

**Transit-board proposed limits** (ceilings, not reservations; sum across all stacks must leave headroom for the host OS, ~300-400 MB):

| Service | `mem_limit` | `cpus` | Rationale |
|---|---|---|---|
| `oba_database` (MySQL) | 512m | 0.5 | Steady-state DB; largest memory consumer |
| `oba_app` (Tomcat + OBA) | 768m | 0.75 | JVM heap; this is the heaviest service |
| `transit-board-api` | 128m | 0.25 | Lightweight API proxy |
| `frontend` | 64m | 0.1 | Static nginx serving built frontend |
| `gtfs_updater` | 128m | 0.25 | Python script, mostly sleeping; spikes during GTFS download/checksum |
| `monitor` | 64m | 0.1 | Lightweight Python stats collector |
| `docker-socket-proxy` | 32m | 0.05 | Tiny Go binary |
| **transit-board total** | **1696m** | **2.0** | Fits within a ~1.7 GB budget for this stack |

These numbers are proposals — see overarching spec's resource budget table for coordination across all repos; if it assigns a different budget to transit-board, adjust these accordingly.

### 4. Logging driver

Add `json-file` logging driver with `max-size: "10m"` / `max-file: "3"` to services currently missing it: `oba_database`, `oba_app`, `transit-board-api`, `frontend`, `gtfs_updater`, `monitor`, `docker-socket-proxy`.

## Edge cases

- **monitor cannot reach docker-socket-proxy**: `read_containers()` already handles connection failures gracefully (returns empty dict, prints warning). No change needed.
- **docker-socket-proxy denies an API call**: Tecnativa proxy returns HTTP 403 for disallowed endpoints. `read_containers()`'s existing `except Exception` handler catches the non-JSON response and returns an empty dict.
- **oba_app hits mem_limit during bundle rebuild**: bundle builds are memory-intensive. `bundle-watcher.sh` already handles build failures (exit code != 0) and restores from backup. The limit may need tuning after observing real builds — note this in the compose file as a comment.
- **gtfs_updater mem_limit during large GTFS download**: `download_feed()` reads the entire feed into memory. LIRR GTFS feeds are typically < 10 MB, well within 128m.
- **`cli` and `oba_app_base` services**: `cli` uses the `cli` profile (not started by default); `oba_app_base` uses the `build` profile. Both should still get resource limits for completeness: `cli`: 128m / 0.25; `oba_app_base`: 512m / 0.5.

## Acceptance criteria

- [ ] `docker.sock` is not mounted directly into any transit-board service except `docker-socket-proxy`.
- [ ] `docker-socket-proxy` is configured with only `CONTAINERS=1` enabled; all other API categories are explicitly denied (`0`).
- [ ] `docker-socket-proxy` mounts `docker.sock` as `:ro`.
- [ ] `monitor` connects to the Docker API via `tcp://docker-socket-proxy:2375`, not via a Unix socket mount.
- [ ] `monitor` and `docker-socket-proxy` communicate over a dedicated internal network (`docker-proxy-internal`) that no other service joins.
- [ ] Every service in `docker-compose.yml` (including profile-only services `cli` and `oba_app_base`) has `mem_limit` and `cpus` set.
- [ ] The sum of all `mem_limit` values across transit-board services does not exceed 1800m.
- [ ] Every service has `logging: driver: "json-file"` with `max-size: "10m"` and `max-file: "3"`.
- [ ] `monitor/monitor.py` supports both TCP and Unix socket transports, selected via the `DOCKER_HOST` env var.
- [ ] All existing `monitor` tests pass; new tests cover the TCP transport path.
- [ ] All existing `gtfs_updater` tests pass with no changes (gtfs_updater is not modified).
- [ ] `docker compose config` succeeds without errors after all changes.

## Tests to write

- `test_http_get_tcp_success`: TCP transport in monitor.py correctly sends an HTTP request and parses the response body when given a `tcp://host:port` DOCKER_HOST.
- `test_http_get_tcp_connection_refused`: a connection error to the TCP proxy is handled gracefully (returns empty dict, does not crash).
- `test_http_get_unix_still_works`: Unix socket path still works when DOCKER_HOST is a filesystem path or `unix://` URL (backward compatibility).
- `test_docker_host_parsing`: `tcp://docker-socket-proxy:2375`, `unix:///var/run/docker.sock`, and `/var/run/docker.sock` are all correctly parsed into the right transport.
- `test_read_containers_with_tcp_transport`: `read_containers()` uses TCP when DOCKER_HOST is set to a tcp:// URL (mock the socket connection).

Existing tests to keep unchanged: all tests in `test_updater.py` (gtfs_updater untouched); `test_monitor.py` tests for `read_cpu`, `read_ram`, `read_disk`, `read_network`, `collect_sample`, `write_event`. Existing `read_containers` tests should be updated for the new transport abstraction (currently take a `socket_path` parameter, becomes a more general `docker_host` parameter).

## Files that will change

- `transit-board/docker-compose.yml` — add `docker-socket-proxy` service; remove `docker.sock` mount from `monitor`; add `mem_limit`, `cpus`, and `logging` to all services; add `docker-proxy-internal` network; add `DOCKER_HOST` env var to `monitor`.
- `transit-board/docker-compose.override.yml` — add `mem_limit` and `cpus` to `frontend`.
- `transit-board/monitor/monitor.py` — generalize `_http_get_unix` to support TCP transport; parse `DOCKER_HOST` env var; update default to `tcp://docker-socket-proxy:2375`.
- `transit-board/monitor/test_monitor.py` — add TCP transport tests; update existing `read_containers` tests for new transport abstraction.

## Resolved decisions (formerly open questions)

1. **Resource limit numbers**: deferred, not skipped. A `docker-stats-log.sh` cron job (defined in the overarching spec) logs real `docker stats` output for a few days across all repos before final `mem_limit`/`cpus` values are locked in. Use the proposed table above as generous placeholders initially.
2. **oba_app memory ceiling**: leave as a placeholder pending the measurement period — if bundle rebuilds are observed OOM-killing near 768m in the logged data, raise it before finalizing.
3. **docker-socket-proxy image**: confirmed — Tecnativa/docker-socket-proxy.
4. **Logging driver addition**: confirmed — in scope for this spec.
5. **`oba_app_base` and `cli` profile services**: confirmed — give them resource limits too, for completeness.
