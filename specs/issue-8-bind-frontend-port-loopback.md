# Spec: issue-8 — bind the frontend's host port 5173 to 127.0.0.1

Tracker: shinpei/transit-board#8

## Goal
Publish the `frontend` service's port only on the host loopback interface, so `http://<server-ip>:5173` no longer answers. The production site is already served over TLS by Caddy (`deparch.dev.donovanrichardson.com`) via the Docker network, so the host publication is not needed for it.

## Scope

### In scope
- `docker-compose.yml`, `frontend` service, `ports:` entry (line 115): `"5173:80"` -> `"127.0.0.1:5173:80"`. This mirrors the existing `oba_app` entry `"127.0.0.1:8080:8080"` (line 54).
- A new static test, `tests/test_docker_compose_ports.py`, that parses `docker-compose.yml` with PyYAML and asserts no service publishes a host port without a loopback prefix. No Docker invocation.

### Out of scope
- `docker-compose.override.yml`: has no `ports:` key for `frontend`; unchanged. In production Caddy reaches `transit-board-frontend-1:80` over the `proxy-net` network.
- `oba_app` (already loopback-bound), `transit-board-api` and all other services (no host ports).
- Hetzner Cloud Firewall status for 5173: unverified, not part of this change. Closing #8 must not be read as a claim that the firewall was checked.
- `ufw` / `ufw-docker` work, tracked in lifeos-poc#5.
- Removing the `ports:` key entirely (see Resolved decision).
- `README.md` (uses `http://localhost:5173`, still valid) and `CODEBASE_ANALYSIS.md` (line 57 becomes stale; that file is regenerated separately).
- Deploying the change (`docker compose up`): an orchestrator/user action after merge, never an implementer action.

## Behavior
Before:
```yaml
frontend:
  ports:
    - "5173:80"
```
After:
```yaml
frontend:
  ports:
    - "127.0.0.1:5173:80"
```
Requests to `http://localhost:5173` from the server keep working (the README Quick Start flow). Requests to the server's public address on 5173 no longer connect.

## Edge cases
- Local/standalone use without the override file: unaffected, `localhost` is loopback.
- Production with the override: Caddy uses the Docker network, not the host port; no effect on the TLS path.
- IPv6: `127.0.0.1:5173:80` binds IPv4 loopback only, the same as the existing `oba_app` pattern; no new gap.
- `cli` and `oba_app_base` use Compose `profiles` and publish no ports.
- Rollout: per `README.md` (the "After any rebuild" note, near line 128), recreate with `docker compose up -d frontend`, then re-run the `OBA_API_KEY=<key> docker compose up -d transit-board-api` step and `docker restart transit-board-frontend-1`, because cascade restarts wipe the injected key.

## Acceptance criteria
Static / unit-testable (implementer, red-green):
- [ ] `frontend` `ports:` is exactly `["127.0.0.1:5173:80"]`.
- [ ] Every `ports:` entry of every service in `docker-compose.yml` has a `127.0.0.1:` host prefix (services without `ports:` pass vacuously). The test fails against the current file (red) and passes after the fix (green).
- [ ] `docker-compose.override.yml` unchanged; no other service gains or loses a `ports:` entry.

Post-deploy verification (orchestrator/user only):
- [ ] `ss -tlnp | grep 5173` shows `127.0.0.1:5173`, not `0.0.0.0` or `*`/`[::]`.
- [ ] `https://deparch.dev.donovanrichardson.com/` still loads and its API calls succeed.
- [ ] `curl http://<server-public-ip>:5173/` from an external host fails to connect.

## Tests to write
- `test_frontend_port_bound_to_loopback`
- `test_no_service_publishes_without_loopback_prefix`

## Files that will change
- `docker-compose.yml` (line 115)
- `tests/test_docker_compose_ports.py` (new; repo-root `tests/`, following `monitor/test_monitor.py` and `gtfs-updater/test_updater.py` as precedent)

## Resolved decision
Bind to loopback rather than remove `ports:`: removing it would break the README Quick Start (`docker compose up -d oba_app frontend`, then `http://localhost:5173`), which has no Caddy. Retiring that workflow would be a separate issue.
